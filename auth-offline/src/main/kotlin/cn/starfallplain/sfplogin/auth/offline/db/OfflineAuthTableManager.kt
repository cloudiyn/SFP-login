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

package cn.starfallplain.sfplogin.auth.offline.db

import com.velocitypowered.api.event.Subscribe
import cn.starfallplain.sfplogin.api.db.HyperZoneDatabaseManager
import cn.starfallplain.sfplogin.api.db.table.ProfileTable
import cn.starfallplain.sfplogin.api.event.db.TableSchemaAction
import cn.starfallplain.sfplogin.api.event.db.TableSchemaEvent
import cn.starfallplain.sfplogin.api.log.info
import cn.starfallplain.sfplogin.api.log.warn
import cn.starfallplain.sfplogin.api.password.ChannelPasswordMigrator
import cn.starfallplain.sfplogin.api.password.ChannelPasswordRepository
import cn.starfallplain.sfplogin.auth.offline.api.db.OfflineAuthTable

/**
 * 离线渠道旧表（`offline_auth`）的生命周期管理。
 *
 * 自统一密码存储改造后，离线密码已全部迁入统一密码表，`offline_auth` 不再承载业务数据。
 * 因此本管理器**不再创建**该表，而是：
 * - 首次启动时把老库 `offline_auth` 的数据迁移到统一密码表，然后删除旧表；
 * - 之后启动直接跳过（表已不存在）。
 */
class OfflineAuthTableManager(
    private val databaseManager: HyperZoneDatabaseManager,
    tablePrefix: String,
    profileTable: ProfileTable,
    private val passwordRepository: ChannelPasswordRepository,
) {
    val offlineAuthTable = OfflineAuthTable(tablePrefix, profileTable)

    private val migrator = ChannelPasswordMigrator(databaseManager, passwordRepository)

    /**
     * 迁移旧 `offline_auth` 数据到统一密码表，并删除旧表（幂等）。
     *
     * 这是本模块启动阶段最早拿到统一密码表的时机，因此顺便为**旧版 SQLite 的统一密码表**
     * 补建唯一索引（Exposed 不会为已存在的表补索引）。
     */
    fun createTable() {
        runCatching {
            migrator.ensurePasswordTableIndexes(passwordRepository.passwordTable.tableName)
            val migrated = migrator.migrateOfflineTable(offlineAuthTable.tableName)
            if (migrated > 0) {
                info { "已迁移 $migrated 条离线密码记录到统一密码表" }
            }
        }.onFailure { warn { "迁移 offline_auth 失败：${it.message}" } }
    }

    fun dropTable() {
        migrator.dropTable(offlineAuthTable.tableName)
    }

    @Subscribe
    fun onSchemaEvent(event: TableSchemaEvent) {
        when (event.action) {
            TableSchemaAction.CREATE_ALL -> createTable()
            TableSchemaAction.DROP_ALL -> dropTable()
        }
    }
}