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

package cn.starfallplain.sfplogin.dialog

import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.proxy.protocol.ProtocolUtils
import io.netty.buffer.ByteBuf
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag

/**
 * 客户端点击 dialog 内 `dynamic/custom` 动作后回传的数据。
 *
 * @property channelId 动作的命名空间 ID
 * @property payload 合并了输入控件取值与 `additions` 的 NBT；无 payload 时为 null
 */
data class DialogCustomAction(
    val channelId: String,
    val payload: CompoundBinaryTag?,
)

/**
 * `custom_click_action`（服务端接收）包体解析。
 *
 * 包体结构：`string id` + `option<anonymousNbt> payload`。
 * 解析过程在 [ByteBuf] 的副本上进行，不会移动原 buffer 的读指针。
 */
object DialogCustomActionCodec {

    /**
     * 尝试把包体解析成自定义点击动作。
     *
     * @return 结构不完整或尾部有多余字节时返回 null
     */
    fun decode(body: ByteBuf, version: ProtocolVersion): DialogCustomAction? {
        val dup = body.duplicate()
        return runCatching {
            if (!dup.isReadable) return null
            val channelId = ProtocolUtils.readString(dup)
            val hasPayload = dup.readBoolean()
            val payload = if (hasPayload) {
                ProtocolUtils.readBinaryTag(dup, version, BinaryTagIO.reader())
            } else {
                null
            }
            // 结构必须恰好消费完，避免把其它未知包误判成我们的动作
            if (dup.isReadable) return null
            DialogCustomAction(
                channelId = channelId,
                payload = payload as? CompoundBinaryTag,
            )
        }.getOrNull()
    }
}
