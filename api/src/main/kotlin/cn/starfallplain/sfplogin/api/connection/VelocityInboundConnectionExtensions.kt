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

package cn.starfallplain.sfplogin.api.connection

import com.velocitypowered.api.proxy.InboundConnection
import io.netty.channel.Channel
import net.kyori.adventure.text.Component
import java.lang.reflect.Field
import java.lang.reflect.Method

// ── 通过 Class.forName 懒加载 velocityProxy 内部类 ────────────────────────────
// 运行时这些类必定存在（插件跑在 Velocity 进程内）；但 api 模块编译期不依赖 velocityProxy。

private val initialInboundConnectionClass: Class<*>? by lazy {
    runCatching { Class.forName("com.velocitypowered.proxy.connection.client.InitialInboundConnection") }.getOrNull()
}

private val loginInboundConnectionClass: Class<*>? by lazy {
    runCatching { Class.forName("com.velocitypowered.proxy.connection.client.LoginInboundConnection") }.getOrNull()
}

/** LoginInboundConnection.delegate 字段（持有 InitialInboundConnection） */
private val loginInboundDelegateField: Field? by lazy {
    loginInboundConnectionClass?.let { clazz ->
        runCatching {
            clazz.getDeclaredField("delegate").also { it.isAccessible = true }
        }.getOrNull()
    }
}

/** InitialInboundConnection/delegate.getConnection() → MinecraftConnection */
private val delegateGetConnectionMethod: Method? by lazy {
    loginInboundDelegateField?.type?.let { delegateType ->
        runCatching {
            delegateType.getDeclaredMethod("getConnection").also { it.isAccessible = true }
        }.getOrNull()
    }
}

// ── 获取 MinecraftConnection.channel ──────────────────────────────────────────

private fun getMinecraftConnectionChannel(mcConnection: Any): Channel {
    // 优先：按类型扫描字段
    val field = generateSequence<Class<*>>(mcConnection.javaClass) { it.superclass }
        .firstNotNullOfOrNull { cls ->
            cls.declaredFields.firstOrNull { Channel::class.java.isAssignableFrom(it.type) }
                ?.also { it.isAccessible = true }
        }
    if (field != null) return field.get(mcConnection) as Channel
    // 回退：方法
    return mcConnection.javaClass.getMethod("channel").invoke(mcConnection) as Channel
}

// ── 公开扩展 API ───────────────────────────────────────────────────────────────

/**
 * 向当前入站连接发送断开消息，兼容 Velocity 不同登录阶段的连接实现。
 */
fun InboundConnection.disconnectWithMessage(userMessage: Component) {
    // disconnect(Component) 是内部类方法，通过反射调用
    fun Any.reflectDisconnect() =
        javaClass.getMethod("disconnect", Component::class.java).invoke(this, userMessage)

    when {
        initialInboundConnectionClass?.isInstance(this) == true ->
            reflectDisconnect()

        loginInboundConnectionClass?.isInstance(this) == true -> {
            val delegate = loginInboundDelegateField?.get(this)
                ?: throw IllegalStateException("Cannot get delegate from LoginInboundConnection instance ${javaClass.name}")
            delegate.reflectDisconnect()
        }

        else -> throw IllegalStateException("未知InboundConnection类型 ${javaClass.name}")
    }
}

/**
 * 获取当前入站连接底层使用的 Netty [Channel]。
 */
fun InboundConnection.getNettyChannel(): Channel {
    when {
        initialInboundConnectionClass?.isInstance(this) == true -> {
            // InitialInboundConnection 有 getConnection() 返回 MinecraftConnection
            val mc = generateSequence<Class<*>>(javaClass) { it.superclass }
                .firstNotNullOfOrNull { cls ->
                    runCatching { cls.getDeclaredMethod("getConnection").also { it.isAccessible = true } }.getOrNull()
                }?.invoke(this)
                ?: throw IllegalStateException("Cannot get MinecraftConnection from ${javaClass.name}")
            return getMinecraftConnectionChannel(mc)
        }

        loginInboundConnectionClass?.isInstance(this) == true -> {
            val delegate = loginInboundDelegateField?.get(this)
                ?: throw IllegalStateException("Cannot get delegate field from ${javaClass.name}")
            val mc = delegateGetConnectionMethod?.invoke(delegate)
                ?: throw IllegalStateException("Cannot invoke getConnection on ${delegate.javaClass.name}")
            return getMinecraftConnectionChannel(mc)
        }

        else -> throw IllegalStateException("未知InboundConnection类型 ${javaClass.name}")
    }
}