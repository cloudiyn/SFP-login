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

package cn.starfallplain.sfplogin.qq.whitelist.db

import com.velocitypowered.api.event.Subscribe
import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.event.db.TableSchemaAction
import cn.starfallplain.sfplogin.api.event.db.TableSchemaEvent
import cn.starfallplain.sfplogin.api.log.warn
import org.jetbrains.exposed.sql.SchemaUtils

/**
 * QQ 白名单表结构管理（补表 / 删表 / 响应 schema 事件）。
 */
class QqWhitelistTableManager(
    private val databaseManager: HyperZoneDatabaseManager,
    val bindingTable: QqBindingTable
) {
    @Suppress("DEPRECATION")
    fun createTable() {
        databaseManager.executeTransaction {
            SchemaUtils.createMissingTablesAndColumns(bindingTable, withLogs = false)
        }
    }

    fun dropTable() {
        databaseManager.executeTransaction {
            SchemaUtils.drop(bindingTable)
            warn { "已删除表: ${bindingTable.tableName}" }
        }
    }

    @Subscribe
    fun onSchemaEvent(event: TableSchemaEvent) {
        when (event.action) {
            TableSchemaAction.CREATE_ALL -> createTable()
            TableSchemaAction.DROP_ALL -> dropTable()
        }
    }
}
