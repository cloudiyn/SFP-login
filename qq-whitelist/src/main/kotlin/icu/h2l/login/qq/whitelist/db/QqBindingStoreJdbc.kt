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

import icu.h2l.api.db.HyperZoneDatabaseManager
import icu.h2l.api.log.warn
import icu.h2l.login.qq.whitelist.store.QqBinding
import icu.h2l.login.qq.whitelist.store.QqBindingStore
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*

/**
 * 基于 Exposed 的绑定关系存储实现。
 */
class QqBindingStoreJdbc(
    private val databaseManager: HyperZoneDatabaseManager,
    private val table: QqBindingTable
) : QqBindingStore {

    override fun findByQq(qqId: Long): List<QqBinding> {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.qqId eq qqId }
                .map(::toBinding)
                .sortedBy { it.boundAt }
        }
    }

    override fun findByProfile(profileId: UUID): QqBinding? {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.profileId eq profileId }
                .limit(1)
                .map(::toBinding)
                .firstOrNull()
        }
    }

    override fun findByPlayerName(playerName: String): QqBinding? {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.playerName.lowerCase() eq playerName.lowercase() }
                .limit(1)
                .map(::toBinding)
                .firstOrNull()
        }
    }

    override fun countByQq(qqId: Long): Int {
        return databaseManager.executeTransaction {
            table.selectAll().where { table.qqId eq qqId }.count().toInt()
        }
    }

    override fun insert(binding: QqBinding): Boolean {
        return try {
            databaseManager.executeTransaction {
                table.insert {
                    it[id] = binding.id
                    it[qqId] = binding.qqId
                    it[profileId] = binding.profileId
                    it[playerName] = binding.playerName
                    it[boundAt] = binding.boundAt
                    it[notifyPending] = binding.notifyPending
                }
            }
            true
        } catch (e: Exception) {
            warn { "写入 QQ 绑定失败: ${e.message}" }
            false
        }
    }

    override fun deleteByProfile(profileId: UUID): Boolean {
        return databaseManager.executeTransaction {
            table.deleteWhere { table.profileId eq profileId } > 0
        }
    }

    override fun markNotified(profileId: UUID) {
        databaseManager.executeTransaction {
            table.update({ table.profileId eq profileId }) {
                it[notifyPending] = false
            }
        }
    }

    private fun toBinding(row: ResultRow): QqBinding {
        return QqBinding(
            id = row[table.id],
            qqId = row[table.qqId],
            profileId = row[table.profileId],
            playerName = row[table.playerName],
            boundAt = row[table.boundAt],
            notifyPending = row[table.notifyPending]
        )
    }
}
