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

package cn.starfallplain.sfplogin.auth.offline

import cn.starfallplain.sfplogin.api.HyperZoneApi
import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import cn.starfallplain.sfplogin.api.log.info
import cn.starfallplain.sfplogin.api.module.HyperSubModule
import cn.starfallplain.sfplogin.api.profile.ChannelPasswordGateRegistry
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileServiceProvider
import cn.starfallplain.sfplogin.auth.offline.command.OfflineAuthCommandRegistrar
import cn.starfallplain.sfplogin.auth.offline.service.OfflineChannelPasswordGate
import cn.starfallplain.sfplogin.auth.offline.config.AuthOfflineConfigLoader
import cn.starfallplain.sfplogin.auth.offline.config.OfflineAuthMessageResourceLoader
import cn.starfallplain.sfplogin.auth.offline.db.OfflineAuthRepository
import cn.starfallplain.sfplogin.auth.offline.db.OfflineAuthTableManager
import cn.starfallplain.sfplogin.auth.offline.listener.OfflinePreLoginListener
import cn.starfallplain.sfplogin.auth.offline.listener.OfflineSessionAuthListener
import cn.starfallplain.sfplogin.auth.offline.mail.JakartaMailOfflineAuthEmailSender
import cn.starfallplain.sfplogin.auth.offline.mail.LoggingOfflineAuthEmailSender
import cn.starfallplain.sfplogin.auth.offline.mail.OfflineAuthEmailSender
import cn.starfallplain.sfplogin.auth.offline.service.OfflineAuthService
import cn.starfallplain.sfplogin.auth.offline.totp.OfflineTotpAuthenticator
import java.util.*

class OfflineSubModule : HyperSubModule {
    lateinit var offlineAuthTableManager: OfflineAuthTableManager
    lateinit var offlineAuthRepository: OfflineAuthRepository
    lateinit var offlineAuthService: OfflineAuthService

    override val credentialChannelIds: Set<String> = setOf("offline")

    override fun register(api: HyperZoneApi) {
        val proxy = api.proxy
        val dataDirectory = api.dataDirectory
        val databaseManager: HyperZoneDatabaseManager = api.databaseManager

        val profileTable = ProfileTable(databaseManager.tablePrefix)
        // Load offline matching configuration for this module
                AuthOfflineConfigLoader.load(dataDirectory)
        OfflineAuthMessageResourceLoader.load(dataDirectory)
        // 统一密码存储：三个认证渠道共享同一份仓储，由核心在启动时绑定
        val channelPasswordRepository = cn.starfallplain.sfplogin.api.password.ChannelPasswordProvider.get()
        offlineAuthTableManager = OfflineAuthTableManager(
            databaseManager = databaseManager,
            tablePrefix = databaseManager.tablePrefix,
            profileTable = profileTable,
            passwordRepository = channelPasswordRepository
        )
        offlineAuthRepository = OfflineAuthRepository(
            passwordRepository = channelPasswordRepository
        )
        val offlineAuthConfig = AuthOfflineConfigLoader.getConfig().main
        val logger = java.util.logging.Logger.getLogger("hzl-auth-offline")
        val emailSender: OfflineAuthEmailSender = when (offlineAuthConfig.email.deliveryMode.uppercase(Locale.ROOT)) {
            "SMTP" -> JakartaMailOfflineAuthEmailSender(
                config = offlineAuthConfig.email.smtp,
                serverName = offlineAuthConfig.email.smtp.serverName,
                logger = logger
            )

            else -> LoggingOfflineAuthEmailSender(logger, offlineAuthConfig.email.deliveryMode.uppercase(Locale.ROOT))
        }
        val totpAuthenticator = OfflineTotpAuthenticator(
            issuer = offlineAuthConfig.totp.issuer,
            pendingExpireMinutes = offlineAuthConfig.totp.pendingExpireMinutes
        )
        offlineAuthService = OfflineAuthService(
            repository = offlineAuthRepository,
            playerAccessor = api.hyperZonePlayers,
            profileService = HyperZoneProfileServiceProvider.get(),
            emailSender = emailSender,
            totpAuthenticator = totpAuthenticator,
            proxy = proxy
        )
        offlineAuthTableManager.createTable()
        proxy.eventManager.register(api, offlineAuthTableManager)

        // Register pre-login listener (handles channel init + offline UUID matching)
        proxy.eventManager.register(api, OfflinePreLoginListener())
        proxy.eventManager.register(api, OfflineSessionAuthListener(offlineAuthService))

        OfflineAuthCommandRegistrar.registerAll(
            commandManager = api.chatCommandManager,
            authService = offlineAuthService
        )
        // 接入统一渠道密码门：让 /login、/register、/changepassword 可被路由到离线渠道
        ChannelPasswordGateRegistry.register(
            OfflineChannelPasswordGate(
                authService = offlineAuthService,
                repository = offlineAuthRepository,
                playerAccessor = api.hyperZonePlayers
            )
        )
        proxy.eventManager.register(
            api,
            OfflineWaitingAreaEventListener(offlineAuthService, OfflineAuthPromptFlow(offlineAuthService))
        )
        info { "OfflineSubModule 已加载，离线聊天命令与提示监听器已注册" }
    }
}