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

package icu.h2l.login.auth.floodgate

import icu.h2l.api.message.HyperZoneMessagePlaceholder
import icu.h2l.api.message.HyperZoneMessageServiceProvider
import icu.h2l.api.player.HyperZonePlayer
import net.kyori.adventure.text.Component

object FloodgateMessages {
    private const val NAMESPACE = "auth-floodgate"

    fun initPlayerFailed(): Component {
        return render("init-player-failed", "Floodgate 登录失败：登录对象初始化失败。")
    }


    fun registrationDisabled(player: HyperZonePlayer): Component {
        return render(
            player,
            "registration-disabled",
            "Floodgate 已完成可信认证，但当前渠道已被管理员禁止新玩家注册。若你已有档案，请使用 /bindcode use [绑定码]。"
        )
    }

    fun registrationDisabledByName(playerName: String): Component {
        return render(
            "registration-disabled",
            "Floodgate 已完成可信认证，但当前渠道已被管理员禁止新玩家注册。若你已有档案，请使用 /bindcode use [绑定码]。",
            HyperZoneMessagePlaceholder.text("player", playerName)
        )
    }

    fun createBlocked(player: HyperZonePlayer): Component {
        return render(
            player,
            "create-blocked",
            "Floodgate 已完成可信认证，但当前注册名无法创建 Profile。若当前是用户名冲突，请修改游戏用户名后再试；若要绑定已有档案，请使用 /bindcode use [绑定码]；若没有可用绑定码，请联系管理员。"
        )
    }

    fun createBlockedByName(playerName: String): Component {
        return render(
            "create-blocked",
            "Floodgate 已完成可信认证，但当前注册名无法创建 Profile。若当前是用户名冲突，请修改游戏用户名后再试；若要绑定已有档案，请使用 /bindcode use [绑定码]；若没有可用绑定码，请联系管理员。",
            HyperZoneMessagePlaceholder.text("player", playerName)
        )
    }

    fun completeFailed(player: HyperZonePlayer): Component {
        return render(player, "complete-failed", "Floodgate 登录失败：认证完成阶段出错。")
    }

    fun completeFailedByName(playerName: String): Component {
        return render(
            "complete-failed",
            "Floodgate 登录失败：认证完成阶段出错。",
            HyperZoneMessagePlaceholder.text("player", playerName)
        )
    }

    fun genericDisconnect(): Component {
        return render("generic-disconnect", "Floodgate 登录失败。")
    }

    private fun render(
        key: String,
        fallback: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        // 消息服务在键缺失时返回 "Missing message" 占位文本而不是 null，必须先用 hasKey 判断，
        // 缺键时回退到代码内置文案，兼容旧版本升级后落盘消息文件尚未补齐新键的数据目录。
        if (service != null && service.hasKey("$NAMESPACE.$key")) {
            return service.render("$NAMESPACE.$key", *placeholders)
        }
        return Component.text(placeholders.fold(fallback) { acc, placeholder ->
            val replacement = when (placeholder) {
                is HyperZoneMessagePlaceholder.Text -> placeholder.value
                is HyperZoneMessagePlaceholder.ComponentValue -> placeholder.value.toString()
            }
            acc.replace("<${placeholder.name}>", replacement)
        })
    }

    private fun render(
        player: HyperZonePlayer,
        key: String,
        fallback: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        if (service != null && service.hasKey("$NAMESPACE.$key")) {
            return service.render(player, "$NAMESPACE.$key", *placeholders)
        }
        return render(key, fallback, *placeholders)
    }
}

