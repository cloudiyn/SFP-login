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
import icu.h2l.api.db.table.ChannelPasswordTable
import icu.h2l.api.db.table.ProfileTable
import icu.h2l.api.log.error
import icu.h2l.api.log.warn
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import java.util.*

/**
 * 统一渠道密码 Repository。
 *
 * 这是三个认证渠道（offline / yggdrasil / floodgate）**唯一**的密码存取入口，
 * 取代了原先各自维护的 `offline_auth` / `entry_<id>` / `floodgate_auth` 密码列读写。
 *
 * 所有方法都不抛异常，失败时返回 null / false 并记录日志。
 */
class ChannelPasswordRepository(
    private val databaseManager: HyperZoneDatabaseManager,
    val passwordTable: ChannelPasswordTable
) {
    /** 建表（IF NOT EXISTS 语义由 SchemaUtils 保证）。 */
    fun createTable() {
        databaseManager.executeTransaction {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(passwordTable, withLogs = false)
        }
    }

    /**
     * 按渠道身份读取记录。
     *
     * @param channel 渠道
     * @param channelKey 渠道身份键
     * @param entryId 入口 ID，仅 yggdrasil 使用，其他渠道传空串
     */
    fun find(channel: PasswordChannel, channelKey: String, entryId: String = ""): ChannelPasswordEntry? {
        return runCatching {
            databaseManager.executeTransaction {
                passwordTable.selectAll()
                    .where {
                        (passwordTable.channel eq channel.id) and
                            (passwordTable.entryId eq entryId) and
                            (passwordTable.channelKey eq channelKey)
                    }
                    .limit(1)
                    .map(::toEntry)
                    .firstOrNull()
            }
        }.getOrElse {
            warn { "读取统一密码记录失败 channel=${channel.id} entry=$entryId key=$channelKey: ${it.message}" }
            null
        }
    }

    /**
     * 按正式档案 ID 读取记录（跨渠道）。
     *
     * 同一档案理论上只应有一条密码记录；若存在多条（历史数据），返回最早的一条。
     */
    fun findByProfileId(profileId: UUID): ChannelPasswordEntry? {
        return runCatching {
            databaseManager.executeTransaction {
                passwordTable.selectAll()
                    .where { passwordTable.profileId eq profileId }
                    .limit(1)
                    .map(::toEntry)
                    .firstOrNull()
            }
        }.getOrElse {
            warn { "按档案读取统一密码记录失败 pid=$profileId: ${it.message}" }
            null
        }
    }

    /** 按玩家名查找记录（仅用于离线渠道与后台管理命令的模糊定位）。 */
    fun findByName(name: String): List<ChannelPasswordEntry> {
        val normalized = name.lowercase(Locale.ROOT)
        return runCatching {
            databaseManager.executeTransaction {
                passwordTable.selectAll()
                    .where { passwordTable.name eq normalized }
                    .map(::toEntry)
            }
        }.getOrElse {
            warn { "按名读取统一密码记录失败 name=$name: ${it.message}" }
            emptyList()
        }
    }

    /** 按邮箱查找（离线找回用）。 */
    fun findByEmail(email: String): ChannelPasswordEntry? {
        val normalized = email.lowercase(Locale.ROOT)
        return runCatching {
            databaseManager.executeTransaction {
                passwordTable.selectAll()
                    .where { passwordTable.email eq normalized }
                    .limit(1)
                    .map(::toEntry)
                    .firstOrNull()
            }
        }.getOrElse {
            warn { "按邮箱读取统一密码记录失败 email=$email: ${it.message}" }
            null
        }
    }

    /**
     * 写入（或覆盖）某渠道的密码，并清除失败计数与封锁。
     *
     * 采用 upsert：命中则更新，未命中则插入一行（[profileId] 允许为空）。
     *
     * @return true 表示写入成功
     */
    fun setPassword(
        channel: PasswordChannel,
        channelKey: String,
        hash: String,
        format: String,
        name: String,
        entryId: String = "",
        profileId: UUID? = null,
    ): Boolean {
        return try {
            databaseManager.executeTransaction {
                val updated = passwordTable.update({
                    (passwordTable.channel eq channel.id) and
                        (passwordTable.entryId eq entryId) and
                        (passwordTable.channelKey eq channelKey)
                }) {
                    it[passwordHash] = hash
                    it[hashFormat] = format
                    it[loginFailCount] = 0
                    it[loginBlockedUntil] = null
                    if (profileId != null) it[this.profileId] = profileId
                }
                if (updated > 0) {
                    return@executeTransaction true
                }

                passwordTable.insert {
                    it[this.channel] = channel.id
                    it[this.entryId] = entryId
                    it[this.name] = name.lowercase(Locale.ROOT)
                    it[this.channelKey] = channelKey
                    it[this.profileId] = profileId
                    it[passwordHash] = hash
                    it[hashFormat] = format
                    it[loginFailCount] = 0
                    it[loginBlockedUntil] = null
                }
                true
            }
        } catch (e: Exception) {
            error(e) { "写入统一密码失败 channel=${channel.id} entry=$entryId key=$channelKey: ${e.message}" }
            false
        }
    }

    /** 更新指定记录的密码（按主键），并清除失败计数、封锁与找回/会话状态。 */
    fun updatePasswordById(id: Int, hash: String, format: String): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[passwordHash] = hash
                    it[hashFormat] = format
                    it[recoveryCodeHash] = null
                    it[recoveryCodeExpireAt] = null
                    it[recoveryRequestedAt] = null
                    it[recoveryVerifyTries] = 0
                    it[resetPasswordVerifiedUntil] = null
                    it[loginFailCount] = 0
                    it[loginBlockedUntil] = null
                    it[sessionIp] = null
                    it[sessionIssuedAt] = null
                    it[sessionExpiresAt] = null
                } > 0
            }
        } catch (e: Exception) {
            warn { "更新统一密码失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 绑定记录的 profileId 补全（档案 attach 后调用）。 */
    fun bindProfileId(id: Int, profileId: UUID): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[this.profileId] = profileId
                } > 0
            }
        } catch (e: Exception) {
            warn { "补全统一密码记录档案失败 id=$id pid=$profileId: ${e.message}" }
            false
        }
    }

    /** 同步绑定行的玩家名（改名时调用）。 */
    fun updateName(id: Int, newName: String): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[name] = newName.lowercase(Locale.ROOT)
                } > 0
            }
        } catch (e: Exception) {
            warn { "更新统一密码记录名称失败 id=$id: ${e.message}" }
            false
        }
    }

    /**
     * 记录一次密码错误，超过 [maxAttempts] 后按 [blockSeconds] 封锁。
     *
     * @return 本次失败后剩余可尝试次数；0 表示已被封锁；null 表示记录不存在。
     */
    fun registerPasswordFailure(id: Int, maxAttempts: Int, blockSeconds: Int): Int? {
        return try {
            databaseManager.executeTransaction {
                val row = passwordTable.selectAll().where { passwordTable.id eq id }
                    .limit(1)
                    .firstOrNull() ?: return@executeTransaction null

                val currentFailCount = row[passwordTable.loginFailCount]
                val newFailCount = currentFailCount + 1
                val shouldBlock = maxAttempts > 0 && newFailCount >= maxAttempts

                passwordTable.update({ passwordTable.id eq id }) {
                    it[loginFailCount] = if (shouldBlock) 0 else newFailCount
                    if (shouldBlock) {
                        it[loginBlockedUntil] = System.currentTimeMillis() + blockSeconds * 1000L
                    }
                }

                if (shouldBlock) 0 else (maxAttempts - newFailCount).coerceAtLeast(0)
            }
        } catch (e: Exception) {
            warn { "记录统一密码失败次数失败 id=$id: ${e.message}" }
            null
        }
    }

    /** 清除失败计数与封锁。 */
    fun resetPasswordFailures(id: Int): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[loginFailCount] = 0
                    it[loginBlockedUntil] = null
                } > 0
            }
        } catch (e: Exception) {
            warn { "清除统一密码失败状态失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 直接设置失败计数与封锁时间（离线登录保护用）。 */
    fun updateLoginProtection(id: Int, failCount: Int, blockedUntil: Long?): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[loginFailCount] = failCount
                    it[loginBlockedUntil] = blockedUntil
                } > 0
            }
        } catch (e: Exception) {
            warn { "更新统一密码登录保护状态失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 更新邮箱（离线渠道）。 */
    fun updateEmail(id: Int, email: String?): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[this.email] = email?.lowercase(Locale.ROOT)
                } > 0
            }
        } catch (e: Exception) {
            warn { "更新统一密码邮箱失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 写入找回码。 */
    fun startRecovery(id: Int, recoveryCodeHash: String, expireAt: Long, requestedAt: Long): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[this.recoveryCodeHash] = recoveryCodeHash
                    it[this.recoveryCodeExpireAt] = expireAt
                    it[this.recoveryRequestedAt] = requestedAt
                    it[this.recoveryVerifyTries] = 0
                    it[this.resetPasswordVerifiedUntil] = null
                } > 0
            }
        } catch (e: Exception) {
            warn { "写入统一密码找回码失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 找回码验证尝试次数 +1。 */
    fun incrementRecoveryVerifyTries(id: Int): Boolean {
        return try {
            databaseManager.executeTransaction {
                val current = passwordTable.selectAll().where { passwordTable.id eq id }
                    .limit(1)
                    .map { it[passwordTable.recoveryVerifyTries] }
                    .firstOrNull() ?: return@executeTransaction false

                passwordTable.update({ passwordTable.id eq id }) {
                    it[recoveryVerifyTries] = current + 1
                } > 0
            }
        } catch (e: Exception) {
            warn { "更新统一密码找回尝试次数失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 标记找回码已验证通过。 */
    fun markRecoveryVerified(id: Int, verifiedUntil: Long): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[resetPasswordVerifiedUntil] = verifiedUntil
                    it[recoveryCodeHash] = null
                    it[recoveryCodeExpireAt] = null
                    it[recoveryRequestedAt] = null
                    it[recoveryVerifyTries] = 0
                } > 0
            }
        } catch (e: Exception) {
            warn { "标记统一密码找回已验证失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 清理找回状态。 */
    fun clearRecoveryState(id: Int): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[recoveryCodeHash] = null
                    it[recoveryCodeExpireAt] = null
                    it[recoveryRequestedAt] = null
                    it[recoveryVerifyTries] = 0
                    it[resetPasswordVerifiedUntil] = null
                } > 0
            }
        } catch (e: Exception) {
            warn { "清理统一密码找回状态失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 签发会话。 */
    fun issueSession(id: Int, sessionIp: String?, issuedAt: Long, expiresAt: Long): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[this.sessionIp] = sessionIp
                    it[this.sessionIssuedAt] = issuedAt
                    it[this.sessionExpiresAt] = expiresAt
                } > 0
            }
        } catch (e: Exception) {
            warn { "签发统一密码会话失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 清理会话。 */
    fun clearSession(id: Int): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[sessionIp] = null
                    it[sessionIssuedAt] = null
                    it[sessionExpiresAt] = null
                } > 0
            }
        } catch (e: Exception) {
            warn { "清理统一密码会话失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 更新 TOTP 密钥。 */
    fun updateTotpSecret(id: Int, totpSecret: String?): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.update({ passwordTable.id eq id }) {
                    it[this.totpSecret] = totpSecret
                    if (totpSecret == null) {
                        it[sessionIp] = null
                        it[sessionIssuedAt] = null
                        it[sessionExpiresAt] = null
                    }
                } > 0
            }
        } catch (e: Exception) {
            warn { "更新统一密码 TOTP 状态失败 id=$id: ${e.message}" }
            false
        }
    }

    /** 删除记录。 */
    fun deleteById(id: Int): Boolean {
        return try {
            databaseManager.executeTransaction {
                passwordTable.deleteWhere { passwordTable.id eq id }
            } > 0
        } catch (e: Exception) {
            warn { "删除统一密码记录失败 id=$id: ${e.message}" }
            false
        }
    }

    private fun toEntry(row: ResultRow): ChannelPasswordEntry {
        return ChannelPasswordEntry(
            id = row[passwordTable.id],
            channel = row[passwordTable.channel],
            entryId = row[passwordTable.entryId],
            name = row[passwordTable.name],
            channelKey = row[passwordTable.channelKey],
            profileId = row[passwordTable.profileId],
            passwordHash = row[passwordTable.passwordHash],
            hashFormat = row[passwordTable.hashFormat],
            loginFailCount = row[passwordTable.loginFailCount],
            loginBlockedUntil = row[passwordTable.loginBlockedUntil],
            email = row[passwordTable.email],
            recoveryCodeHash = row[passwordTable.recoveryCodeHash],
            recoveryCodeExpireAt = row[passwordTable.recoveryCodeExpireAt],
            recoveryRequestedAt = row[passwordTable.recoveryRequestedAt],
            recoveryVerifyTries = row[passwordTable.recoveryVerifyTries],
            resetPasswordVerifiedUntil = row[passwordTable.resetPasswordVerifiedUntil],
            sessionIp = row[passwordTable.sessionIp],
            sessionIssuedAt = row[passwordTable.sessionIssuedAt],
            sessionExpiresAt = row[passwordTable.sessionExpiresAt],
            totpSecret = row[passwordTable.totpSecret],
        )
    }
}

/**
 * [ChannelPasswordRepository] 的全局访问器。
 *
 * 由核心（velocity 模块）在启动时创建唯一实例并 [bind]，
 * 三个认证子模块通过 [get] / [getOrNull] 共享同一份统一密码存储。
 */
object ChannelPasswordProvider {
    @Volatile
    private var repository: ChannelPasswordRepository? = null

    fun bind(repository: ChannelPasswordRepository) {
        this.repository = repository
    }

    fun get(): ChannelPasswordRepository =
        repository ?: error("Channel password repository is not available yet")

    fun getOrNull(): ChannelPasswordRepository? = repository
}

