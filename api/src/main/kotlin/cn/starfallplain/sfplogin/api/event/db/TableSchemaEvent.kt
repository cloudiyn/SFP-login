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

package cn.starfallplain.sfplogin.api.event.db

/**
 * 数据表结构维护动作类型。
 */
enum class TableSchemaAction {
    /** 创建全部核心与模块表。 */
    CREATE_ALL,

    /** 删除全部核心与模块表。 */
    DROP_ALL,
}

/**
 * 在核心准备执行表结构维护动作时抛出的事件。
 *
 * @property action 即将执行的结构维护动作
 */
data class TableSchemaEvent(
    val action: TableSchemaAction,
)
