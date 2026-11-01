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

package cn.starfallplain.sfplogin.manager

/**
 * 等待区放行的事务边界。
 *
 * 放行动作是"先写离场标记，再交给 serverAdapter 真正放行"。这两步中任何一步抛错都会留下
 * 半成品状态：调用方若已经先把玩家记成"已通知"，之后的重试就会被这条记录挡掉，玩家永久
 * 卡在等待区。因此把三步收进一个事务：
 *
 * 1. 写离场标记；
 * 2. 执行放行动作；
 * 3. 任一步失败就回滚离场标记，并报告失败以便重试。
 */
internal object WaitingAreaReleaseTransaction {

    /**
     * 执行一次放行事务。
     *
     * @param markLeavePending 写入离场标记
     * @param clearLeavePending 回滚离场标记（只会在失败路径调用）
     * @param release 真正放行动作（调用 serverAdapter、发送放行提示等）
     * @param onFailure 失败回调，用于记录日志
     * @return 放行成功返回 `true`；失败时返回 `false`，此时调用方**不得**记录玩家已放行
     */
    fun run(
        markLeavePending: () -> Unit,
        clearLeavePending: () -> Unit,
        release: () -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ): Boolean {
        var pendingMarked = false
        return try {
            markLeavePending()
            pendingMarked = true
            release()
            true
        } catch (t: Throwable) {
            onFailure(t)
            if (pendingMarked) {
                try {
                    clearLeavePending()
                } catch (rollbackFailure: Throwable) {
                    onFailure(rollbackFailure)
                }
            }
            false
        }
    }
}
