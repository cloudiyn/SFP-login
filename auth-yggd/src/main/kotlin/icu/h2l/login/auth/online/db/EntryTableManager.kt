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

package icu.h2l.login.auth.online.db

import com.velocitypowered.api.event.Subscribe
import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.api.event.db.TableSchemaAction
import icu.h2l.api.event.db.TableSchemaEvent
import icu.h2l.api.log.info
import icu.h2l.api.log.warn
import icu.h2l.api.password.ChannelPasswordMigrator
import icu.h2l.login.auth.online.api.db.EntryTable
import icu.h2l.login.auth.online.events.EntryRegisterEvent
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry 表管理器
 * 负责 Entry 表的注册、查询、创建、删除与事件处理
 */
class EntryTableManager(
    private val databaseManager: HyperZoneDatabaseManager,
    private val tablePrefix: String,
    private val profileTable: ProfileTable
) {
    // 注册在启动线程完成，读取可能在 Netty 事件循环/命令线程发生，必须线程安全
    private val entryTables = ConcurrentHashMap<String, EntryTable>()

    /** 旧密码数据迁移器（把入口表里的旧密码按玩家名搬进统一密码表的 offline 渠道）。 */
    private val passwordMigrator: ChannelPasswordMigrator? by lazy {
        icu.h2l.api.password.ChannelPasswordProvider.getOrNull()
            ?.let { ChannelPasswordMigrator(databaseManager, it) }
    }

    private fun createEntryTable(entryTable: EntryTable) {
        databaseManager.executeTransaction {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(entryTable, withLogs = false)
            relaxPidNullabilityIfNeeded(entryTable)
        }
        // 该入口表可能来自旧版本：把其密码列迁移到统一密码表（offline 渠道），然后清空旧密码列（绑定行保留）
        val migrator = passwordMigrator
        if (migrator == null) {
            warn { "统一密码表尚未就绪，跳过入口表 ${entryTable.tableName} 的密码列迁移" }
            return
        }
        runCatching {
            val entryId = entryTable.tableName.removePrefix("${tablePrefix}entry_").lowercase()
            val migrated = migrator.migrateEntryTable(entryId, entryTable.tableName)
            if (migrated > 0) {
                info { "已迁移入口 $entryId 的 $migrated 条密码记录到统一密码表" }
                migrator.clearEntryPasswordColumns(entryId, entryTable.tableName)
            }
        }.onFailure { warn { "迁移入口表 ${entryTable.tableName} 密码失败：${it.message}" } }
    }

    /**
     * 兼容旧库：把已存在表里 NOT NULL 的 `pid` 列放宽为可空。
     *
     * [SchemaUtils.createMissingTablesAndColumns] 在 MySQL/H2 上能自动放宽可空性，
     * 但在 SQLite 上**不会**（实测会残留 `NOT NULL constraint failed`），
     * 导致密码门在档案建档前写入"仅密码行"失败、玩家看到"密码保存失败"。
     * 这里针对不同方言显式修复。
     */
    private fun relaxPidNullabilityIfNeeded(entryTable: EntryTable) {
        val tx = TransactionManager.current()
        val dialect = tx.db.dialect.name.lowercase()
        val tableName = entryTable.tableName
        try {
            when {
                dialect.contains("sqlite") -> relaxPidNullabilityOnSqlite(tx, tableName)
                dialect.contains("mysql") || dialect.contains("mariadb") || dialect.contains("h2") ->
                    tx.exec("ALTER TABLE $tableName MODIFY COLUMN pid BINARY(16) NULL") { }
                else -> {
                    // 其他方言交由 createMissingTablesAndColumns 自行处理，这里不做破坏性改写
                }
            }
        } catch (e: Exception) {
            // 表尚不存在或该列已可空时部分方言会报错，降级为警告，不阻断启动
            warn { "放宽 $tableName.pid 可空性时跳过（可能表未建或已可空）: ${e.message}" }
        }
    }

    /**
     * SQLite 不支持 `ALTER COLUMN`，采用"重建表"方式放宽 `pid` 可空性。
     *
     * 仅当检测到 `pid` 仍为 NOT NULL 时才执行，避免每次启动都重建。
     * 全程使用原生 DDL，不依赖 Exposed 的建表逻辑，保证新旧表结构完全一致。
     */
    private fun relaxPidNullabilityOnSqlite(
        tx: org.jetbrains.exposed.sql.Transaction,
        tableName: String
    ) {
        val pidNotNull = tx.exec(
            "SELECT COUNT(*) FROM pragma_table_info('$tableName') WHERE name = 'pid' AND \"notnull\" = 1"
        ) { rs -> if (rs.next()) rs.getInt(1) > 0 else false } ?: false
        if (!pidNotNull) {
            return
        }

        info { "检测到旧版 SQLite 表 $tableName 的 pid 为 NOT NULL，正在重建以放宽为可空" }
        val legacyTable = "${tableName}__legacy_pid"
        val beforeCount = tx.exec("SELECT COUNT(*) FROM $tableName") { if (it.next()) it.getInt(1) else -1 }

        // 旧表可能含密码列（历史遗留）；先探测其存在，迁移时一并保留，
        // 密码本身由 ChannelPasswordMigrator 在读表阶段搬入统一密码表。
        val hasPasswordColumns = tx.exec(
            "SELECT COUNT(*) FROM pragma_table_info('$tableName') WHERE name = 'password_hash'"
        ) { rs -> if (rs.next()) rs.getInt(1) > 0 else false } ?: false

        // 1) 原表改名，腾出目标表名
        tx.exec("ALTER TABLE $tableName RENAME TO $legacyTable")

        // 2) 用与原表一致的列结构重建目标表，仅把 pid 放宽为可空
        val baseColumns = "id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(32) NOT NULL, " +
            "uuid BINARY(16) NOT NULL, pid BINARY(16) NULL"
        val extraColumns = if (hasPasswordColumns) {
            ", password_hash VARCHAR(255) NULL, hash_format VARCHAR(32) NULL, " +
                "login_fail_count INT DEFAULT 0 NOT NULL, login_blocked_until BIGINT NULL"
        } else {
            ""
        }
        tx.exec("CREATE TABLE $tableName ($baseColumns$extraColumns)")

        // 3) 回填老数据
        if (hasPasswordColumns) {
            tx.exec(
                "INSERT INTO $tableName (id, name, uuid, pid, password_hash, hash_format, login_fail_count, login_blocked_until) " +
                    "SELECT id, name, uuid, pid, password_hash, hash_format, login_fail_count, login_blocked_until " +
                    "FROM $legacyTable"
            ) { }
        } else {
            tx.exec("INSERT INTO $tableName (id, name, uuid, pid) SELECT id, name, uuid, pid FROM $legacyTable") { }
        }

        // 4) 丢弃旧表，并重建唯一索引（与 Exposed 的 uniqueIndex(name, uuid) 对齐）
        tx.exec("DROP TABLE $legacyTable")
        tx.exec("CREATE UNIQUE INDEX IF NOT EXISTS ${tableName}_name_uuid_unique ON $tableName (name, uuid)") { }

        val afterCount = tx.exec("SELECT COUNT(*) FROM $tableName") { if (it.next()) it.getInt(1) else -1 }
        info { "[PID-MIGRATION] 已重建 $tableName: before=$beforeCount after=$afterCount" }
    }

    fun registerEntry(entryId: String): EntryTable {
        val normalizedId = entryId.lowercase()
        return entryTables.computeIfAbsent(normalizedId) {
            info { "注册入口表: $normalizedId" }
            EntryTable(normalizedId, tablePrefix, profileTable)
        }
    }

    fun getEntryTable(entryId: String): EntryTable? {
        return entryTables[entryId.lowercase()]
    }

    /**
     * 获取（并在缺失时按需注册 + 建表）入口表。
     *
     * 命令/事件线程可能在注册事件尚未完成时就需要写入，这里做一次自愈，
     * 避免因时序问题导致 [icu.h2l.login.auth.online.db.EntryDatabaseHelper] 静默返回失败。
     */
    fun getOrRegisterEntryTable(entryId: String): EntryTable {
        val normalizedId = entryId.lowercase()
        return entryTables.computeIfAbsent(normalizedId) {
            info { "按需注册入口表: $normalizedId" }
            EntryTable(normalizedId, tablePrefix, profileTable).also { createEntryTable(it) }
        }
    }

    fun createAllEntryTables() {
        entryTables.values.forEach(::createEntryTable)
    }

   fun dropAllEntryTables() {
        databaseManager.executeTransaction {
            entryTables.values.forEach { entryTable ->
                SchemaUtils.drop(entryTable)
                warn { "已删除表: ${entryTable.tableName}" }
            }
        }
    }

    @Subscribe
    fun onSchemaEvent(event: TableSchemaEvent) {
        when (event.action) {
            TableSchemaAction.CREATE_ALL -> createAllEntryTables()
            TableSchemaAction.DROP_ALL -> dropAllEntryTables()
        }
    }

    @Subscribe
    fun onEntryRegister(event: EntryRegisterEvent) {
        info { "接收到 Entry 注册事件: ${event.configName} (ID: ${event.entryConfig.id})" }

        val entryTable = registerEntry(event.entryConfig.id)

        createEntryTable(entryTable)
    }
}
