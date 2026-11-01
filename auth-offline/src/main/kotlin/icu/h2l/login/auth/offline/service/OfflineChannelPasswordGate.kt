/*
 * This file is part of HyperZoneLogin, licensed under the GNU Affero General Public License v3.0 or later.
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

package icu.h2l.login.auth.offline.service

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.player.HyperZonePlayer
import icu.h2l.api.player.HyperZonePlayerAccessor
import icu.h2l.api.profile.ChannelPasswordGate
import icu.h2l.api.profile.ChannelPasswordGateStatus
import icu.h2l.login.auth.offline.OfflineAuthMessages
import icu.h2l.login.auth.offline.db.OfflineAuthRepository
import net.kyori.adventure.text.Component
import java.util.*

/**
 * 离线（本地账号）渠道的密码门实现。
 *
 * 离线验证本身就是靠本地密码完成的，
 * 这里把既有能力接入统一的渠道密码门体系，
 * 使 /login、/register、/changepassword 三个共享命令可以统一路由到本渠道。
 *
 * 自 yggdrasil / floodgate 的本地密码门删除后，本门成为**全渠道统一的密码门**：
 * 任何尚未完成档案绑定的玩家（无论其远端认证渠道），
 * 只要其玩家名在本渠道存在密码记录，就由本门接管密码命令；
 * 无记录时也由本门负责 /register 首次注册。
 */
class OfflineChannelPasswordGate(
    private val authService: OfflineAuthService,
    private val repository: OfflineAuthRepository,
    private val playerAccessor: HyperZonePlayerAccessor
) : ChannelPasswordGate {

    override val channelId: String = CHANNEL_ID

    /** 统一兜底渠道；与其他更具体的渠道冲突时让位。 */
    override val priority: Int = 0

    override fun supports(player: HyperZonePlayer): Boolean {
        if (player.getSubmittedCredentials().isNotEmpty()) {
            return false
        }
        // 已绑定档案说明本次连接已完成密码校验，不再认领
        if (player.hasAttachedProfile()) {
            return false
        }
        // 统一密码门：任何仍需密码校验的玩家（含 yggdrasil / floodgate 渠道）都由本门接管
        return true
    }

    override fun getGateStatus(player: HyperZonePlayer): ChannelPasswordGateStatus? {
        // 已提交凭证 = 密码已经通过（登录流程会先提交凭证再触发 overVerify）
        if (player.getSubmittedCredentials().isNotEmpty()) {
            return null
        }
        // 已绑定档案说明本次连接已完成密码校验，不再认领
        if (player.hasAttachedProfile()) {
            return null
        }
        val normalizedName = player.clientOriginalName.lowercase(Locale.ROOT)
        return if (repository.getByName(normalizedName) != null) {
            ChannelPasswordGateStatus.NEEDS_LOGIN
        } else {
            ChannelPasswordGateStatus.NEEDS_REGISTER
        }
    }

    /**
     * 统一密码门：只要玩家尚未绑定档案，本次连接就必然要过一次密码门，
     * 因此 struck 直通模式必须先把玩家桥接到等待区，否则无法输入 /login、/register。
     */
    override fun willHold(player: HyperZonePlayer): Boolean {
        if (player.getSubmittedCredentials().isNotEmpty()) {
            return false
        }
        return !player.hasAttachedProfile()
    }

    override fun handleRegister(player: Player, args: Array<String>): Component {
        if (args.size != 2 || args[0] != args[1]) {
            return if (args.size != 2) {
                OfflineAuthMessages.REGISTER_USAGE
            } else {
                OfflineAuthMessages.PASSWORD_MISMATCH
            }
        }
        return authService.register(player, args[0]).message
    }

    override fun handleLogin(player: Player, args: Array<String>): Component {
        val hyperPlayer = runCatching { playerAccessor.getByPlayer(player) }.getOrNull()
        if (hyperPlayer != null && hyperPlayer.hasAttachedProfile()) {
            return OfflineAuthMessages.ALREADY_LOGGED_IN
        }

        val request = parseLoginRequest(args) ?: return OfflineAuthMessages.LOGIN_USAGE
        return when (request) {
            is LoginRequest.Default -> authService.login(player, request.password, request.totpCode)
            is LoginRequest.ExplicitUser -> authService.loginAs(
                player,
                request.username,
                request.password,
                request.totpCode
            )
        }.message
    }

    override fun handleChangePassword(player: Player, args: Array<String>): Component {
        if (args.size != 2) {
            return OfflineAuthMessages.CHANGE_PASSWORD_USAGE
        }
        return authService.changePassword(player, args[0], args[1]).message
    }

    private fun parseLoginRequest(args: Array<String>): LoginRequest? {
        return when {
            args.isNotEmpty() && args[0].equals("as", ignoreCase = true) && args.size in 3..4 -> LoginRequest.ExplicitUser(
                username = args[1],
                password = args[2],
                totpCode = args.getOrNull(3)
            )
            args.isNotEmpty() && args[0].equals("as", ignoreCase = true) -> null
            args.size in 1..2 -> LoginRequest.Default(
                password = args[0],
                totpCode = args.getOrNull(1)
            )
            else -> null
        }
    }

    private sealed interface LoginRequest {
        data class Default(val password: String, val totpCode: String?) : LoginRequest

        data class ExplicitUser(val username: String, val password: String, val totpCode: String?) : LoginRequest
    }

    companion object {
        private const val CHANNEL_ID = "offline"
    }
}
