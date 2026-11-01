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

package cn.starfallplain.sfplogin.vServer.backend.compat

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import cn.starfallplain.sfplogin.api.event.profile.LoginProfileReplaceEvent
import cn.starfallplain.sfplogin.SFPLoginMain

/**
 * Backend 模式专用：处理目标服为登录服（fallbackAuthServer）时的档案替换。
 *
 * 在 [PostOrder.LATE] 阶段运行，晚于默认监听器（[PostOrder.NORMAL]），
 * 以确保在 NORMAL 阶段设置了正式档案之后再将其覆写为临时档案。
 *
 * 对于 Floodgate 渠道玩家，[PostOrder.LAST] 的 FloodgateLoginProfileReplaceListener
 * 会在此之后进行最终覆写，无需此处特殊处理。
 */
class BackendLoginProfileReplaceListener {

    @Subscribe(order = PostOrder.LATE)
    fun onLoginProfileReplace(event: LoginProfileReplaceEvent) {
        if (!isEnabled()) return
        if (!isLoginServerTarget(event.targetServerName)) return

        val hyperPlayer = event.hyperZonePlayer
        val tempProfile = hyperPlayer.getTemporaryGameProfile()
        event.profile = tempProfile
        event.modified = true
    }

    private fun isLoginServerTarget(targetServerName: String): Boolean {
        val loginServerName = SFPLoginMain.getCoreConfig().vServer.backend.fallbackAuthServer.trim()
        if (loginServerName.isBlank()) return false
        return targetServerName.equals(loginServerName, ignoreCase = true)
    }

    private fun isEnabled(): Boolean {
        return SFPLoginMain.getInstance().serverAdapter?.needsBackendInitialProfileCompat() == true
    }
}

