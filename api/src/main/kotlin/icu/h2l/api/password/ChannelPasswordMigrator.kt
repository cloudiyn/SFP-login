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

package icu.h2l.api.password

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.log.info
import icu.h2l.api.log.warn
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.nio.ByteBuffer
import java.sql.ResultSet
import java.util.UUID

/**
 * 旧密码数据自动迁移器。
 *
 * 统一密码存储改造上线后，老库中的密码分散在三处：
 * 1. `offline_auth`（离线渠道整条记录）；
 * 2. `entry_<id>`（yggdrasil 各入口的密码列）；
 * 3. `floodgate_auth`（floodgate 的密码列）。
 *
 * 本迁移器把它们一次性搬入统一密码表 `<prefix>password`，随后**删除旧表**。
 * 迁移幂等：统一表中已存在同 `(channel, entryId, channelKey)` 行时跳过，不会覆盖新数据。
 *
 * 由**各认证子模块在自己的启动阶段**调用对应方法（各模块最清楚自己的旧表名），
 * 因此这里不依赖任何具体表类，全部走原生 SQL，且容忍缺列。
 */
class ChannelPasswordMigrator(
    private val databaseManager: HyperZoneDatabaseManager,
    private val repository: ChannelPasswordRepository,
) {
    /**
     * 迁移 `offline_auth` 表（整条记录：密码 + 邮箱 + 找回 + 会话 + TOTP）。
     *
     * 旧表只有在**全部有效行都已成功落入统一表**后才删除：
     * 存在任何失败 / 跳过（如哈希为空、写入报错）时保留旧表，下次启动重试，
     * 避免"部分迁移 + 删表"造成不可逆的数据丢失。
     *
     * @return 迁移条数
     */
    fun migrateOfflineTable(tableName: String): Int {
        if (!tableExists(tableName)) return 0
        var count = 0
        var migratedCleanly = true
        queryAll(tableName).forEach { row ->
            val rawName = row["name"] as? String
            val name = rawName?.lowercase()
            if (name.isNullOrBlank()) {
                warn { "$tableName 存在缺少 name 的行，跳过迁移并保留旧表" }
                migratedCleanly = false
                return@forEach
            }
            val hash = row["password_hash"] as? String
            if (hash.isNullOrBlank()) {
                // 从未设置过密码的占位行，视为无需迁移（不算失败）
                return@forEach
            }
            if (repository.find(PasswordChannel.OFFLINE, name) != null) {
                // 统一表中已有该玩家（例如上次已迁移成功），视为已处理
                return@forEach
            }

            val inserted = repository.setPassword(
                channel = PasswordChannel.OFFLINE,
                channelKey = name,
                hash = hash,
                format = row["hash_format"] as? String ?: ChannelPasswordService.HASH_FORMAT_SHA256,
                name = name,
                profileId = readUuid(row["profile_id"]),
            )
            if (inserted) {
                count++
                applyOfflineExtras(name, row)
            } else {
                warn { "迁移 $tableName 中玩家 $name 的密码失败，保留旧表以便重试" }
                migratedCleanly = false
            }
        }
        if (migratedCleanly) {
            dropTable(tableName)
        } else {
            warn { "$tableName 存在未成功迁移的行，已保留旧表，下次启动将重试" }
        }
        return count
    }

    /**
     * 迁移单个 `entry_<id>` 表的密码列。
     *
     * 自 yggdrasil 渠道的本地密码门删除后，密码统一归 **offline 渠道**（按玩家名索引）管理，
     * 因此这里把旧入口密码按**玩家名**搬进 offline 渠道，使统一密码门可继续校验。
     *
     * 注意：本项目历史版本的入口表从未包含密码列，因此对当前版本该分支为兼容性兜底
     * （仅当第三方改版或极旧库确实存在 `password_hash` 列时才会生效）。
     *
     * @param entryId 入口 ID（小写）
     * @param tableName 入口表物理名
     * @return 迁移条数
     */
    fun migrateEntryTable(entryId: String, tableName: String): Int {
        if (!tableExists(tableName)) return 0
        var count = 0
        queryAll(tableName).forEach { row ->
            val hash = row["password_hash"] as? String ?: return@forEach
            if (hash.isBlank()) return@forEach
            val name = (row["name"] as? String)?.lowercase()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (repository.find(PasswordChannel.OFFLINE, name) != null) return@forEach

            val inserted = repository.setPassword(
                channel = PasswordChannel.OFFLINE,
                channelKey = name,
                hash = hash,
                format = row["hash_format"] as? String ?: ChannelPasswordService.HASH_FORMAT_SHA256,
                name = name,
                profileId = readUuid(row["pid"]),
            )
            if (inserted) {
                count++
                applyLoginProtection(PasswordChannel.OFFLINE, name, "", row)
            }
        }
        // 入口表仍承载渠道身份绑定（name/uuid/pid），因此**不删除整表**，只保留
        return count
    }

    /**
     * 迁移 `floodgate_auth` 表的密码列。
     *
     * 同样归入 **offline 渠道**（按玩家名索引），供统一密码门校验。
     * 与入口表同理，本项目历史版本的 floodgate 表从未包含密码列，此处为兼容性兜底。
     *
     * @return 迁移条数
     */
    fun migrateFloodgateTable(tableName: String): Int {
        if (!tableExists(tableName)) return 0
        var count = 0
        queryAll(tableName).forEach { row ->
            val hash = row["password_hash"] as? String ?: return@forEach
            if (hash.isBlank()) return@forEach
            val name = (row["name"] as? String)?.lowercase()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (repository.find(PasswordChannel.OFFLINE, name) != null) return@forEach

            val inserted = repository.setPassword(
                channel = PasswordChannel.OFFLINE,
                channelKey = name,
                hash = hash,
                format = row["hash_format"] as? String ?: ChannelPasswordService.HASH_FORMAT_SHA256,
                name = name,
                profileId = readUuid(row["profile_id"]),
            )
            if (inserted) {
                count++
                applyLoginProtection(PasswordChannel.OFFLINE, name, "", row)
            }
        }
        // floodgate_auth 仍承载渠道身份绑定（name/xuid/profileId），**不删除整表**
        return count
    }

    /**
     * 清空 `floodgate_auth` 的密码列（绑定行保留，只移除已迁移的密码字段）。
     *
     * 与入口表保持一致，一并清空失败计数与封锁时间等残留字段；
     * 旧结构若不含这些列则自动跳过对应子句。
     */
    fun clearFloodgatePasswordColumns(tableName: String) {
        if (!tableExists(tableName)) return
        runCatching {
            databaseManager.executeTransaction {
                val assignments = buildList {
                    if (hasColumn(tableName, "password_hash")) add("password_hash = NULL")
                    if (hasColumn(tableName, "hash_format")) add("hash_format = NULL")
                    if (hasColumn(tableName, "login_fail_count")) add("login_fail_count = 0")
                    if (hasColumn(tableName, "login_blocked_until")) add("login_blocked_until = NULL")
                }
                if (assignments.isEmpty()) return@executeTransaction
                TransactionManager.current().exec(
                    "UPDATE ${quote(tableName)} SET ${assignments.joinToString(", ")}"
                ) { }
            }
        }.onFailure { warn { "清空 $tableName 密码列失败：${it.message}" } }
    }

    /**
     * 清空所有 `entry_<id>` 表的密码列（绑定行保留）。
     */
    fun clearEntryPasswordColumns(entryId: String, tableName: String) {
        if (!tableExists(tableName)) return
        runCatching {
            databaseManager.executeTransaction {
                val assignments = buildList {
                    if (hasColumn(tableName, "password_hash")) add("password_hash = NULL")
                    if (hasColumn(tableName, "hash_format")) add("hash_format = NULL")
                    if (hasColumn(tableName, "login_fail_count")) add("login_fail_count = 0")
                    if (hasColumn(tableName, "login_blocked_until")) add("login_blocked_until = NULL")
                }
                if (assignments.isEmpty()) return@executeTransaction
                TransactionManager.current().exec(
                    "UPDATE ${quote(tableName)} SET ${assignments.joinToString(", ")}"
                ) { }
            }
        }.onFailure { warn { "清空 $tableName 密码列失败：${it.message}" } }
    }

    /** 删除指定旧表（用于 offline_auth 这类整表迁移后不再需要的表）。 */
    fun dropTable(tableName: String) {
        if (!tableExists(tableName)) return
        runCatching {
            databaseManager.executeTransaction {
                TransactionManager.current().exec("DROP TABLE ${quote(tableName)}") { }
            }
            info { "已删除旧密码表 $tableName" }
        }.onFailure { warn { "删除旧表 $tableName 失败：${it.message}" } }
    }

    /**
     * 兼容旧版 SQLite 统一密码表：补建缺失的唯一索引。
     *
     * Exposed 的 [org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns] 在 SQLite 上
     * **不会**为已存在的表补建索引（表已存在时直接跳过），因此早期版本创建的 `<prefix>password`
     * 表可能缺少 `(channel, entry_id, channel_key)` 唯一索引，导致 upsert 退化为重复插入。
     *
     * 这里用原生 DDL 显式补建（`IF NOT EXISTS` 幂等），仅 SQLite 需要。
     * 其他方言由 createMissingTablesAndColumns 自行处理。
     */
    fun ensurePasswordTableIndexes(passwordTableName: String) {
        if (!tableExists(passwordTableName)) return
        runCatching {
            databaseManager.executeTransaction {
                val tx: Transaction = TransactionManager.current()
                val dialect = tx.db.dialect.name.lowercase()
                if (!dialect.contains("sqlite")) return@executeTransaction

                tx.exec(
                    "CREATE UNIQUE INDEX IF NOT EXISTS ${passwordTableName}_channel_entry_key " +
                        "ON ${quote(passwordTableName)} (channel, entry_id, channel_key)"
                ) { }
                info { "已确认统一密码表 $passwordTableName 的唯一索引" }
            }
        }.onFailure { warn { "补建统一密码表索引失败 $passwordTableName：${it.message}" } }
    }

    private fun applyOfflineExtras(name: String, row: Map<String, Any?>) {
        val entry = repository.find(PasswordChannel.OFFLINE, name) ?: return
        (row["email"] as? String)?.let { repository.updateEmail(entry.id, it) }
        val recoveryHash = row["recovery_code_hash"] as? String
        val recoveryExpire = (row["recovery_code_expire_at"] as? Number)?.toLong()
        val recoveryRequested = (row["recovery_requested_at"] as? Number)?.toLong()
        if (recoveryHash != null && recoveryExpire != null && recoveryRequested != null) {
            repository.startRecovery(entry.id, recoveryHash, recoveryExpire, recoveryRequested)
        }
        (row["reset_password_verified_until"] as? Number)?.toLong()?.let {
            repository.markRecoveryVerified(entry.id, it)
        }
        (row["totp_secret"] as? String)?.let { repository.updateTotpSecret(entry.id, it) }

        val failCount = (row["login_fail_count"] as? Number)?.toInt() ?: 0
        val blockedUntil = (row["login_blocked_until"] as? Number)?.toLong()
        if (failCount != 0 || blockedUntil != null) {
            repository.updateLoginProtection(entry.id, failCount, blockedUntil)
        }

        val sessionIssuedAt = (row["session_issued_at"] as? Number)?.toLong()
        val sessionExpiresAt = (row["session_expires_at"] as? Number)?.toLong()
        if (sessionIssuedAt != null && sessionExpiresAt != null) {
            repository.issueSession(entry.id, row["session_ip"] as? String, sessionIssuedAt, sessionExpiresAt)
        }
    }

    private fun applyLoginProtection(
        channel: PasswordChannel,
        channelKey: String,
        entryId: String,
        row: Map<String, Any?>,
    ) {
        val entry = repository.find(channel, channelKey, entryId) ?: return
        val failCount = (row["login_fail_count"] as? Number)?.toInt() ?: 0
        val blockedUntil = (row["login_blocked_until"] as? Number)?.toLong()
        if (failCount != 0 || blockedUntil != null) {
            repository.updateLoginProtection(entry.id, failCount, blockedUntil)
        }
    }

    /** 读取一张旧表的全部行（列名小写 → 值）。 */
    private fun queryAll(tableName: String): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        databaseManager.executeTransaction {
            TransactionManager.current().exec("SELECT * FROM ${quote(tableName)}") { rs ->
                collectRows(rs, rows)
            }
        }
        return rows
    }

    private fun collectRows(rs: ResultSet, into: MutableList<Map<String, Any?>>) {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }
        while (rs.next()) {
            val row = HashMap<String, Any?>(columns.size)
            columns.forEachIndexed { index, column -> row[column] = normalize(rs.getObject(index + 1)) }
            into += row
        }
    }

    /**
     * 判断物理表是否存在。
     *
     * 注意：**不能**用 `SELECT 1 FROM <table> WHERE 1 = 0` 这类零行查询来探测表是否存在——
     * 表缺失时数据库会直接抛异常，而异常会被 Exposed 的事务重试机制捕获，
     * 刷出多条 WARN 堆栈（即使外层 `runCatching` 兜住也无法避免）。
     * 这里改为查询各数据库的元数据视图，表缺失时只返回空结果集。
     */
    private fun tableExists(tableName: String): Boolean {
        return runCatching {
            databaseManager.executeTransaction {
                val tx: Transaction = TransactionManager.current()
                val dialect = tx.db.dialect.name.lowercase()
                val sql = metadataTableExistsSql(dialect, tableName)
                if (sql != null) {
                    tx.exec(sql) { rs -> rs.next() } ?: false
                } else {
                    // 未知方言：退化为零行查询探测，失败时由外层吞掉
                    tx.exec("SELECT 1 FROM ${quote(tableName)} WHERE 1 = 0") { }
                    true
                }
            }
        }.getOrDefault(false)
    }

    /** 生成"表是否存在"的元数据查询 SQL；未知方言返回 null。 */
    private fun metadataTableExistsSql(dialect: String, tableName: String): String? {
        val literal = tableName.replace("'", "''")
        return when {
            dialect.contains("sqlite") ->
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$literal'"

            dialect.contains("mysql") || dialect.contains("mariadb") ->
                "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = '$literal'"

            dialect.contains("postgres") || dialect.contains("postgre") ->
                "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = current_schema() AND table_name = '$literal'"

            dialect.contains("h2") ->
                "SELECT table_name FROM information_schema.tables WHERE table_name = '$literal'"

            else -> null
        }
    }

    /** 生成"列是否存在"的元数据查询 SQL；未知方言返回 null。 */
    private fun metadataColumnExistsSql(dialect: String, tableName: String, column: String): String? {
        val tableLiteral = tableName.replace("'", "''")
        val columnLiteral = column.replace("'", "''")
        return when {
            dialect.contains("mysql") || dialect.contains("mariadb") ->
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = '$tableLiteral' " +
                    "AND column_name = '$columnLiteral'"

            dialect.contains("postgres") || dialect.contains("postgre") ->
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = current_schema() AND table_name = '$tableLiteral' " +
                    "AND column_name = '$columnLiteral'"

            dialect.contains("h2") ->
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_name = '$tableLiteral' AND column_name = '$columnLiteral'"

            else -> null
        }
    }

    /** 把 JDBC 驱动可能返回的方言类型（如时间戳）归一化到 Long。 */
    private fun normalize(value: Any?): Any? {
        return when (value) {
            is java.sql.Timestamp -> value.time
            is java.sql.Date -> value.time
            is java.time.Instant -> value.toEpochMilli()
            else -> value
        }
    }

    /**
     * 读取一个 UUID 字段，兼容不同驱动的返回形态。
     *
     * SQLite / MySQL 的 BINARY(16) 列经 JDBC 读回时可能是 `byte[]`（16 字节），
     * 直接 `as? UUID` 会静默得到 null 并丢失 profileId；这里一并处理。
     */
    private fun readUuid(value: Any?): UUID? {
        return when (value) {
            null -> null
            is UUID -> value
            is ByteArray -> if (value.size == 16) bytesToUuid(value) else null
            is String -> runCatching { UUID.fromString(value.trim()) }.getOrNull()
            else -> null
        }
    }

    private fun bytesToUuid(bytes: ByteArray): UUID {
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    /** 判断表是否存在指定列（用于兼容不同版本的旧表结构）。 */
    private fun hasColumn(tableName: String, column: String): Boolean {
        return runCatching {
            databaseManager.executeTransaction {
                val tx: Transaction = TransactionManager.current()
                val dialect = tx.db.dialect.name.lowercase()
                if (dialect.contains("sqlite")) {
                    tx.exec(
                        "SELECT COUNT(*) FROM pragma_table_info('${tableName.replace("'", "''")}') " +
                            "WHERE name = '${column.replace("'", "''")}'"
                    ) { rs -> if (rs.next()) rs.getInt(1) > 0 else false } ?: false
                } else {
                    val sql = metadataColumnExistsSql(dialect, tableName, column)
                    if (sql != null) {
                        tx.exec(sql) { rs -> rs.next() } ?: false
                    } else {
                        // 未知方言：退化为零行查询探测（表/列缺失时会抛异常，由外层吞掉）
                        runCatching {
                            tx.exec("SELECT ${quote(column)} FROM ${quote(tableName)} WHERE 1 = 0") { }
                            true
                        }.getOrDefault(false)
                    }
                }
            }
        }.getOrDefault(false)
    }

    private fun quote(name: String): String = "\"$name\""
}
