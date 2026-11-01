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

package cn.starfallplain.sfplogin.module

import cn.starfallplain.sfplogin.api.module.HyperSubModule
import cn.starfallplain.sfplogin.api.module.ModuleDescriptor

/**
 * 内置模块描述符表。
 *
 * 主插件只遍历 [descriptors] 完成装配，不再逐个硬编码模块；[byId] 兼顾按 id 查询
 * （例如命令或诊断使用）。描述符只描述"是什么"，不持有任何运行时状态。
 */
object EmbeddedModuleRegistry {
    val authFloodgate = ModuleDescriptor(
        id = "hzl-auth-floodgate",
        configKey = "authFloodgate",
        displayName = "Auth Floodgate",
        implementationClassName = "cn.starfallplain.sfplogin.auth.floodgate.FloodgateSubModule",
        requiredPluginIds = listOf("floodgate"),
    )

    val authOffline = ModuleDescriptor(
        id = "hzl-auth-offline",
        configKey = "authOffline",
        displayName = "Auth Offline",
        implementationClassName = "cn.starfallplain.sfplogin.auth.offline.OfflineSubModule",
    )

    val authYggd = ModuleDescriptor(
        id = "hzl-auth-yggd",
        configKey = "authYggd",
        displayName = "Auth Yggdrasil",
        implementationClassName = "cn.starfallplain.sfplogin.auth.online.main.YggdrasilSubModule",
    )

    val safe = ModuleDescriptor(
        id = "hzl-safe",
        configKey = "safe",
        displayName = "Safe",
        implementationClassName = "cn.starfallplain.sfplogin.safe.SafeSubModule",
    )

    val profileSkin = ModuleDescriptor(
        id = "hzl-profile-skin",
        configKey = "profileSkin",
        displayName = "Profile Skin",
        implementationClassName = "cn.starfallplain.sfplogin.profile.skin.ProfileSkinSubModule",
    )

    val qqWhitelist = ModuleDescriptor(
        id = "hzl-qq-whitelist",
        configKey = "qqWhitelist",
        displayName = "QQ Whitelist",
        implementationClassName = "cn.starfallplain.sfplogin.qq.whitelist.QqWhitelistSubModule",
    )

    val dataMerge = ModuleDescriptor(
        id = "hzl-data-merge",
        configKey = "dataMerge",
        displayName = "Data Merge",
        implementationClassName = "cn.starfallplain.sfplogin.merge.MergeSubModule",
    )

    /** 全部内置模块，按装配顺序排列。 */
    val descriptors: List<ModuleDescriptor> = listOf(
        authFloodgate,
        authOffline,
        authYggd,
        safe,
        profileSkin,
        qqWhitelist,
        dataMerge,
    )

    private val descriptorsById: Map<String, ModuleDescriptor> = descriptors.associateBy { it.id }

    fun byId(id: String): ModuleDescriptor? = descriptorsById[id]

    fun instantiate(spec: ModuleDescriptor, classLoader: ClassLoader): HyperSubModule? {
        val className = spec.implementationClassName ?: return null
        val moduleClass = try {
            Class.forName(className, true, classLoader)
        } catch (_: ClassNotFoundException) {
            return null
        }

        require(HyperSubModule::class.java.isAssignableFrom(moduleClass)) {
            "$className does not implement HyperSubModule"
        }

        val constructor = moduleClass.getDeclaredConstructor()
        constructor.isAccessible = true
        return constructor.newInstance() as HyperSubModule
    }
}


