/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.utilities.damage;

import kernitus.plugin.OldCombatMechanics.OCMMain;
import kernitus.plugin.OldCombatMechanics.module.OCMModule;
import kernitus.plugin.OldCombatMechanics.module.ModuleSwordBlocking;
import kernitus.plugin.OldCombatMechanics.utilities.scheduling.OCMScheduler;
import kernitus.plugin.OldCombatMechanics.utilities.scheduling.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class EntityDamageByEntityListener extends OCMModule {

    private static EntityDamageByEntityListener INSTANCE;
    private volatile boolean enabled;
    // Written by the damaged entity's thread with plain puts of immutable entries; the memory sweep on the global
    // thread removes only the exact entry it judged.
    private final ConcurrentHashMap<UUID, StoredDamage> lastDamages;
    private volatile TaskHandle expirySweepTask;
    private static final long EXPIRY_SWEEP_INTERVAL_TICKS = 20L;
    private static final long MIN_LAST_DAMAGE_TTL_TICKS = 20L;
    // Memory only: entries untouched for this long are dropped by the sweep. Gameplay expiry uses entity ticks.
    private static final long MIN_SWEEP_AGE_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final long NANOS_PER_TICK = TimeUnit.MILLISECONDS.toNanos(50L);
    private static final String[] DAMAGE_TYPE_REGISTRIES = {"damage_type", "damage_types"};
    private static final String BYPASSES_COOLDOWN_TAG = "bypasses_cooldown";
    private volatile DamageSourceCooldownBypassSupport damageSourceCooldownBypassSupport;

    public EntityDamageByEntityListener(OCMMain plugin) {
        super(plugin, "entity-damage-listener");
        INSTANCE = this;
        lastDamages = new ConcurrentHashMap<>();
    }

    public static EntityDamageByEntityListener getINSTANCE() {
        return INSTANCE;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            startExpirySweeperIfNeeded();
        } else {
            stopExpirySweeperIfNeeded();
            lastDamages.clear();
        }
    }

    private synchronized void startExpirySweeperIfNeeded() {
        if (expirySweepTask != null) return;
        expirySweepTask = OCMScheduler.runGlobalAtFixedRate(this::sweepExpiredEntries,
                EXPIRY_SWEEP_INTERVAL_TICKS, EXPIRY_SWEEP_INTERVAL_TICKS);
    }

    private synchronized void stopExpirySweeperIfNeeded() {
        final TaskHandle task = expirySweepTask;
        if (task == null) return;
        task.cancel();
        expirySweepTask = null;
    }

    /** Stores the damage and extends its expiry, counted in the damaged entity's own ticks. */
    private void storeLastDamage(LivingEntity damagee, double damage) {
        // Some implementations / test setups set maximumNoDamageTicks to 0, but damage immunity bookkeeping can
        // still matter for a short period (e.g. cancelled fire ticks during invulnerability). Keep a small minimum.
        final long delayTicks = Math.max(MIN_LAST_DAMAGE_TTL_TICKS, damagee.getMaximumNoDamageTicks());
        final long nowTicks = damagee.getTicksLived();
        final long candidateExpiry = nowTicks + delayTicks;
        final UUID uuid = damagee.getUniqueId();
        final StoredDamage existing = lastDamages.get(uuid);
        if (existing != null && existing.expires && !existing.isReset(nowTicks)
                && candidateExpiry <= existing.expiryTicks) {
            // The expiry does not grow: keep it, and the entry itself when the damage is unchanged too.
            if (Double.compare(existing.damage, damage) == 0) return;
            lastDamages.put(uuid, new StoredDamage(damage, existing.createdTicks, existing.expiryTicks,
                    existing.sweepAtNanos));
            return;
        }
        final long sweepAfterNanos = Math.max(MIN_SWEEP_AGE_NANOS, 4L * delayTicks * NANOS_PER_TICK);
        lastDamages.put(uuid, new StoredDamage(damage, nowTicks, candidateExpiry, System.nanoTime() + sweepAfterNanos));
    }

    private void sweepExpiredEntries() {
        final long now = System.nanoTime();
        for (Map.Entry<UUID, StoredDamage> entry : lastDamages.entrySet()) {
            final StoredDamage judged = entry.getValue();
            if (!judged.expires || now - judged.sweepAtNanos < 0) continue;
            lastDamages.remove(entry.getKey(), judged);
        }
    }

    private static final class StoredDamage {
        private final double damage;
        // False only for entries injected by integration tests, which never expire, as a damage with no expiry did.
        private final boolean expires;
        private final long createdTicks;
        private final long expiryTicks;
        private final long sweepAtNanos;

        // Integration tests inject entries with this shape.
        private StoredDamage(double damage) {
            this.damage = damage;
            this.expires = false;
            this.createdTicks = 0L;
            this.expiryTicks = 0L;
            this.sweepAtNanos = 0L;
        }

        private StoredDamage(double damage, long createdTicks, long expiryTicks, long sweepAtNanos) {
            this.damage = damage;
            this.expires = true;
            this.createdTicks = createdTicks;
            this.expiryTicks = expiryTicks;
            this.sweepAtNanos = sweepAtNanos;
        }

        // Old CraftBukkit recreates a player on respawn, which restarts its tick count.
        private boolean isReset(long nowTicks) {
            return nowTicks < createdTicks;
        }

        private boolean isExpired(long nowTicks) {
            return expires && (isReset(nowTicks) || expiryTicks <= nowTicks);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        final Entity damagee = event.getEntity();

        if (!(event instanceof EntityDamageByEntityEvent)) {
            // Damage immunity only applies to living entities
            if (!(damagee instanceof LivingEntity)) return;
            final LivingEntity livingDamagee = ((LivingEntity) damagee);

            final StoredDamage stored = lastDamages.get(livingDamagee.getUniqueId());
            final Double storedDamage = stored == null ? null : stored.damage;
            debug("Non-entity damage before restore: lastDamage=" + livingDamagee.getLastDamage()
                    + " stored=" + storedDamage, livingDamagee);
            debug("Non-entity damage before restore: lastDamage=" + livingDamagee.getLastDamage()
                    + " stored=" + storedDamage);

            restoreLastDamage(livingDamagee);
            debug("Non-entity damage after restore: lastDamage=" + livingDamagee.getLastDamage(), livingDamagee);
            debug("Non-entity damage after restore: lastDamage=" + livingDamagee.getLastDamage());

            double newDamage = event.getDamage(); // base damage, before defence calculations

            // Overdamage due to immunity
            // Invulnerability will cause less damage if they attack with a stronger weapon while vulnerable
            // That is, the difference in damage will be dealt, but only if new attack is stronger than previous one
            newDamage = checkOverdamage(livingDamagee, event, newDamage);

            if (newDamage < 0) {
                debug("Damage was " + newDamage + " setting to 0");
                newDamage = 0;
            }

            // Set damage, this should scale effects in the 1.9 way in case some of our modules are disabled
            event.setDamage(newDamage);
            debug("Attack damage (before defence): " + newDamage);

        } else {
            final Entity damager = ((EntityDamageByEntityEvent) event).getDamager();

            // Call event constructor before setting lastDamage back, because we need it for calculations
            final OCMEntityDamageByEntityEvent e = new OCMEntityDamageByEntityEvent
                    (damager, damagee, event.getCause(), event.getDamage());

            // Set last damage to actual value for other modules and plugins to use
            // This will be set back to 0 in MONITOR listener on the next tick to detect all potential overdamages.
            // If there is large delay between last time an entity was damaged and the next damage,
            // the last damage might have been removed from the weak hash map. This is intended, as the immunity
            // ticks tends to be a short period of time anyway and last damage is irrelevant after immunity has expired.
            if (damagee instanceof LivingEntity)
                restoreLastDamage((LivingEntity) damagee);

            // Call event for the other modules to make their modifications
            plugin.getServer().getPluginManager().callEvent(e);

            if (e.isCancelled()) return;

            // Now we re-calculate damage modified by the modules and set it back to original event
            // Attack components order: (Base + Potion effects, scaled by attack delay) + Critical Hit + (Enchantments, scaled by attack delay)
            // Hurt components order: Overdamage - Armour - Resistance - Armour enchants - Absorption
            double newDamage = e.getBaseDamage();

            debug("Base: " + e.getBaseDamage(), damager);
            debug("Base: " + e.getBaseDamage());

            // Weakness potion
            final double weaknessModifier = e.getWeaknessModifier() * e.getWeaknessLevel();
            final double weaknessAddend = e.isWeaknessModifierMultiplier() ? newDamage * weaknessModifier : weaknessModifier;
            // Don't modify newDamage yet so both potion effects are calculated off of the base damage
            debug("Weak: " + weaknessAddend);
            debug("Weak: " + weaknessAddend, damager);

            // Strength potion
            debug("Strength level: " + e.getStrengthLevel());
            debug("Strength level: " + e.getStrengthLevel(), damager);
            double strengthModifier = e.getStrengthModifier() * e.getStrengthLevel();
            if (!e.isStrengthModifierMultiplier()) newDamage += strengthModifier;
            else if (e.isStrengthModifierAddend()) newDamage *= ++strengthModifier;
            else newDamage *= strengthModifier;

            debug("Strength: " + strengthModifier);
            debug("Strength: " + strengthModifier, damager);

            newDamage += weaknessAddend;

            // Scale by attack delay
            // float currentItemAttackStrengthDelay = 1.0D / GenericAttributes.ATTACK_SPEED * 20.0D
            // attack strength ticker goes up by 1 every tick, is reset to 0 after an attack
            // float f2 = MathHelper.clamp((attackStrengthTicker + 0.5) / currentItemAttackStrengthDelay, 0.0F, 1.0F);
            // f *= 0.2F + f2 * f2 * 0.8F;
            // the multiplier is equivalent to y = 0.8x^2 + 0.2
            // because x (f2) is always between 0 and 1, the multiplier will always be between 0.2 and 1
            // this implies 40 speed is the minimum to always have full attack strength
            if (damager instanceof HumanEntity) {
                final float cooldown = DamageUtils.getAttackCooldown.apply((HumanEntity) damager, 0.5F); // i.e. f2
                debug("Scale by attack delay: " + newDamage + " *= 0.2 + " + cooldown + "^2 * 0.8");
                newDamage *= 0.2F + cooldown * cooldown * 0.8F;
            }

            // Critical hit
            final double criticalMultiplier = e.getCriticalMultiplier();
            debug("Crit " + newDamage + " *= " + criticalMultiplier);
            newDamage *= criticalMultiplier;

            // Enchantment damage, scaled by attack cooldown
            double enchantmentDamage = e.getMobEnchantmentsDamage() + e.getSharpnessDamage();
            if (damager instanceof HumanEntity) {
                final float cooldown = DamageUtils.getAttackCooldown.apply((HumanEntity) damager, 0.5F);
                debug("Scale enchantments by attack delay: " + enchantmentDamage + " *= " + cooldown);
                enchantmentDamage *= cooldown;
            }
            newDamage += enchantmentDamage;
            debug("Mob " + e.getMobEnchantmentsDamage() + " Sharp: " + e.getSharpnessDamage() + " Scaled: " + enchantmentDamage, damager);

            // Paper sword blocking (consumable-based, no shield)
            final ModuleSwordBlocking swordBlocking = ModuleSwordBlocking.getInstance();
            double paperBlockReduction = 0;
            if (event instanceof EntityDamageByEntityEvent && swordBlocking != null) {
                paperBlockReduction = swordBlocking.applyPaperBlockingReduction((EntityDamageByEntityEvent) event, newDamage);
                if (paperBlockReduction > 0) {
                    final double preBlockDamage = newDamage;
                    newDamage = Math.max(0, newDamage - paperBlockReduction);
                    ((EntityDamageByEntityEvent) event).setDamage(EntityDamageEvent.DamageModifier.BLOCKING, -paperBlockReduction);
                    ((EntityDamageByEntityEvent) event).setDamage(EntityDamageEvent.DamageModifier.BASE, preBlockDamage);
                    debug("Sword block (Paper): " + preBlockDamage + " - " + paperBlockReduction + " = " + newDamage, damager);
                }
            }

            if (damagee instanceof LivingEntity) {
                // Overdamage due to immunity
                // Invulnerability will cause less damage if they attack with a stronger weapon while vulnerable
                // That is, the difference in damage will be dealt, but only if new attack is stronger than previous one
                // Value before overdamage will become new "last damage"
                newDamage = checkOverdamage(((LivingEntity) damagee), event, newDamage);
            }

            if (newDamage < 0) {
                debug("Damage was " + newDamage + " setting to 0", damager);
                newDamage = 0;
            }

            // Set damage; if we already populated modifiers for blocking, avoid overwriting BASE.
            if (paperBlockReduction > 0 && event instanceof EntityDamageByEntityEvent) {
                ((EntityDamageByEntityEvent) event).setDamage(EntityDamageEvent.DamageModifier.BASE, newDamage + paperBlockReduction);
                // BLOCKING was set earlier; total damage is BASE + BLOCKING (+ others)
            } else {
                event.setDamage(newDamage);
            }
            debug("New Damage: " + newDamage, damager);
            debug("Attack damage (before defence): " + newDamage);
        }
    }

    /**
     * Set entity's last damage to 0 a tick after the event so all overdamage attacks get through.
     * The last damage is overridden by NMS code regardless of what the actual damage is set to via Spigot.
     * Finally, the LOWEST priority listener above will set the last damage back to the correct value
     * for other plugins to use the next time the entity is damaged.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void afterEntityDamage(EntityDamageEvent event) {
        final Entity damagee = event.getEntity();

        if (event instanceof EntityDamageByEntityEvent) {
            // Expired entries count as absent, as they did once the per-tick sweep had removed them.
            if (damagee instanceof LivingEntity && resolveStoredDamage((LivingEntity) damagee) != null) {
                // Set last damage to 0, so we can detect attacks even by weapons with a weaker attack value than what OCM would calculate
                OCMScheduler.runEntityDelayed(damagee, () -> {
                    ((LivingEntity) damagee).setLastDamage(0);
                    debug("Set last damage to 0", damagee);
                    debug("Set last damage to 0");
                }, null, 1L);
            }
        } else {
            // if not EDBYE then we leave last damage as is
            if (damagee instanceof LivingEntity) {
                final LivingEntity livingDamagee = (LivingEntity) damagee;
                if ((float) livingDamagee.getNoDamageTicks() > (float) livingDamagee.getMaximumNoDamageTicks() / 2.0F
                        && resolveStoredDamage(livingDamagee) != null) {
                    debug("Non-entity damage inside invulnerability window, keeping stored last damage", livingDamagee);
                    debug("Non-entity damage inside invulnerability window, keeping stored last damage");
                    return;
                }
                clearStoredDamage(livingDamagee);
            }
            debug("Non-entity damage, using default last damage", damagee);
            debug("Non-entity damage, using default last damage");
        }
    }

    /**
     * Restored the correct last damage for the given entity
     *
     * @param damagee The living entity to try to restore the last damage for
     */
    private void restoreLastDamage(LivingEntity damagee) {
        final StoredDamage stored = resolveStoredDamage(damagee);
        if (stored != null) {
            final double lastStoredDamage = stored.damage;
            final LivingEntity livingDamagee = damagee;
            livingDamagee.setLastDamage(lastStoredDamage);
            storeLastDamage(livingDamagee, lastStoredDamage);
            debug("Set last damage back to " + lastStoredDamage, livingDamagee);
            debug("Set last damage back to " + lastStoredDamage);
        } else {
            debug("No stored last damage to restore", damagee);
            debug("No stored last damage to restore");
        }
    }

    private double checkOverdamage(LivingEntity livingDamagee, EntityDamageEvent event, double newDamage) {
        if (damageSourceBypassesCooldown(event)) {
            debug("Damage source bypasses cooldown, skipping overdamage immunity", livingDamagee);
            debug("Damage source bypasses cooldown, skipping overdamage immunity");
            return newDamage;
        }

        final double incomingDamage = newDamage; // base damage (before defence), used for baseline tracking
        final double newLastDamage = Math.max(0, incomingDamage);

        /*
         * Vanilla 1.12 EntityLiving#damageEntity(DamageSource, float) flow:
         * - If noDamageTicks > maxNoDamageTicks / 2:
         *     - If damage <= lastDamage -> return false (cancel)
         *     - Else call damageEntity0(source, damage - lastDamage)
         *     - Then lastDamage = damage
         * - Else:
         *     - Call damageEntity0(source, damage)
         *     - lastDamage = damage
         *     - Set noDamageTicks = maxNoDamageTicks, etc.
         *
         * This means any successful fire tick overwrites lastDamage, so we must restore
         * the correct baseline before applying our overdamage checks.
         */
        if ((float) livingDamagee.getNoDamageTicks() > (float) livingDamagee.getMaximumNoDamageTicks() / 2.0F) {
            // Last damage was either set to correct value above in this listener, or we're using the server's value
            // If other plugins later modify BASE damage, they should either be taking last damage into account,
            // or ignoring the event if it is cancelled
            final StoredDamage stored = resolveStoredDamage(livingDamagee);
            final double lastDamage = stored != null ? stored.damage : livingDamagee.getLastDamage();
            if (newDamage <= lastDamage) {
                event.setDamage(0);
                event.setCancelled(true);
                debug("Was fake overdamage, cancelling " + newDamage + " <= " + lastDamage);
                // Do not overwrite the stored baseline with this cancelled damage (e.g. fire tick),
                // otherwise the next attack can incorrectly bypass immunity.
                storeLastDamage(livingDamagee, lastDamage);
                return 0;
            }

            debug("Overdamage: " + newDamage + " - " + lastDamage);
            // We must subtract previous damage from new weapon damage for this attack
            newDamage -= lastDamage;

            debug("Last damage " + lastDamage + " new damage: " + newLastDamage + " applied: " + newDamage
                    + " ticks: " + livingDamagee.getNoDamageTicks() + " /" + livingDamagee.getMaximumNoDamageTicks()
            );
        }
        // Update the last damage done, including when it was overdamage.
        // This means attacks must keep increasing in value during immunity period to keep dealing overdamage.
        storeLastDamage(livingDamagee, newLastDamage);

        return newDamage;
    }

    private StoredDamage resolveStoredDamage(LivingEntity damagee) {
        final UUID uuid = damagee.getUniqueId();
        final StoredDamage stored = lastDamages.get(uuid);
        if (stored != null && stored.isExpired(damagee.getTicksLived())) {
            lastDamages.remove(uuid, stored);
            return null;
        }
        return stored;
    }

    private void clearStoredDamage(LivingEntity damagee) {
        lastDamages.remove(damagee.getUniqueId());
    }

    private boolean damageSourceBypassesCooldown(EntityDamageEvent event) {
        DamageSourceCooldownBypassSupport support = damageSourceCooldownBypassSupport;
        if (support == null) {
            support = DamageSourceCooldownBypassSupport.create();
            damageSourceCooldownBypassSupport = support;
        }
        return support.isBypassed(event);
    }

    private static final class DamageSourceCooldownBypassSupport {
        private final Method getDamageSourceMethod;
        private final Method getDamageTypeMethod;
        private final Method isTaggedMethod;
        private final Object bypassesCooldownTag;

        private DamageSourceCooldownBypassSupport(
                Method getDamageSourceMethod,
                Method getDamageTypeMethod,
                Method isTaggedMethod,
                Object bypassesCooldownTag
        ) {
            this.getDamageSourceMethod = getDamageSourceMethod;
            this.getDamageTypeMethod = getDamageTypeMethod;
            this.isTaggedMethod = isTaggedMethod;
            this.bypassesCooldownTag = bypassesCooldownTag;
        }

        private static DamageSourceCooldownBypassSupport create() {
            try {
                final Class<?> damageSourceClass = Class.forName("org.bukkit.damage.DamageSource");
                final Class<?> damageTypeClass = Class.forName("org.bukkit.damage.DamageType");
                final Method getDamageSourceMethod = EntityDamageEvent.class.getMethod("getDamageSource");
                final Method getDamageTypeMethod = damageSourceClass.getMethod("getDamageType");
                final Object tag = findBypassesCooldownTag(damageTypeClass);
                if (tag == null) return unsupported();
                final Method isTaggedMethod = findIsTaggedMethod(tag);
                if (isTaggedMethod == null) return unsupported();
                return new DamageSourceCooldownBypassSupport(getDamageSourceMethod, getDamageTypeMethod, isTaggedMethod, tag);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                return unsupported();
            } catch (Exception ignored) {
                return unsupported();
            }
        }

        private static DamageSourceCooldownBypassSupport unsupported() {
            return new DamageSourceCooldownBypassSupport(null, null, null, null);
        }

        private static Object findBypassesCooldownTag(Class<?> damageTypeClass) {
            for (String registry : DAMAGE_TYPE_REGISTRIES) {
                try {
                    final Object tag = Bukkit.class
                            .getMethod("getTag", String.class, NamespacedKey.class, Class.class)
                            .invoke(null, registry, NamespacedKey.minecraft(BYPASSES_COOLDOWN_TAG), damageTypeClass);
                    if (tag != null) return tag;
                } catch (Exception ignored) {
                    // Try the next registry spelling; older APIs may not expose damage-type tags at all.
                }
            }
            return null;
        }

        private static Method findIsTaggedMethod(Object tag) {
            for (Method method : tag.getClass().getMethods()) {
                if (!"isTagged".equals(method.getName()) || method.getParameterTypes().length != 1) continue;
                return method;
            }
            return null;
        }

        private boolean isBypassed(EntityDamageEvent event) {
            if (bypassesCooldownTag == null) return false;
            try {
                final Object damageSource = getDamageSourceMethod.invoke(event);
                if (damageSource == null) return false;
                final Object damageType = getDamageTypeMethod.invoke(damageSource);
                if (damageType == null) return false;
                return Boolean.TRUE.equals(isTaggedMethod.invoke(bypassesCooldownTag, damageType));
            } catch (Exception ignored) {
                return false;
            }
        }
    }

}
