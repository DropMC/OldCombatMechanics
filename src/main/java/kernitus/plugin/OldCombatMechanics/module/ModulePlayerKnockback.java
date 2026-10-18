/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.module;

import com.cryptomorin.xseries.XAttribute;
import kernitus.plugin.OldCombatMechanics.OCMMain;
import kernitus.plugin.OldCombatMechanics.utilities.CompatibilityCapabilities;
import kernitus.plugin.OldCombatMechanics.utilities.compatibility.MythicMobsKnockbackBridge;
import kernitus.plugin.OldCombatMechanics.utilities.scheduling.TickClock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import com.cryptomorin.xseries.XEnchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reverts knockback formula to 1.8.
 * Also disables netherite knockback resistance.
 */
public class ModulePlayerKnockback extends OCMModule {

    private volatile double knockbackHorizontal;
    private volatile double knockbackVertical;
    private volatile double knockbackVerticalLimit;
    private volatile double knockbackExtraHorizontal;
    private volatile double knockbackExtraVertical;
    private volatile boolean netheriteKnockbackResistance;

    // Knockback override for the next PlayerVelocityEvent.
    // Performance/correctness:
    // - Use a normal map (WeakHashMap can drop entries unpredictably).
    // - Keep entries for the tick they were created in only. If PlayerVelocityEvent does not fire, a stale entry
    //   must not affect a later, unrelated velocity event (explosions, plugins, etc.).
    // - No task per hit and no sweep task: the victim's thread checks the tick when it consumes the entry.
    private final Map<UUID, PendingKnockback> pendingKnockback = new ConcurrentHashMap<>();
    private final MythicMobsKnockbackBridge mythicMobsKnockbackBridge;
    private final Attribute knockbackResistanceAttribute = XAttribute.KNOCKBACK_RESISTANCE.get();

    public ModulePlayerKnockback(OCMMain plugin) {
        super(plugin, "old-player-knockback");
        mythicMobsKnockbackBridge = new MythicMobsKnockbackBridge(plugin);
        reload();
    }

    @Override
    public void reload() {
        knockbackHorizontal = module().getDouble("knockback-horizontal", 0.4);
        knockbackVertical = module().getDouble("knockback-vertical", 0.4);
        knockbackVerticalLimit = module().getDouble("knockback-vertical-limit", 0.4);
        knockbackExtraHorizontal = module().getDouble("knockback-extra-horizontal", 0.5);
        knockbackExtraVertical = module().getDouble("knockback-extra-vertical", 0.1);
        netheriteKnockbackResistance = module().getBoolean("enable-knockback-resistance", false)
                && CompatibilityCapabilities.isKnockbackResistanceAvailable();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        pendingKnockback.remove(e.getPlayer().getUniqueId());
    }

    // Vanilla does its own knockback, so we need to set it again.
    // priority = lowest because we are ignoring the existing velocity, which could
    // break other plugins
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerVelocityEvent(PlayerVelocityEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        final PendingKnockback pending = pendingKnockback.remove(uuid);
        if (pending == null || !pending.isValidAt(TickClock.ownerTick())) return;
        if (mythicMobsKnockbackBridge.consumeNoKnockback(uuid, pending.sourceId)) {
            return;
        }
        event.setVelocity(pending.velocity);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        // Disable netherite kb, the knockback resistance attribute makes the velocity
        // event not be called
        final Entity entity = event.getEntity();
        if (!(entity instanceof Player) || netheriteKnockbackResistance)
            return;
        final Player damagee = (Player) entity;
        if (!damagee.isOnline())
            return;

        // This depends on the attacker's combat mode
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event instanceof EntityDamageByEntityEvent) {
            final Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
            if (!isEnabled(damager))
                return;
        } else {
            if (!isEnabled(damagee))
                return;
        }

        final AttributeInstance attribute = knockbackResistanceAttribute == null ? null : damagee.getAttribute(knockbackResistanceAttribute);
        if (attribute == null) return;
        attribute.getModifiers().forEach(attribute::removeModifier);
    }

    // Monitor priority because we don't modify anything here, but apply on velocity
    // change event
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageEntity(EntityDamageByEntityEvent event) {
        final Entity damager = event.getDamager();
        if (!(damager instanceof LivingEntity))
            return;
        final LivingEntity attacker = (LivingEntity) damager;

        final Entity damagee = event.getEntity();
        if (!(damagee instanceof Player))
            return;
        final Player victim = (Player) damagee;

        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK)
            return;
        if (event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) > 0)
            return;

        if (attacker instanceof HumanEntity) {
            if (!isEnabled(attacker))
                return;
        } else if (!isEnabled(victim))
            return;

        // Figure out base knockback direction
        Location attackerLocation = attacker.getLocation();
        Location victimLocation = victim.getLocation();
        double d0 = attackerLocation.getX() - victimLocation.getX();
        double d1;

        for (d1 = attackerLocation.getZ() - victimLocation.getZ(); d0 * d0
                + d1 * d1 < 1.0E-4D; d1 = (Math.random() - Math.random()) * 0.01D) {
            d0 = (Math.random() - Math.random()) * 0.01D;
        }

        final double magnitude = Math.sqrt(d0 * d0 + d1 * d1);

        // Get player knockback before any friction is applied
        final Vector playerVelocity = victim.getVelocity();

        // Apply friction, then add base knockback
        playerVelocity.setX((playerVelocity.getX() / 2) - (d0 / magnitude * knockbackHorizontal));
        playerVelocity.setY((playerVelocity.getY() / 2) + knockbackVertical);
        playerVelocity.setZ((playerVelocity.getZ() / 2) - (d1 / magnitude * knockbackHorizontal));

        // Calculate bonus knockback for sprinting or knockback enchantment levels
        final EntityEquipment equipment = attacker.getEquipment();
        if (equipment != null) {
            final ItemStack heldItem = equipment.getItemInMainHand().getType() == Material.AIR
                    ? equipment.getItemInOffHand()
                    : equipment.getItemInMainHand();

            int bonusKnockback;
            if (XEnchantment.KNOCKBACK.getEnchant() == null) {
                bonusKnockback = 0;
            } else {
                bonusKnockback = heldItem.getEnchantmentLevel(XEnchantment.KNOCKBACK.getEnchant());
            }
            if (attacker instanceof Player && ((Player) attacker).isSprinting()) {
                bonusKnockback++;
            }

            if (playerVelocity.getY() > knockbackVerticalLimit)
                playerVelocity.setY(knockbackVerticalLimit);

            if (bonusKnockback > 0) { // Apply bonus knockback
                playerVelocity.add(new Vector((-Math.sin(attacker.getLocation().getYaw() * 3.1415927F / 180.0F) *
                        (float) bonusKnockback * knockbackExtraHorizontal), knockbackExtraVertical,
                        Math.cos(attacker.getLocation().getYaw() * 3.1415927F / 180.0F) *
                                (float) bonusKnockback * knockbackExtraHorizontal));
            }
        }

        if (netheriteKnockbackResistance) {
            // Allow netherite to affect the horizontal knockback. Each piece of armour
            // yields 10% resistance
            final AttributeInstance attribute = knockbackResistanceAttribute == null ? null : victim.getAttribute(knockbackResistanceAttribute);
            if (attribute != null) {
                final double resistance = 1 - attribute.getValue();
                playerVelocity.multiply(new Vector(resistance, 1, resistance));
            }
        }

        final UUID victimId = victim.getUniqueId();
        if (mythicMobsKnockbackBridge.consumeNoKnockback(victimId, attacker.getUniqueId())) {
            return;
        }

        // Knockback is sent immediately in 1.8+, there is no reason to send packets
        // manually
        final long tick = TickClock.ownerTick();
        pendingKnockback.put(victimId, new PendingKnockback(playerVelocity, attacker.getUniqueId(), tick, tick));
    }

    private static final class PendingKnockback {
        private final Vector velocity;
        private final UUID sourceId;
        // Valid from createdAtTick to expiresAtTick inclusive, on the victim's clock. Both bounds matter on Folia,
        // where moving to another region can move the clock backwards.
        private final long createdAtTick;
        private final long expiresAtTick;

        // Integration tests inject entries with this shape.
        private PendingKnockback(Vector velocity, UUID sourceId, long expiresAtTick) {
            this(velocity, sourceId, Long.MIN_VALUE, expiresAtTick);
        }

        private PendingKnockback(Vector velocity, UUID sourceId, long createdAtTick, long expiresAtTick) {
            // Defensive clone: callers may re-use/mutate the Vector instance.
            this.velocity = velocity == null ? new Vector() : velocity.clone();
            this.sourceId = sourceId;
            this.createdAtTick = createdAtTick;
            this.expiresAtTick = expiresAtTick;
        }

        private boolean isValidAt(long tick) {
            return tick >= createdAtTick && tick <= expiresAtTick;
        }
    }
}
