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

package cn.starfallplain.sfplogin.auth.floodgate.db

import com.velocitypowered.api.event.Subscribe
import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import cn.starfallplain.sfplogin.api.event.db.TableSchemaAction
import cn.starfallplain.sfplogin.api.event.db.TableSchemaEvent
import cn.starfallplain.sfplogin.api.log.info
import cn.starfallplain.sfplogin.api.log.warn
import cn.starfallplain.sfplogin.api.password.ChannelPasswordMigrator
import cn.starfallplain.sfplogin.auth.floodgate.api.db.FloodgateAuthTable
import org.jetbrains.exposed.sql.SchemaUtils

class FloodgateAuthTableManager(
    private val databaseManager: HyperZoneDatabaseManager,
    tablePrefix: String,
    profileTable: ProfileTable,
    /** 统一密码表仓库；由核心在启动时绑定，这里默认取全局实例。 */
    private val passwordRepository: cn.starfallplain.sfplogin.api.password.ChannelPasswordRepository? =
        cn.starfallplain.sfplogin.api.password.ChannelPasswordProvider.getOrNull(),
) {
    val floodgateAuthTable = FloodgateAuthTable(tablePrefix, profileTable)

    /** 旧密码数据迁移器（把 floodgate_auth 的旧密码列按玩家名搬进统一密码表的 offline 渠道）。 */
    private val passwordMigrator: ChannelPasswordMigrator? by lazy {
        val repository = passwordRepository ?: cn.starfallplain.sfplogin.api.password.ChannelPasswordProvider.getOrNull()
        repository?.let { ChannelPasswordMigrator(databaseManager, it) }
    }

    fun createTable() {
        databaseManager.executeTransaction {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(floodgateAuthTable, withLogs = false)
        }
        // 旧库可能已带密码列：迁移到统一密码表后清空旧密码列（绑定行 name/xuid/profileId 保留）
        val migrator = passwordMigrator
        if (migrator == null) {
            warn { "统一密码表尚未就绪，跳过 floodgate_auth 的密码列迁移" }
            return
        }
        runCatching {
            val migrated = migrator.migrateFloodgateTable(floodgateAuthTable.tableName)
            if (migrated > 0) {
                info { "已迁移 $migrated 条 Floodgate 密码记录到统一密码表" }
                migrator.clearFloodgatePasswordColumns(floodgateAuthTable.tableName)
            }
        }.onFailure { warn { "迁移 floodgate_auth 密码失败：${it.message}" } }
    }

    fun dropTable() {
        databaseManager.executeTransaction {
            SchemaUtils.drop(floodgateAuthTable)
            warn { "已删除表: ${floodgateAuthTable.tableName}" }
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
