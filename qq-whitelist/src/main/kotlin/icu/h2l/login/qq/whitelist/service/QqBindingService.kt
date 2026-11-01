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

package icu.h2l.login.qq.whitelist.service

import icu.h2l.api.db.Profile
import icu.h2l.login.qq.whitelist.config.QqWhitelistConfig
import icu.h2l.login.qq.whitelist.message.QqGroupMessages
import icu.h2l.login.qq.whitelist.store.QqBindCodeService
import icu.h2l.login.qq.whitelist.store.QqBinding
import icu.h2l.login.qq.whitelist.store.QqBindingStore
import java.util.UUID

/**
 * 等待区放行闸门的裁决结果。
 */
sealed interface QqGateOutcome {
    /**
     * 放行；[newlyBoundQq] 非空表示本次登录需要提示「已完成 QQ 绑定」。
     */
    data class Allowed(val newlyBoundQq: Long? = null) : QqGateOutcome

    /**
     * 拒绝放行，并携带玩家需要发到群里的验证码。
     */
    data class Denied(val code: String) : QqGateOutcome
}

/**
 * 解绑结果。
 */
sealed interface QqUnbindOutcome {
    data class Ok(val binding: QqBinding) : QqUnbindOutcome
    data class NotFound(val playerName: String?) : QqUnbindOutcome
}

/**
 * QQ 绑定白名单业务服务。
 *
 * 该层不依赖 Velocity / Adventure，便于单元测试：
 * 群消息通过 [sender] 发出，OneBot 连接状态通过 [onebotAvailable] 查询。
 */
class QqBindingService(
    private val config: QqWhitelistConfig,
    private val store: QqBindingStore,
    private val codes: QqBindCodeService,
    private val messages: QqGroupMessages,
    private val onebotAvailable: () -> Boolean,
    private val sender: (groupId: Long, text: String) -> Unit,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()

    /**
     * 处理一条群消息；只有配置内的群、且命中命令关键字时才会响应。
     */
    fun handleGroupMessage(groupId: Long, qqId: Long, rawMessage: String) {
        if (config.groups.isEmpty()) return
        if (groupId !in config.groups) return

        val text = rawMessage.trim()
        if (text.isEmpty()) return

        // 先匹配更长的「绑定查询」，避免被「绑定」前缀抢占
        if (text.startsWith(config.commands.statusKeyword)) {
            replyStatus(groupId, qqId)
            return
        }
        if (text.startsWith(config.commands.bindKeyword)) {
            val code = text.removePrefix(config.commands.bindKeyword).trim()
            if (code.isEmpty()) {
                send(groupId, qqId, messages.text("group.usage.bind", "keyword" to config.commands.bindKeyword))
                return
            }
            bind(groupId, qqId, code)
            return
        }
        if (text.startsWith(config.commands.unbindKeyword)) {
            val name = text.removePrefix(config.commands.unbindKeyword).trim()
            if (name.isEmpty()) {
                send(groupId, qqId, messages.text("group.usage.unbind", "keyword" to config.commands.unbindKeyword))
                return
            }
            replyUnbind(groupId, qqId, unbindByQqAndName(qqId, name))
        }
    }

    /**
     * 为即将被拦截的玩家签发验证码。
     */
    fun issueCode(profileId: UUID, playerName: String): String {
        return codes.issue(profileId, playerName).code
    }

    /**
     * 闸门裁决：已绑定直接放行；未绑定且开启强制拦截时签发验证码并拒绝。
     */
    fun evaluate(profile: Profile): QqGateOutcome {
        val binding = store.findByProfile(profile.id)
        if (binding != null) {
            if (config.binding.notifyOnceOnLogin && binding.notifyPending) {
                store.markNotified(profile.id)
                return QqGateOutcome.Allowed(newlyBoundQq = binding.qqId)
            }
            return QqGateOutcome.Allowed()
        }
        if (!config.enforce) {
            log("玩家 ${profile.name} 未绑定 QQ，但 enforce=false，已放行")
            return QqGateOutcome.Allowed()
        }
        if (!onebotAvailable()) {
            if (config.onebot.failOpenWhenDisconnected) {
                log("OneBot 未连接且 fail-open-when-disconnected=true，临时放行玩家 ${profile.name}")
                return QqGateOutcome.Allowed()
            }
            log("OneBot 未连接且 fail-open-when-disconnected=false，拒绝玩家 ${profile.name}")
            return QqGateOutcome.Denied(pendingOrIssueCode(profile))
        }
        val code = pendingOrIssueCode(profile)
        log("玩家 ${profile.name} 未绑定 QQ，已签发验证码并拦截")
        return QqGateOutcome.Denied(code)
    }

    /**
     * 复用仍然有效的验证码，避免核心层重复询问闸门时不断更换验证码。
     */
    private fun pendingOrIssueCode(profile: Profile): String {
        codes.pending(profile.id)?.let { return it.code }
        return issueCode(profile.id, profile.name)
    }

    /**
     * 查询某个 QQ 的全部绑定。
     */
    fun bindingsOf(qqId: Long): List<QqBinding> = store.findByQq(qqId)

    /**
     * 查询某个档案的绑定。
     */
    fun bindingOf(profileId: UUID): QqBinding? = store.findByProfile(profileId)

    /**
     * 群内自助解绑：只能解绑该 QQ 自己绑定过的账号。
     */
    fun unbindByQqAndName(qqId: Long, playerName: String): QqUnbindOutcome {
        synchronized(lock) {
            val target = store.findByQq(qqId).firstOrNull { it.playerName.equals(playerName, ignoreCase = true) }
                ?: return QqUnbindOutcome.NotFound(playerName)
            return if (store.deleteByProfile(target.profileId)) {
                QqUnbindOutcome.Ok(target)
            } else {
                QqUnbindOutcome.NotFound(playerName)
            }
        }
    }

    /**
     * 按档案解绑（游戏内自助解绑 / 管理员强制解绑）。
     */
    fun unbindByProfile(profileId: UUID): QqUnbindOutcome {
        synchronized(lock) {
            val removed = store.removeByProfile(profileId) ?: return QqUnbindOutcome.NotFound(null)
            return QqUnbindOutcome.Ok(removed)
        }
    }

    /**
     * 按玩家名解绑（管理员强制解绑）。
     */
    fun adminUnbindByName(playerName: String): QqUnbindOutcome {
        synchronized(lock) {
            val target = store.findByPlayerName(playerName) ?: return QqUnbindOutcome.NotFound(playerName)
            return if (store.deleteByProfile(target.profileId)) {
                QqUnbindOutcome.Ok(target)
            } else {
                QqUnbindOutcome.NotFound(playerName)
            }
        }
    }

    private fun bind(groupId: Long, qqId: Long, rawCode: String) {
        val verify = codes.verify(rawCode)
        when (verify) {
            is QqBindCodeService.VerifyResult.NotFound -> {
                send(groupId, qqId, messages.text("group.code-not-found"))
                return
            }
            is QqBindCodeService.VerifyResult.Expired -> {
                send(groupId, qqId, messages.text("group.code-expired"))
                return
            }
            is QqBindCodeService.VerifyResult.AttemptsExceeded -> {
                send(groupId, qqId, messages.text("group.code-attempts"))
                return
            }
            is QqBindCodeService.VerifyResult.Ok -> Unit
        }
        val entry = verify.code
        synchronized(lock) {
            val existing = store.findByProfile(entry.profileId)
            if (existing != null) {
                codes.consume(entry.code)
                if (existing.qqId == qqId) {
                    send(groupId, qqId, messages.text("group.already-bound", "player" to existing.playerName))
                } else {
                    send(groupId, qqId, messages.text("group.profile-conflict", "player" to existing.playerName))
                }
                return
            }
            val count = store.countByQq(qqId)
            if (count >= config.binding.maxPerQq) {
                send(groupId, qqId, messages.text("group.limit-reached", "max" to config.binding.maxPerQq.toString()))
                return
            }
            val binding = QqBinding(
                id = UUID.randomUUID(),
                qqId = qqId,
                profileId = entry.profileId,
                playerName = entry.playerName,
                boundAt = clock(),
                notifyPending = true
            )
            val inserted = store.insert(binding)
            codes.consume(entry.code)
            if (inserted) {
                log("QQ $qqId 绑定档案 ${entry.playerName}(${entry.profileId}) 成功")
                send(
                    groupId,
                    qqId,
                    messages.text(
                        "group.bind-success",
                        "player" to entry.playerName,
                        "qq" to qqId.toString(),
                        "count" to (count + 1).toString(),
                        "max" to config.binding.maxPerQq.toString()
                    )
                )
            } else {
                send(groupId, qqId, messages.text("group.profile-conflict", "player" to entry.playerName))
            }
        }
    }

    private fun replyStatus(groupId: Long, qqId: Long) {
        val bindings = store.findByQq(qqId)
        if (bindings.isEmpty()) {
            send(groupId, qqId, messages.text("group.status-empty"))
            return
        }
        send(groupId, qqId, messages.text("group.status-header", "count" to bindings.size.toString()))
        bindings.forEach { binding ->
            send(
                groupId,
                qqId,
                messages.text(
                    "group.status-line",
                    "player" to binding.playerName,
                    "qq" to binding.qqId.toString()
                )
            )
        }
    }

    private fun replyUnbind(groupId: Long, qqId: Long, outcome: QqUnbindOutcome) {
        when (outcome) {
            is QqUnbindOutcome.Ok -> send(
                groupId,
                qqId,
                messages.text("group.unbind-success", "player" to outcome.binding.playerName)
            )
            is QqUnbindOutcome.NotFound -> send(
                groupId,
                qqId,
                messages.text("group.unbind-not-found", "player" to (outcome.playerName ?: ""))
            )
        }
    }

    private fun send(groupId: Long, qqId: Long, text: String) {
        val prefix = if (config.commands.replyAtSender) "[CQ:at,qq=$qqId] " else ""
        sender(groupId, prefix + text)
    }
}
