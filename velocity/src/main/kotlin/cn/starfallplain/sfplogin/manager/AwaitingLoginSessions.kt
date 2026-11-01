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

import cn.starfallplain.sfplogin.api.event.auth.LoginHandleResult
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleSession
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 记录当前仍在进行中的 claim 及其会话。
 *
 * 唯一用途是回答“这次 dispatch 里还有 claim 在等玩家手动输入吗”——超时检查靠它把
 * “玩家看提示、打命令”的这段时间排除在固定超时之外。单独成类，避免 LoginManager
 * 为了这个判定而长期持有 session 引用。
 */
internal class AwaitingLoginSessions {

    private class Entry(
        val future: CompletableFuture<LoginHandleResult>,
        val session: LoginHandleSession,
    )

    private val entries = CopyOnWriteArrayList<Entry>()

    fun track(future: CompletableFuture<LoginHandleResult>, session: LoginHandleSession) {
        val entry = Entry(future, session)
        entries += entry
        future.whenComplete { _, _ ->
            entries.remove(entry)
        }
    }

    fun isAwaitingPlayerInput(future: CompletableFuture<LoginHandleResult>): Boolean {
        return runCatching {
            entries.firstOrNull { entry -> entry.future === future }
                ?.session
                ?.awaitingPlayerInput() == true
        }.getOrDefault(false)
    }

    fun isAwaitingPlayerInputAny(futures: Iterable<CompletableFuture<LoginHandleResult>>): Boolean {
        return futures.any { future -> !future.isDone && isAwaitingPlayerInput(future) }
    }

    fun clear() {
        entries.clear()
    }
}
