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

package cn.starfallplain.sfplogin

// Module implementations (auth-offline, auth-yggd, data-merge) are now separate plugins
// and will register themselves with the main plugin at runtime. Do not import them here.
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.proxy.ProxyServer
import cn.starfallplain.sfplogin.api.HyperZoneApi
import cn.starfallplain.sfplogin.api.command.HyperChatCommandManager
import cn.starfallplain.sfplogin.api.command.HyperChatCommandRegistration
import cn.starfallplain.sfplogin.api.message.HyperZoneMessageServiceProvider
import cn.starfallplain.sfplogin.api.module.HyperSubModule
import cn.starfallplain.sfplogin.api.player.HyperZonePlayerAccessor
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredentialFlowProvider
import cn.starfallplain.sfplogin.api.profile.CredentialChannelRegistryProvider
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileServiceProvider
import cn.starfallplain.sfplogin.api.profile.WaitingAreaReleaseGateRegistry
import cn.starfallplain.sfplogin.api.profile.WaitingAreaReleaseGateRegistryProvider
import cn.starfallplain.sfplogin.api.util.ConfigCommentTranslatorProvider
import cn.starfallplain.sfplogin.api.util.ConfigFormat
import cn.starfallplain.sfplogin.api.util.ConfigFormatProvider
import cn.starfallplain.sfplogin.api.util.ConfigLoader
import cn.starfallplain.sfplogin.config.i18n.ConfigCommentI18nService
import cn.starfallplain.sfplogin.api.auth.AuthPromptPresenterRegistry
import cn.starfallplain.sfplogin.api.vServer.HyperZoneVServerAdapter
import cn.starfallplain.sfplogin.dialog.SfpDialogAuthPromptPresenter
import cn.starfallplain.sfplogin.command.HyperZoneLoginCommand
import cn.starfallplain.sfplogin.config.*
import cn.starfallplain.sfplogin.database.DatabaseConfig
import cn.starfallplain.sfplogin.database.DatabaseHelper
import cn.starfallplain.sfplogin.inject.network.VelocityNetworkModule
import cn.starfallplain.sfplogin.listener.*
import cn.starfallplain.sfplogin.manager.HyperChatCommandManagerImpl
import cn.starfallplain.sfplogin.manager.HyperZonePlayerManager
import cn.starfallplain.sfplogin.manager.LoginManager
import cn.starfallplain.sfplogin.message.MessageKeys
import cn.starfallplain.sfplogin.message.MessageService
import cn.starfallplain.sfplogin.module.EmbeddedModuleRegistry
import cn.starfallplain.sfplogin.module.EmbeddedModuleSpec
import cn.starfallplain.sfplogin.profile.CredentialChannelRegistryImpl
import cn.starfallplain.sfplogin.profile.VelocityHyperZoneProfileService
import cn.starfallplain.sfplogin.profile.WaitingAreaReleaseGateRegistryImpl
import cn.starfallplain.sfplogin.util.isSnapshotPluginVersion
import cn.starfallplain.sfplogin.util.readRuntimePluginVersion
import cn.starfallplain.sfplogin.util.registerApiLogger
import cn.starfallplain.sfplogin.vServer.command.ExitVServerCommand
import cn.starfallplain.sfplogin.vServer.command.OverVServerCommand
import cn.starfallplain.sfplogin.vServer.outpre.OutPreVServerAuth
import cn.starfallplain.sfplogin.vServer.outpre.OutPreAuthEndpointProbe
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import java.nio.file.Files
import java.nio.file.Path

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
class SFPLoginMain(
    private val server: ProxyServer,
    val logger: ComponentLogger,
    val dataDirectory: Path,
    private val plugin: HyperZoneApi
) {
    private val slowTestOverRegistration = HyperChatCommandRegistration(
        name = "over",
        executor = OverVServerCommand()
    )
    @Volatile
    private var slowTestCommandRegistered = false

    var activeVServerAdapter: HyperZoneVServerAdapter? = null
    lateinit var databaseManager: cn.starfallplain.sfplogin.manager.DatabaseManager
    lateinit var databaseHelper: DatabaseHelper
    lateinit var profileService: VelocityHyperZoneProfileService
    lateinit var credentialChannelRegistry: CredentialChannelRegistryImpl
    lateinit var messageService: MessageService
    lateinit var loginManager: LoginManager
    lateinit var waitingAreaReleaseGateRegistry: WaitingAreaReleaseGateRegistry
    val serverAdapter: HyperZoneVServerAdapter?
        get() = activeVServerAdapter
    val hyperZonePlayers: HyperZonePlayerAccessor
        get() = HyperZonePlayerManager
    val chatCommandManager: HyperChatCommandManager
        get() = HyperChatCommandManagerImpl


    companion object {
        private lateinit var instance: SFPLoginMain
        private lateinit var coreConfig: CoreConfig
        private lateinit var startConfig: StartConfig

        @JvmStatic
        fun getCoreConfig(): CoreConfig = coreConfig

        @JvmStatic
        fun getInstance(): SFPLoginMain = instance

    }

    init {
        instance = this
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    fun onEnable(event: ProxyInitializeEvent) {
        registerApiLogger()
        // ── 第一步：加载启动前置配置（不受 i18n 影响，硬编码提示）──────────────────────
        startConfig = StartConfigLoader.load(dataDirectory)
        if (!startConfig.ready) {
            logger.error("================================================================")
            logger.error("  你看起来似乎还没准备好，请先配置 start.conf 再继续使用 SFP-login")
            logger.error("  It seems you are not ready yet.")
            logger.error("  Please configure start.conf before using SFP-login.")
            logger.error("================================================================")
            return
        }
        // ── 第二步：根据 start.conf 中的 format 绑定全局配置格式 ──────────────────────
        ConfigFormatProvider.bind(ConfigFormat.fromKey(startConfig.format))
        // ── 第三步：根据 start.conf 中的 language 初始化配置注释 i18n 服务 ──────────
        ConfigCommentTranslatorProvider.bind(ConfigCommentI18nService(logger, startConfig.language))
        loadCoreConfig()
        credentialChannelRegistry = CredentialChannelRegistryImpl(coreConfig.auth)
        messageService = MessageService(dataDirectory, logger)
        messageService.load(coreConfig.messages)
        HyperZoneMessageServiceProvider.bind(messageService)
        connectDatabase()
        // 创建基础表（Profile 表等）
        createBaseTables()
        // 绑定统一渠道密码存储（三渠道共用），供各认证子模块读取
        val channelPasswordRepository =
            cn.starfallplain.sfplogin.api.password.ChannelPasswordRepository(databaseManager, databaseManager.channelPasswordTable)
        cn.starfallplain.sfplogin.api.password.ChannelPasswordProvider.bind(channelPasswordRepository)
        // 绑定统一渠道密码校验/会话服务（三渠道复用 offline 的校验与会话逻辑）
        cn.starfallplain.sfplogin.api.password.ChannelPasswordServiceProvider.bind(
            cn.starfallplain.sfplogin.api.password.ChannelPasswordService(channelPasswordRepository)
        )
        profileService = VelocityHyperZoneProfileService(databaseHelper)
        HyperZoneProfileServiceProvider.bind(profileService)
        loginManager = LoginManager(server, profileService)
        HyperZoneCredentialFlowProvider.bind(loginManager)
        CredentialChannelRegistryProvider.bind(credentialChannelRegistry)
        // 绑定等待区放行闸门注册表：模块可在 register(api) 阶段注册“离开等待区前”的放行校验
        // （例如 QQ 绑定白名单），核心层在 attach 完成后、真正离开等待区之前同步询问。
        waitingAreaReleaseGateRegistry = WaitingAreaReleaseGateRegistryImpl()
        WaitingAreaReleaseGateRegistryProvider.bind(waitingAreaReleaseGateRegistry)

        activeVServerAdapter = null

        val configuredOutPreAuthAddress = coreConfig.vServer.outpre.resolveOutpreAuthAddress()
        if (configuredOutPreAuthAddress != null) {
            val outPreAdapter = OutPreVServerAuth(server)
            activeVServerAdapter = outPreAdapter
            outPreAdapter.init(plugin)
            logger.info(
                "Using outpre waiting-area adapter on direct auth endpoint '{}' ({})",
                coreConfig.vServer.outpre.outpreAuthTargetLabel(),
                configuredOutPreAuthAddress,
            )
            warnIfOutpreAuthUnreachable(configuredOutPreAuthAddress)
        } else {
            logger.warn(
                "vServer.outpre authHost/authPort 无效，等待区不可用；请检查 core.conf 的 vServer.outpre 配置"
            )
        }

        HyperChatCommandManagerImpl.bindVServer(proxy, activeVServerAdapter)
        activeVServerAdapter?.let { proxy.eventManager.register(plugin, it) }

        chatCommandManager.register(
            HyperChatCommandRegistration(
                name = "exit",
                executor = ExitVServerCommand()
            )
        )
        syncSlowTestCommands()

//        最后加载模块
        // 注册 Java dialog 登录提示框（Minecraft 1.21.6+），基岩表单由 auth-floodgate 模块自行注册
        registerAuthPromptPresenters()
        // Keep internal modules that are part of the main plugin
        registerModule(VelocityNetworkModule(), plugin)
        registerConfiguredEmbeddedModules()
        // External modules (auth-offline, auth-yggd, data-merge) will be loaded as
        // separate Velocity plugins and should call `registerModule(...)` on this
        // main plugin during their own initialization.
        val hzlCommand = HyperZoneLoginCommand().createCommand()
        val hzlCommandMeta = proxy.commandManager.metaBuilder(hzlCommand).build()
        proxy.commandManager.register(hzlCommandMeta, hzlCommand)
        proxy.eventManager.register(plugin, AttachedProfileInitialGameProfileListener())
        proxy.eventManager.register(plugin, LoginProfileReplaceDefaultListener())
        proxy.eventManager.register(plugin, LoginVerifyListener())
        proxy.eventManager.register(plugin, PlayerAreaLifecycleListener)
        proxy.eventManager.register(plugin, HyperZonePlayerManager)

        logInternalTestWarning()

    }

    val proxy: ProxyServer
        get() = server

    fun registerModule(module: HyperSubModule, api: HyperZoneApi) {
        try {
            module.credentialChannelIds.forEach { channelId ->
                val ability = credentialChannelRegistry.registerChannel(channelId)
                logger.info(
                    "凭证渠道已注册: {} (模块: {}) [canRegister={}]",
                    channelId,
                    module.javaClass.simpleName,
                    ability.canRegister
                )
            }
            module.register(api)
            logger.info("模块加载成功: ${module.javaClass.name}")
        } catch (e: Exception) {
            logger.error("加载模块 ${module.javaClass.name} 失败: ${e.message}", e)
        }
    }

    private fun registerConfiguredEmbeddedModules() {
        registerEmbeddedModule(EmbeddedModuleRegistry.authFloodgate, coreConfig.modules.authFloodgate)
        registerEmbeddedModule(EmbeddedModuleRegistry.authOffline, coreConfig.modules.authOffline)
        registerEmbeddedModule(EmbeddedModuleRegistry.authYggd, coreConfig.modules.authYggd)
        registerEmbeddedModule(EmbeddedModuleRegistry.safe, coreConfig.modules.safe)
        registerEmbeddedModule(EmbeddedModuleRegistry.profileSkin, coreConfig.modules.profileSkin)
        registerEmbeddedModule(EmbeddedModuleRegistry.qqWhitelist, coreConfig.modules.qqWhitelist)
        registerEmbeddedModule(EmbeddedModuleRegistry.dataMerge, coreConfig.modules.dataMerge)
    }

    private fun registerEmbeddedModule(spec: EmbeddedModuleSpec, enabled: Boolean) {
        if (!enabled) {
            logger.info("内置模块已禁用: ${spec.displayName} (modules.conf -> ${spec.configKey}=false)")
            return
        }

        if (proxy.pluginManager.getPlugin(spec.externalPluginId).isPresent) {
            logger.info("检测到外部插件 ${spec.externalPluginId}，跳过内置模块 ${spec.displayName}")
            return
        }

        val missingRequiredPlugins = spec.requiredPluginIds.filter { requiredPluginId ->
            !proxy.pluginManager.getPlugin(requiredPluginId).isPresent
        }
        if (missingRequiredPlugins.isNotEmpty()) {
            logger.info(
                "内置模块 ${spec.displayName} 依赖插件缺失: ${missingRequiredPlugins.joinToString()}，已跳过"
            )
            return
        }

        val embeddedModule = try {
            EmbeddedModuleRegistry.instantiate(spec, javaClass.classLoader)
        } catch (e: Throwable) {
            logger.error("内置模块 ${spec.displayName} 实例化失败: ${e.message}", e)
            return
        }

        if (embeddedModule == null) {
            logger.info("当前主 jar 未内置模块 ${spec.displayName}，已跳过；如需单文件分发，请使用 monolith 产物")
            return
        }

        registerModule(embeddedModule, plugin)
    }


    /**
     * Trigger re-join authentication flow in the active waiting-area implementation.
     */
    fun triggerVServerReJoinForPlayer(player: com.velocitypowered.api.proxy.Player) {
        serverAdapter?.reJoin(player)
            ?: messageService.send(player, MessageKeys.HzlCommand.AUTH_FLOW_UNAVAILABLE)
    }

    /**
     * 启动时不阻塞地在后台探测认证服可达性；连不上只 WARN，不影响代理启动。
     */
    private fun warnIfOutpreAuthUnreachable(address: java.net.InetSocketAddress) {
        val thread = Thread({
            if (!OutPreAuthEndpointProbe.isReachable(address)) {
                logger.warn(
                    "outpre 认证服 {}:{} 当前不可达；等待区连接会失败，请确认认证服已启动（测试服常用 127.0.0.1:30066）",
                    address.hostString,
                    address.port,
                )
            }
        }, "SFP-login outpre auth probe")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 注册 Java dialog 登录提示框展示层。
     *
     * 关闭 `auth.prompt.dialogEnabled` 时完全不注册，等待区回退到聊天命令提示。
     * 基岩（Floodgate）表单由 `auth-floodgate` 模块以更高优先级自行注册。
     */
    private fun registerAuthPromptPresenters() {
        val prompt = coreConfig.auth.prompt
        if (!prompt.dialogEnabled) {
            logger.info("登录提示框已禁用（auth.prompt.dialogEnabled=false），等待区将使用聊天命令提示")
            return
        }
        AuthPromptPresenterRegistry.register(
            SfpDialogAuthPromptPresenter(
                timeoutMillis = prompt.dialogTimeoutSeconds.coerceAtLeast(0L) * 1_000L,
                inputLengthLimit = prompt.inputLengthLimit.coerceAtLeast(8),
            )
        )
        logger.info("已注册 Java dialog 登录提示框（协议 {}）", cn.starfallplain.sfplogin.dialog.DialogPacketIdsTable.supportedProtocols().sorted())
    }

    private fun logInternalTestWarning() {
        val pluginVersion = readRuntimePluginVersion(javaClass.classLoader)
        if (!isSnapshotPluginVersion(pluginVersion)) {
            return
        }
        logger.warn("========================================")
        logger.warn("=== ⚠ 内测版本，可能有 bug，请勿分发 ===")
        logger.warn("========================================")
    }

    fun reloadRuntimeConfigs() {
        loadCoreConfig()
        if (::messageService.isInitialized) {
            messageService.load(coreConfig.messages)
        }
        syncSlowTestCommands()
    }

    private fun syncSlowTestCommands() {
        if (coreConfig.debug.slowTest.enabled) {
            if (!slowTestCommandRegistered) {
                chatCommandManager.register(slowTestOverRegistration)
                slowTestCommandRegistered = true
            }
            return
        }

        if (slowTestCommandRegistered) {
            chatCommandManager.unregister(slowTestOverRegistration.name)
            slowTestCommandRegistered = false
        }
    }


    





    private fun loadCoreConfig() {
        var migrated = false
        val config = ConfigLoader.loadConfig<CoreConfig>(
            dataDirectory = dataDirectory,
            fileName = CoreConfigMigrator.CONFIG_FILE_NAME,
            defaultProvider = { CoreConfig() },
            postLoadHook = { node, loaded, _ ->
                migrated = CoreConfigMigrator.migrate(node, dataDirectory, logger)
                if (migrated) loaded.copy(vServer = loaded.vServer.copy(mode = CoreConfigMigrator.TARGET_MODE)) else loaded
            },
            forceSaveHook = { _, firstCreation -> firstCreation || migrated },
        )
        coreConfig = config
        // 配置加载完成后，用 defaultLocale 覆盖 i18n 服务，使后续模块配置首次生成时使用正确语言
        ConfigCommentTranslatorProvider.bind(ConfigCommentI18nService(logger, config.messages.defaultLocale))
    }

    private fun connectDatabase() {
        logger.info("正在初始化数据库...")
        
        val dbConfig = when (coreConfig.database.type.uppercase()) {
            "SQLITE" -> {
                val dbPath = dataDirectory.resolve(coreConfig.database.sqlite.path)
                // 确保数据库文件的父目录存在
                dbPath.parent?.let { Files.createDirectories(it) }
                DatabaseConfig.sqlite(
                    path = dbPath.toString(),
                    tablePrefix = coreConfig.database.tablePrefix,
                    connectionTimeout = coreConfig.database.pool.connectionTimeout,
                    idleTimeout = coreConfig.database.pool.idleTimeout,
                    maxLifetime = coreConfig.database.pool.maxLifetime
                )
            }
            "MYSQL" -> {
                DatabaseConfig.mysql(
                    host = coreConfig.database.mysql.host,
                    port = coreConfig.database.mysql.port,
                    database = coreConfig.database.mysql.database,
                    username = coreConfig.database.mysql.username,
                    password = coreConfig.database.mysql.password,
                    tablePrefix = coreConfig.database.tablePrefix,
                    parameters = coreConfig.database.mysql.parameters,
                    driverClassName = coreConfig.database.mysql.driverClassName,
                    maximumPoolSize = coreConfig.database.pool.maximumPoolSize,
                    minimumIdle = coreConfig.database.pool.minimumIdle,
                    connectionTimeout = coreConfig.database.pool.connectionTimeout,
                    idleTimeout = coreConfig.database.pool.idleTimeout,
                    maxLifetime = coreConfig.database.pool.maxLifetime
                )
            }
            "MARIADB" -> {
                DatabaseConfig.mariadb(
                    host = coreConfig.database.mariadb.host,
                    port = coreConfig.database.mariadb.port,
                    database = coreConfig.database.mariadb.database,
                    username = coreConfig.database.mariadb.username,
                    password = coreConfig.database.mariadb.password,
                    tablePrefix = coreConfig.database.tablePrefix,
                    parameters = coreConfig.database.mariadb.parameters,
                    driverClassName = coreConfig.database.mariadb.driverClassName,
                    maximumPoolSize = coreConfig.database.pool.maximumPoolSize,
                    minimumIdle = coreConfig.database.pool.minimumIdle,
                    connectionTimeout = coreConfig.database.pool.connectionTimeout,
                    idleTimeout = coreConfig.database.pool.idleTimeout,
                    maxLifetime = coreConfig.database.pool.maxLifetime
                )
            }
            "H2" -> {
                throw IllegalArgumentException(
                    "核心模块已不再支持 H2 数据库，请改用 SQLITE/MYSQL/MARIADB。若需要读取旧 H2 数据，请使用 data-merge 模块。"
                )
            }
            else -> {
                logger.error("不支持的数据库类型: ${coreConfig.database.type}, 使用默认 SQLite")
                val dbPath = dataDirectory.resolve(coreConfig.database.sqlite.path)
                // 确保数据库文件的父目录存在
                dbPath.parent?.let { Files.createDirectories(it) }
                DatabaseConfig.sqlite(
                    path = dbPath.toString(),
                    tablePrefix = coreConfig.database.tablePrefix
                )
            }
        }
        
        databaseManager = cn.starfallplain.sfplogin.manager.DatabaseManager(
            config = dbConfig,
            proxy = proxy
        )
        
        databaseManager.connect()
        databaseHelper = DatabaseHelper(databaseManager)
        
        logger.info("数据库连接完成")
    }
    
    private fun createBaseTables() {
        logger.info("正在创建基础数据表...")
        databaseManager.createBaseTables()
        logger.info("基础数据表创建完成")
    }
}
