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

package icu.h2l.login.auth.online.api.db

import icu.h2l.api.db.table.ProfileTable
import org.jetbrains.exposed.sql.Table
import java.util.*

/**
 * 入口表基类
 * 用于不同的登录入口（如 mojang、offline 等）
 *
 * 本表只承载**入口身份绑定**（name/uuid/pid）。本地密码已统一交由 offline 渠道的
 * 密码体系管理，不再在本表中存放任何密码字段。
 *
 * @param entryId 入口ID，全小写，如 "mojang"、"offline"
 * @param prefix 表名前缀，默认为空字符串
 * @param profileTable 档案表实例（保留以兼容既有构造调用；由于 [pid] 可为空，此处不再声明外键）
 */
@Suppress("UNUSED_PARAMETER")
class EntryTable(entryId: String, prefix: String = "", profileTable: ProfileTable) : Table("${prefix}entry_$entryId") {
    /**
     * 自增ID
     */
    val id = integer("id").autoIncrement()

    /**
     * 入口处的玩家名
     */
    val name = varchar("name", 32)

    /**
     * 入口处的UUID
     */
    val uuid = uuid("uuid")

    /**
     * 映射的档案ID。
     *
     * 允许为空：入口身份可能在档案建档（bind）之前写入，
     * 此时该行只承载入口身份，`pid` 待档案 attach 后再补全。
     *
     * 注：为支持可空的绑定关系，这里不再声明外键约束，改由 [EntryDatabaseHelper] 在写入时保证一致性。
     */
    val pid = uuid("pid").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // 确保 name + uuid 的组合是唯一的
        uniqueIndex(name, uuid)
    }
}