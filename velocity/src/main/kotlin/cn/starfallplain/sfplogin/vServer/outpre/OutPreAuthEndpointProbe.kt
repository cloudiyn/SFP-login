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

package cn.starfallplain.sfplogin.vServer.outpre

import java.net.InetSocketAddress
import java.net.Socket

/**
 * 探测 outpre 认证服直连地址是否可达。
 *
 * 只用于启动时给出 WARN 提示，不阻塞启动：认证服晚于代理启动是常见部署顺序。
 */
object OutPreAuthEndpointProbe {

    const val DEFAULT_TIMEOUT_MILLIS = 2_000

    fun isReachable(address: InetSocketAddress, timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): Boolean {
        if (address.port !in 1..65535) {
            return false
        }
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address.hostString, address.port), timeoutMillis)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
