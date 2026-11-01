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

package cn.starfallplain.sfplogin.auth.online.main

import cn.starfallplain.sfplogin.api.HyperZoneApi
import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import cn.starfallplain.sfplogin.api.message.HyperZoneModuleMessageResources
import cn.starfallplain.sfplogin.api.module.HyperSubModule
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileServiceProvider
import cn.starfallplain.sfplogin.auth.online.db.EntryTableManager
import cn.starfallplain.sfplogin.auth.online.listener.AuthListener
import cn.starfallplain.sfplogin.auth.online.manager.EntryConfigManager
import cn.starfallplain.sfplogin.auth.online.manager.YggdrasilAuthModule

class YggdrasilSubModule : HyperSubModule {
    lateinit var entryConfigManager: EntryConfigManager
    lateinit var entryTableManager: EntryTableManager
    lateinit var yggdrasilAuthModule: YggdrasilAuthModule

    override val credentialChannelIds: Set<String> = setOf("yggdrasil")

    override fun register(api: HyperZoneApi) {
        val proxy = api.proxy
        val dataDirectory = api.dataDirectory
        val databaseManager: HyperZoneDatabaseManager = api.databaseManager
        HyperZoneModuleMessageResources.copyBundledLocales(dataDirectory, "auth-yggd", javaClass.classLoader)

        val entryConfigManager = EntryConfigManager(dataDirectory, proxy)
        val entryTableManager = EntryTableManager(
            databaseManager = databaseManager,
            tablePrefix = databaseManager.tablePrefix,
            profileTable = ProfileTable(databaseManager.tablePrefix)
        )

        proxy.eventManager.register(api, entryTableManager)

        entryConfigManager.loadAllConfigs()

        val yggdrasilAuthModule = YggdrasilAuthModule(
            proxy = proxy,
            entryConfigManager = entryConfigManager,
            databaseManager = databaseManager,
            entryTableManager = entryTableManager,
            profileService = HyperZoneProfileServiceProvider.get(),
            playerAccessor = api.hyperZonePlayers
        )
        val authListener = AuthListener(yggdrasilAuthModule)

        proxy.eventManager.register(api, authListener)

        // 渠道密码共享命令由 offline 渠道统一注册并路由，本渠道不再注册自己的密码门

        this.entryConfigManager = entryConfigManager
        this.entryTableManager = entryTableManager
        this.yggdrasilAuthModule = yggdrasilAuthModule

    }
}
