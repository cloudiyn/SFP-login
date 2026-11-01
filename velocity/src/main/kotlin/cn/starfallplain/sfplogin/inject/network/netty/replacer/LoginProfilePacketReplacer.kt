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

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.config.VelocityConfiguration
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.PlayerDataForwarding
import com.velocitypowered.proxy.protocol.ProtocolUtils
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket
import cn.starfallplain.sfplogin.api.event.profile.LoginProfileReplaceEvent
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.api.log.error
import cn.starfallplain.sfplogin.api.log.info
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.manager.HyperZonePlayerManager
import cn.starfallplain.sfplogin.util.hasSemanticGameProfileDifference
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import cn.starfallplain.sfplogin.sfpLoginMain
import cn.starfallplain.sfplogin.player.ProfileSkinApplySupport
import cn.starfallplain.sfplogin.runtime.ConnectionHandoff
import cn.starfallplain.sfplogin.runtime.HandoffState
import java.util.concurrent.CompletableFuture

internal fun shouldRewriteLoginProfile(currentProfile: GameProfile, expectedProfile: GameProfile): Boolean {
    return hasSemanticGameProfileDifference(expectedProfile, currentProfile)
}

internal const val MODERN_FORWARDING_SIGNATURE_LENGTH = 32

/**
 * 解析后端在 LoginPluginRequest 中声明的 modern forwarding 版本。
 *
 * payload 结构为 `32 字节签名 + VarInt 版本号`；当内容缺失、长度不足以覆盖签名或 VarInt 解码失败时
 * 返回 null，由调用方回退到 [PlayerDataForwarding.MODERN_DEFAULT]。函数不修改传入 buffer 的读指针。
 */
internal fun decodeModernForwardingVersion(content: ByteBuf?): Int? {
    if (content == null) {
        return null
    }
    if (content.readableBytes() <= MODERN_FORWARDING_SIGNATURE_LENGTH) {
        return null
    }
    val duplicate = content.duplicate()
    duplicate.skipBytes(MODERN_FORWARDING_SIGNATURE_LENGTH)
    return runCatching { ProtocolUtils.readVarInt(duplicate) }.getOrNull()
}

/**
 * 重写发往后端的登录档案包（事件驱动版）。
 *
 * 首次 write 时触发 [LoginProfileReplaceEvent]，初始档案（可能含皮肤）异步解析后交给事件；
 * 监听器可修改事件中的档案并将 modified 置为 true。
 * - modified = false：说明无需补丁，自移除并按原样放行；
 * - modified = true：使用事件中的档案替换登录阶段下发给后端的档案包。
 *
 * 解析过程通过 [ConnectionHandoff] 挂起：事件循环线程只负责排队与在结果就绪后按序放行，
 * 不会因为等待事件或皮肤解析而阻塞。
 */

class LoginProfilePacketReplacer(
    private val channel: Channel
) : ChannelOutboundHandlerAdapter() {

    private val handoff = ConnectionHandoff("login-profile")

    private lateinit var player: Player
    private lateinit var hyperPlayer: HyperZonePlayer
    private lateinit var targetServerName: String
    private lateinit var config: VelocityConfiguration

    /** 由事件监听器确认后的最终替换档案；仅在 [replaceEnabled] 为 true 后有效。 */
    private lateinit var replacedProfile: GameProfile

    @Volatile
    private var replaceEnabled = false

    @Volatile
    private var initialized = false

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        if (initialized) {
            forward(ctx, msg, promise)
            return
        }
        if (handoff.state() != HandoffState.JOIN) {
            handoff.defer { forward(ctx, msg, promise) }
            return
        }
        if (!beginResolution(ctx)) {
            forward(ctx, msg, promise)
            return
        }
        handoff.defer { forward(ctx, msg, promise) }
    }

    /**
     * 首次出站写时抓取连接上下文并发起异步解析。
     *
     * @return false 表示当前连接还取不到后端连接关联，按原样放行；true 表示已进入交接流程。
     */
    private fun beginResolution(ctx: ChannelHandlerContext): Boolean {
        val connection = ctx.channel().pipeline().get(MinecraftConnection::class.java) ?: return false
        val association = connection.association as? ServerConnection ?: return false

        player = association.player
        targetServerName = association.server.serverInfo.name
        hyperPlayer = HyperZonePlayerManager.getByPlayer(player)
        config = sfpLoginMain().proxy.configuration as VelocityConfiguration

        if (!handoff.beginHold()) {
            return true
        }

        // 必须切到工作线程：事件监听器（如 Floodgate 档案替换）可能同步等待皮肤解析，
        // 若在事件循环上触发就会把该连接以及同循环上的其它连接一起卡住。
        val future = resolveInitialForwardingProfileAsync().thenComposeAsync { initialProfile ->
            if (initialProfile == null) {
                return@thenComposeAsync CompletableFuture.completedFuture<LoginProfileReplaceEvent?>(null)
            }
            sfpLoginMain().proxy.eventManager.fire(
                LoginProfileReplaceEvent(hyperPlayer, targetServerName, initialProfile)
            )
        }

        future.whenComplete { event, throwable ->
            ctx.executor().execute { completeResolution(ctx, event, throwable) }
        }
        return true
    }

    private fun completeResolution(
        ctx: ChannelHandlerContext,
        event: LoginProfileReplaceEvent?,
        throwable: Throwable?,
    ) {
        if (throwable != null) {
            error(throwable) { "LoginProfilePacketReplacer resolution failed: ${throwable.message}" }
            handoff.markFailed()
            fireException(ctx, throwable)
            return
        }

        try {
            if (event != null && event.modified) {
                replacedProfile = event.profile
                replaceEnabled = true
                initialized = true
                info {
                    "[LoginProfileReplace] 替换成功: server=$targetServerName name=${replacedProfile.name} uuid=${replacedProfile.id}"
                }
            } else {
                retire()
            }
        } catch (t: Throwable) {
            error(t) { "LoginProfilePacketReplacer resolution failed: ${t.message}" }
            handoff.markFailed()
            fireException(ctx, t)
            return
        }

        handoff.markVerified()
        handoff.release()
    }

    private fun forward(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        try {
            val outgoing = if (replaceEnabled) replaceMessage(msg) else msg
            ctx.write(outgoing, promise)
        } catch (t: Throwable) {
            error(t) { "LoginProfilePacketReplacer write failed: ${t.message}" }
            fireException(ctx, t)
            retire()
        }
    }

    private fun fireException(ctx: ChannelHandlerContext, throwable: Throwable) {
        try {
            ctx.fireExceptionCaught(throwable)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun replaceMessage(msg: Any?): Any? {
        return when (msg) {
            is ServerLoginPacket -> genServerLogin()
            is LoginPluginResponsePacket -> {
                retire()
                genLoginPluginResponse(msg)
            }
            else -> msg
        }
    }

    private fun retire() {
        runCatching { channel.pipeline().remove(this) }
    }

    private fun genLoginPluginResponse(msg: LoginPluginResponsePacket): LoginPluginResponsePacket {
//        不判定 CTD兼容，理论上也不需要判断
//        if (config.playerInfoForwardingMode != PlayerInfoForwarding.MODERN) {
//            return msg
//        }

        val requestedForwardingVersion = resolveRequestedForwardingVersion(msg.content())
        val forwardingData = PlayerDataForwarding.createForwardingData(
            config.forwardingSecret,
            getPlayerRemoteAddressAsString(),
            player.protocolVersion,
            replacedProfile,
            player.identifiedKey,
            requestedForwardingVersion,
        )
        return LoginPluginResponsePacket(msg.id, true, forwardingData)
    }

    private fun resolveRequestedForwardingVersion(content: ByteBuf?): Int {
        val decoded = decodeModernForwardingVersion(content)
        if (decoded != null) {
            return decoded
        }

        debug(HyperZoneDebugType.NETWORK_REWRITE) {
            "[ProfileSkinFlow] modern forwarding version unavailable, fallback=${PlayerDataForwarding.MODERN_DEFAULT}, readableBytes=${content?.readableBytes()}, target=$targetServerName"
        }
        return PlayerDataForwarding.MODERN_DEFAULT
    }

    private fun getPlayerRemoteAddressAsString(): String {
        val addr = player.remoteAddress.address.hostAddress
        val ipv6ScopeIdx = addr.indexOf('%')
        return if (ipv6ScopeIdx == -1) addr else addr.substring(0, ipv6ScopeIdx)
    }

    private fun genServerLogin(): ServerLoginPacket {
        // 统一使用 (String, UUID) 构造器确保 holderUuid 非 null。
        // 高版本协议（≥1.20.2，含 26.x / 1.21.x）encode 时会无条件 writeUuid(holderUuid)，
        // 若使用 (String, IdentifiedKey) 构造器则 holderUuid 为 null，导致 NPE 无法进入服务器。
        // playerKey 通过 setPlayerKey 补充设置，兼容 1.19-1.19.2 的 IdentifiedKey 序列化。
        val packet = ServerLoginPacket(replacedProfile.name, replacedProfile.id)
        player.identifiedKey?.let { packet.setPlayerKey(it) }
        return packet
    }

    /**
     * 计算本次连接应向后端转发的初始档案（供事件的 initialProfile 字段使用）。
     *
     * 仅根据玩家是否处于等待区来决定使用临时档案还是正式档案，并把皮肤解析放在
     * [ProfileSkinApplySupport.applyAsync] 的异步链上，避免在事件循环里等待。
     */
    private fun resolveInitialForwardingProfileAsync(): CompletableFuture<GameProfile?> {
        if (!hyperPlayer.hasAttachedProfile()) {
            return CompletableFuture.completedFuture(hyperPlayer.getTemporaryGameProfile())
        }
        return runCatching { ProfileSkinApplySupport.applyAsync(hyperPlayer) }
            .getOrElse { throwable ->
                debug(HyperZoneDebugType.NETWORK_REWRITE) {
                    "[ProfileSkinFlow] initial profile resolution failed: ${throwable.message}"
                }
                CompletableFuture.completedFuture(null)
            }
    }
}
