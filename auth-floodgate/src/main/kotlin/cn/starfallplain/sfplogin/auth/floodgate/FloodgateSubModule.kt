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

package cn.starfallplain.sfplogin.auth.floodgate

import cn.starfallplain.sfplogin.api.HyperZoneApi
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import cn.starfallplain.sfplogin.api.log.info
import cn.starfallplain.sfplogin.api.message.HyperZoneModuleMessageResources
import cn.starfallplain.sfplogin.api.module.HyperSubModule
import cn.starfallplain.sfplogin.auth.floodgate.config.FloodgateAuthConfigLoader
import cn.starfallplain.sfplogin.auth.floodgate.db.FloodgateAuthRepository
import cn.starfallplain.sfplogin.auth.floodgate.db.FloodgateAuthTableManager
import cn.starfallplain.sfplogin.auth.floodgate.listener.FloodgateGameProfileListener
import cn.starfallplain.sfplogin.auth.floodgate.listener.FloodgateLoginProfileReplaceListener
import cn.starfallplain.sfplogin.auth.floodgate.listener.FloodgateOpenStartAuthListener
import cn.starfallplain.sfplogin.auth.floodgate.listener.FloodgateVServerAuthListener
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateApiHolder
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateAuthService
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateSessionHolder

class FloodgateSubModule : HyperSubModule {
    override val credentialChannelIds: Set<String> = setOf("floodgate")

    override fun register(api: HyperZoneApi) {
        HyperZoneModuleMessageResources.copyBundledLocales(api.dataDirectory, "auth-floodgate", javaClass.classLoader)
        val config = FloodgateAuthConfigLoader.load(api.dataDirectory)
        val profileTable = ProfileTable(api.databaseManager.tablePrefix)
        val tableManager = FloodgateAuthTableManager(
            databaseManager = api.databaseManager,
            tablePrefix = api.databaseManager.tablePrefix,
            profileTable = profileTable,
            passwordRepository = cn.starfallplain.sfplogin.api.password.ChannelPasswordProvider.getOrNull(),
        )
        val repository = FloodgateAuthRepository(
            databaseManager = api.databaseManager,
            table = tableManager.floodgateAuthTable,
        )
        val floodgateApiHolder = FloodgateApiHolder()
        val authService = FloodgateAuthService(
            api = api,
            floodgateApiHolder = floodgateApiHolder,
            sessionHolder = FloodgateSessionHolder(),
            repository = repository,
            config = config,
        )
        tableManager.createTable()
        api.proxy.eventManager.register(api, tableManager)
        api.proxy.eventManager.register(api, FloodgateOpenStartAuthListener(authService, floodgateApiHolder))
        api.proxy.eventManager.register(api, FloodgateGameProfileListener(authService, floodgateApiHolder))
        api.proxy.eventManager.register(api, FloodgateVServerAuthListener(authService))
        api.proxy.eventManager.register(api, FloodgateLoginProfileReplaceListener())

        // 渠道密码共享命令与统一密码门由 offline 渠道负责注册与路由，本渠道不再注册自己的密码门

        info {
            "FloodgateSubModule 已加载；已创建 Floodgate 专属凭证绑定表，并在 OpenStartAuth 与初始档案校验阶段补注册 Floodgate 接管监听器；自动去除 Floodgate API 玩家名前缀=${config.stripUsernamePrefix}；Profile 解析透传 Floodgate UUID=${config.passFloodgateUuidToProfileResolve}"
        }
    }
}

