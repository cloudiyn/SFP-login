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

package icu.h2l.login.qq.whitelist.gate

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.db.Profile
import icu.h2l.api.profile.WaitingAreaReleaseGate
import icu.h2l.login.qq.whitelist.config.QqWhitelistConfig
import icu.h2l.login.qq.whitelist.message.QqGroupMessages
import icu.h2l.login.qq.whitelist.service.QqBindingService
import icu.h2l.login.qq.whitelist.service.QqGateOutcome
import net.kyori.adventure.text.Component

/**
 * QQ 白名单放行闸门。
 *
 * 在玩家「认证通过、正式档案已 attach、但尚未离开等待区」时裁决：
 * - 已绑定 QQ → 放行（首次绑定的登录会附带一次性提示）；
 * - 未绑定且 enforce=true → 拒绝并踢出，把验证码展示给玩家；
 * - 命中豁免（权限节点 / 配置的档案 UUID）→ 直接放行。
 */
class QqWhitelistReleaseGate(
    private val config: QqWhitelistConfig,
    private val service: QqBindingService,
    private val messages: QqGroupMessages
) : WaitingAreaReleaseGate {

    override val id: String = "qq-whitelist"

    override fun evaluate(player: Player, profile: Profile): WaitingAreaReleaseGate.Decision {
        if (isBypassed(player, profile)) {
            return WaitingAreaReleaseGate.Decision.Allow()
        }

        return when (val outcome = service.evaluate(profile)) {
            is QqGateOutcome.Allowed -> WaitingAreaReleaseGate.Decision.Allow(
                notice = outcome.newlyBoundQq?.let { qq ->
                    component(player, "player.bound-notice", "qq" to qq.toString())
                }
            )

            is QqGateOutcome.Denied -> WaitingAreaReleaseGate.Decision.Deny(
                disconnect = true,
                message = component(
                    player,
                    "player.unbound-kick",
                    "code" to outcome.code,
                    "keyword" to config.commands.bindKeyword
                )
            )
        }
    }

    private fun isBypassed(player: Player, profile: Profile): Boolean {
        if (profile.id in config.bypass.profiles) {
            return true
        }
        val permission = config.bypass.permission.trim()
        return permission.isNotEmpty() && player.hasPermission(permission)
    }

    private fun component(player: Player, key: String, vararg placeholders: Pair<String, String>): Component {
        val locale = runCatching { player.effectiveLocale?.toLanguageTag() }.getOrNull()
        return Component.text(messages.playerText(locale, key, *placeholders))
    }
}
