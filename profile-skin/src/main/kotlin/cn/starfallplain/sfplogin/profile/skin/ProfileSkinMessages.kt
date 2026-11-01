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

package cn.starfallplain.sfplogin.profile.skin

import cn.starfallplain.sfplogin.api.message.HyperZoneMessageServiceProvider
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import net.kyori.adventure.text.Component

object ProfileSkinMessages {
    private const val NAMESPACE = "profile-skin"

    fun processing(player: HyperZonePlayer): Component {
        return render(player, "processing", "正在处理皮肤信息，请稍候…")
    }

    fun ready(player: HyperZonePlayer): Component {
        return render(player, "ready", "皮肤信息处理完成，进入游戏后将自动应用。")
    }

    fun readyWithFallback(player: HyperZonePlayer): Component {
        return render(player, "ready-with-fallback", "皮肤信息处理完成，但使用了回退结果，显示可能不稳定。")
    }

    fun noSkin(player: HyperZonePlayer): Component {
        return render(player, "no-skin", "未检测到可用皮肤信息，将使用默认外观。")
    }

    fun repairFailed(player: HyperZonePlayer): Component {
        return render(player, "repair-failed", "皮肤修复失败，需要重新进入游戏")
    }

    private fun render(player: HyperZonePlayer, key: String, fallback: String): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        // 消息服务在键缺失时返回 "Missing message" 占位文本而不是 null，必须先用 hasKey 判断，
        // 缺键时回退到代码内置文案，兼容旧版本升级后落盘消息文件尚未补齐新键的数据目录。
        if (service != null && service.hasKey("$NAMESPACE.$key")) {
            return service.render(player, "$NAMESPACE.$key")
        }
        return Component.text(fallback)
    }
}

