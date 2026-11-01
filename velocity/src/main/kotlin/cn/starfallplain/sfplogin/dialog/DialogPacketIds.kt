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

package cn.starfallplain.sfplogin.dialog

/**
 * PLAY 状态下 dialog 相关协议包的数值 ID。
 *
 * @property showDialog 客户端接收 `show_dialog`
 * @property clearDialog 客户端接收 `clear_dialog`
 * @property customClickAction 服务端接收 `custom_click_action`
 */
data class DialogPacketIds(
    val showDialog: Int,
    val clearDialog: Int,
    val customClickAction: Int,
)

/**
 * 各协议版本的 dialog 包 ID 表。
 *
 * ID 来源：PrismarineJS minecraft-data 的 `data/pc/<version>/protocol.json`
 * （取 `play.toClient` / `play.toServer` 映射中 `show_dialog`、`clear_dialog`、
 * `custom_click_action` 的键）；表中**只收录已核对过的版本**。
 *
 * Velocity 4.1.0 的 [com.velocitypowered.proxy.protocol.StateRegistry] 只在 CONFIG 阶段
 * 注册了 dialog 包，PLAY 阶段没有注册，因此 PLAY 阶段必须自己拼包（见 [SfpDialogPackets]）。
 *
 * 未收录的协议版本一律视为不支持，由调用方回退到聊天提示。
 */
object DialogPacketIdsTable {

    /** dialog 从该协议版本（Minecraft 1.21.6）开始引入。 */
    const val MIN_SUPPORTED_PROTOCOL = 771

    private val byProtocol: Map<Int, DialogPacketIds> = linkedMapOf(
        // 1.21.6
        771 to DialogPacketIds(showDialog = 0x85, clearDialog = 0x84, customClickAction = 0x41),
        // 1.21.7 / 1.21.8
        772 to DialogPacketIds(showDialog = 0x85, clearDialog = 0x84, customClickAction = 0x41),
        // 1.21.9 / 1.21.10
        773 to DialogPacketIds(showDialog = 0x8A, clearDialog = 0x89, customClickAction = 0x41),
        // 1.21.11
        774 to DialogPacketIds(showDialog = 0x8A, clearDialog = 0x89, customClickAction = 0x41),
        // 26.1 / 26.1.1 / 26.1.2
        775 to DialogPacketIds(showDialog = 0x8C, clearDialog = 0x8B, customClickAction = 0x44),
    )

    /**
     * 查询协议版本对应的包 ID。
     *
     * @param protocolVersion Velocity 中的协议号
     * @return 未收录的版本返回 null
     */
    fun forProtocol(protocolVersion: Int): DialogPacketIds? = byProtocol[protocolVersion]

    /**
     * 该协议版本是否支持 dialog。
     */
    fun supports(protocolVersion: Int): Boolean =
        protocolVersion >= MIN_SUPPORTED_PROTOCOL && byProtocol.containsKey(protocolVersion)

    /**
     * 所有支持的协议号，供测试与诊断使用。
     */
    fun supportedProtocols(): Set<Int> = byProtocol.keys
}
