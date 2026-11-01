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

import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.protocol.StateRegistry
import com.velocitypowered.proxy.protocol.ProtocolUtils
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.kyori.adventure.nbt.CompoundBinaryTag

/**
 * PLAY 阶段直接向客户端发送 / 清除 dialog。
 *
 * Velocity 4.1.0 的 [StateRegistry] 只在 CONFIG 阶段注册了 `show_dialog` 与 `clear_dialog`，
 * PLAY 阶段没有任何注册。`MinecraftEncoder` 只处理 `MinecraftPacket` 实例，
 * 其它类型（例如 [ByteBuf]）会被原样交给后续的长度 / 压缩编码器，
 * 因此这里手工拼出 `[varint packetId][body]` 并通过 `connection.write` 下发。
 */
object SfpDialogPackets {

    /** dialog 内联 NBT 时的 holder ID：0 表示后面跟着未注册的 dialog 数据。 */
    private const val INLINE_DIALOG_HOLDER_ID = 0

    /**
     * 该玩家当前是否具备发送 dialog 的条件（协议支持且已进入 PLAY）。
     */
    fun canSend(player: ConnectedPlayer): Boolean =
        DialogPacketIdsTable.supports(player.protocolVersion.protocol)
            && player.connection.state == StateRegistry.PLAY

    /**
     * 发送 `show_dialog`。
     *
     * @return 已提交到事件循环时返回 true
     */
    fun show(player: ConnectedPlayer, dialog: CompoundBinaryTag): Boolean {
        val ids = DialogPacketIdsTable.forProtocol(player.protocolVersion.protocol) ?: return false
        player.connection.eventLoop().execute {
            if (player.connection.state != StateRegistry.PLAY) return@execute
            val buf = Unpooled.buffer()
            ProtocolUtils.writeVarInt(buf, ids.showDialog)
            ProtocolUtils.writeVarInt(buf, INLINE_DIALOG_HOLDER_ID)
            ProtocolUtils.writeBinaryTag(buf, player.protocolVersion, dialog)
            player.connection.write(buf)
            player.connection.flush()
        }
        return true
    }

    /**
     * 发送 `clear_dialog`（包体为空）。
     */
    fun clear(player: ConnectedPlayer): Boolean {
        val ids = DialogPacketIdsTable.forProtocol(player.protocolVersion.protocol) ?: return false
        player.connection.eventLoop().execute {
            if (player.connection.state != StateRegistry.PLAY) return@execute
            val buf = Unpooled.buffer()
            ProtocolUtils.writeVarInt(buf, ids.clearDialog)
            player.connection.write(buf)
            player.connection.flush()
        }
        return true
    }
}
