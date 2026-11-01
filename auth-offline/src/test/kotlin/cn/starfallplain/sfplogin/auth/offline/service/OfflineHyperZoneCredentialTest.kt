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

import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import cn.starfallplain.sfplogin.auth.offline.db.OfflineAuthRepository
import cn.starfallplain.sfplogin.auth.offline.util.ExtraUuidUtils
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class OfflineHyperZoneCredentialTest {
    private lateinit var repository: OfflineAuthRepository
    private lateinit var database: Database
    private val profileId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        val profileTable = ProfileTable()
        val offlineAuthTable = cn.starfallplain.sfplogin.api.db.table.ChannelPasswordTable("", profileTable)
        val databaseManager = object : HyperZoneDatabaseManager {
            override val tablePrefix: String = ""

            override fun <T> executeTransaction(statement: () -> T): T {
                return transaction(database) { statement() }
            }
        }
        transaction(database) {
            SchemaUtils.create(profileTable, offlineAuthTable)
            profileTable.insert {
                it[profileTable.id] = profileId
                it[profileTable.name] = "AliceProfile"
                it[profileTable.uuid] = UUID.fromString("33333333-3333-4333-8333-333333333333")
            }
        }
        repository = OfflineAuthRepository(
            passwordRepository = cn.starfallplain.sfplogin.api.password.ChannelPasswordRepository(databaseManager, offlineAuthTable)
        )
    }

    @Test
    fun `credential exposes offline channel and normalized name`() {
        val credential = OfflineHyperZoneCredential(
            repository = repository,
            registrationName = "Alice",
            normalizedName = "alice"
        )

        assertEquals("offline", credential.channelId)
        assertEquals("alice", credential.credentialId)
        assertEquals("Alice", credential.getRegistrationName())
    }

    @Test
    fun `bound profile id comes from repository by normalized name`() {
        transaction(database) {
            repository.create(
                name = "alice",
                passwordHash = "hashed-password",
                hashFormat = "sha256",
                profileId = profileId
            )
        }
        val credential = OfflineHyperZoneCredential(
            repository = repository,
            registrationName = "Alice",
            normalizedName = "alice"
        )

        assertEquals(profileId, credential.getBoundProfileId())
    }

    @Test
    fun `known profile id is returned without repository lookup`() {
        val credential = OfflineHyperZoneCredential(
            repository = repository,
            registrationName = "Alice",
            normalizedName = "alice",
            knownProfileId = profileId
        )

        assertEquals(profileId, credential.getBoundProfileId())
    }

    @Test
    fun `suggested profile create uuid is null when passthrough is disabled`() {
        val credential = OfflineHyperZoneCredential(
            repository = repository,
            registrationName = "Alice",
            normalizedName = "alice",
            passProfileCreateUuid = false
        )

        assertNull(credential.getSuggestedProfileCreateUuid())
    }

    @Test
    fun `suggested profile create uuid is the offline uuid when passthrough is enabled`() {
        val credential = OfflineHyperZoneCredential(
            repository = repository,
            registrationName = "Alice",
            normalizedName = "alice",
            passProfileCreateUuid = true
        )

        assertEquals(ExtraUuidUtils.getNormalOfflineUUID("Alice"), credential.getSuggestedProfileCreateUuid())
    }
}
