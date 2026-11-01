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

package cn.starfallplain.sfplogin.inject.network

import com.velocitypowered.api.permission.PermissionFunction
import com.velocitypowered.api.permission.PermissionProvider
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection
import com.velocitypowered.proxy.connection.client.AuthSessionHandler
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.connection.client.InitialConnectSessionHandler
import com.velocitypowered.proxy.connection.client.LoginInboundConnection
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.reflect.VelocityInternalAccess
import cn.starfallplain.sfplogin.sfpLoginMain

/**
 * Velocity 内部反射访问的门面，保持与旧版调用方兼容的扩展函数 API。
 *
 * 内部实现已全部委托给 [VelocityInternalAccess]，后者负责跨版本模糊定位与 ASM 加速。
 */
object NettyReflectionHelper {

    fun LoginInboundConnection.fireLogin(action: Runnable) {
        VelocityInternalAccess.fireLogin(this, action)
    }

    fun LoginInboundConnection.reflectedDelegatedConnection(): MinecraftConnection {
        return VelocityInternalAccess.delegatedConnection(this)
    }

    fun LoginInboundConnection.reflectedCleanup() {
        VelocityInternalAccess.cleanup(this)
    }

    fun ConnectedPlayer.reflectedTeardown() {
        VelocityInternalAccess.teardown(this)
    }

    fun createConnectedPlayer(
        server: VelocityServer,
        inbound: LoginInboundConnection,
        profile: GameProfile,
        onlineMode: Boolean,
    ): ConnectedPlayer {
        return runCatching {
            VelocityInternalAccess.createConnectedPlayer(server, inbound, profile, onlineMode)
        }.getOrElse { ex ->
            sfpLoginMain().logger.error("反射创建 ConnectedPlayer 失败。", ex)
            throw ex
        }
    }

    fun defaultPermissions(): PermissionProvider {
        return VelocityInternalAccess.defaultPermissions()
    }

    fun setPermissionFunction(player: ConnectedPlayer, function: PermissionFunction) {
        runCatching {
            VelocityInternalAccess.setPermissionFunction(player, function)
        }.getOrElse { ex ->
            sfpLoginMain().logger.error("反射设置 ConnectedPlayer 权限函数失败。", ex)
            throw ex
        }
    }

    fun setConnectionInFlight(player: ConnectedPlayer, serverConnection: VelocityServerConnection?) {
        runCatching {
            VelocityInternalAccess.setConnectionInFlight(player, serverConnection)
        }.getOrElse { ex ->
            sfpLoginMain().logger.error(
                "反射设置 ConnectedPlayer.connectionInFlight 失败。", ex
            )
            throw ex
        }
    }

    fun setGameProfile(player: ConnectedPlayer, profile: GameProfile) {
        runCatching {
            VelocityInternalAccess.setConnectedPlayerProfile(player, profile)
        }.getOrElse { ex ->
            sfpLoginMain().logger.error(
                "反射设置 ConnectedPlayer.profile 失败。", ex
            )
            throw ex
        }
    }

    fun createAuthSessionHandler(
        server: VelocityServer?,
        inbound: LoginInboundConnection?,
        profile: GameProfile?,
        onlineMode: Boolean,
        serverIdHash: String,
    ): AuthSessionHandler {
        return runCatching {
            VelocityInternalAccess.createAuthSessionHandler(server, inbound, profile, onlineMode, serverIdHash)
        }.getOrElse { ex ->
            sfpLoginMain().logger.error("反射创建 AuthSessionHandler 失败。", ex)
            throw ex
        }
    }

    fun createInitialConnectSessionHandler(
        player: ConnectedPlayer,
        server: VelocityServer,
    ): InitialConnectSessionHandler {
        return runCatching {
            VelocityInternalAccess.createInitialConnectSessionHandler(player, server)
        }.getOrElse { ex ->
            sfpLoginMain().logger.error(
                "反射创建 InitialConnectSessionHandler 失败。", ex
            )
            throw ex
        }
    }
}
