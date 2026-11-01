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

package cn.starfallplain.sfplogin.api.password

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * 跨渠道统一的密码校验与短期会话服务。
 *
 * 三渠道（offline / yggdrasil / floodgate）的密码校验、失败计数、封锁与会话签发
 * 在改造后共用本服务，行为与离线渠道保持一致：
 * - 支持 plain / sha256 / authme 三种历史哈希格式；
 * - 连续输错达到阈值后按策略封锁；
 * - 登录成功后按策略签发短期会话，同一 IP 再进可免密自动登录。
 *
 * 渠道之间通过 [PasswordChannel] + channelKey（+ entryId）定位同一条记录，
 * 因此本服务不感知任何渠道身份细节。
 */
class ChannelPasswordService(
    private val repository: ChannelPasswordRepository = ChannelPasswordProvider.get()
) {

    /** 密码校验结果。 */
    sealed interface VerifyResult {
        /** 校验通过；[passwordId] 已清零失败计数。 */
        data class Success(val entry: ChannelPasswordEntry) : VerifyResult

        /** 玩家尚在该渠道没有任何密码记录。 */
        data object NoRecord : VerifyResult

        /** 当前处于封锁期。[remainingSeconds] 为剩余时长。 */
        data class Blocked(val remainingSeconds: Long) : VerifyResult

        /** 密码错误。[remainingAttempts] 为剩余可尝试次数（0 表示本次已触发封锁）。 */
        data class Wrong(val remainingAttempts: Int) : VerifyResult
    }

    /** 会话自动登录判定结果。 */
    sealed interface SessionResult {
        /** 会话有效，可免密放行。 */
        data object Valid : SessionResult

        /** 会话无效（过期 / IP 不匹配 / 未签发）。 */
        data object Invalid : SessionResult

        /** 该渠道当前没有任何可用的会话记录。 */
        data object NoSession : SessionResult
    }

    /**
     * 校验密码并处理失败计数/封锁。
     *
     * @param entry 统一密码表中的目标记录
     * @param rawPassword 玩家输入的明文密码
     * @param policy 当前渠道策略
     */
    /**
     * 直接从统一密码表读取记录（含会话字段）。
     *
     * 各渠道的绑定记录（entry / floodgate_auth）只保留身份与密码字段，
     * 会话信息统一存于密码表；需要判断会话时必须用本方法取真实行。
     */
    fun findEntry(
        channel: PasswordChannel,
        channelKey: String,
        entryId: String = ""
    ): ChannelPasswordEntry? = repository.find(channel, channelKey, entryId)

    fun verifyPassword(entry: ChannelPasswordEntry, rawPassword: String, policy: ChannelPasswordPolicy): VerifyResult {
        val now = System.currentTimeMillis()
        val blockedUntil = entry.loginBlockedUntil
        if (blockedUntil != null && blockedUntil > now) {
            return VerifyResult.Blocked(((blockedUntil - now) / 1000).coerceAtLeast(1))
        }

        if (!matches(rawPassword, entry.passwordHash, entry.hashFormat)) {
            val nextFailCount = entry.loginFailCount + 1
            val shouldBlock = policy.maxAttempts > 0 && nextFailCount >= policy.maxAttempts
            if (shouldBlock) {
                repository.updateLoginProtection(
                    entry.id,
                    0,
                    now + policy.blockSeconds * 1000L
                )
                return VerifyResult.Wrong(0)
            }
            repository.updateLoginProtection(entry.id, nextFailCount, null)
            return VerifyResult.Wrong((policy.maxAttempts - nextFailCount).coerceAtLeast(0))
        }

        repository.resetPasswordFailures(entry.id)
        return VerifyResult.Success(entry)
    }

    /**
     * 校验明文密码是否匹配存储值，兼容 plain / sha256 / authme 三种格式。
     */
    fun matches(rawPassword: String, storedHash: String?, storedFormat: String?): Boolean {
        if (storedHash.isNullOrBlank()) {
            return false
        }
        return when (storedFormat?.lowercase(Locale.ROOT)) {
            HASH_FORMAT_PLAIN -> rawPassword == storedHash
            HASH_FORMAT_AUTHME -> verifyAuthMe(rawPassword, storedHash)
            HASH_FORMAT_SHA256, null -> sha256Hex(rawPassword) == storedHash
            else -> sha256Hex(rawPassword) == storedHash
        }
    }

    /**
     * 按策略校验新密码是否符合规则。
     *
     * @return null 表示通过；否则返回不通过原因
     */
    fun validateNewPassword(playerName: String, rawPassword: String, policy: ChannelPasswordPolicy): ValidationError? {
        if (rawPassword.length < policy.minLength) {
            return ValidationError.TooShort(policy.minLength)
        }
        if (rawPassword.length > policy.maxLength) {
            return ValidationError.TooLong(policy.maxLength)
        }
        if (policy.denyNameInPassword &&
            rawPassword.lowercase(Locale.ROOT).contains(playerName.lowercase(Locale.ROOT))
        ) {
            return ValidationError.ContainsName(playerName)
        }
        return null
    }

    /** 新密码规则校验错误。 */
    sealed interface ValidationError {
        data class TooShort(val minLength: Int) : ValidationError
        data class TooLong(val maxLength: Int) : ValidationError
        data class ContainsName(val name: String) : ValidationError
    }

    /**
     * 校验短期会话是否仍然有效（供进服时的免密自动登录使用）。
     */
    fun checkSession(entry: ChannelPasswordEntry, policy: ChannelPasswordPolicy, currentIp: String?): SessionResult {
        if (!policy.sessionEnabled) {
            return SessionResult.Invalid
        }
        val issuedAt = entry.sessionIssuedAt ?: return SessionResult.NoSession
        val expiresAt = entry.sessionExpiresAt ?: return SessionResult.NoSession
        val now = System.currentTimeMillis()

        val invalidByTime = expiresAt <= now || issuedAt > expiresAt
        val invalidByIp = policy.sessionBindIp && !entry.sessionIp.isNullOrBlank() && entry.sessionIp != currentIp
        if (invalidByTime || invalidByIp) {
            repository.clearSession(entry.id)
            return SessionResult.Invalid
        }
        return SessionResult.Valid
    }

    /**
     * 登录成功后按策略签发短期会话。
     */
    fun issueSession(entry: ChannelPasswordEntry, policy: ChannelPasswordPolicy, currentIp: String?): Boolean {
        if (!policy.sessionEnabled) {
            repository.clearSession(entry.id)
            return false
        }
        val now = System.currentTimeMillis()
        val sessionIp = if (policy.sessionBindIp) currentIp else null
        val expiresAt = now + policy.sessionExpireMinutes.coerceAtLeast(1) * 60_000L
        return repository.issueSession(entry.id, sessionIp, now, expiresAt)
    }

    /** 清理会话。 */
    fun clearSession(entryId: Int): Boolean = repository.clearSession(entryId)

    /** 计算 SHA-256 十六进制摘要。 */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun verifyAuthMe(password: String, storedHash: String): Boolean {
        val parts = storedHash.split("$")
        if (parts.size != 4) {
            return false
        }
        val salt = parts[2]
        val expected = parts[3]
        val first = sha256Hex(password)
        val computed = sha256Hex(first + salt)
        return computed.equals(expected, ignoreCase = true)
    }

    companion object {
        const val HASH_FORMAT_PLAIN = "plain"
        const val HASH_FORMAT_SHA256 = "sha256"
        const val HASH_FORMAT_AUTHME = "authme"
    }
}

/**
 * [ChannelPasswordService] 的全局访问器。
 *
 * 由核心（velocity 模块）在启动时创建唯一实例并 [bind]；
 * 三个认证子模块通过 [get] / [getOrNull] 共享同一份校验与会话逻辑。
 */
object ChannelPasswordServiceProvider {
    @Volatile
    private var service: ChannelPasswordService? = null

    fun bind(service: ChannelPasswordService) {
        this.service = service
    }

    fun get(): ChannelPasswordService =
        service ?: error("Channel password service is not available yet")

    fun getOrNull(): ChannelPasswordService? = service
}
