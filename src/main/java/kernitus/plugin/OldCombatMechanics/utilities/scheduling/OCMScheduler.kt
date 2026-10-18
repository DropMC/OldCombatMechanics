/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.utilities.scheduling

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.function.Consumer
import java.util.logging.Level

/**
 * A cancellable scheduled task, backed by either a Folia [io.papermc.paper.threadedregions.scheduler.ScheduledTask]
 * or a [BukkitTask].
 */
interface TaskHandle {
    fun cancel()

    val isCancelled: Boolean
}

/**
 * Schedules work on the thread that owns what it touches.
 *
 * Paper (1.19.4+), Folia and Canvas expose the region scheduler API, which is called through method handles resolved
 * once on enable because the compile classpath is spigot-api. Without that API every call falls back to the Bukkit
 * scheduler with the same delays as before.
 */
object OCMScheduler {
    private const val NO_TICK = Long.MIN_VALUE

    private lateinit var plugin: Plugin

    @Volatile
    private var folia: FoliaApi? = null

    @JvmStatic
    fun initialise(plugin: Plugin) {
        this.plugin = plugin
        folia = FoliaApi.resolve(plugin)
        TickClock.initialise(plugin)
    }

    @JvmStatic
    fun hasRegionScheduler(): Boolean = folia != null

    @JvmStatic
    fun isOwnedByCurrentRegion(entity: Entity): Boolean {
        val api = folia ?: return Bukkit.isPrimaryThread()
        return api.ownsEntity(entity)
    }

    @JvmStatic
    fun isOwnedByCurrentRegion(location: Location): Boolean {
        val api = folia ?: return Bukkit.isPrimaryThread()
        return api.ownsLocation(location)
    }

    /** Runs [task] now if the current thread owns [entity], otherwise on the entity's next tick. */
    @JvmStatic
    fun runOn(
        entity: Entity,
        task: Runnable,
        retired: Runnable?,
    ) {
        val api = folia
        if (api == null) {
            if (Bukkit.isPrimaryThread()) task.run() else Bukkit.getScheduler().runTask(plugin, task)
            return
        }
        if (api.ownsEntity(entity)) {
            task.run()
            return
        }
        if (!api.entityExecute(entity, plugin, task, retired, 1L)) retired?.run()
    }

    @JvmStatic
    fun forEachOnlinePlayer(action: Consumer<Player>) {
        for (player in Bukkit.getOnlinePlayers()) {
            runOn(player, Runnable { action.accept(player) }, null)
        }
    }

    /**
     * Runs [task] on [entity]'s thread after [delay] ticks. Returns false when the entity is already gone, in which
     * case [retired] has run.
     */
    @JvmStatic
    fun runEntityDelayed(
        entity: Entity,
        task: Runnable,
        retired: Runnable?,
        delay: Long,
    ): Boolean {
        val api = folia
        if (api == null) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delay)
            return true
        }
        val ticks = maxOf(1L, delay)
        val dueTick = if (api.ownsEntity(entity)) TickClock.ownerTick() + ticks else NO_TICK
        val aligned = AlignedTask(api, entity, task, retired, ticks, dueTick)
        if (!api.entityExecute(entity, plugin, aligned, retired, ticks)) {
            retired?.run()
            return false
        }
        return true
    }

    /**
     * Repeats [task] on [entity]'s thread. Returns null when the entity is already gone, in which case [retired]
     * has run.
     */
    @JvmStatic
    fun runEntityAtFixedRate(
        entity: Entity,
        task: Consumer<TaskHandle>,
        retired: Runnable?,
        initialDelay: Long,
        period: Long,
    ): TaskHandle? {
        val api = folia
        if (api == null) {
            val handle = BukkitTaskHandle()
            handle.bind(
                Bukkit.getScheduler().runTaskTimer(
                    plugin,
                    Runnable { if (!handle.isCancelled) task.accept(handle) },
                    initialDelay,
                    period,
                ),
            )
            return handle
        }
        val handle = FoliaTaskHandle(api)
        val raw =
            api.entityRunAtFixedRate(
                entity,
                plugin,
                Consumer<Any> { scheduled ->
                    handle.bind(scheduled)
                    if (!handle.isCancelled) task.accept(handle)
                },
                retired,
                maxOf(1L, initialDelay),
                maxOf(1L, period),
            )
        if (raw == null) {
            retired?.run()
            return null
        }
        handle.bind(raw)
        return handle
    }

    /** Runs [task] on the thread that owns [location]'s chunk, on its next tick. */
    @JvmStatic
    fun runAt(
        location: Location,
        task: Runnable,
    ) {
        val api = folia
        if (api == null) {
            Bukkit.getScheduler().runTask(plugin, task)
            return
        }
        api.regionExecute(plugin, location.world!!, location.blockX shr 4, location.blockZ shr 4, task)
    }

    /** Runs [task] now if this is the global (or main) thread, otherwise on the global region's next tick. */
    @JvmStatic
    fun runGlobal(task: Runnable) {
        val api = folia
        if (api == null) {
            if (Bukkit.isPrimaryThread()) task.run() else Bukkit.getScheduler().runTask(plugin, task)
            return
        }
        if (api.isGlobalThread()) task.run() else api.globalExecute(plugin, task)
    }

    @JvmStatic
    fun runGlobalDelayed(
        task: Runnable,
        delay: Long,
    ): TaskHandle {
        val api = folia
        if (api == null) {
            return BukkitTaskHandle().also { it.bind(Bukkit.getScheduler().runTaskLater(plugin, task, delay)) }
        }
        val handle = FoliaTaskHandle(api)
        handle.bind(
            api.globalRunDelayed(
                plugin,
                Consumer<Any> { if (!handle.isCancelled) task.run() },
                maxOf(1L, delay),
            ),
        )
        return handle
    }

    @JvmStatic
    fun runGlobalAtFixedRate(
        task: Runnable,
        initialDelay: Long,
        period: Long,
    ): TaskHandle {
        val api = folia
        if (api == null) {
            return BukkitTaskHandle().also {
                it.bind(Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelay, period))
            }
        }
        val handle = FoliaTaskHandle(api)
        handle.bind(
            api.globalRunAtFixedRate(
                plugin,
                Consumer<Any> { if (!handle.isCancelled) task.run() },
                maxOf(1L, initialDelay),
                maxOf(1L, period),
            ),
        )
        return handle
    }

    @JvmStatic
    fun runAsync(task: Runnable) {
        val api = folia
        if (api == null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task)
            return
        }
        api.asyncRunNow(plugin, Consumer<Any> { task.run() })
    }

    /** Runs [task] off the tick threads after [ticks] server ticks, like Bukkit's runTaskLaterAsynchronously. */
    @JvmStatic
    fun runAsyncAfterTicks(
        ticks: Long,
        task: Runnable,
    ): TaskHandle {
        val api = folia
        if (api == null) {
            return BukkitTaskHandle().also {
                it.bind(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks))
            }
        }
        val handle = FoliaTaskHandle(api)
        handle.bind(
            api.globalRunDelayed(
                plugin,
                Consumer<Any> {
                    if (!handle.isCancelled) {
                        api.asyncRunNow(plugin, Consumer<Any> { if (!handle.isCancelled) task.run() })
                    }
                },
                maxOf(1L, ticks),
            ),
        )
        return handle
    }

    @JvmStatic
    fun cancelGlobalAndAsync() {
        val api = folia
        if (api == null) {
            Bukkit.getScheduler().cancelTasks(plugin)
            return
        }
        api.globalCancelTasks(plugin)
        api.asyncCancelTasks(plugin)
    }

    /**
     * Paper runs the Bukkit heartbeat before the entity schedulers, so a 1-tick entity task scheduled from a Bukkit
     * task would run later in the same tick. When the delay has not elapsed on the owner's clock yet, wait the rest.
     */
    private class AlignedTask(
        private val api: FoliaApi,
        private val entity: Entity,
        private val task: Runnable,
        private val retired: Runnable?,
        private val delay: Long,
        private var dueTick: Long,
    ) : Runnable {
        override fun run() {
            val due = dueTick
            if (due != NO_TICK) {
                dueTick = NO_TICK
                val now = TickClock.ownerTick()
                if (now < due) {
                    if (!api.entityExecute(entity, plugin, this, retired, minOf(due - now, delay))) retired?.run()
                    return
                }
            }
            task.run()
        }
    }
}

/**
 * The current tick of the thread that owns the entity being looked at: Paper's server tick, or the region's tick on
 * Folia. Only meaningful for same-tick checks on that owner thread.
 */
object TickClock {
    private var currentTick: MethodHandle? = null

    @Volatile
    private var fallbackTick = 0L

    internal fun initialise(plugin: Plugin) {
        currentTick =
            try {
                MethodHandles
                    .publicLookup()
                    .findVirtual(
                        Server::class.java,
                        "getCurrentTick",
                        MethodType.methodType(Int::class.javaPrimitiveType),
                    ).bindTo(Bukkit.getServer())
            } catch (ignored: ReflectiveOperationException) {
                null
            }
        if (currentTick == null) {
            // Spigot has no tick counter: count heartbeats, as the old per-module sweep tasks did.
            fallbackTick = 0L
            Bukkit.getScheduler().runTaskTimer(plugin, Runnable { fallbackTick++ }, 1L, 1L)
        }
    }

    @JvmStatic
    fun ownerTick(): Long {
        val handle = currentTick ?: return fallbackTick
        return (handle.invokeExact() as Int).toLong()
    }
}

class BukkitTaskHandle : TaskHandle {
    @Volatile
    private var task: BukkitTask? = null

    @Volatile
    override var isCancelled = false
        private set

    internal fun bind(task: BukkitTask) {
        this.task = task
        if (isCancelled) task.cancel()
    }

    override fun cancel() {
        isCancelled = true
        task?.cancel()
    }
}

class FoliaTaskHandle internal constructor(
    private val api: FoliaApi,
) : TaskHandle {
    @Volatile
    private var task: Any? = null

    @Volatile
    override var isCancelled = false
        private set

    internal fun bind(task: Any) {
        if (this.task === task) return
        this.task = task
        if (isCancelled) api.cancel(task)
    }

    override fun cancel() {
        isCancelled = true
        task?.let { api.cancel(it) }
    }
}

/** Method handles for the region scheduler API, typed with Object so this compiles against spigot-api. */
class FoliaApi private constructor(
    private val getEntityScheduler: MethodHandle,
    private val entityExecute: MethodHandle,
    private val entityRunAtFixedRate: MethodHandle,
    private val regionExecute: MethodHandle,
    private val globalExecute: MethodHandle,
    private val globalRunDelayed: MethodHandle,
    private val globalRunAtFixedRate: MethodHandle,
    private val globalCancelTasks: MethodHandle,
    private val asyncRunNow: MethodHandle,
    private val asyncCancelTasks: MethodHandle,
    private val taskCancel: MethodHandle,
    private val ownsEntity: MethodHandle,
    private val ownsLocation: MethodHandle,
    private val isGlobalThread: MethodHandle?,
) {
    fun ownsEntity(entity: Entity): Boolean = ownsEntity.invokeExact(entity as Any?) as Boolean

    fun ownsLocation(location: Location): Boolean = ownsLocation.invokeExact(location as Any?) as Boolean

    fun isGlobalThread(): Boolean {
        val handle = isGlobalThread ?: return Bukkit.isPrimaryThread()
        return handle.invokeExact() as Boolean
    }

    fun entityExecute(
        entity: Entity,
        plugin: Plugin,
        run: Runnable,
        retired: Runnable?,
        delay: Long,
    ): Boolean {
        val scheduler = getEntityScheduler.invokeExact(entity as Any?) as Any?
        return entityExecute.invokeExact(scheduler, plugin as Any?, run as Any?, retired as Any?, delay) as Boolean
    }

    fun entityRunAtFixedRate(
        entity: Entity,
        plugin: Plugin,
        task: Consumer<Any>,
        retired: Runnable?,
        initialDelay: Long,
        period: Long,
    ): Any? {
        val scheduler = getEntityScheduler.invokeExact(entity as Any?) as Any?
        return entityRunAtFixedRate.invokeExact(
            scheduler,
            plugin as Any?,
            task as Any?,
            retired as Any?,
            initialDelay,
            period,
        ) as Any?
    }

    fun regionExecute(
        plugin: Plugin,
        world: World,
        chunkX: Int,
        chunkZ: Int,
        run: Runnable,
    ) {
        regionExecute.invokeExact(plugin as Any?, world as Any?, chunkX, chunkZ, run as Any?)
    }

    fun globalExecute(
        plugin: Plugin,
        run: Runnable,
    ) {
        globalExecute.invokeExact(plugin as Any?, run as Any?)
    }

    fun globalRunDelayed(
        plugin: Plugin,
        task: Consumer<Any>,
        delay: Long,
    ): Any = globalRunDelayed.invokeExact(plugin as Any?, task as Any?, delay) as Any

    fun globalRunAtFixedRate(
        plugin: Plugin,
        task: Consumer<Any>,
        initialDelay: Long,
        period: Long,
    ): Any = globalRunAtFixedRate.invokeExact(plugin as Any?, task as Any?, initialDelay, period) as Any

    fun globalCancelTasks(plugin: Plugin) {
        globalCancelTasks.invokeExact(plugin as Any?)
    }

    fun asyncRunNow(
        plugin: Plugin,
        task: Consumer<Any>,
    ) {
        asyncRunNow.invokeExact(plugin as Any?, task as Any?) as Any?
    }

    fun asyncCancelTasks(plugin: Plugin) {
        asyncCancelTasks.invokeExact(plugin as Any?)
    }

    fun cancel(task: Any) {
        taskCancel.invokeExact(task as Any?) as Any?
    }

    companion object {
        private const val PACKAGE = "io.papermc.paper.threadedregions.scheduler."

        internal fun resolve(plugin: Plugin): FoliaApi? {
            val entitySchedulerClass =
                try {
                    Class.forName(PACKAGE + "EntityScheduler")
                } catch (ignored: ClassNotFoundException) {
                    return null
                }
            return try {
                build(plugin.server, entitySchedulerClass)
            } catch (e: ReflectiveOperationException) {
                plugin.logger.log(
                    Level.WARNING,
                    "Region scheduler API found but not usable, using the Bukkit scheduler",
                    e,
                )
                null
            } catch (e: RuntimeException) {
                plugin.logger.log(
                    Level.WARNING,
                    "Region scheduler API found but not usable, using the Bukkit scheduler",
                    e,
                )
                null
            }
        }

        private fun build(
            server: Server,
            entitySchedulerClass: Class<*>,
        ): FoliaApi {
            val lookup = MethodHandles.publicLookup()
            val obj = Any::class.java
            val long = Long::class.javaPrimitiveType!!
            val int = Int::class.javaPrimitiveType!!
            val bool = Boolean::class.javaPrimitiveType!!
            val void = Void.TYPE
            val regionSchedulerClass = Class.forName(PACKAGE + "RegionScheduler")
            val globalSchedulerClass = Class.forName(PACKAGE + "GlobalRegionScheduler")
            val asyncSchedulerClass = Class.forName(PACKAGE + "AsyncScheduler")
            val scheduledTaskClass = Class.forName(PACKAGE + "ScheduledTask")
            val plugin = Plugin::class.java
            val runnable = Runnable::class.java
            val consumer = Consumer::class.java

            fun virtual(
                owner: Class<*>,
                name: String,
                type: MethodType,
            ): MethodHandle = lookup.findVirtual(owner, name, type)

            fun bound(
                owner: Class<*>,
                receiver: Any,
                name: String,
                type: MethodType,
                erased: MethodType,
            ): MethodHandle = virtual(owner, name, type).bindTo(receiver).asType(erased)

            val regionScheduler = server.javaClass.getMethod("getRegionScheduler").invoke(server)
            val globalScheduler = server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server)
            val asyncScheduler = server.javaClass.getMethod("getAsyncScheduler").invoke(server)

            val isGlobalThread =
                try {
                    bound(
                        Server::class.java,
                        server,
                        "isGlobalTickThread",
                        MethodType.methodType(bool),
                        MethodType.methodType(bool),
                    )
                } catch (ignored: NoSuchMethodException) {
                    null
                }

            return FoliaApi(
                getEntityScheduler =
                    virtual(Entity::class.java, "getScheduler", MethodType.methodType(entitySchedulerClass))
                        .asType(MethodType.methodType(obj, obj)),
                entityExecute =
                    virtual(
                        entitySchedulerClass,
                        "execute",
                        MethodType.methodType(bool, plugin, runnable, runnable, long),
                    ).asType(MethodType.methodType(bool, obj, obj, obj, obj, long)),
                entityRunAtFixedRate =
                    virtual(
                        entitySchedulerClass,
                        "runAtFixedRate",
                        MethodType.methodType(scheduledTaskClass, plugin, consumer, runnable, long, long),
                    ).asType(MethodType.methodType(obj, obj, obj, obj, obj, long, long)),
                regionExecute =
                    bound(
                        regionSchedulerClass,
                        regionScheduler,
                        "execute",
                        MethodType.methodType(void, plugin, World::class.java, int, int, runnable),
                        MethodType.methodType(void, obj, obj, int, int, obj),
                    ),
                globalExecute =
                    bound(
                        globalSchedulerClass,
                        globalScheduler,
                        "execute",
                        MethodType.methodType(void, plugin, runnable),
                        MethodType.methodType(void, obj, obj),
                    ),
                globalRunDelayed =
                    bound(
                        globalSchedulerClass,
                        globalScheduler,
                        "runDelayed",
                        MethodType.methodType(scheduledTaskClass, plugin, consumer, long),
                        MethodType.methodType(obj, obj, obj, long),
                    ),
                globalRunAtFixedRate =
                    bound(
                        globalSchedulerClass,
                        globalScheduler,
                        "runAtFixedRate",
                        MethodType.methodType(scheduledTaskClass, plugin, consumer, long, long),
                        MethodType.methodType(obj, obj, obj, long, long),
                    ),
                globalCancelTasks =
                    bound(
                        globalSchedulerClass,
                        globalScheduler,
                        "cancelTasks",
                        MethodType.methodType(void, plugin),
                        MethodType.methodType(void, obj),
                    ),
                asyncRunNow =
                    bound(
                        asyncSchedulerClass,
                        asyncScheduler,
                        "runNow",
                        MethodType.methodType(scheduledTaskClass, plugin, consumer),
                        MethodType.methodType(obj, obj, obj),
                    ),
                asyncCancelTasks =
                    bound(
                        asyncSchedulerClass,
                        asyncScheduler,
                        "cancelTasks",
                        MethodType.methodType(void, plugin),
                        MethodType.methodType(void, obj),
                    ),
                taskCancel =
                    virtual(
                        scheduledTaskClass,
                        "cancel",
                        MethodType.methodType(Class.forName(PACKAGE + "ScheduledTask\$CancelledState")),
                    ).asType(MethodType.methodType(obj, obj)),
                ownsEntity =
                    bound(
                        Server::class.java,
                        server,
                        "isOwnedByCurrentRegion",
                        MethodType.methodType(bool, Entity::class.java),
                        MethodType.methodType(bool, obj),
                    ),
                ownsLocation =
                    bound(
                        Server::class.java,
                        server,
                        "isOwnedByCurrentRegion",
                        MethodType.methodType(bool, Location::class.java),
                        MethodType.methodType(bool, obj),
                    ),
                isGlobalThread = isGlobalThread,
            )
        }
    }
}
