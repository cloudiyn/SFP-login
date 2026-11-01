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

package cn.starfallplain.sfplogin.api.event.profile

import com.velocitypowered.api.event.annotation.AwaitingEvent
import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.util.GameProfile

/**
 * 在代理层收到客户端初始 GameProfile 后触发。
 *
 * 监听器可将 [pass] 设为 true，表示该初始档案已被外部模块确认可信，
 * 主插件随后将跳过自己的 remap 前缀/UUID 校验流程。
 *
 * @property connection 当前入站连接
 * @property gameProfile 客户端上报的初始档案
 */
@AwaitingEvent
class VerifyInitialGameProfileEvent(
    val connection: InboundConnection,
    val gameProfile: GameProfile,
) {
    /**
     * 是否允许跳过核心对初始档案的 remap 校验。
     */
    var pass: Boolean = false
}

