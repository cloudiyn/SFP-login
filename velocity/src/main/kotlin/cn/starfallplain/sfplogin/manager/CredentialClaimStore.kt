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

package cn.starfallplain.sfplogin.manager

import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.player.getChannel
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredential
import io.netty.channel.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 channel 保存"已提交但尚未验证"的凭证。
 *
 * 登录编排层只负责把一个 claim 产出的凭证暂存起来，真正的档案 attach 由
 * [cn.starfallplain.sfplogin.api.profile.HyperZoneCredentialFlow.overVerify] 完成。把这份存储
 * 单独抽出来，是为了让"凭证生命周期"与"放行/冲突处理"互不纠缠。
 */
internal class CredentialClaimStore {

    private val credentialsByChannel = ConcurrentHashMap<Channel, HyperZoneCredential>()

    fun submit(player: HyperZonePlayer, credential: HyperZoneCredential) {
        submitToChannel(resolveChannel(player), credential)
    }

    /**
     * 暂存凭证。同一 channel 上已有凭证时直接报错——必须先替换，否则会静默丢凭据，
     * 事后排查“为什么验证没生效”会非常困难。
     */
    fun submitToChannel(channel: Channel, credential: HyperZoneCredential) {
        val existing = credentialsByChannel[channel]
        if (existing != null) {
            throw IllegalStateException(
                "玩家 channel=$channel 已存在凭证 ${existing.channelId}，必须先替换后再提交"
            )
        }
        credentialsByChannel[channel] = credential
    }

    fun replace(player: HyperZonePlayer, oldChannelId: String, credential: HyperZoneCredential) {
        val channel = resolveChannel(player)
        val existing = credentialsByChannel[channel]
            ?: throw IllegalStateException("玩家 ${player.clientOriginalName} 当前没有可替换凭证")
        if (existing.channelId != oldChannelId) {
            throw IllegalStateException(
                "玩家 ${player.clientOriginalName} 当前凭证渠道与期望不一致: " +
                    "expected=$oldChannelId actual=${existing.channelId}"
            )
        }
        credentialsByChannel[channel] = credential
    }

    fun get(player: HyperZonePlayer): List<HyperZoneCredential> {
        val channel = resolveChannelOrNull(player) ?: return emptyList()
        return credentialsByChannel[channel]?.let(::listOf).orEmpty()
    }

    fun clear(player: HyperZonePlayer) {
        resolveChannelOrNull(player)?.let(credentialsByChannel::remove)
    }

    fun clearByChannel(channel: Channel) {
        credentialsByChannel.remove(channel)
    }

    fun resolveChannel(player: HyperZonePlayer): Channel {
        return resolveChannelOrNull(player)
            ?: throw IllegalStateException("玩家 ${player.clientOriginalName} 当前未绑定 Proxy Player，无法定位 channel")
    }

    fun resolveChannelOrNull(player: HyperZonePlayer): Channel? {
        return player.getProxyPlayerOrNull()?.getChannel()
    }
}
