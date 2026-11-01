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

package cn.starfallplain.sfplogin.profile.skin

import cn.starfallplain.sfplogin.api.HyperZoneApi
import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import cn.starfallplain.sfplogin.api.log.info
import cn.starfallplain.sfplogin.api.message.HyperZoneModuleMessageResources
import cn.starfallplain.sfplogin.api.module.HyperSubModule
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileServiceProvider
import cn.starfallplain.sfplogin.profile.skin.config.ProfileSkinConfigLoader
import cn.starfallplain.sfplogin.profile.skin.db.*
import cn.starfallplain.sfplogin.profile.skin.service.ProfileSkinSelfReplayService
import cn.starfallplain.sfplogin.profile.skin.service.ProfileSkinService

class ProfileSkinSubModule : HyperSubModule {
    lateinit var tableManager: ProfileSkinCacheTableManager
    lateinit var cacheRepository: ProfileSkinCacheRepository
    lateinit var profileRepository: ProfileSkinProfileRepository
    lateinit var service: ProfileSkinService
    lateinit var selfReplayService: ProfileSkinSelfReplayService

    override fun register(api: HyperZoneApi) {
        val proxy = api.proxy
        val dataDirectory = api.dataDirectory
        val databaseManager: HyperZoneDatabaseManager = api.databaseManager
        HyperZoneModuleMessageResources.copyBundledLocales(dataDirectory, "profile-skin", javaClass.classLoader)
        val config = ProfileSkinConfigLoader.load(dataDirectory)
        val cacheTable = ProfileSkinCacheTable(
            prefix = databaseManager.tablePrefix
        )
        val profileTable = ProfileSkinProfileTable(
            prefix = databaseManager.tablePrefix,
            profileTable = ProfileTable(databaseManager.tablePrefix),
            cacheTable = cacheTable
        )

        tableManager = ProfileSkinCacheTableManager(databaseManager, cacheTable, profileTable)
        cacheRepository = ProfileSkinCacheRepository(databaseManager, cacheTable)
        profileRepository = ProfileSkinProfileRepository(databaseManager, profileTable)
        service = ProfileSkinService(
            config,
            cacheRepository,
            profileRepository,
            HyperZoneProfileServiceProvider.get(),
            api.hyperZonePlayers
        )
        selfReplayService = ProfileSkinSelfReplayService(
            api.hyperZonePlayers,
            config,
            cacheRepository,
            profileRepository,
            HyperZoneProfileServiceProvider.get()
        )

        tableManager.createTable()
        proxy.eventManager.register(api, tableManager)
        proxy.eventManager.register(api, service)
        proxy.eventManager.register(api, selfReplayService)

        info { "ProfileSkinSubModule 已加载，skin_cache / skin_profile、皮肤修复与 self replay 监听器已注册" }
    }
}

