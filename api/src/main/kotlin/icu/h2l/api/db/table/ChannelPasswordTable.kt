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

package icu.h2l.api.db.table

import org.jetbrains.exposed.sql.Table

/**
 * 统一渠道密码表。
 *
 * 三个认证渠道（offline / yggdrasil / floodgate）的本地密码统一存放在这一张表中，
 * 校验逻辑、失败计数与封锁策略完全共用，不再各渠道各写一份。
 *
 * 统一身份键 [channelKey] 的取值规则：
 * - `offline`：规范化（小写）后的玩家名；
 * - `yggdrasil`：远端 Yggdrasil 返回的 UUID 字符串（配合 [entryId] 区分多入口）；
 * - `floodgate`：微软 XUID 的字符串形式。
 *
 * [entryId] 仅 yggdrasil 渠道使用（同一 uuid 在不同入口下是不同绑定），
 * 其他渠道固定为空串，以便参与唯一索引。
 *
 * 唯一约束为 `(channel, entryId, channelKey)` 三元组，保证同一渠道、同一入口、同一身份只有一行密码。
 *
 * @param prefix 表名前缀，与核心业务表保持一致
 * @param profileTable 正式档案表（保留以兼容既有构造调用；[profileId] 允许为空，故不声明外键）
 */
@Suppress("UNUSED_PARAMETER")
class ChannelPasswordTable(prefix: String = "", profileTable: ProfileTable) : Table("${prefix}password") {
    /** 自增主键。 */
    val id = integer("id").autoIncrement()

    /** 渠道标识：offline / yggdrasil / floodgate。 */
    val channel = varchar("channel", 32)

    /** 入口 ID，仅 yggdrasil 使用；其他渠道为空串。 */
    val entryId = varchar("entry_id", 64).default("")

    /** 入口处的玩家名（用于展示与按名查找）。 */
    val name = varchar("name", 32)

    /** 渠道身份键，见类注释。 */
    val channelKey = varchar("channel_key", 128)

    /**
     * 指向的正式档案 ID。
     *
     * 允许为空：密码门可能在档案 attach 之前就要求玩家设置密码，
     * 此时该行只承载渠道身份与密码，待 attach 后补全。
     */
    val profileId = uuid("profile_id").nullable()

    /** 密码哈希，为空表示尚未设置本地密码。 */
    val passwordHash = varchar("password_hash", 255).nullable()

    /** 密码哈希格式。 */
    val hashFormat = varchar("hash_format", 32).nullable()

    /** 连续密码错误次数（防爆破统计）。 */
    val loginFailCount = integer("login_fail_count").default(0)

    /** 密码错误过多后封锁到的时间戳（毫秒），为空表示未封锁。 */
    val loginBlockedUntil = long("login_blocked_until").nullable()

    /** 找回邮箱（offline 渠道使用）。 */
    val email = varchar("email", 255).nullable()

    /** 找回码哈希（offline 渠道使用）。 */
    val recoveryCodeHash = varchar("recovery_code_hash", 255).nullable()

    /** 找回码过期时间戳（offline 渠道使用）。 */
    val recoveryCodeExpireAt = long("recovery_code_expire_at").nullable()

    /** 找回码请求时间戳（offline 渠道使用）。 */
    val recoveryRequestedAt = long("recovery_requested_at").nullable()

    /** 找回码验证尝试次数（offline 渠道使用）。 */
    val recoveryVerifyTries = integer("recovery_verify_tries").default(0)

    /** 找回验证通过后允许重置密码的截止时间戳（offline 渠道使用）。 */
    val resetPasswordVerifiedUntil = long("reset_password_verified_until").nullable()

    /** 会话绑定 IP（offline 渠道使用）。 */
    val sessionIp = varchar("session_ip", 64).nullable()

    /** 会话签发时间戳（offline 渠道使用）。 */
    val sessionIssuedAt = long("session_issued_at").nullable()

    /** 会话过期时间戳（offline 渠道使用）。 */
    val sessionExpiresAt = long("session_expires_at").nullable()

    /** TOTP 密钥（offline 渠道使用）。 */
    val totpSecret = varchar("totp_secret", 64).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("${tableName}_channel_entry_key", channel, entryId, channelKey)
    }
}
