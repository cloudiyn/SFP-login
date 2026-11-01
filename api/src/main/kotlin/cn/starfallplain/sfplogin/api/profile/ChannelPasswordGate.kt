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
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import net.kyori.adventure.text.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 渠道密码门状态。
 *
 * 当一个可信认证渠道（如 Yggdrasil / Floodgate）在远端验证通过后，
 * 若该渠道启用了本地密码保护，则玩家会被密码门拦截在等待区，
 * 直到完成首次密码设置（注册）或密码校验（登录）。
 */
enum class ChannelPasswordGateStatus {
    /** 该渠道绑定记录尚未设置过密码，等待玩家执行 /register。 */
    NEEDS_REGISTER,

    /** 该渠道绑定记录已存在密码，等待玩家执行 /login。 */
    NEEDS_LOGIN,
}

/**
 * 渠道密码门处理器。
 *
 * 每个支持本地密码保护的认证渠道（offline / yggdrasil / floodgate ...）
 * 都应提供一个实现并注册到 [ChannelPasswordGateRegistry]。
 *
 * 密码门的生命周期与等待区语义保持一致：
 * - 渠道可信认证已通过、密码尚未通过时，玩家停留在等待区；
 * - 密码通过后，由 gate 负责提交渠道凭证并触发核心 overVerify，完成档案 attach。
 */
interface ChannelPasswordGate {

    /** 对应的凭证渠道 ID（与 [HyperZoneCredential.channelId] 一致）。 */
    val channelId: String

    /**
     * 命令路由优先级。
     *
     * 数值越大越优先认领玩家。当多个渠道都可能匹配同一玩家时
     * （例如 Floodgate 玩家与离线玩家都不是在线模式连接），
     * 更具体的渠道应返回更高的优先级。
     */
    val priority: Int
        get() = 0

    /**
     * 判断当前玩家连接是否属于本渠道。
     *
     * 该判断只用于命令路由，不表示玩家一定处于密码门中；
     * 玩家是否正被密码门拦截请使用 [getGateStatus]。
     */
    fun supports(player: HyperZonePlayer): Boolean

    /**
     * 查询玩家当前是否被本渠道密码门拦截。
     *
     * 返回 null 表示玩家当前不在密码门中。
     */
    fun getGateStatus(player: HyperZonePlayer): ChannelPasswordGateStatus?

    /**
     * 本渠道是否基于"主动挂起状态"拦截玩家。
     *
     * 与 [getGateStatus] 配合用于登录方式检测：只有返回 `true` 的 gate
     * 才会在 [ChannelPasswordGateRegistry.resolveGate] 中被视为"正在拦截该玩家"，
     * 从而被优先选中。
     *
     * 无状态的兜底渠道（如离线验证：不依赖任何挂起状态，仅凭当前玩家名查库判断
     * NEEDS_REGISTER / NEEDS_LOGIN）应保持默认 `false`，否则会把本属于其他渠道
     * 密码门的玩家抢走。
     */
    fun holdsPlayerByPendingState(): Boolean = false

    /**
     * 预测本渠道密码门"接下来是否会拦下该玩家"。
     *
     * 与 [getGateStatus] 的区别：后者只在玩家**已经**被拦下后才有值，
     * 而本方法用于在流程开始前（例如 struck 直通模式决定是否放行）提前判断。
     *
     * 默认返回 `false`，表示该 gate 无法提前预测，按 [getGateStatus] 的实时结果处理。
     */
    fun willHold(player: HyperZonePlayer): Boolean = false

    /**
     * 处理 /register 命令。
     *
     * @return 应展示给玩家的结果消息
     */
    fun handleRegister(player: Player, args: Array<String>): Component

    /**
     * 处理 /login 命令。
     *
     * @return 应展示给玩家的结果消息
     */
    fun handleLogin(player: Player, args: Array<String>): Component

    /**
     * 处理 /changepassword 命令。
     *
     * @return 应展示给玩家的结果消息
     */
    fun handleChangePassword(player: Player, args: Array<String>): Component
}

/**
 * [ChannelPasswordGate] 的全局注册表。
 *
 * 各认证模块在自己的注册阶段把 gate 实例注册进来；
 * 共享密码命令（login / register / changepassword）通过该注册表
 * 把命令执行路由到玩家当前所属渠道的 gate。
 */
object ChannelPasswordGateRegistry {

    private val gates = ConcurrentHashMap<String, ChannelPasswordGate>()

    /**
     * 注册（或覆盖）一个渠道密码门。
     */
    fun register(gate: ChannelPasswordGate) {
        gates[gate.channelId] = gate
    }

    /**
     * 按渠道 ID 注销密码门。
     */
    fun unregister(channelId: String) {
        gates.remove(channelId)
    }

    /**
     * 按渠道 ID 获取密码门。
     */
    fun byChannelId(channelId: String): ChannelPasswordGate? = gates[channelId]

    /**
     * 获取当前已注册的全部密码门。
     */
    fun all(): Collection<ChannelPasswordGate> = gates.values.toList()

    /**
     * 为玩家解析应当处理其密码命令的 gate。
     *
     * 解析规则（按优先级从高到低）：
     * 1. **正在拦截该玩家的 gate**：遍历所有 gate，若其 [ChannelPasswordGate.getGateStatus] 非空，
     *    说明玩家当前正被该渠道密码门拦下，直接返回它。这是最权威的信号——
     *    密码门阶段凭证尚未提交，[HyperZonePlayer.getSubmittedCredentials] 为空，
     *    仅靠 [ChannelPasswordGate.supports] 的启发式判断会把玩家误判到兜底渠道（offline）。
     * 2. 若玩家已有已提交凭证，按凭证的 channelId 精确匹配；
     * 3. 否则遍历所有 [ChannelPasswordGate.supports] 命中者，取 [ChannelPasswordGate.priority] 最高者
     *    （更具体的渠道如 Floodgate 应高于兜底渠道如 Offline）。
     */
    fun resolveGate(player: HyperZonePlayer): ChannelPasswordGate? {
        // 1. 优先返回正在拦截该玩家的 gate（仅限基于主动挂起状态的渠道，
        //    避免无状态兜底渠道把属于其他渠道密码门的玩家抢走）
        gates.values
            .firstOrNull { gate ->
                gate.holdsPlayerByPendingState() &&
                    runCatching { gate.getGateStatus(player) }.getOrNull() != null
            }
            ?.let { return it }

        // 2. 已有已提交凭证：按渠道精确匹配
        val credentialChannelId = player.getSubmittedCredentials().firstOrNull()?.channelId
        if (credentialChannelId != null) {
            gates[credentialChannelId]?.let { return it }
        }

        // 3. 回退：按 supports 命中者取优先级最高者
        return gates.values
            .filter { it.supports(player) }
            .maxByOrNull { it.priority }
    }

    /**
     * 判断当前是否存在会给该玩家弹出密码门的渠道。
     *
     * 供 struck 直通模式在放行前预判：若会弹门，就不能走直通（客户端会停在 LOGIN 阶段无法输入命令），
     * 必须先进入等待区桥接。
     */
    fun willHold(player: HyperZonePlayer): Boolean {
        return runCatching {
            gates.values.any { gate -> gate.supports(player) && gate.willHold(player) }
        }.getOrDefault(false)
    }
}
