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

package cn.starfallplain.sfplogin.api.profile

import cn.starfallplain.sfplogin.api.player.HyperZonePlayer

/**
 * 凭证提交的阶段。
 *
 * 一次完整的"提交并链接"必然经过 [SUBMITTED] 与 [LINKED]（或 [REJECTED]）：
 * - [SUBMITTED]：凭证只是登记在玩家会话上，尚未消费；
 * - [LINKED]：核心已消费该凭证并成功把档案 attach 到玩家；
 * - [REJECTED]：链接失败，且玩家凭证状态已回滚到提交之前。
 */
enum class CredentialLinkStage {
    SUBMITTED,
    LINKED,
    REJECTED,
}

/**
 * [HyperZoneCredentialFlow.submitAndLink] 的结果。
 *
 * @property stage 本次提交到达的阶段
 * @property attachedProfileId 仅 [CredentialLinkStage.LINKED] 时非空：本次链接到的档案 ID
 * @property reason 仅 [CredentialLinkStage.REJECTED] 时非空：失败原因
 */
data class CredentialLinkResult(
    val stage: CredentialLinkStage,
    val attachedProfileId: java.util.UUID? = null,
    val reason: String? = null,
) {
    val isLinked: Boolean
        get() = stage == CredentialLinkStage.LINKED
}

interface HyperZoneCredentialFlow {
    /**
     * 只登记凭证（[CredentialLinkStage.SUBMITTED]），不触发验证。
     *
     * 仅在需要"先提交、稍后统一验证"的场景使用；普通登录流程请用 [submitAndLink]。
     */
    fun submitCredential(player: HyperZonePlayer, credential: HyperZoneCredential)

    fun replaceCredential(player: HyperZonePlayer, oldChannelId: String, credential: HyperZoneCredential)

    fun getSubmittedCredentials(player: HyperZonePlayer): List<HyperZoneCredential>

    fun clearCredentials(player: HyperZonePlayer)

    /**
     * 消费玩家当前登记的凭证并尝试 attach 档案。
     *
     * @return 成功时为已 attach 的档案 ID；失败（没有可消费的凭证、档案不存在等）时为 `null`
     */
    fun overVerify(player: HyperZonePlayer): java.util.UUID?

    /**
     * 显式"提交并链接"：登记凭证（SUBMITTED）→ 消费并 attach（LINKED）。
     *
     * 任一步失败都会把玩家会话的凭证状态回滚到本次调用之前，并返回
     * [CredentialLinkStage.REJECTED]，调用方据此决定如何提示玩家——认证失败不应
     * 留下半提交的凭证，否则后续流程会把玩家当成已认证。
     */
    fun submitAndLink(player: HyperZonePlayer, credential: HyperZoneCredential): CredentialLinkResult {
        val previous = getSubmittedCredentials(player)
        submitCredential(player, credential)
        val attachedProfileId = runCatching { overVerify(player) }.getOrElse { throwable ->
            restoreCredentials(player, previous)
            return CredentialLinkResult(CredentialLinkStage.REJECTED, reason = throwable.message)
        }
        if (attachedProfileId == null) {
            restoreCredentials(player, previous)
            return CredentialLinkResult(CredentialLinkStage.REJECTED, reason = "profile attach failed")
        }
        return CredentialLinkResult(CredentialLinkStage.LINKED, attachedProfileId = attachedProfileId)
    }

    /**
     * 把玩家会话的凭证恢复为 [credential] 列表（空列表表示清空）。
     *
     * 回滚语义：先清空再依次重新登记，保证不会残留本次失败提交的凭证。
     */
    fun restoreCredentials(player: HyperZonePlayer, credentials: List<HyperZoneCredential>) {
        clearCredentials(player)
        credentials.forEach { submitCredential(player, it) }
    }

    @Suppress("unused")
    fun resetVerify(player: HyperZonePlayer)
}

object HyperZoneCredentialFlowProvider {
    @Volatile
    private var flow: HyperZoneCredentialFlow? = null

    fun bind(flow: HyperZoneCredentialFlow) {
        this.flow = flow
    }

    /**
     * 解除绑定（插件卸载时调用），之后 [getOrNull] 返回 `null`。
     */
    fun unbind() {
        flow = null
    }

    fun get(): HyperZoneCredentialFlow =
        flow ?: error("HyperZone credential flow is not available yet")

    fun getOrNull(): HyperZoneCredentialFlow? = flow
}

