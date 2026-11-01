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

import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccessPacket
import cn.starfallplain.sfplogin.api.event.profile.ServerLoginSuccessEvent
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.api.log.error
import cn.starfallplain.sfplogin.manager.HyperZonePlayerManager
import cn.starfallplain.sfplogin.runtime.ConnectionHandoff
import cn.starfallplain.sfplogin.runtime.HandoffState
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import cn.starfallplain.sfplogin.sfpLoginMain

/**
 * 改写发往客户端的 `ServerLoginSuccessPacket`。
 *
 * 事件结果可能来自异步监听器（皮肤解析、外部档案源），此前在出站回调里直接 `join()` 会阻塞
 * 连接的事件循环，进而卡住同一循环上的所有连接。现在改用 [ConnectionHandoff]：
 * 事件未就绪时把本次以及后续出站写按序排队，结果回到事件循环后再放行；
 * 这样客户端看到的包顺序与阻塞版本完全一致，但线程不再被占用。
 */
class ServerLoginSuccessPacketReplacer(
    private val channel: Channel
) : ChannelOutboundHandlerAdapter() {

    private val handoff = ConnectionHandoff("server-login-success")

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        if (msg !is ServerLoginSuccessPacket) {
            if (!handoff.isHolding()) {
                super.write(ctx, msg, promise)
                return
            }
            // 扣留期间必须按序排队，否则登录成功包会排在这些包之后。
            handoff.defer { ctx.write(msg, promise) }
            return
        }

        if (handoff.state() != HandoffState.JOIN) {
            handoff.defer { ctx.write(msg, promise) }
            return
        }

        val hyperPlayer = runCatching {
            HyperZonePlayerManager.getByChannel(channel)
        }.getOrNull()

        if (hyperPlayer == null) {
            debug(HyperZoneDebugType.NETWORK_REWRITE) {
                "[ProfileSkinFlow] login success passthrough: no hyper player for channel=$channel"
            }
            super.write(ctx, msg, promise)
            return
        }

        val event = ServerLoginSuccessEvent(
            hyperZonePlayer = hyperPlayer,
            currentUuid = msg.uuid,
            currentUsername = msg.username,
            currentProperties = msg.properties
        )

        val future = try {
            sfpLoginMain().proxy.eventManager.fire(event)
        } catch (throwable: Throwable) {
            error(throwable) { "ServerLoginSuccessPacketReplacer write failed: ${throwable.message}" }
            handoff.markFailed()
            fireException(ctx, throwable)
            return
        }

        if (future.isDone) {
            applyAndWrite(ctx, msg, promise, event)
            return
        }

        if (!handoff.beginHold()) {
            handoff.defer { ctx.write(msg, promise) }
            return
        }

        handoff.defer { applyAndWrite(ctx, msg, promise, event) }

        future.whenComplete { _, throwable ->
            channel.eventLoop().execute {
                if (throwable != null) {
                    error(throwable) { "ServerLoginSuccessPacketReplacer event failed: ${throwable.message}" }
                    handoff.markFailed()
                    fireException(ctx, throwable)
                    return@execute
                }
                handoff.markVerified()
                handoff.release()
            }
        }
    }

    private fun applyAndWrite(
        ctx: ChannelHandlerContext,
        msg: ServerLoginSuccessPacket,
        promise: ChannelPromise?,
        event: ServerLoginSuccessEvent,
    ) {
        try {
            if (event.rewritePacket) {
                msg.uuid = event.uuid
                msg.username = event.username
                msg.properties = event.properties
            }
            ctx.pipeline().remove(this)
            ctx.write(msg, promise)
        } catch (t: Throwable) {
            error(t) { "ServerLoginSuccessPacketReplacer write failed: ${t.message}" }
            fireException(ctx, t)
        }
    }

    private fun fireException(ctx: ChannelHandlerContext, throwable: Throwable) {
        try {
            ctx.fireExceptionCaught(throwable)
        } catch (_: Throwable) {
            // ignore
        }
    }
}
