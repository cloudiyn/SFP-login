/*
 * This file is part of SFP-login, licensed under the GNU Affero General Public License v3.0 or later.
 *
 * Copyright (C) ksqeib (庆灵) <ksqeib@qq.com>
 * Copyright (C) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package cn.starfallplain.sfplogin.runtime

import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.config.CoreConfig
import cn.starfallplain.sfplogin.config.StartConfig
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次插件运行时（“上下文”）的生命周期容器。
 *
 * 负责：
 * - 持有与主插件实例绑定的运行时状态（[coreConfig]、[startConfig]），取代原先挂在
 *   `companion object` 上的可变静态字段；
 * - 记录启动过程中注册的每一项资源及其释放动作，[close] 时**逆序**释放，
 *   保证“后注册的依赖先释放”（例如先解绑 provider、再断开数据库）。
 *
 * 任何新增的全局注册（provider 绑定、事件监听、命令、线程池）都应当通过
 * [onClose] 登记释放动作，避免插件卸载后遗留悬挂引用。
 */
class HyperZoneContext internal constructor(
    val main: SFPLoginMain,
) : AutoCloseable {

    val logger: ComponentLogger
        get() = main.logger

    val dataDirectory: Path
        get() = main.dataDirectory

    /**
     * 当前生效的核心配置快照。
     *
     * `null` 表示配置尚未加载完成；读取方通过 [requireCoreConfig] 显式表达“此时必须已加载”。
     */
    @Volatile
    var coreConfig: CoreConfig? = null
        internal set

    /**
     * 当前生效的启动前置配置（`start.conf`）。
     */
    @Volatile
    var startConfig: StartConfig? = null
        internal set

    private val resources = CopyOnWriteArrayList<RegisteredResource>()
    private val closing = AtomicBoolean(false)

    val isClosing: Boolean
        get() = closing.get()

    fun requireCoreConfig(): CoreConfig =
        coreConfig ?: error("SFP-login core config is not available yet")

    fun requireStartConfig(): StartConfig =
        startConfig ?: error("SFP-login start config is not available yet")

    /**
     * 登记一个释放动作；返回的 [RegisteredResource] 可单独 [RegisteredResource.release]，
     * 已经释放过的动作不会在 [close] 时重复执行。
     */
    fun onClose(name: String, closer: () -> Unit): RegisteredResource {
        val resource = RegisteredResource(name, closer)
        resources += resource
        if (closing.get()) {
            // 关闭过程中才登记的资源立即释放，避免泄漏。
            resource.release()
        }
        return resource
    }

    /**
     * 逆序释放所有已登记资源。单个资源释放失败只记录日志，不中断其余资源的释放。
     */
    override fun close() {
        if (!closing.compareAndSet(false, true)) {
            return
        }
        val snapshot = resources.toList()
        resources.clear()
        snapshot.asReversed().forEach { resource ->
            try {
                resource.release()
            } catch (t: Throwable) {
                logger.warn("释放资源 {} 失败: {}", resource.name, t.message, t)
            }
        }
    }

    /**
     * 单个已登记资源。释放动作保证只执行一次。
     */
    class RegisteredResource internal constructor(
        val name: String,
        private val closer: () -> Unit,
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) {
                closer()
            }
        }
    }
}

/**
 * 全局运行时句柄：只保存“当前上下文”这一个可变引用。
 *
 * 这样插件重载时旧上下文会被整体丢弃，而不是像 `companion object` 那样残留旧配置与旧服务实例。
 */
object HyperZoneRuntime {

    @Volatile
    private var context: HyperZoneContext? = null

    fun start(main: SFPLoginMain): HyperZoneContext {
        context?.let { previous ->
            previous.logger.warn("检测到上一个运行时上下文仍未关闭，先执行关闭")
            previous.close()
        }
        return HyperZoneContext(main).also { context = it }
    }

    fun current(): HyperZoneContext? = context

    fun require(): HyperZoneContext = context ?: error("SFP-login runtime is not available yet")

    fun close() {
        val previous = context ?: return
        context = null
        previous.close()
    }
}
