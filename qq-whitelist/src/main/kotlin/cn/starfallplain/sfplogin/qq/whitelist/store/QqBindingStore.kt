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

package cn.starfallplain.sfplogin.qq.whitelist.store

import java.util.UUID

/**
 * 一条「QQ ↔ Minecraft 档案」绑定关系。
 *
 * @property id 关系主键
 * @property qqId 绑定的 QQ 号
 * @property profileId 绑定的正式档案 ID
 * @property playerName 绑定时使用的玩家名（仅用于展示/自助解绑）
 * @property boundAt 绑定时间戳（毫秒）
 * @property notifyPending 是否还有「下次登录提示」未发送
 */
data class QqBinding(
    val id: UUID,
    val qqId: Long,
    val profileId: UUID,
    val playerName: String,
    val boundAt: Long,
    val notifyPending: Boolean = true
)

/**
 * 绑定关系存储抽象，便于在单元测试中使用内存实现替换数据库实现。
 */
interface QqBindingStore {
    /**
     * 查询某个 QQ 的全部绑定。
     */
    fun findByQq(qqId: Long): List<QqBinding>

    /**
     * 查询某个档案的绑定（一个档案同时只允许绑定一个 QQ）。
     */
    fun findByProfile(profileId: UUID): QqBinding?

    /**
     * 按玩家名查询绑定（不区分大小写），用于管理员强制解绑。
     */
    fun findByPlayerName(playerName: String): QqBinding?

    /**
     * 查询某个 QQ 的绑定数量。
     */
    fun countByQq(qqId: Long): Int

    /**
     * 新增绑定；档案已存在绑定时应抛出异常或返回 false，由实现自行保证唯一性。
     */
    fun insert(binding: QqBinding): Boolean

    /**
     * 按档案删除绑定。
     */
    fun deleteByProfile(profileId: UUID): Boolean

    /**
     * 按档案删除绑定，并返回被删除的记录（不存在时返回 null）。
     */
    fun removeByProfile(profileId: UUID): QqBinding? {
        val existing = findByProfile(profileId) ?: return null
        return if (deleteByProfile(profileId)) existing else null
    }

    /**
     * 标记「下次登录提示」已发送。
     */
    fun markNotified(profileId: UUID)
}
