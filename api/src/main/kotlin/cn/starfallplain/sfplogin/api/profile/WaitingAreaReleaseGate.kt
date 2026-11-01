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

package cn.starfallplain.sfplogin.api.profile

import com.velocitypowered.api.proxy.Player
import cn.starfallplain.sfplogin.api.db.Profile
import net.kyori.adventure.text.Component

/**
 * 「离开等待区 / 进入游戏区」之前的统一放行闸门。
 *
 * 核心层在 [cn.starfallplain.sfplogin.api.player.HyperZonePlayer] 已完成正式档案 attach、且 Profile 独占校验与
 * 同档案在线冲突校验都通过之后，会依次询问所有已注册的闸门实现，只有全部返回
 * [WaitingAreaReleaseGate.Decision.Allow] 时才会真正把玩家送进游戏区。
 *
 * 与 [cn.starfallplain.sfplogin.api.event.area.WaitingAreaLeaveEvent] 的区别：
 * - 该事件目前只允许用于“提示类弱依赖场景”，不保证稳定、完整、唯一触发，不能用于踢出或权限控制；
 * - 本闸门是**同步、阻塞式**的放行判定，核心层保证它在“认证通过、档案已 attach、但玩家尚未离开等待区”
 *   这一时间点被调用，拒绝即代表玩家本次登录停留在等待区（或按 [Decision.Deny.disconnect] 断开连接）。
 *
 * 实现约定：
 * - 实现必须是幂等且尽量轻量的：核心层可能在同一次登录中因补发/刷新而重复调用；
 * - 实现不得在内部再触发等待区迁移或调用 `attachProfile`；
 * - 实现抛出的异常会被核心层捕获并按放行处理，同时记录警告日志。
 */
interface WaitingAreaReleaseGate {
    /**
     * 闸门标识，用于注册表内去重与日志定位（建议与模块 ID 保持一致）。
     */
    val id: String

    /**
     * 对一次「即将离开等待区」的放行请求做出裁决。
     *
     * @param player 已经完成认证并 attach 正式档案的代理层玩家
     * @param profile 该玩家本次会话 attach 的正式档案
     * @return [Decision.Allow] 表示放行；[Decision.Deny] 表示本次不放行
     */
    fun evaluate(player: Player, profile: Profile): Decision

    /**
     * 闸门裁决结果。
     */
    sealed interface Decision {
        /**
         * 放行。
         *
         * @property notice 可选的一次性提示消息；核心层会在放行后把它发给玩家（可为 null）
         */
        data class Allow(
            val notice: Component? = null
        ) : Decision

        /**
         * 拒绝放行。
         *
         * @property disconnect 是否同时断开玩家的代理连接（true = 踢出，false = 留在等待区）
         * @property message 展示给玩家的拒绝原因（踢出时需要提供）
         */
        data class Deny(
            val disconnect: Boolean,
            val message: Component?
        ) : Decision
    }
}

/**
 * [WaitingAreaReleaseGate] 的全局注册表。
 *
 * 核心层在启动时绑定实现；模块在 `register(api)` 阶段注册自己的闸门。
 */
interface WaitingAreaReleaseGateRegistry {
    /**
     * 注册一个闸门；同一 [WaitingAreaReleaseGate.id] 重复注册时后者覆盖前者。
     */
    fun register(gate: WaitingAreaReleaseGate)

    /**
     * 注销指定 id 的闸门。
     */
    fun unregister(id: String)

    /**
     * 返回当前已注册的全部闸门（按注册顺序）。
     */
    fun getGates(): List<WaitingAreaReleaseGate>

    /**
     * 依次询问全部闸门；任意一个返回 [WaitingAreaReleaseGate.Decision.Deny] 时立即返回该拒绝结果。
     *
     * 全部放行时返回 [WaitingAreaReleaseGate.Decision.Allow]（首个非空 notice 会被携带返回）。
     */
    fun evaluate(player: Player, profile: Profile): WaitingAreaReleaseGate.Decision
}

/**
 * [WaitingAreaReleaseGateRegistry] 的全局访问入口。
 */
object WaitingAreaReleaseGateRegistryProvider {
    @Volatile
    private var registry: WaitingAreaReleaseGateRegistry? = null

    /**
     * 绑定当前运行时的闸门注册表。
     */
    fun bind(registry: WaitingAreaReleaseGateRegistry) {
        this.registry = registry
    }

    /**
     * 获取已绑定的注册表，未初始化时抛错。
     */
    fun get(): WaitingAreaReleaseGateRegistry =
        registry ?: error("SFP-login waiting-area release gate registry is not available yet")

    /**
     * 获取已绑定的注册表，当前不可用时返回 null。
     */
    fun getOrNull(): WaitingAreaReleaseGateRegistry? = registry
}
