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

package icu.h2l.login.qq.whitelist.store

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 一次性的绑定验证码。
 *
 * @property code 验证码（统一大写，便于玩家在群里输入）
 * @property profileId 该验证码对应的档案 ID
 * @property playerName 该验证码对应的玩家名
 * @property expiresAt 过期时间戳（毫秒）
 * @property attempts 已失败的尝试次数
 */
data class QqBindCode(
    val code: String,
    val profileId: UUID,
    val playerName: String,
    val expiresAt: Long,
    val attempts: Int = 0
)

/**
 * 绑定验证码服务：生成、校验、作废。
 *
 * 验证码只保存在内存中（服务端重启后玩家重新登录即可获得新的验证码），
 * 便于单元测试注入 [clock] 与 [codeFactory]。
 */
class QqBindCodeService(
    private val ttlMillis: Long,
    private val codeLength: Int,
    private val attemptLimit: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codeFactory: (Int) -> String = ::randomDigitsCode
) {
    /**
     * 验证码校验结果。
     */
    sealed interface VerifyResult {
        /**
         * 校验通过。
         */
        data class Ok(val code: QqBindCode) : VerifyResult

        /**
         * 验证码不存在（或已被使用）。
         */
        data object NotFound : VerifyResult

        /**
         * 验证码已过期。
         */
        data object Expired : VerifyResult

        /**
         * 失败尝试次数超过上限，验证码已作废。
         */
        data object AttemptsExceeded : VerifyResult
    }

    private val codes = ConcurrentHashMap<String, QqBindCode>()

    /**
     * 为指定档案签发验证码，并作废旧验证码。
     */
    fun issue(profileId: UUID, playerName: String): QqBindCode {
        invalidateProfile(profileId)
        val now = clock()
        var candidate: QqBindCode
        var attempt = 0
        do {
            val code = codeFactory(codeLength)
            candidate = QqBindCode(
                code = code,
                profileId = profileId,
                playerName = playerName,
                expiresAt = now + ttlMillis
            )
            attempt++
        } while (codes.containsKey(candidate.code) && attempt < 16)
        codes[candidate.code] = candidate
        return candidate
    }

    /**
     * 查询当前仍有效的验证码（不消耗次数）。
     */
    fun pending(profileId: UUID): QqBindCode? {
        val now = clock()
        return codes.values.firstOrNull { it.profileId == profileId && now < it.expiresAt }
    }

    /**
     * 校验验证码；不通过时会累加失败次数，达到上限则作废。
     */
    fun verify(rawCode: String): VerifyResult {
        val normalized = normalize(rawCode)
        val existing = codes[normalized] ?: return VerifyResult.NotFound
        if (clock() >= existing.expiresAt) {
            codes.remove(normalized)
            return VerifyResult.Expired
        }
        if (existing.attempts >= attemptLimit) {
            codes.remove(normalized)
            return VerifyResult.AttemptsExceeded
        }
        return VerifyResult.Ok(existing)
    }

    /**
     * 记录一次失败尝试（验证码内容不匹配但仍命中同一验证码时不会调用），
     * 主要供「玩家输入了错误验证码」的通用失败路径使用。
     */
    fun recordFailure(rawCode: String) {
        val normalized = normalize(rawCode)
        val existing = codes[normalized] ?: return
        val updated = existing.copy(attempts = existing.attempts + 1)
        if (updated.attempts >= attemptLimit) {
            codes.remove(normalized)
        } else {
            codes[normalized] = updated
        }
    }

    /**
     * 消费验证码（绑定成功后调用）。
     */
    fun consume(rawCode: String) {
        codes.remove(normalize(rawCode))
    }

    /**
     * 作废指定验证码。
     */
    fun invalidate(rawCode: String) {
        codes.remove(normalize(rawCode))
    }

    /**
     * 作废某个档案已签发的验证码。
     */
    fun invalidateProfile(profileId: UUID) {
        codes.entries.removeIf { it.value.profileId == profileId }
    }

    /**
     * 清理已过期的验证码，可由定时任务调用。
     */
    fun purgeExpired() {
        val now = clock()
        codes.entries.removeIf { now >= it.value.expiresAt }
    }

    companion object {
        internal fun normalize(raw: String): String = raw.trim().uppercase()

        internal fun randomDigitsCode(length: Int): String {
            val safeLength = length.coerceIn(4, 12)
            val builder = StringBuilder(safeLength)
            repeat(safeLength) {
                builder.append(('0'..'9').random())
            }
            return builder.toString()
        }
    }
}
