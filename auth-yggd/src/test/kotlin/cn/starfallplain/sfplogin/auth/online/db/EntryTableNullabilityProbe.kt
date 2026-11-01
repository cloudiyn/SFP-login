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

package cn.starfallplain.sfplogin.auth.online.db

import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import cn.starfallplain.sfplogin.auth.online.api.db.EntryTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID

/**
 * 回归：旧库里 `entry_<id>.pid` 是 NOT NULL 时，启动迁移必须把它放宽为可空。
 *
 * 背景：密码门在档案建档（attach）之前就要写入"仅密码行"，INSERT 不带 `pid`。
 * Exposed 的 `createMissingTablesAndColumns` 在 MySQL/H2 上能自动放宽可空性，
 * 但在 **SQLite 上不会**——线上实测报
 * `SQLITE_CONSTRAINT_NOTNULL: NOT NULL constraint failed: hz_entry_mojang.pid`，
 * 玩家看到"密码保存失败"。因此 [EntryTableManager] 里加了显式迁移。
 */
class EntryTableNullabilityProbe {

    private val entryId = "littleskin"

    /** 模拟旧版表结构：pid 非空。 */
    private class LegacyEntryTable(entryId: String) : Table("entry_$entryId") {
        val id = integer("id").autoIncrement()
        val name = varchar("name", 32)
        val uuid = uuid("uuid")
        val pid = uuid("pid")
        val passwordHash = varchar("password_hash", 255).nullable()
        val hashFormat = varchar("hash_format", 32).nullable()
        val loginFailCount = integer("login_fail_count").default(0)
        val loginBlockedUntil = long("login_blocked_until").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    private fun runScenario(db: Database, label: String) {
        val profileTable = ProfileTable()

        // 1) 用旧结构建表，并插入一条已绑定记录（模拟已有老数据）
        val legacyUuid = UUID.randomUUID()
        transaction(db) {
            SchemaUtils.create(profileTable)
            SchemaUtils.create(LegacyEntryTable(entryId))
            LegacyEntryTable(entryId).insert {
                it[name] = "OldUser"
                it[uuid] = legacyUuid
                it[pid] = UUID.randomUUID()
            }
        }

        // 2) 走真实启动迁移路径
        val databaseManager = object : HyperZoneDatabaseManager {
            override val tablePrefix: String = ""
            override fun <T> executeTransaction(statement: () -> T): T = transaction(db) { statement() }
        }
        val manager = EntryTableManager(databaseManager, "", profileTable)
        manager.getOrRegisterEntryTable(entryId)

        // 迁移后 pid 必须已可空（SQLite 用 pragma 校验；H2 直接靠后续插入成功间接验证）
        if (label == "sqlite") {
            val pidStillNotNull = transaction(db) {
                exec("SELECT COUNT(*) FROM pragma_table_info('entry_$entryId') WHERE name='pid' AND \"notnull\"=1") {
                    if (it.next()) it.getInt(1) else -1
                }
            }
            assertEquals(0, pidStillNotNull, "[$label] 迁移后 pid 应已可空")
        }

        // 3) 迁移后入口表必须能插入 pid 为空的绑定行（本地密码已统一交由 offline 体系，
        //    入口表只保留身份绑定；此处仅确认 pid 可空性迁移生效）
        val newUuid = UUID.randomUUID()
        transaction(db) {
            EntryTable(entryId, "", profileTable).insert {
                it[name] = "NewUser"
                it[uuid] = newUuid
                it[pid] = null
            }
        }
        val insertedUuid = transaction(db) {
            EntryTable(entryId, "", profileTable).selectAll()
                .where { EntryTable(entryId, "", profileTable).uuid eq newUuid }
                .count()
        }

        // 4) 老数据必须仍在
        val rows = transaction(db) { EntryTable(entryId, "", profileTable).selectAll().toList() }
        val uuids = rows.map { it[EntryTable(entryId, "", profileTable).uuid] }

        assertEquals(1, insertedUuid, "[$label] 迁移后应能插入 pid 为空的绑定行")
        assertTrue(uuids.contains(legacyUuid), "[$label] 迁移必须保留旧记录，实际 uuids=$uuids")
        assertEquals(2, rows.size, "[$label] 迁移后入口表应有 2 条记录，实际 uuids=$uuids")
    }

    @Test
    fun `legacy NOT NULL pid is relaxed on h2`() {
        val db = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        runScenario(db, "h2")
    }

    @Test
    fun `legacy NOT NULL pid is relaxed on sqlite`() {
        val file = Files.createTempFile("entry-nullable", ".db").toFile()
        file.delete()
        val db = Database.connect(url = "jdbc:sqlite:${file.absolutePath}", driver = "org.sqlite.JDBC")
        runScenario(db, "sqlite")
    }
}
