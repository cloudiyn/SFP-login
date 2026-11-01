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

@file:Suppress("unused")

package cn.starfallplain.sfplogin.qq.whitelist

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import cn.starfallplain.sfplogin.api.HyperZoneApiProvider

/**
 * 独立插件形态的入口；内置（monolith）形态下由核心层反射加载 [QqWhitelistSubModule]。
 */
@Plugin(id = "hzl-qq-whitelist", name = "SFP-login - QQ Whitelist")
class QqWhitelistBootstrap @Inject constructor(
    private val server: ProxyServer
) {
    private val logger = java.util.logging.Logger.getLogger("hzl-qq-whitelist")

    @Subscribe
    fun onEnable(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        if (!server.pluginManager.getPlugin("sfplogin").isPresent) {
            logger.warning("SFP-login 主插件未安装，QQ 白名单模块暂不加载")
            return
        }
        try {
            HyperZoneApiProvider.get().registerModule(QqWhitelistSubModule())
        } catch (t: Throwable) {
            logger.warning("注册 QQ 白名单模块失败: ${t.message}")
        }
    }

    @Subscribe
    fun onDisable(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
        QqWhitelistRuntime.shutdown()
    }
}
