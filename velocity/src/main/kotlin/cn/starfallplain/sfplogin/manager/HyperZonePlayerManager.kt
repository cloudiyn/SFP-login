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

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.proxy.Player
import cn.starfallplain.sfplogin.api.event.connection.OpenPreLoginEvent
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.player.HyperZonePlayerAccessor
import cn.starfallplain.sfplogin.api.player.getChannel
import cn.starfallplain.sfplogin.api.util.RemapUtils
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.listener.PlayerAreaLifecycleListener
import cn.starfallplain.sfplogin.player.VelocityHyperZonePlayer
import io.netty.channel.Channel
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object HyperZonePlayerManager : HyperZonePlayerAccessor {
    private val playersByChannel = ConcurrentHashMap<Channel, VelocityHyperZonePlayer>()

    @Subscribe(priority = Short.MIN_VALUE)
    fun onPreLoginChannelInit(event: OpenPreLoginEvent) {
        // Run last so other listeners can finish deciding the player's online/offline mode.
        create(event.channel, event.userName, event.uuid, event.isOnline)
    }

    override fun create(channel: Channel, userName: String, uuid: UUID?, isOnline: Boolean): HyperZonePlayer {
        val finalUuid = uuid ?: run {
            val prefix = SFPLoginMain.getCoreConfig().remap.prefix
            RemapUtils.genUUID(userName, prefix)
        }
        val createdPlayer = VelocityHyperZonePlayer(userName, finalUuid, isOnline)
        val existing = playersByChannel.putIfAbsent(channel, createdPlayer)
        if (existing != null) {
            throw IllegalStateException("重复创建 HyperZonePlayer：channel=$channel clientOriginal=$userName")
        }
        return createdPlayer
    }

    override fun getByPlayer(player: Player): HyperZonePlayer {
       return getByChannel(player.getChannel())
    }

    fun getByPlayerOrNull(player: Player): VelocityHyperZonePlayer? {
        return getByChannelOrNull(player.getChannel())
    }

    override fun getByChannel(channel: Channel): HyperZonePlayer {
        return playersByChannel[channel]
            ?: throw IllegalStateException("未找到对应的 HyperZonePlayer：channel=$channel")
    }

    fun getByChannelOrNull(channel: Channel): VelocityHyperZonePlayer? {
        return playersByChannel[channel]
    }

    fun remove(player: Player) {
        val channel = player.getChannel()
        SFPLoginMain.getInstance().loginManager.clearCredentialsByChannel(channel)
        playersByChannel.remove(channel)?.let { removedPlayer ->
            runCatching {
                SFPLoginMain.getInstance().profileService.clear(removedPlayer)
            }
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val player = event.player
        getByPlayerOrNull(player)?.let { PlayerAreaLifecycleListener.onDisconnect(player, it) }
        remove(player)
    }
}
