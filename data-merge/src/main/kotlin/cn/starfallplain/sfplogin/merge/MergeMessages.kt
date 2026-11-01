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

package cn.starfallplain.sfplogin.merge

import com.velocitypowered.api.command.CommandSource
import cn.starfallplain.sfplogin.api.message.HyperZoneMessagePlaceholder
import cn.starfallplain.sfplogin.api.message.HyperZoneMessageServiceProvider
import net.kyori.adventure.text.Component

object MergeMessages {
    private const val NAMESPACE = "data-merge"

    fun usageMl(source: CommandSource): Component = render(source, "usage.ml", "/hzl-merge ml")
    fun usageAm(source: CommandSource): Component = render(source, "usage.am", "/hzl-merge am")
    fun alreadyRunning(source: CommandSource): Component = render(source, "already-running", "迁移正在执行中，请稍后再试")
    fun startMl(source: CommandSource): Component = render(source, "start.ml", "开始执行 ML 迁移，请稍候...")
    fun startAm(source: CommandSource): Component = render(source, "start.am", "开始执行 AUTHME 迁移，请稍候...")

    fun completed(source: CommandSource, summary: String): Component {
        return render(source, "completed", "迁移完成: <summary>", HyperZoneMessagePlaceholder.text("summary", summary))
    }

    fun detailLog(source: CommandSource, fileName: String): Component {
        return render(source, "detail-log", "详细日志已输出到 <file_name>", HyperZoneMessagePlaceholder.text("file_name", fileName))
    }

    fun failed(source: CommandSource, reason: String?): Component {
        return render(
            source,
            "failed",
            "迁移失败: <reason>",
            HyperZoneMessagePlaceholder.text("reason", reason ?: "未知错误")
        )
    }

    private fun render(
        source: CommandSource,
        key: String,
        fallback: String,
        vararg placeholders: HyperZoneMessagePlaceholder
    ): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull()
        // 消息服务在键缺失时返回 "Missing message" 占位文本而不是 null，必须先用 hasKey 判断，
        // 缺键时回退到代码内置文案，兼容旧版本升级后落盘消息文件尚未补齐新键的数据目录。
        if (service != null && service.hasKey("$NAMESPACE.$key")) {
            return service.render(source, "$NAMESPACE.$key", *placeholders)
        }
        return Component.text(placeholders.fold(fallback) { acc, placeholder ->
            val replacement = when (placeholder) {
                is HyperZoneMessagePlaceholder.Text -> placeholder.value
                is HyperZoneMessagePlaceholder.ComponentValue -> placeholder.value.toString()
            }
            acc.replace("<${placeholder.name}>", replacement)
        })
    }
}

