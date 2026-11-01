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

import com.velocitypowered.proxy.VelocityServer
import cn.starfallplain.sfplogin.api.HyperZoneApi
import cn.starfallplain.sfplogin.api.module.HyperSubModule
import cn.starfallplain.sfplogin.inject.network.listener.NetworkInjectListener
import cn.starfallplain.sfplogin.reflect.VelocityInternalAccess

class VelocityNetworkModule : HyperSubModule {
    override fun register(api: HyperZoneApi) {
        val proxy = api.proxy as VelocityServer

        val connectionManager = VelocityInternalAccess.getConnectionManager(proxy)
        val injector = VelocityNetworkInjectorImpl(connectionManager, proxy)

        injector.injectToServerInitializer()

//        发送到玩家方向
        proxy.eventManager.register(
            api,
            NetworkInjectListener(injector),
        )

//        发送到服务器方向
        injector.injectToBackend()
    }
}