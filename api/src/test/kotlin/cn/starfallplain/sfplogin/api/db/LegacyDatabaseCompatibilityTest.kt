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

package cn.starfallplain.sfplogin.api.db

import cn.starfallplain.sfplogin.api.db.table.ChannelPasswordTable
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.*

/**
 * R1-2 数据库兼容回归：旧版 SQLite（profile / binding_code / offline_auth）在新 jar 启动 DDL 之后
 * 必须仍然可读，且不得出现任何 destructive migration（删表、删列）。
 *
 * 旧版 schema 由旧 jar 自身生成，这里用当前 [ProfileTable] 生成等价的 `profile` 表，
 * 再用原始 SQL 补出已经不再被代码引用的遗留表。
 */
class LegacyDatabaseCompatibilityTest {

    private lateinit var database: Database

    private val prefix = ""
    private val profileTable = ProfileTable(prefix)
    private val passwordTable = ChannelPasswordTable(prefix, ProfileTable(prefix))

    private val jdbcUrl = "jdbc:sqlite:file:legacy_compat_${System.nanoTime()}?mode=memory&cache=shared"

    /** 常驻共享连接：内存库生命周期绑定在连接上，保证 JDBC 与 Exposed 看到同一份数据。 */
    private val legacyConnection = DriverManager.getConnection(jdbcUrl)

    private val profileId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val profileUuid = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @BeforeEach
    fun setUp() {
        database = Database.connect(jdbcUrl, "org.sqlite.JDBC")
    }

    @AfterEach
    fun tearDown() {
        runCatching { legacyConnection.close() }
    }

    @Test
    fun `startup ddl keeps profile rows and leaves legacy tables untouched`() {
        createLegacyDatabase()

        // 新 jar 启动时的 DDL，等价于 DatabaseManager.createBaseTables()
        runStartupDdl()

        val rows = transaction(database) {
            profileTable.selectAll().map { it[profileTable.id] to it[profileTable.name] }
        }
        assertEquals(listOf(profileId to "Alice"), rows, "档案行与 UUID 必须原样保留")

        assertEquals(1, countRows("binding_code"), "binding_code 是遗留表，必须保留")
        assertEquals(1, countRows("offline_auth"), "offline_auth 的清理只走显式迁移器，启动 DDL 不得删除")
        assertTrue(tableExists("password"), "统一密码表应被增量创建")
        assertEquals(listOf("id", "name", "uuid"), columnNames("profile"), "不得删除或重命名已有列")
    }

    @Test
    fun `startup ddl is idempotent across restarts`() {
        createLegacyDatabase()

        runStartupDdl()
        runStartupDdl()

        assertEquals(1, countRows("profile"))
        assertEquals(1, countRows("binding_code"))
        assertEquals(1, countRows("offline_auth"))
    }

    // ---- helpers ----

    private fun runStartupDdl() {
        transaction(database) {
            SchemaUtils.create(profileTable)
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(passwordTable, withLogs = false)
        }
    }

    /** 还原旧版 jar 的数据库形态：正式档案 + 绑定码表 + 旧离线密码表。 */
    private fun createLegacyDatabase() {
        // 旧 jar 用同样的 Exposed 模型建表
        transaction(database) {
            SchemaUtils.create(profileTable)
            profileTable.insert {
                it[id] = profileId
                it[name] = "Alice"
                it[uuid] = profileUuid
            }
        }

        jdbc { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS binding_code (
                        code VARCHAR(32) NOT NULL PRIMARY KEY,
                        profile_id BINARY(16) NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS offline_auth (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name VARCHAR(32) NOT NULL,
                        password_hash VARCHAR(255) NULL,
                        hash_format VARCHAR(32) NULL
                    )
                    """.trimIndent()
                )
                stmt.executeUpdate(
                    "INSERT INTO offline_auth (name, password_hash, hash_format) VALUES ('alice', 'legacy-hash', 'sha256')"
                )
            }
            // 旧版 binding_code.profile_id 是 NOT NULL 的外键列，按 BINARY(16) 形态写入
            connection.prepareStatement(
                "INSERT INTO binding_code (code, profile_id, created_at) VALUES (?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, "ABC123")
                stmt.setBytes(2, uuidToBytes(profileId))
                stmt.setLong(3, 1L)
                stmt.executeUpdate()
            }
        }
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T = block(legacyConnection)

    private fun countRows(tableName: String): Int {
        return jdbc { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM \"$tableName\"").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    private fun tableExists(tableName: String): Boolean {
        return runCatching {
            jdbc { connection ->
                connection.createStatement().use { it.executeQuery("SELECT 1 FROM \"$tableName\" WHERE 1 = 0") }
                true
            }
        }.getOrDefault(false)
    }

    private fun columnNames(tableName: String): List<String> {
        return transaction(database) {
            TransactionManager.current().exec("PRAGMA table_info(\"$tableName\")") { rs: java.sql.ResultSet ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            } ?: emptyList()
        }
    }
}
