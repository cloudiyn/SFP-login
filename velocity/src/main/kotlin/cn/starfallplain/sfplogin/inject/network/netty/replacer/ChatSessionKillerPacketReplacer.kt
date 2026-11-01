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

package cn.starfallplain.sfplogin.inject.network.netty.replacer

import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import cn.starfallplain.sfplogin.api.log.error
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.inject.network.ChatSessionUpdatePacketIdResolver
import cn.starfallplain.sfplogin.manager.HyperZonePlayerManager
import cn.starfallplain.sfplogin.reflect.VelocityInternalAccess
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil

/**
 * Shared backend-channel replacer for `killChatSession`.
 *
 * It is attached to every backend connection initializer so both backend mode and
 * outpre bridge mode can drop the client chat-session-update packet before it is
 * forwarded to the real auth/waiting backend.
 */
class ChatSessionKillerPacketReplacer(
    private val channel: Channel,
) : ChannelOutboundHandlerAdapter() {
    private lateinit var player: ConnectedPlayer
    private var shouldKill = true
    private var chatSessionPacketId: Int? = 0

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        try {
            initFields(ctx) ?: run {
                super.write(ctx, msg, promise)
                return
            }

            if (!shouldKill) {
                retire()
                super.write(ctx, msg, promise)
                return
            }

            if (msg !is ByteBuf) {
                super.write(ctx, msg, promise)
                return
            }

            val packetId = readPacketId(msg)
            if (packetId != null && chatSessionPacketId == packetId) {
                retire()
                ReferenceCountUtil.safeRelease(msg)
                promise?.setSuccess()
                return
            }

            super.write(ctx, msg, promise)
        } catch (t: Throwable) {
            error(t) { "ChatSessionKillerPacketReplacer write failed: ${t.message}" }
            try {
                ctx.fireExceptionCaught(t)
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun readPacketId(msg: ByteBuf): Int? {
        if (!msg.isReadable) {
            return null
        }

        val duplicate = msg.duplicate()
        return runCatching {
            VelocityInternalAccess.readVarInt(duplicate)
        }.getOrNull()
    }

    private fun retire() {
        channel.pipeline().remove(this)
    }

    private fun initFields(ctx: ChannelHandlerContext): Unit? {
        if (::player.isInitialized) {
            return Unit
        }

        val connection = ctx.channel().pipeline().get(MinecraftConnection::class.java) ?: return null
        val resolvedPlayer = when (val association = connection.association) {
            is VelocityServerConnection -> association.player
            else -> return null
        }

        player = resolvedPlayer
        val hyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(player)
        chatSessionPacketId = ChatSessionUpdatePacketIdResolver.resolve(player.protocolVersion)
        shouldKill = SFPLoginMain.getCoreConfig().misc.killChatSession
                && hyperPlayer?.isOnlinePlayer == true
                && chatSessionPacketId != null
        return Unit
    }
}

