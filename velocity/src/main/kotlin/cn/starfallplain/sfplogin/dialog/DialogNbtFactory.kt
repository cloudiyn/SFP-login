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

import cn.starfallplain.sfplogin.api.auth.AuthPromptRequest
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag

/**
 * 把 [AuthPromptRequest] 编译成 Minecraft dialog NBT。
 *
 * 结构参考 Minecraft 1.21.6 起的 dialog 数据格式：
 * - 根节点 `type = minecraft:multi_action`
 * - `body` 为 `plain_message` 列表
 * - `inputs` 为 `minecraft:text` 列表，提交时客户端按 `key` 回传
 * - `actions` 只有一个按钮，动作是 `dynamic/custom`，回传 payload 走
 *   `custom_click_action` 包（见 [SfpDialogPackets] 与 [DialogCustomAction]）
 */
object DialogNbtFactory {

    /** 自定义动作的命名空间 ID，客户端会把输入值合并到该 payload 回传。 */
    const val ACTION_CHANNEL_ID = "hzl:auth"

    private const val DEFAULT_WIDTH = 200
    private const val ERROR_COLOR = "red"

    /**
     * 构造 dialog 根 NBT。
     */
    fun build(request: AuthPromptRequest): CompoundBinaryTag {
        val bodyLines = buildList {
            addAll(request.bodyLines)
            request.errorLine?.let { add(it) }
        }
        val errorLineIndex = request.errorLine?.let { request.bodyLines.size }
        return CompoundBinaryTag.builder()
            .putString("type", "minecraft:multi_action")
            .put("title", textComponent(request.title))
            .put("body", plainMessageList(bodyLines, errorLineIndex))
            .put("inputs", textInputList(request))
            .put("actions", actionList(request))
            .putBoolean("can_close_with_escape", true)
            .putString("after_action", "close")
            .build()
    }

    private fun plainMessageList(lines: List<String>, errorLineIndex: Int?): ListBinaryTag {
        val list = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
        lines.forEachIndexed { index, line ->
            val message = CompoundBinaryTag.builder()
                .putString("type", "minecraft:plain_message")
                .put("contents", textComponent(line, error = index == errorLineIndex))
                .putInt("width", DEFAULT_WIDTH)
                .build()
            list.add(message)
        }
        return list.build()
    }

    private fun textInputList(request: AuthPromptRequest): ListBinaryTag {
        val list = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
        request.inputs.forEach { input ->
            list.add(
                CompoundBinaryTag.builder()
                    .putString("type", "minecraft:text")
                    .putString("key", input.key)
                    .put("label", textComponent(input.label))
                    .putInt("width", DEFAULT_WIDTH)
                    .putInt("max_length", input.maxLength)
                    .build(),
            )
        }
        return list.build()
    }

    private fun actionList(request: AuthPromptRequest): ListBinaryTag {
        val action = CompoundBinaryTag.builder()
            .putString("type", "dynamic/custom")
            .putString("id", ACTION_CHANNEL_ID)
            .put(
                "additions",
                CompoundBinaryTag.builder()
                    .putString("mode", request.mode.name)
                    .build(),
            )
            .build()
        val button = CompoundBinaryTag.builder()
            .put("label", textComponent(request.submitLabel))
            .putInt("width", DEFAULT_WIDTH)
            .put("action", action)
            .build()
        return ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(button).build()
    }

    private fun textComponent(text: String, error: Boolean = false): CompoundBinaryTag =
        CompoundBinaryTag.builder()
            .putString("text", text)
            .apply { if (error) putString("color", ERROR_COLOR) }
            .build()
}
