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

package icu.h2l.login.profile

import com.velocitypowered.api.proxy.Player
import icu.h2l.api.db.Profile
import icu.h2l.api.log.warn
import icu.h2l.api.profile.WaitingAreaReleaseGate
import icu.h2l.api.profile.WaitingAreaReleaseGateRegistry
import net.kyori.adventure.text.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 核心层默认的等待区放行闸门注册表实现。
 *
 * 闸门按注册顺序依次裁决，先到的 [WaitingAreaReleaseGate.Decision.Deny] 直接生效；
 * 单个闸门抛出的异常不会中断流程，仅记录警告并按放行处理。
 */
class WaitingAreaReleaseGateRegistryImpl : WaitingAreaReleaseGateRegistry {
    private val lock = Any()
    private val gates = mutableListOf<WaitingAreaReleaseGate>()
    private val registeredIds = ConcurrentHashMap.newKeySet<String>()

    override fun register(gate: WaitingAreaReleaseGate) {
        synchronized(lock) {
            if (!registeredIds.add(gate.id)) {
                gates.removeAll { it.id == gate.id }
            }
            gates += gate
        }
    }

    override fun unregister(id: String) {
        synchronized(lock) {
            registeredIds.remove(id)
            gates.removeAll { it.id == id }
        }
    }

    override fun getGates(): List<WaitingAreaReleaseGate> = synchronized(lock) { gates.toList() }

    override fun evaluate(player: Player, profile: Profile): WaitingAreaReleaseGate.Decision {
        var notice: Component? = null
        val snapshot = getGates()
        for (gate in snapshot) {
            val decision = try {
                gate.evaluate(player, profile)
            } catch (throwable: Throwable) {
                warn {
                    "等待区放行闸门 ${gate.id} 裁决异常，已按放行处理：${throwable.message}"
                }
                WaitingAreaReleaseGate.Decision.Allow()
            }
            when (decision) {
                is WaitingAreaReleaseGate.Decision.Deny -> return decision
                is WaitingAreaReleaseGate.Decision.Allow -> {
                    if (notice == null) {
                        notice = decision.notice
                    }
                }
            }
        }
        return WaitingAreaReleaseGate.Decision.Allow(notice)
    }
}
