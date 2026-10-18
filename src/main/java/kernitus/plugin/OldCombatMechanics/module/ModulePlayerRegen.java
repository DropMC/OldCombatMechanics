/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.module;

import com.cryptomorin.xseries.XAttribute;
import kernitus.plugin.OldCombatMechanics.OCMMain;
import kernitus.plugin.OldCombatMechanics.utilities.MathsHelper;
import kernitus.plugin.OldCombatMechanics.utilities.scheduling.OCMScheduler;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Establishes custom health regeneration rules.
 * Default values based on 1.8 from
 * <a href="https://minecraft.gamepedia.com/Hunger?oldid=948685">wiki</a>
 */
public class ModulePlayerRegen extends OCMModule {

    // Vanilla 1.8 natural regen is driven by ticks (foodTickTimer reaches 80 ticks), not wall-clock time.
    // We therefore measure "interval" in ticks so behaviour stays consistent with TPS drops, rather than
    // speeding up/slowing down based on real time.
    //
    // Performance/correctness:
    // - Use a normal map (WeakHashMap<UUID, ...> can drop entries unpredictably).
    // - Count in the player's own ticks (getTicksLived), which advance once per tick on whichever thread ticks
    //   the player, so no counter task is needed.
    private final Map<UUID, Long> lastHealTick = new ConcurrentHashMap<>();
    private volatile long intervalTicks;
    private volatile int healAmount;
    private volatile float exhaustionToApply;

    public ModulePlayerRegen(OCMMain plugin) {
        super(plugin, "old-player-regen");
        reload();
    }

    @Override
    public void reload() {
        final long intervalMillis = module().getLong("interval");
        // Config is in milliseconds for user friendliness, but internal logic is tick based.
        intervalTicks = Math.max(1L, Math.round(intervalMillis / 50.0));
        healAmount = module().getInt("amount");
        exhaustionToApply = (float) module().getDouble("exhaustion");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (e.getEntityType() != EntityType.PLAYER
                || e.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED)
            return;

        final Player p = (Player) e.getEntity();
        if (!isEnabled(p))
            return;

        final UUID playerId = p.getUniqueId();

        // We cancel the regen, but saturation and exhaustion need to be adjusted
        // separately
        // Exhaustion is modified in the next tick, and saturation in the tick following
        // that (if exhaustion > 4)
        e.setCancelled(true);

        // Get exhaustion & saturation values before healing modifies them
        final float previousExhaustion = p.getExhaustion();
        final float previousSaturation = p.getSaturation();

        // Check that it has been at least x ticks since last heal
        final long currentTick = p.getTicksLived();
        Long lastTick = lastHealTick.get(playerId);
        // Old CraftBukkit recreates the player on respawn, restarting its tick count: no previous heal then.
        if (lastTick != null && currentTick < lastTick) lastTick = null;
        debug("Exh: " + previousExhaustion + " Sat: " + previousSaturation + " Ticks since: " +
                        (lastTick == null ? "?" : (currentTick - lastTick)),
                p);

        // If we're skipping this heal, we must fix the exhaustion in the following tick
        if (lastTick != null && currentTick - lastTick < intervalTicks) {
            OCMScheduler.runEntityDelayed(p, () -> p.setExhaustion(previousExhaustion), null, 1L);
            return;
        }

        final double maxHealth = p.getAttribute(XAttribute.MAX_HEALTH.get()).getValue();
        final double playerHealth = p.getHealth();

        if (playerHealth < maxHealth) {
            p.setHealth(MathsHelper.clamp(playerHealth + healAmount, 0.0, maxHealth));
            lastHealTick.put(playerId, currentTick);
        }

        // Calculate new exhaustion value, must be between 0 and 4. If above, it will
        // reduce the saturation in the following tick.
        OCMScheduler.runEntityDelayed(p, () -> {
            // We do this in the next tick because bukkit doesn't stop the exhaustion change
            // when cancelling the event
            p.setExhaustion(previousExhaustion + exhaustionToApply);
            debug("Exh before: " + previousExhaustion + " Now: " + p.getExhaustion() +
                    " Sat now: " + previousSaturation, p);
        }, null, 1L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        lastHealTick.remove(e.getPlayer().getUniqueId());
    }
}
