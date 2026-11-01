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

package cn.starfallplain.sfplogin.config

/**
 * 运行时配置快照持有者。
 *
 * 约定：
 * - 快照一旦发布就不可变（[CoreConfig] 与其内部数据结构都是只读的），读方拿到引用后不需要加锁；
 * - 重载时构造**新**快照再整体替换，避免运行中的请求看到半新半旧的配置；
 * - [revision] 单调递增，日志里带上它就能判断某段行为用的是哪一版配置。
 */
class ConfigStore {

    /**
     * 一份完整配置快照。
     *
     * @property core 该版本的完整核心配置
     * @property revision 发布序号，从 1 开始
     */
    data class Snapshot(
        val core: CoreConfig,
        val revision: Long,
    )

    @Volatile
    private var currentSnapshot: Snapshot? = null

    /** 发布新快照，返回发布后的快照。 */
    fun publish(core: CoreConfig): Snapshot {
        val next = Snapshot(core, (currentSnapshot?.revision ?: 0L) + 1L)
        currentSnapshot = next
        return next
    }

    fun current(): Snapshot? = currentSnapshot

    fun coreOrNull(): CoreConfig? = currentSnapshot?.core

    fun revision(): Long = currentSnapshot?.revision ?: 0L

    fun require(): Snapshot {
        return currentSnapshot ?: error("configuration snapshot is not available yet")
    }

    fun requireCore(): CoreConfig = require().core

    /** 关闭时清空，避免已释放的上下文继续对外提供配置。 */
    fun clear() {
        currentSnapshot = null
    }
}
