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

package icu.h2l.login.qq.whitelist.db

import org.jetbrains.exposed.sql.Table

/**
 * QQ 绑定关系表。
 */
class QqBindingTable(prefix: String) : Table("${prefix}qq_binding") {
    // 关系主键
    val id = uuid("id")

    // QQ 号（一个 QQ 可绑定多个档案）
    val qqId = long("qq_id").index()

    // 档案 ID（一个档案同时只能绑定一个 QQ）
    val profileId = uuid("profile_id").uniqueIndex()

    // 绑定时使用的玩家名
    val playerName = varchar("player_name", 32)

    // 绑定时间戳（毫秒）
    val boundAt = long("bound_at")

    // 是否还有「下次登录提示」未发送
    val notifyPending = bool("notify_pending").default(true)

    override val primaryKey = PrimaryKey(id)
}
