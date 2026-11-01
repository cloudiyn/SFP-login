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

package icu.h2l.login.qq.whitelist

import icu.h2l.api.HyperZoneApi
import icu.h2l.api.log.info
import icu.h2l.api.log.warn
import icu.h2l.api.message.HyperZoneModuleMessageResources
import icu.h2l.api.module.HyperSubModule
import icu.h2l.api.profile.WaitingAreaReleaseGateRegistryProvider
import icu.h2l.login.qq.whitelist.command.QqCommands
import icu.h2l.login.qq.whitelist.config.QqWhitelistConfigLoader
import icu.h2l.login.qq.whitelist.db.QqBindingStoreJdbc
import icu.h2l.login.qq.whitelist.db.QqBindingTable
import icu.h2l.login.qq.whitelist.db.QqWhitelistTableManager
import icu.h2l.login.qq.whitelist.gate.QqWhitelistReleaseGate
import icu.h2l.login.qq.whitelist.message.QqGroupMessages
import icu.h2l.login.qq.whitelist.onebot.NettyOneBotClient
import icu.h2l.login.qq.whitelist.onebot.OneBotClient
import icu.h2l.login.qq.whitelist.service.QqBindingService
import icu.h2l.login.qq.whitelist.store.QqBindCodeService

/**
 * QQ 绑定白名单子模块。
 *
 * 同时支持两种形态：
 * - monolith：被核心层通过反射实例化后调用 [register]；
 * - 独立 jar：由 [QqWhitelistBootstrap] 在 `ProxyInitializeEvent` 中注册。
 */
class QqWhitelistSubModule : HyperSubModule {

    override fun register(api: HyperZoneApi) {
        val dataDirectory = api.dataDirectory
        HyperZoneModuleMessageResources.copyBundledLocales(dataDirectory, "qq-whitelist", javaClass.classLoader)

        val config = QqWhitelistConfigLoader.load(dataDirectory)
        if (!config.enabled) {
            info { "QQ 白名单模块已在配置中禁用（qq-whitelist.conf: enabled=false）" }
            return
        }

        val databaseManager = api.databaseManager
        val bindingTable = QqBindingTable(databaseManager.tablePrefix)
        val tableManager = QqWhitelistTableManager(databaseManager, bindingTable)
        tableManager.createTable()
        api.proxy.eventManager.register(api, tableManager)

        val store = QqBindingStoreJdbc(databaseManager, bindingTable)
        val codeService = QqBindCodeService(
            ttlMillis = config.binding.codeTtlSeconds.coerceAtLeast(1) * 1000,
            codeLength = config.binding.codeLength,
            attemptLimit = config.binding.codeAttemptLimit
        )
        val messages = QqGroupMessages(dataDirectory, config.groupLanguage)
        val oneBotClient: OneBotClient = NettyOneBotClient(config.onebot)
        val service = QqBindingService(
            config = config,
            store = store,
            codes = codeService,
            messages = messages,
            onebotAvailable = { oneBotClient.isConnected() },
            sender = { groupId, text -> oneBotClient.sendGroupMessage(groupId, text) },
            log = { message -> info { message } }
        )
        oneBotClient.setGroupMessageHandler { groupId, qqId, rawMessage ->
            service.handleGroupMessage(groupId, qqId, rawMessage)
        }

        if (config.groups.isEmpty()) {
            warn { "QQ 白名单未配置 groups，群内绑定命令不会生效" }
        } else {
            oneBotClient.start()
        }

        val gate = QqWhitelistReleaseGate(config, service, messages)
        if (config.groups.isEmpty()) {
            // 没有配置群时无法完成绑定，再拦截等于把所有人挡在门外，因此不注册闸门。
            warn { "QQ 白名单未配置 groups，放行闸门不会生效（相当于所有人可通过）" }
        } else {
            WaitingAreaReleaseGateRegistryProvider.getOrNull()?.register(gate)
                ?: warn { "等待区放行闸门注册表不可用，QQ 白名单将不会拦截未绑定玩家" }
        }

        QqCommands.register(api, config, service, messages)
        QqWhitelistRuntime.bind(oneBotClient, gate.id)

        info {
            "QQ 白名单模块已加载：群 ${config.groups.size} 个，enforce=${config.enforce}，" +
                "单 QQ 上限 ${config.binding.maxPerQq}"
        }
    }
}

/**
 * 模块级运行时句柄，供独立插件形态在关闭时释放资源。
 */
object QqWhitelistRuntime {
    @Volatile
    private var oneBotClient: OneBotClient? = null

    @Volatile
    private var gateId: String? = null

    fun bind(client: OneBotClient, gateId: String) {
        this.oneBotClient = client
        this.gateId = gateId
    }

    fun shutdown() {
        oneBotClient?.stop()
        oneBotClient = null
        gateId?.let { id -> WaitingAreaReleaseGateRegistryProvider.getOrNull()?.unregister(id) }
        gateId = null
    }
}
