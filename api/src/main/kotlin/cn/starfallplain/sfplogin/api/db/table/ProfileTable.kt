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

package cn.starfallplain.sfplogin.api.db.table

import org.jetbrains.exposed.sql.Table

/**
 * 游戏内正式档案表定义。
 *
 * 该表保存 [cn.starfallplain.sfplogin.api.db.Profile] 的核心字段，且 `name` 与 `uuid` 都必须在整库范围内唯一。
 *
 * @param prefix 表名前缀，默认为空字符串
 */
class ProfileTable(prefix: String = "") : Table("${prefix}profile") {
    /**
     * 档案主键，用于和各认证入口建立映射。
     */
    val id = uuid("id")

    /**
     * 玩家正式游戏名。
     */
    val name = varchar("name", 32).uniqueIndex()

    /**
     * 玩家正式游戏 UUID。
     */
    val uuid = uuid("uuid").uniqueIndex()

    /**
     * 表主键定义。
     */
    override val primaryKey = PrimaryKey(id)
}