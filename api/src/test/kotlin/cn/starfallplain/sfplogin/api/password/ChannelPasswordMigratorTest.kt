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

package cn.starfallplain.sfplogin.api.password

import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.db.table.ChannelPasswordTable
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 旧版数据库升级兼容测试。
 *
 * 覆盖三件事：
 * 1. `offline_auth` → 统一密码表的整表迁移（幂等、含扩展字段、含 byte[] 形态的 profileId）；
 * 2. 迁移失败时**不删除**旧表（防止不可逆的数据丢失）；
 * 3. 完成迁移后删除旧表，且唯一索引在旧版 SQLite 上被补建。
 */
class ChannelPasswordMigratorTest {

    private lateinit var database: Database
    private lateinit var databaseManager: HyperZoneDatabaseManager
    private lateinit var repository: ChannelPasswordRepository
    private val prefix = ""

    private val profileTable = ProfileTable(prefix)
    private val passwordTable = ChannelPasswordTable(prefix, ProfileTable(prefix))

    private val profileId = UUID.randomUUID()
    private val jdbcUrl = "jdbc:sqlite:file:migrator_test_${System.nanoTime()}?mode=memory&cache=shared"
    private val legacyConnection = java.sql.DriverManager.getConnection(jdbcUrl)

    @BeforeEach
    fun setUp() {
        database = Database.connect(jdbcUrl, "org.sqlite.JDBC")
        databaseManager = object : HyperZoneDatabaseManager {
            override val tablePrefix: String = prefix

            override fun <T> executeTransaction(statement: () -> T): T = transaction(database) { statement() }
        }
        transaction(database) {
            SchemaUtils.create(profileTable, passwordTable)
        }
        repository = ChannelPasswordRepository(databaseManager, passwordTable)
    }

    @AfterEach
    fun tearDown() {
        runCatching { legacyConnection.close() }
    }

    @Test
    fun `migrates legacy offline_auth rows including extras and drops the old table`() {
        createLegacyOfflineAuthTable()
        insertLegacyOfflineAuthRow(
            name = "Alice",
            hash = "abc123",
            format = "sha256",
            profileId = profileId,
            email = "alice@example.com",
            totpSecret = "TOTPSECRET",
            failCount = 2,
            blockedUntil = 9_999_999_999_999L
        )

        val migrator = ChannelPasswordMigrator(databaseManager, repository)
        val migrated = migrator.migrateOfflineTable("offline_auth")

        assertEquals(1, migrated)

        val entry = repository.find(PasswordChannel.OFFLINE, "alice")
        assertNotNull(entry)
        assertEquals("abc123", entry!!.passwordHash)
        assertEquals("sha256", entry.hashFormat)
        assertEquals(profileId, entry.profileId)
        assertEquals("alice@example.com", entry.email)
        assertEquals("TOTPSECRET", entry.totpSecret)
        assertEquals(2, entry.loginFailCount)
        assertEquals(9_999_999_999_999L, entry.loginBlockedUntil)

        // 全部行迁移成功后，旧表应被删除
        assertFalse(tableExists("offline_auth"))
    }

    @Test
    fun `reads binary-16 profile id returned as byte array`() {
        createLegacyOfflineAuthTable()
        // 模拟驱动返回 BINARY(16) 的 byte[] 形态
        insertLegacyOfflineAuthRow(
            name = "Bob",
            hash = "hash-bob",
            format = "sha256",
            profileId = profileId,
            profileIdAsBlob = true
        )

        val migrator = ChannelPasswordMigrator(databaseManager, repository)
        migrator.migrateOfflineTable("offline_auth")

        val entry = repository.find(PasswordChannel.OFFLINE, "bob")
        assertNotNull(entry)
        // 关键：byte[] 不应被静默丢弃而变成 null
        assertEquals(profileId, entry!!.profileId)
    }

    @Test
    fun `keeps legacy table when a row cannot be migrated`() {
        createLegacyOfflineAuthTable()
        insertLegacyOfflineAuthRow(name = "Valid", hash = "hash-valid")
        // 名字为空白：无法建立渠道键，属于"未成功迁移"，必须保留旧表以便人工处理
        insertLegacyOfflineAuthRow(name = "   ", hash = "hash-orphan")

        val migrator = ChannelPasswordMigrator(databaseManager, repository)
        val migrated = migrator.migrateOfflineTable("offline_auth")

        assertEquals(1, migrated)
        assertNotNull(repository.find(PasswordChannel.OFFLINE, "valid"))
        assertTrue(tableExists("offline_auth"), "存在未迁移行时不得删除旧表")
    }

    @Test
    fun `is idempotent across repeated startups`() {
        createLegacyOfflineAuthTable()
        insertLegacyOfflineAuthRow(name = "Carol", hash = "hash-carol", format = "sha256")

        val migrator = ChannelPasswordMigrator(databaseManager, repository)
        assertEquals(1, migrator.migrateOfflineTable("offline_auth"))

        // 第二次启动：旧表已删除，迁移应为空操作且不报错
        assertEquals(0, migrator.migrateOfflineTable("offline_auth"))
        assertEquals(1, repository.findByName("carol").size)
    }

    @Test
    fun `skips rows without password and still completes table drop`() {
        createLegacyOfflineAuthTable()
        insertLegacyOfflineAuthRow(name = "Nopass", hash = null)

        val migrator = ChannelPasswordMigrator(databaseManager, repository)
        assertEquals(0, migrator.migrateOfflineTable("offline_auth"))
        assertNull(repository.find(PasswordChannel.OFFLINE, "nopass"))
        assertFalse(tableExists("offline_auth"), "无密码的占位行不应阻止旧表清理")
    }

    @Test
    fun `ensures unique index exists on legacy password table`() {
        val migrator = ChannelPasswordMigrator(databaseManager, repository)
        // 幂等调用两次，且不因索引已存在而报错
        migrator.ensurePasswordTableIndexes(passwordTable.tableName)
        migrator.ensurePasswordTableIndexes(passwordTable.tableName)

        val indexNames = queryIndexNames(passwordTable.tableName)
        assertTrue(
            indexNames.any { it.contains("channel_entry_key") },
            "期望存在唯一索引，实际索引: $indexNames"
        )
    }

    @Test
    fun `clear column helpers tolerate legacy tables without password columns`() {
        createLegacyOfflineAuthTable()
        insertLegacyOfflineAuthRow(name = "Dave", hash = "hash-dave", failCount = 3)

        val migrator = ChannelPasswordMigrator(databaseManager, repository)
        // 旧表存在密码列：应被清空且不抛异常
        migrator.clearFloodgatePasswordColumns("offline_auth")
        migrator.clearEntryPasswordColumns("main", "offline_auth")

        val cleared: Pair<String?, Int>? = transaction(database) {
            TransactionManager.current().exec(
                "SELECT password_hash, login_fail_count FROM offline_auth WHERE name = 'Dave'"
            ) { rs: java.sql.ResultSet ->
                if (rs.next()) rs.getString(1) to rs.getInt(2) else null
            }
        }
        assertNull(cleared?.first)
        assertEquals(0, cleared?.second)

        // 完全不存在的列也应当安全跳过
        migrator.clearFloodgatePasswordColumns("password")
    }

    // ---- helpers ----

    private fun createLegacyOfflineAuthTable() {
        jdbc { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS offline_auth (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name VARCHAR(32) NOT NULL,
                        password_hash VARCHAR(255) NULL,
                        hash_format VARCHAR(32) NULL,
                        profile_id BINARY(16) NULL,
                        email VARCHAR(255) NULL,
                        recovery_code_hash VARCHAR(255) NULL,
                        recovery_code_expire_at BIGINT NULL,
                        recovery_requested_at BIGINT NULL,
                        recovery_verify_tries INT DEFAULT 0 NOT NULL,
                        reset_password_verified_until BIGINT NULL,
                        login_fail_count INT DEFAULT 0 NOT NULL,
                        login_blocked_until BIGINT NULL,
                        session_ip VARCHAR(64) NULL,
                        session_issued_at BIGINT NULL,
                        session_expires_at BIGINT NULL,
                        totp_secret VARCHAR(64) NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun insertLegacyOfflineAuthRow(
        name: String?,
        hash: String?,
        format: String? = "sha256",
        profileId: UUID? = null,
        profileIdAsBlob: Boolean = false,
        email: String? = null,
        totpSecret: String? = null,
        failCount: Int = 0,
        blockedUntil: Long? = null
    ) {
        jdbc { connection ->
            val sql = "INSERT INTO offline_auth " +
                "(name, password_hash, hash_format, profile_id, email, totp_secret, login_fail_count, login_blocked_until) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, name)
                stmt.setString(2, hash)
                stmt.setString(3, format)
                when {
                    profileId == null -> stmt.setNull(4, java.sql.Types.BINARY)
                    profileIdAsBlob -> stmt.setBytes(4, uuidToBytes(profileId))
                    else -> stmt.setString(4, profileId.toString())
                }
                stmt.setString(5, email)
                stmt.setString(6, totpSecret)
                stmt.setInt(7, failCount)
                if (blockedUntil == null) {
                    stmt.setNull(8, java.sql.Types.BIGINT)
                } else {
                    stmt.setLong(8, blockedUntil)
                }
                stmt.executeUpdate()
            }
        }
    }

    /** 查询某表的索引名（复用已连接的 database 实例）。 */
    private fun queryIndexNames(tableName: String): List<String> {
        return transaction(database) {
            TransactionManager.current().exec(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$tableName'"
            ) { rs: java.sql.ResultSet ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            } ?: emptyList()
        }
    }

    /**
     * 直接用 JDBC 操作旧表（绕过 Exposed）。
     *
     * 内存库（`file:...?mode=memory&shared`）的生命周期绑定在连接上：
     * 这里持有一条**常驻共享连接** [legacyConnection]，保证 JDBC 写入的旧表
     * 与迁移器通过 Exposed 读到的是同一份数据。
     */
    private fun <T> jdbc(block: (java.sql.Connection) -> T): T = block(legacyConnection)

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun tableExists(tableName: String): Boolean {
        return runCatching {
            jdbc { connection ->
                connection.createStatement().use { it.executeQuery("SELECT 1 FROM \"$tableName\" WHERE 1 = 0") }
                true
            }
        }.getOrDefault(false)
    }
}
