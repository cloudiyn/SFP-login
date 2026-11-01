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

package cn.starfallplain.sfplogin.auth.offline.service

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.db.Profile
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.player.HyperZonePlayerAccessor
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredential
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredentialFlow
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredentialFlowProvider
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileService
import cn.starfallplain.sfplogin.auth.offline.OfflineAuthMessages
import cn.starfallplain.sfplogin.auth.offline.api.db.OfflineAuthTable
import cn.starfallplain.sfplogin.auth.offline.config.AuthOfflineConfigLoader
import cn.starfallplain.sfplogin.auth.offline.db.OfflineAuthRepository
import cn.starfallplain.sfplogin.auth.offline.mail.OfflineAuthEmailSender
import cn.starfallplain.sfplogin.auth.offline.totp.OfflineTotpAuthenticator
import cn.starfallplain.sfplogin.auth.offline.util.ExtraUuidUtils
import io.mockk.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class OfflineAuthServiceRegisterTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var repository: OfflineAuthRepository
    private lateinit var player: Player
    private lateinit var hyperZonePlayer: HyperZonePlayer
    private lateinit var proxy: ProxyServer
    private lateinit var profileService: HyperZoneProfileService
    private lateinit var credentialFlow: HyperZoneCredentialFlow
    private lateinit var service: OfflineAuthService
    private lateinit var profileTable: ProfileTable
    private lateinit var offlineAuthTable: cn.starfallplain.sfplogin.api.db.table.ChannelPasswordTable
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        AuthOfflineConfigLoader.load(tempDir)
        AuthOfflineConfigLoader.getConfig().main.passOfflineUuidToProfileResolve = false

        database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        profileTable = ProfileTable()
        offlineAuthTable = cn.starfallplain.sfplogin.api.db.table.ChannelPasswordTable("", profileTable)
        val databaseManager = object : HyperZoneDatabaseManager {
            override val tablePrefix: String = ""

            override fun <T> executeTransaction(statement: () -> T): T {
                return transaction(database) { statement() }
            }
        }
        transaction(database) {
            SchemaUtils.create(profileTable, offlineAuthTable)
        }

        val channelPasswordRepository =
            cn.starfallplain.sfplogin.api.password.ChannelPasswordRepository(databaseManager, offlineAuthTable)
        cn.starfallplain.sfplogin.api.password.ChannelPasswordProvider.bind(channelPasswordRepository)
        cn.starfallplain.sfplogin.api.password.ChannelPasswordServiceProvider.bind(
            cn.starfallplain.sfplogin.api.password.ChannelPasswordService(channelPasswordRepository)
        )
        repository = OfflineAuthRepository(passwordRepository = channelPasswordRepository)
        player = mockk(relaxed = true)
        hyperZonePlayer = mockk(relaxUnitFun = true)
        proxy = mockk(relaxed = true)
        profileService = mockk()
        credentialFlow = mockk(relaxUnitFun = true)
        HyperZoneCredentialFlowProvider.bind(credentialFlow)
        every { credentialFlow.submitAndLink(any(), any()) } returns
            cn.starfallplain.sfplogin.api.profile.CredentialLinkResult(
                cn.starfallplain.sfplogin.api.profile.CredentialLinkStage.LINKED,
                attachedProfileId = java.util.UUID.randomUUID()
            )

        every { hyperZonePlayer.clientOriginalName } returns USERNAME
        every { hyperZonePlayer.getSubmittedCredentials() } returns emptyList()
        every { hyperZonePlayer.hasAttachedProfile() } returns false
        every { hyperZonePlayer.canBind() } returns true
        service = OfflineAuthService(
            repository = repository,
            playerAccessor = TestPlayerAccessor(hyperZonePlayer),
            profileService = profileService,
            emailSender = NoopEmailSender,
            totpAuthenticator = OfflineTotpAuthenticator("SFP-login-Test", 5),
            proxy = proxy
        )
    }

    @Test
    fun `register creates a new offline password entry when player can resolve profile`() {
        insertProfile()

        every { profileService.getAttachedProfile(hyperZonePlayer) } returns null
        // 服务现在通过凭证与 ProfileService 交互；passthrough 关闭时凭证建议 UUID 为 null
        every { profileService.canCreate(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedProfileCreateUuid() == null
        }) } returns true
        every { profileService.create(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedProfileCreateUuid() == null
        }) } returns PROFILE

        val result = service.register(player, VALID_PASSWORD)
        val saved = repository.getByName(NORMALIZED_NAME)

        assertTrue(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_SUCCESS, result.message)
        assertNotNull(saved)
        assertEquals(PROFILE.id, saved!!.profileId)
        assertEquals("sha256", saved.hashFormat)
        assertEquals(64, saved.passwordHash.length)
        assertNotEquals(VALID_PASSWORD, saved.passwordHash)
        verify(exactly = 1) {
            credentialFlow.submitAndLink(hyperZonePlayer, match {
                it.channelId == "offline" &&
                    it.credentialId == NORMALIZED_NAME &&
                    it.getBoundProfileId() == PROFILE.id
            })
        }
    }

    @Test
    fun `register passes normal offline uuid to profile resolve when passthrough is enabled`() {
        enableOfflineUuidPassthrough()
        insertProfile()

        val offlineUuid = ExtraUuidUtils.getNormalOfflineUUID(USERNAME)
        every { profileService.getAttachedProfile(hyperZonePlayer) } returns null
        // 服务现在通过凭证与 ProfileService 交互；passthrough 开启时凭证建议 UUID 为离线 UUID
        every { profileService.canCreate(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedProfileCreateUuid() == offlineUuid
        }) } returns true
        every { profileService.create(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedProfileCreateUuid() == offlineUuid
        }) } returns PROFILE

        val result = service.register(player, VALID_PASSWORD)

        assertTrue(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_SUCCESS, result.message)
        verify(exactly = 1) { profileService.canCreate(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedProfileCreateUuid() == offlineUuid
        }) }
        verify(exactly = 1) { profileService.create(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedProfileCreateUuid() == offlineUuid
        }) }
        verify(exactly = 0) { profileService.create(match<HyperZoneCredential> {
            it.getSuggestedProfileCreateUuid() == null
        }) }
    }

    @Test
    fun `register falls back to binding existing profile when direct registration is unavailable`() {
        insertProfile()

        every { profileService.getAttachedProfile(hyperZonePlayer) } returns PROFILE
        every { hyperZonePlayer.canBind() } returns true

        val result = service.register(player, VALID_PASSWORD)
        val saved = repository.getByProfileId(PROFILE.id)

        assertTrue(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_BOUND_SUCCESS, result.message)
        assertNotNull(saved)
        verify(exactly = 1) { profileService.getAttachedProfile(hyperZonePlayer) }
        verify(exactly = 0) { credentialFlow.submitAndLink(hyperZonePlayer, any()) }
    }

    @Test
    fun `register reports denial when attached profile binding is not allowed`() {
        every { profileService.getAttachedProfile(hyperZonePlayer) } returns PROFILE
        every { hyperZonePlayer.canBind() } returns false

        val result = service.register(player, VALID_PASSWORD)

        assertFalse(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_BIND_DENIED, result.message)
    }

    @Test
    fun `register reports existing offline password when fallback binding target is already linked`() {
        insertProfile()
        repository.create(
            name = NORMALIZED_NAME,
            passwordHash = "existing-hash",
            hashFormat = "sha256",
            profileId = PROFILE.id
        )

        every { profileService.getAttachedProfile(hyperZonePlayer) } returns PROFILE
        every { hyperZonePlayer.canBind() } returns true

        val result = service.register(player, VALID_PASSWORD)

        assertFalse(result.success)
        assertEquals(OfflineAuthMessages.OFFLINE_PASSWORD_ALREADY_SET, result.message)
    }

    @Test
    fun `join prompts no longer advertise direct register binding when creation is unavailable`() {
        insertProfile()

        every { hyperZonePlayer.hasAttachedProfile() } returns false
        every { profileService.getAttachedProfile(hyperZonePlayer) } returns PROFILE

        val prompts = service.getJoinPrompts(player)

        assertTrue(prompts.contains(OfflineAuthMessages.REGISTER_REQUEST))
        assertTrue(prompts.contains(OfflineAuthMessages.LOGIN_OTHER_USERNAME_PROMPT))
        assertEquals(2, prompts.size)
    }

    @Test
    fun `login supports explicitly choosing another offline username`() {
        insertProfile()
        repository.create(
            name = NORMALIZED_NAME,
            passwordHash = hashPassword(VALID_PASSWORD),
            hashFormat = "sha256",
            profileId = PROFILE.id
        )

        every { hyperZonePlayer.hasAttachedProfile() } returns false
        every { hyperZonePlayer.clientOriginalName } returns OTHER_NAME

        val result = service.loginAs(player, USERNAME, VALID_PASSWORD)

        assertTrue(result.success)
        assertEquals(OfflineAuthMessages.LOGIN_SUCCESS, result.message)
        verify(exactly = 1) {
            credentialFlow.submitAndLink(hyperZonePlayer, match {
                it.channelId == "offline" &&
                    it.credentialId == NORMALIZED_NAME &&
                    it.getBoundProfileId() == PROFILE.id
            })
        }
    }

    @Test
    fun `login without explicit username gives a hint when current connection name is unregistered`() {
        every { hyperZonePlayer.hasAttachedProfile() } returns false
        every { hyperZonePlayer.clientOriginalName } returns OTHER_NAME
        every { profileService.getAttachedProfile(hyperZonePlayer) } returns null

        val result = service.login(player, VALID_PASSWORD)

        assertFalse(result.success)
        assertEquals(OfflineAuthMessages.loginCurrentNameNotRegistered(OTHER_NAME), result.message)
    }

    @Test
    fun `register reports create blocked when profile creation is unavailable`() {
        every { profileService.getAttachedProfile(hyperZonePlayer) } returns null
        every { profileService.canCreate(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedProfileCreateUuid() == null
        }) } returns false

        val result = service.register(player, VALID_PASSWORD)

        assertFalse(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_CREATE_BLOCKED, result.message)
        assertNull(repository.getByName(NORMALIZED_NAME))
        verify(exactly = 0) { profileService.create(any()) }
        verify(exactly = 0) { credentialFlow.submitAndLink(any(), any()) }
    }

    @Test
    fun `register blocked by creation probes profile service with offline uuid when passthrough is enabled`() {
        enableOfflineUuidPassthrough()

        val offlineUuid = ExtraUuidUtils.getNormalOfflineUUID(USERNAME)
        every { profileService.getAttachedProfile(hyperZonePlayer) } returns null
        every { profileService.canCreate(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedProfileCreateUuid() == offlineUuid
        }) } returns false

        val result = service.register(player, VALID_PASSWORD)

        assertFalse(result.success)
        assertEquals(OfflineAuthMessages.REGISTER_CREATE_BLOCKED, result.message)
        verify(exactly = 1) { profileService.canCreate(match<HyperZoneCredential> {
            it.getRegistrationName() == USERNAME && it.getSuggestedProfileCreateUuid() == offlineUuid
        }) }
        verify(exactly = 0) { profileService.create(any()) }
    }

    private fun insertProfile() {
        transaction(database) {
            profileTable.insert {
                it[id] = PROFILE.id
                it[name] = PROFILE.name
                it[uuid] = PROFILE.uuid
            }
        }
    }

    private fun enableOfflineUuidPassthrough() {
        AuthOfflineConfigLoader.getConfig().main.passOfflineUuidToProfileResolve = true
    }

    private object NoopEmailSender : OfflineAuthEmailSender {
        override fun sendRecoveryCode(message: OfflineAuthEmailSender.RecoveryCodeMailMessage): OfflineAuthEmailSender.DeliveryResult {
            return OfflineAuthEmailSender.DeliveryResult(success = true)
        }
    }

    private class TestPlayerAccessor(
        private val hyperZonePlayer: HyperZonePlayer
    ) : HyperZonePlayerAccessor {
        override fun create(
            channel: io.netty.channel.Channel,
            userName: String,
            uuid: UUID?,
            isOnline: Boolean
        ): HyperZonePlayer {
            return hyperZonePlayer
        }

        override fun getByPlayer(player: Player): HyperZonePlayer {
            return hyperZonePlayer
        }

        override fun getByChannel(channel: io.netty.channel.Channel): HyperZonePlayer {
            return hyperZonePlayer
        }
    }

    companion object {
        private const val USERNAME = "Alice"
        private const val NORMALIZED_NAME = "alice"
        private const val OTHER_NAME = "GuestUser"
        private const val VALID_PASSWORD = "SecurePass123"
        private val PROFILE = Profile(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            name = USERNAME,
            uuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
        )

        private fun hashPassword(password: String): String {
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}



