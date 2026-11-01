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

package cn.starfallplain.sfplogin.listener

import com.velocitypowered.api.event.Subscribe
import cn.starfallplain.sfplogin.api.event.profile.LoginProfileReplaceEvent
import cn.starfallplain.sfplogin.util.hasSemanticGameProfileDifference

/**
 * [LoginProfileReplaceEvent] 的默认处理器（PostOrder.NORMAL）。
 *
 * 比较事件中的 [LoginProfileReplaceEvent.initialProfile]（HZL 预期档案）
 * 与 Velocity 当前持有的 [com.velocitypowered.api.util.GameProfile]：
 * - 若两者语义一致，说明链路无需修补，不设置 modified，替换器将自移除；
 * - 若存在差异，将 initialProfile 设为最终档案并将 modified 置为 true。
 *
 * 认证子模块（如 auth-floodgate）可在 PostOrder.LAST 阶段覆写 [LoginProfileReplaceEvent.profile]。
 */
class LoginProfileReplaceDefaultListener {
    @Subscribe
    fun onLoginProfileReplace(event: LoginProfileReplaceEvent) {
        val velocityPlayer = event.hyperZonePlayer.getProxyPlayerOrNull() ?: return
        val currentVcProfile = velocityPlayer.gameProfile
        val expectedProfile = event.initialProfile

        if (!hasSemanticGameProfileDifference(expectedProfile, currentVcProfile)) {
            // 档案已经与预期一致，无需替换
            return
        }

        event.profile = expectedProfile
        event.modified = true
    }
}

