/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.utilities.storage

import kernitus.plugin.OldCombatMechanics.ModuleLoader
import kernitus.plugin.OldCombatMechanics.api.PlayerModuleOverride
import org.bukkit.OfflinePlayer
import org.bukkit.entity.HumanEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-player module override store.
 */
object PlayerModuleOverrides {
    private val overridesByPlayer = ConcurrentHashMap<UUID, ConcurrentHashMap<String, PlayerModuleOverride>>()

    @JvmStatic
    fun setOverride(
        human: HumanEntity,
        moduleName: String,
        state: PlayerModuleOverride,
    ) {
        requireOnline(human)
        val normalised = ModuleLoader.normaliseModuleName(moduleName)
        if (state == PlayerModuleOverride.DEFAULT) {
            clearOverride(human, normalised)
            return
        }
        // Atomic per player, so a concurrent clearOverride cannot drop the map this write lands in.
        overridesByPlayer.compute(human.uniqueId) { _, existing ->
            (existing ?: ConcurrentHashMap()).also { it[normalised] = state }
        }
    }

    @JvmStatic
    fun getOverride(
        human: HumanEntity,
        moduleName: String,
    ): PlayerModuleOverride {
        requireOnline(human)
        val perPlayer = overridesByPlayer[human.uniqueId] ?: return PlayerModuleOverride.DEFAULT
        return perPlayer.getOrDefault(ModuleLoader.normaliseModuleName(moduleName), PlayerModuleOverride.DEFAULT)
    }

    /** Like [getOverride], but returns null instead of throwing when the player has just gone offline. */
    @JvmStatic
    fun getOverrideIfOnline(
        human: HumanEntity,
        moduleName: String,
    ): PlayerModuleOverride? {
        if (!isOnline(human)) return null
        val perPlayer = overridesByPlayer[human.uniqueId] ?: return PlayerModuleOverride.DEFAULT
        return perPlayer.getOrDefault(ModuleLoader.normaliseModuleName(moduleName), PlayerModuleOverride.DEFAULT)
    }

    @JvmStatic
    fun clearOverride(
        human: HumanEntity,
        moduleName: String,
    ): Boolean {
        requireOnline(human)
        val normalised = ModuleLoader.normaliseModuleName(moduleName)
        var removed: PlayerModuleOverride? = null
        overridesByPlayer.computeIfPresent(human.uniqueId) { _, perPlayer ->
            removed = perPlayer.remove(normalised)
            if (perPlayer.isEmpty()) null else perPlayer
        }
        return removed != null
    }

    @JvmStatic
    fun clearAll(human: HumanEntity): Boolean {
        requireOnline(human)
        return overridesByPlayer.remove(human.uniqueId) != null
    }

    @JvmStatic
    fun clearAll() {
        overridesByPlayer.clear()
    }

    @JvmStatic
    private fun requireOnline(human: HumanEntity) {
        require(isOnline(human)) { "HumanEntity ${human.name} is not online" }
    }

    private fun isOnline(human: HumanEntity): Boolean =
        when (human) {
            is OfflinePlayer -> human.isOnline
            else -> true // HumanEntity (e.g. a living NPC) — assume present in world
        }
}
