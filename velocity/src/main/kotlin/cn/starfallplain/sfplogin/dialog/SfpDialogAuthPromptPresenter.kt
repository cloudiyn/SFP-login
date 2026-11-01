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

package cn.starfallplain.sfplogin.dialog

import cn.starfallplain.sfplogin.api.auth.AuthPromptPresenter
import cn.starfallplain.sfplogin.api.auth.AuthPromptRequest
import cn.starfallplain.sfplogin.api.auth.AuthPromptSession
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import com.velocitypowered.proxy.connection.client.ConnectedPlayer
import io.netty.buffer.ByteBuf
import net.kyori.adventure.nbt.CompoundBinaryTag
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 已打开 dialog 的回调目标。
 */
interface DialogPromptTarget {
    /**
     * 客户端点击提交按钮后回传的 payload。
     */
    fun submit(payload: CompoundBinaryTag?)
}

/**
 * dialog 回传包的中央路由。
 *
 * 同一玩家同一时刻只保留一个 dialog 会话，后注册的会替换先前的。
 */
object DialogPromptRouter {

    private val targets = ConcurrentHashMap<UUID, DialogPromptTarget>()

    fun register(playerId: UUID, target: DialogPromptTarget) {
        targets[playerId] = target
    }

    fun unregister(playerId: UUID, target: DialogPromptTarget) {
        targets.remove(playerId, target)
    }

    fun target(playerId: UUID): DialogPromptTarget? = targets[playerId]

    /**
     * 尝试把客户端送来的包体当作 dialog 回传消费掉。
     *
     * @return 已消费时返回 true，调用方不应再转发给后端
     */
    fun tryHandle(player: ConnectedPlayer, body: ByteBuf): Boolean {
        val action = DialogCustomActionCodec.decode(body, player.protocolVersion) ?: return false
        if (action.channelId != DialogNbtFactory.ACTION_CHANNEL_ID) return false
        // 无论是否有活跃会话都消费掉：过期响应不应被转发到后端触发未知包错误
        targets[player.uniqueId]?.submit(action.payload)
        return true
    }

    /**
     * 玩家断开时清理残留会话。
     */
    fun clear(playerId: UUID) {
        targets.remove(playerId)
    }

    /**
     * 清空全部 dialog 会话（插件关闭时调用）。
     */
    fun clearAll() {
        targets.clear()
    }
}

/**
 * 基于 Java 版 dialog（Minecraft 1.21.6+）的认证提示框展示层。
 *
 * 仅在协议版本已收录于 [DialogPacketIdsTable] 且玩家已进入 PLAY 阶段时可用；
 * 其它情况由调用方回退到聊天提示。
 */
class SfpDialogAuthPromptPresenter(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    override val inputLengthLimit: Int = AuthPromptPresenter.DEFAULT_INPUT_LENGTH_LIMIT,
) : AuthPromptPresenter {

    companion object {
        /** 展示层 ID。 */
        const val PRESENTER_ID = "dialog"

        /** 默认超时（毫秒）：超时后关闭 dialog 并回退到聊天提示。 */
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L

        @Volatile
        private var timerRef: ScheduledExecutorService? = null

        private fun timer(): ScheduledExecutorService =
            timerRef ?: synchronized(this) {
                timerRef ?: Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "sfplogin-dialog-timeout").apply { isDaemon = true }
                }.also { timerRef = it }
            }

        /**
         * 停止 dialog 超时调度线程（插件关闭时调用）。
         */
        fun shutdown() {
            synchronized(this) {
                timerRef?.shutdownNow()
                timerRef = null
            }
        }
    }

    override val id: String = PRESENTER_ID

    override val priority: Int = 100

    override fun isSupported(player: HyperZonePlayer): Boolean {
        val proxyPlayer = player.getProxyPlayerOrNull() as? ConnectedPlayer ?: return false
        return SfpDialogPackets.canSend(proxyPlayer)
    }

    override fun present(
        player: HyperZonePlayer,
        request: AuthPromptRequest,
        onSubmit: (Map<String, String>) -> Unit,
        onClosed: () -> Unit,
    ): AuthPromptSession? {
        val proxyPlayer = player.getProxyPlayerOrNull() as? ConnectedPlayer ?: return null
        if (!SfpDialogPackets.canSend(proxyPlayer)) return null
        return DialogSession(proxyPlayer, request, onSubmit, onClosed, timeoutMillis)
    }

    private class DialogSession(
        private val player: ConnectedPlayer,
        initialRequest: AuthPromptRequest,
        private val onSubmit: (Map<String, String>) -> Unit,
        private val onClosed: () -> Unit,
        timeoutMillis: Long,
    ) : AuthPromptSession, DialogPromptTarget {

        private val closed = AtomicBoolean(false)
        private val playerId: UUID = player.uniqueId

        @Volatile
        private var request: AuthPromptRequest = initialRequest

        @Volatile
        private var timeoutTask: ScheduledFuture<*>? = null

        init {
            DialogPromptRouter.register(playerId, this)
            render(initialRequest)
            scheduleTimeout(timeoutMillis)
        }

        override fun show(request: AuthPromptRequest) {
            if (closed.get()) return
            this.request = request
            render(request)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            timeoutTask?.cancel(false)
            DialogPromptRouter.unregister(playerId, this)
            SfpDialogPackets.clear(player)
            runCatching { onClosed() }
        }

        override fun submit(payload: CompoundBinaryTag?) {
            if (closed.get()) return
            val current = request
            val values = current.inputs.mapNotNull { input ->
                val value = payload?.getString(input.key, "") ?: ""
                if (value.isEmpty()) null else input.key to value
            }.toMap()
            runCatching { onSubmit(values) }
        }

        private fun render(request: AuthPromptRequest) {
            SfpDialogPackets.show(player, DialogNbtFactory.build(request))
        }

        private fun scheduleTimeout(timeoutMillis: Long) {
            if (timeoutMillis <= 0) return
            timeoutTask = timer().schedule(
                {
                    if (closed.compareAndSet(false, true)) {
                        DialogPromptRouter.unregister(playerId, this)
                        SfpDialogPackets.clear(player)
                        runCatching { onClosed() }
                    }
                },
                timeoutMillis,
                TimeUnit.MILLISECONDS,
            )
        }
    }
}
