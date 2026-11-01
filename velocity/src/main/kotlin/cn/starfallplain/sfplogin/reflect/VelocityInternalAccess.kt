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

package cn.starfallplain.sfplogin.reflect

import com.velocitypowered.api.network.HandshakeIntent
import com.velocitypowered.api.permission.PermissionFunction
import com.velocitypowered.api.permission.PermissionProvider
import com.velocitypowered.api.proxy.crypto.IdentifiedKey
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.proxy.VelocityServer
import com.velocitypowered.proxy.connection.MinecraftConnection
import com.velocitypowered.proxy.connection.MinecraftSessionHandler
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection
import com.velocitypowered.proxy.connection.client.AuthSessionHandler
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import com.velocitypowered.proxy.connection.client.InitialConnectSessionHandler
import com.velocitypowered.proxy.connection.client.InitialLoginSessionHandler
import com.velocitypowered.proxy.connection.client.LoginInboundConnection
import com.velocitypowered.proxy.network.ConnectionManager
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelInitializer
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.util.UUID

/**
 * Velocity 内部 API 统一访问层。
 *
 * 所有对 `com.velocitypowered.proxy.*` 内部类的反射访问均集中在此对象中，
 * 使用 [FuzzyLookup] 进行模糊定位、候选名回退，以提升跨版本兼容性。
 *
 * 热路径关键操作（如 [readVarInt]）通过 [AsmReflectUtil] 以字节码生成的方式
 * 消除反射调用开销。
 *
 * ## 设计原则
 * - 所有 `lazy` 初始化均延迟到首次使用时，避免插件启动时因依赖缺失而崩溃。
 * - 对于同一字段/方法在不同 Velocity 版本中名称不同的情况，使用候选名列表。
 * - 对于跨版本构造函数参数可能变化的类，使用模糊构造函数匹配。
 */
@Suppress("MemberVisibilityCanBePrivate")
object VelocityInternalAccess {

    // ══════════════════════════════════════════════════════════════════════════
    // Class Registry — 按 FQN 懒加载，ClassNotFoundException 时提供 null 回退
    // ══════════════════════════════════════════════════════════════════════════

    /** `com.velocitypowered.proxy.crypto.IdentifiedKeyImpl`（可能随版本移动） */
    val identifiedKeyImplClass: Class<*>? by lazy {
        runCatching {
            Class.forName("com.velocitypowered.proxy.crypto.IdentifiedKeyImpl")
        }.getOrNull()
    }

    /** `com.velocitypowered.proxy.server.PingSessionHandler`（包私有） */
    val pingSessionHandlerClass: Class<*>? by lazy {
        runCatching {
            Class.forName("com.velocitypowered.proxy.server.PingSessionHandler")
        }.getOrNull()
    }

    /** `com.velocitypowered.proxy.connection.VelocityConstants` */
    val velocityConstantsClass: Class<*>? by lazy {
        runCatching {
            Class.forName("com.velocitypowered.proxy.connection.VelocityConstants")
        }.getOrNull()
    }

    /** `com.velocitypowered.proxy.util.VelocityProperties` */
    val velocityPropertiesClass: Class<*>? by lazy {
        runCatching {
            Class.forName("com.velocitypowered.proxy.util.VelocityProperties")
        }.getOrNull()
    }

    /** `com.velocitypowered.proxy.protocol.ProtocolUtils` */
    val protocolUtilsClass: Class<*>? by lazy {
        runCatching {
            Class.forName("com.velocitypowered.proxy.protocol.ProtocolUtils")
        }.getOrNull()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VelocityConstants fields
    // ══════════════════════════════════════════════════════════════════════════

    /** `VelocityConstants.EMPTY_BYTE_ARRAY`，如果找不到则回退到空数组。 */
    val EMPTY_BYTE_ARRAY: ByteArray by lazy {
        val clazz = velocityConstantsClass ?: return@lazy ByteArray(0)
        runCatching {
            FuzzyLookup.fieldByNames(clazz, "EMPTY_BYTE_ARRAY").get(null) as ByteArray
        }.getOrElse { ByteArray(0) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VelocityProperties
    // ══════════════════════════════════════════════════════════════════════════

    private val velocityPropertiesReadBooleanMethod: Method? by lazy {
        val clazz = velocityPropertiesClass ?: return@lazy null
        runCatching {
            FuzzyLookup.methodByNames(
                clazz,
                arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!),
                "readBoolean",
            )
        }.getOrNull()
    }

    /**
     * 调用 `VelocityProperties.readBoolean(name, default)`。
     * 如果该类或方法不存在，直接返回 [default]。
     */
    fun velocityPropertiesReadBoolean(name: String, default: Boolean): Boolean {
        val method = velocityPropertiesReadBooleanMethod ?: return default
        return runCatching {
            method.invoke(null, name, default) as Boolean
        }.getOrElse { default }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ProtocolUtils — ASM 加速的热路径方法
    // ══════════════════════════════════════════════════════════════════════════

    private val readVarIntInvoker: AsmReflectUtil.IntFromByteBufInvoker? by lazy {
        val clazz = protocolUtilsClass ?: return@lazy null
        runCatching {
            val method = FuzzyLookup.methodByNames(
                clazz,
                arrayOf(ByteBuf::class.java),
                "readVarInt",
            )
            AsmReflectUtil.makeIntFromByteBufInvoker(method)
        }.getOrNull()
    }

    /**
     * 调用 `ProtocolUtils.readVarInt(buf)`。
     * 使用 ASM 生成的桥接类消除反射开销；若生成失败则回退到直接反射调用。
     *
     * @throws UnsupportedOperationException 如果 ProtocolUtils 类不存在
     */
    fun readVarInt(buf: ByteBuf): Int {
        val invoker = readVarIntInvoker
            ?: throw UnsupportedOperationException("ProtocolUtils.readVarInt is not available")
        return invoker.invoke(buf)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IdentifiedKeyImpl
    // ══════════════════════════════════════════════════════════════════════════

    private val identifiedKeyImplInternalAddHolderMethod: Method? by lazy {
        val clazz = identifiedKeyImplClass ?: return@lazy null
        runCatching {
            FuzzyLookup.methodByNames(
                clazz,
                arrayOf(UUID::class.java),
                "internalAddHolder",
            )
        }.getOrNull()
    }

    /** 判断 [key] 是否为 `IdentifiedKeyImpl` 实例（跨版本安全）。 */
    fun isIdentifiedKeyImpl(key: Any?): Boolean {
        if (key == null) return false
        return identifiedKeyImplClass?.isInstance(key) ?: false
    }

    /**
     * 调用 `IdentifiedKeyImpl.internalAddHolder(uuid)`。
     * @return 调用结果，若方法不可用则返回 false
     */
    fun identifiedKeyImplInternalAddHolder(key: IdentifiedKey, uuid: UUID?): Boolean {
        val method = identifiedKeyImplInternalAddHolderMethod ?: return false
        return runCatching { method.invoke(key, uuid) as Boolean }.getOrElse { false }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VelocityServer — cm 字段
    // ══════════════════════════════════════════════════════════════════════════

    private val velocityServerCmField: Field by lazy {
        FuzzyLookup.fieldByNames(VelocityServer::class.java, "cm")
    }

    /** 从 [VelocityServer] 实例获取 `ConnectionManager`。 */
    fun getConnectionManager(server: VelocityServer): ConnectionManager {
        return velocityServerCmField.get(server) as ConnectionManager
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VelocityServer — player registries
    // ══════════════════════════════════════════════════════════════════════════

    private val velocityServerConnectionsByNameField: Field by lazy {
        FuzzyLookup.fieldByNames(VelocityServer::class.java, "connectionsByName")
    }
    private val velocityServerConnectionsByUuidField: Field by lazy {
        FuzzyLookup.fieldByNames(VelocityServer::class.java, "connectionsByUuid")
    }

    @Suppress("UNCHECKED_CAST")
    fun connectionsByName(server: VelocityServer): MutableMap<String, ConnectedPlayer> =
        velocityServerConnectionsByNameField.get(server) as MutableMap<String, ConnectedPlayer>

    @Suppress("UNCHECKED_CAST")
    fun connectionsByUuid(server: VelocityServer): MutableMap<UUID, ConnectedPlayer> =
        velocityServerConnectionsByUuidField.get(server) as MutableMap<UUID, ConnectedPlayer>

    // ══════════════════════════════════════════════════════════════════════════
    // VelocityRegisteredServer — players field
    // ══════════════════════════════════════════════════════════════════════════

    private val velocityRegisteredServerPlayersField: Field by lazy {
        val clazz = Class.forName("com.velocitypowered.proxy.server.VelocityRegisteredServer")
        FuzzyLookup.fieldByNames(clazz, "players")
    }

    @Suppress("UNCHECKED_CAST")
    fun registeredServerPlayers(registeredServer: Any): MutableMap<UUID, ConnectedPlayer> =
        velocityRegisteredServerPlayersField.get(registeredServer) as MutableMap<UUID, ConnectedPlayer>

    // ══════════════════════════════════════════════════════════════════════════
    // ConnectionManager fields
    // ══════════════════════════════════════════════════════════════════════════

    private val connectionManagerEndpointsField: Field by lazy {
        FuzzyLookup.fieldByNames(ConnectionManager::class.java, "endpoints")
    }

    private val connectionManagerBackendChannelInitializerField: Field by lazy {
        // 3.4: "backendChannelInitializer"; 3.5+: 可能已重命名
        FuzzyLookup.fieldByNames(ConnectionManager::class.java, "backendChannelInitializer", "clientChannelInitializer")
    }

    @Suppress("UNCHECKED_CAST")
    fun <K, V> getEndpoints(cm: ConnectionManager): com.google.common.collect.Multimap<K, V> =
        connectionManagerEndpointsField.get(cm) as com.google.common.collect.Multimap<K, V>

    fun getBackendChannelInitializerHolder(cm: ConnectionManager): Any =
        connectionManagerBackendChannelInitializerField.get(cm)

    // ══════════════════════════════════════════════════════════════════════════
    // ConnectedPlayer fields / methods
    // ══════════════════════════════════════════════════════════════════════════

    private val connectedPlayerProfileField: Field by lazy {
        FuzzyLookup.fieldByNames(ConnectedPlayer::class.java, "profile")
    }

    private val connectedPlayerConnectionInFlightField: Field by lazy {
        FuzzyLookup.fieldByNames(ConnectedPlayer::class.java, "connectionInFlight")
    }

    private val connectedPlayerConnectedServerField: Field by lazy {
        FuzzyLookup.fieldByNames(ConnectedPlayer::class.java, "connectedServer")
    }

    private val connectedPlayerDefaultPermissionsField: Field by lazy {
        FuzzyLookup.fieldByNames(ConnectedPlayer::class.java, "DEFAULT_PERMISSIONS")
    }

    private val connectedPlayerSetPermissionFunctionMethod: Method by lazy {
        FuzzyLookup.methodByNames(
            ConnectedPlayer::class.java,
            arrayOf(PermissionFunction::class.java),
            "setPermissionFunction",
        )
    }

    private val connectedPlayerTeardownMethod: Method by lazy {
        FuzzyLookup.methodByNames(ConnectedPlayer::class.java, "teardown")
    }

    fun setConnectedPlayerProfile(player: ConnectedPlayer, profile: GameProfile) {
        connectedPlayerProfileField.set(player, profile)
    }

    fun setConnectionInFlight(player: ConnectedPlayer, connection: VelocityServerConnection?) {
        connectedPlayerConnectionInFlightField.set(player, connection)
    }
    fun setConnectedServer(player: ConnectedPlayer, connection: VelocityServerConnection?) {
        connectedPlayerConnectedServerField.set(player, connection)
    }

    private val serverConnectionConnectionField: Field by lazy {
        FuzzyLookup.fieldByNames(VelocityServerConnection::class.java, "connection")
    }

    fun setServerConnectionConnection(serverConnection: VelocityServerConnection, connection: MinecraftConnection?) {
        serverConnectionConnectionField.set(serverConnection, connection)
    }

    fun defaultPermissions(): PermissionProvider =
        connectedPlayerDefaultPermissionsField.get(null) as PermissionProvider

    fun setPermissionFunction(player: ConnectedPlayer, function: PermissionFunction) {
        connectedPlayerSetPermissionFunctionMethod.invoke(player, function)
    }

    fun teardown(player: ConnectedPlayer) {
        connectedPlayerTeardownMethod.invoke(player)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LoginInboundConnection methods
    // ══════════════════════════════════════════════════════════════════════════

    private val loginInboundFireLoginMethod: Method by lazy {
        // Velocity 3.4: "loginEventFired"; 可能在未来版本重命名
        FuzzyLookup.methodByNames(
            LoginInboundConnection::class.java,
            arrayOf(Runnable::class.java),
            "loginEventFired", "fireLogin", "fireLoginEvent",
        )
    }

    private val loginInboundDelegatedConnectionMethod: Method by lazy {
        FuzzyLookup.methodByNames(
            LoginInboundConnection::class.java,
            "delegatedConnection", "getConnection", "connection",
        )
    }

    private val loginInboundCleanupMethod: Method by lazy {
        FuzzyLookup.methodByNames(
            LoginInboundConnection::class.java,
            "cleanup",
        )
    }

    fun fireLogin(inbound: LoginInboundConnection, action: Runnable) {
        loginInboundFireLoginMethod.invoke(inbound, action)
    }

    fun delegatedConnection(inbound: LoginInboundConnection): MinecraftConnection =
        loginInboundDelegatedConnectionMethod.invoke(inbound) as MinecraftConnection

    fun cleanup(inbound: LoginInboundConnection) {
        loginInboundCleanupMethod.invoke(inbound)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // InitialLoginSessionHandler — inbound 字段
    // ══════════════════════════════════════════════════════════════════════════

    private val initialLoginSessionHandlerInboundField: Field by lazy {
        FuzzyLookup.fieldByNames(
            InitialLoginSessionHandler::class.java,
            "inbound",
        )
    }

    fun getInitialLoginSessionHandlerInbound(handler: InitialLoginSessionHandler): LoginInboundConnection =
        initialLoginSessionHandlerInboundField.get(handler) as LoginInboundConnection

    // ══════════════════════════════════════════════════════════════════════════
    // EncryptionRequestPacket — shouldAuthenticate 字段
    // ══════════════════════════════════════════════════════════════════════════

    private val encryptionRequestShouldAuthenticateField: Field by lazy {
        FuzzyLookup.fieldByNames(
            Class.forName("com.velocitypowered.proxy.protocol.packet.EncryptionRequestPacket"),
            "shouldAuthenticate",
        )
    }

    fun setEncryptionRequestShouldAuthenticate(packet: Any, value: Boolean) {
        encryptionRequestShouldAuthenticateField.setBoolean(packet, value)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ConnectedPlayer constructor (fuzzy)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 创建 [ConnectedPlayer] 实例。
     *
     * 尝试顺序：
     * 1. 8-arg (server, profile, connection, virtualHost, rawVirtualHost, onlineMode, intent, identifiedKey)
     * 2. 7-arg（无 rawVirtualHost）
     *
     * 若均失败则回退到模糊构造函数匹配（包含 VelocityServer + GameProfile + MinecraftConnection + Boolean）。
     */
    fun createConnectedPlayer(
        server: VelocityServer,
        inbound: LoginInboundConnection,
        profile: GameProfile,
        onlineMode: Boolean,
    ): ConnectedPlayer {
        val mc = delegatedConnection(inbound)
        val virtualHost = inbound.virtualHost.orElse(null)
        val rawVirtualHost = inbound.rawVirtualHost.orElse(null)
        val intent = inbound.handshakeIntent
        val identifiedKey = inbound.identifiedKey

        val ctor8 = runCatching {
            FuzzyLookup.constructorByParamCandidates(
                ConnectedPlayer::class.java,
                arrayOf(
                    VelocityServer::class.java,
                    GameProfile::class.java,
                    MinecraftConnection::class.java,
                    InetSocketAddress::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    HandshakeIntent::class.java,
                    IdentifiedKey::class.java,
                ),
            )
        }.getOrNull()

        if (ctor8 != null) {
            return ctor8.newInstance(server, profile, mc, virtualHost, rawVirtualHost, onlineMode, intent, identifiedKey) as ConnectedPlayer
        }

        // 7-arg fallback (no rawVirtualHost)
        val ctor7 = runCatching {
            FuzzyLookup.constructorByParamCandidates(
                ConnectedPlayer::class.java,
                arrayOf(
                    VelocityServer::class.java,
                    GameProfile::class.java,
                    MinecraftConnection::class.java,
                    InetSocketAddress::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    HandshakeIntent::class.java,
                    IdentifiedKey::class.java,
                ),
            )
        }.getOrNull()

        if (ctor7 != null) {
            return ctor7.newInstance(server, profile, mc, virtualHost, onlineMode, intent, identifiedKey) as ConnectedPlayer
        }

        // Last resort: fuzzy match
        val ctorFuzzy = FuzzyLookup.constructorFuzzy(
            ConnectedPlayer::class.java,
            VelocityServer::class.java,
            GameProfile::class.java,
            MinecraftConnection::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        // Build args array matching parameter order dynamically
        val args = ctorFuzzy.parameters.map { param ->
            when {
                param.type.isAssignableFrom(VelocityServer::class.java) -> server
                param.type.isAssignableFrom(GameProfile::class.java) -> profile
                param.type.isAssignableFrom(MinecraftConnection::class.java) -> mc
                param.type.isAssignableFrom(InetSocketAddress::class.java) -> virtualHost
                param.type == String::class.java -> rawVirtualHost
                param.type == Boolean::class.javaPrimitiveType -> onlineMode
                param.type.isAssignableFrom(HandshakeIntent::class.java) -> intent
                param.type.isAssignableFrom(IdentifiedKey::class.java) -> identifiedKey
                else -> null
            }
        }.toTypedArray()
        return ctorFuzzy.newInstance(*args) as ConnectedPlayer
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AuthSessionHandler constructor (fuzzy)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 创建 [AuthSessionHandler] 实例。
     *
     * Velocity 3.4: 4-arg (server, inbound, profile, onlineMode)
     * Velocity 3.5+: 5-arg (+ serverIdHash)
     */
    fun createAuthSessionHandler(
        server: VelocityServer?,
        inbound: LoginInboundConnection?,
        profile: GameProfile?,
        onlineMode: Boolean,
        serverIdHash: String,
    ): AuthSessionHandler {
        val ctor5 = runCatching {
            FuzzyLookup.constructorByParamCandidates(
                AuthSessionHandler::class.java,
                arrayOf(
                    VelocityServer::class.java,
                    LoginInboundConnection::class.java,
                    GameProfile::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    String::class.java,
                ),
            )
        }.getOrNull()
        if (ctor5 != null) {
            return ctor5.newInstance(server, inbound, profile, onlineMode, serverIdHash) as AuthSessionHandler
        }

        val ctor4 = FuzzyLookup.constructorByParamCandidates(
            AuthSessionHandler::class.java,
            arrayOf(
                VelocityServer::class.java,
                LoginInboundConnection::class.java,
                GameProfile::class.java,
                Boolean::class.javaPrimitiveType!!,
            ),
        )
        return ctor4.newInstance(server, inbound, profile, onlineMode) as AuthSessionHandler
    }

    // ══════════════════════════════════════════════════════════════════════════
    // InitialConnectSessionHandler constructor
    // ══════════════════════════════════════════════════════════════════════════

    fun createInitialConnectSessionHandler(
        player: ConnectedPlayer,
        server: VelocityServer,
    ): InitialConnectSessionHandler {
        val ctor = FuzzyLookup.constructorByParamCandidates(
            InitialConnectSessionHandler::class.java,
            arrayOf(ConnectedPlayer::class.java, VelocityServer::class.java),
        )
        return ctor.newInstance(player, server) as InitialConnectSessionHandler
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PingSessionHandler constructor
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 创建 `PingSessionHandler` 实例（参数顺序跨版本可能变化，使用 [FuzzyLookup.constructorFuzzy]）。
     */
    fun createPingSessionHandler(
        pingFuture: java.util.concurrent.CompletableFuture<*>,
        registeredServer: Any,
        conn: MinecraftConnection,
        protocolVersion: com.velocitypowered.api.network.ProtocolVersion,
        virtualHost: Any?,
    ): MinecraftSessionHandler {
        val clazz = pingSessionHandlerClass
            ?: throw UnsupportedOperationException("PingSessionHandler class not found")
        // 使用模糊构造函数：只要求包含 MinecraftConnection 类型参数
        val ctor = FuzzyLookup.constructorFuzzy(clazz, MinecraftConnection::class.java)
        // virtualHost 跨版本可能是 String 或 Optional<String>；按参数类型归一化
        val virtualHostString: String? = when (virtualHost) {
            is java.util.Optional<*> -> virtualHost.orElse(null) as? String
            else -> virtualHost as? String
        }
        val args = ctor.parameters.map { param ->
            when {
                java.util.concurrent.CompletableFuture::class.java.isAssignableFrom(param.type) -> pingFuture
                param.type.isAssignableFrom(conn.javaClass) -> conn
                com.velocitypowered.api.network.ProtocolVersion::class.java.isAssignableFrom(param.type) -> protocolVersion
                param.type == String::class.java -> virtualHostString
                java.util.Optional::class.java.isAssignableFrom(param.type) ->
                    java.util.Optional.ofNullable(virtualHostString)
                param.type.isInstance(registeredServer) -> registeredServer
                else -> null
            }
        }.toTypedArray()
        return ctor.newInstance(*args) as MinecraftSessionHandler
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ChannelInitializer.initChannel 反射（私有方法）
    // ══════════════════════════════════════════════════════════════════════════

    private val channelInitializerInitChannelMethod: Method by lazy {
        FuzzyLookup.methodByNames(
            ChannelInitializer::class.java,
            arrayOf(io.netty.channel.Channel::class.java),
            "initChannel",
        )
    }

    fun invokeInitChannel(initializer: ChannelInitializer<*>, channel: io.netty.channel.Channel) {
        channelInitializerInitChannelMethod.invoke(initializer, channel)
    }
    // ══════════════════════════════════════════════════════════════════════════
    // 启动自检
    // ══════════════════════════════════════════════════════════════════════════

    /** 一项自检失败：入口名称 + 给运维的排查提示。 */
    data class SelfCheckFailure(val name: String, val hint: String)

    /**
     * 自检项表：名称 -> 解析探针。
     *
     * 探针只做"能否解析到字段/方法/类"，不触碰真实玩家连接，因此可以在启动早期调用。
     * 任何一项解析失败都意味着当前 Velocity 版本与插件不匹配，相关功能会在运行时抛异常，
     * 所以启动时就把结论打成明确日志，而不是等玩家登录时才报 NPE。
     */
    private val selfChecks: List<Pair<String, () -> Any?>> = listOf(
        "ProtocolUtils.readVarInt" to { readVarIntInvoker },
        "VelocityServer.connectionManager" to { velocityServerCmField },
        "VelocityServer.connectionsByName" to { velocityServerConnectionsByNameField },
        "VelocityServer.connectionsByUuid" to { velocityServerConnectionsByUuidField },
        "VelocityRegisteredServer.players" to { velocityRegisteredServerPlayersField },
        "ConnectionManager.endpoints" to { connectionManagerEndpointsField },
        "ConnectionManager.backendChannelInitializer" to { connectionManagerBackendChannelInitializerField },
        "ConnectedPlayer.profile" to { connectedPlayerProfileField },
        "ConnectedPlayer.connectionInFlight" to { connectedPlayerConnectionInFlightField },
        "ConnectedPlayer.connectedServer" to { connectedPlayerConnectedServerField },
        "ConnectedPlayer.DEFAULT_PERMISSIONS" to { connectedPlayerDefaultPermissionsField },
        "ConnectedPlayer.setPermissionFunction" to { connectedPlayerSetPermissionFunctionMethod },
        "ConnectedPlayer.teardown" to { connectedPlayerTeardownMethod },
        "VelocityServerConnection.connection" to { serverConnectionConnectionField },
        "LoginInboundConnection.fireLogin" to { loginInboundFireLoginMethod },
        "LoginInboundConnection.delegatedConnection" to { loginInboundDelegatedConnectionMethod },
        "LoginInboundConnection.cleanup" to { loginInboundCleanupMethod },
        "InitialLoginSessionHandler.inbound" to { initialLoginSessionHandlerInboundField },
        "EncryptionRequestPacket.shouldAuthenticate" to { encryptionRequestShouldAuthenticateField },
        "ChannelInitializer.initChannel" to { channelInitializerInitChannelMethod },
        "class PingSessionHandler" to { pingSessionHandlerClass },
        "class IdentifiedKeyImpl" to { identifiedKeyImplClass },
    )

    /** 自检项数量，用于启动日志里的"通过 N 项"提示。 */
    val selfCheckItemCount: Int get() = selfChecks.size

    /**
     * 解析全部内部入口并返回失败的项。
     *
     * @return 空列表表示当前服务端版本与插件兼容；否则每项都带一条排查提示
     */
    fun selfCheck(): List<SelfCheckFailure> {
        val failures = mutableListOf<SelfCheckFailure>()
        for ((name, probe) in selfChecks) {
            val resolved = runCatching { probe() != null }.getOrDefault(false)
            if (!resolved) {
                failures += SelfCheckFailure(name, hintFor(name))
            }
        }
        return failures
    }

    private fun hintFor(name: String): String = when {
        name.startsWith("class ") ->
            "当前服务端缺少该内部类，插件不支持此 Velocity 版本，请升级/降级到受支持的版本"
        else ->
            "该字段/方法在当前 Velocity 版本中不存在或已被重命名，相关功能会在运行时不可用"
    }
}
