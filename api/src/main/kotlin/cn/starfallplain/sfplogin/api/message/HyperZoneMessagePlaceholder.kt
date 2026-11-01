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

package cn.starfallplain.sfplogin.api.message

import net.kyori.adventure.text.Component

/**
 * 消息模板渲染时可传入的占位符值。
 */
sealed interface HyperZoneMessagePlaceholder {
    /**
     * 模板中使用的占位符名称。
     */
    val name: String

    /**
     * 以纯文本形式替换模板占位符的值。
     *
     * @property value 最终写入模板的字符串值
     */
    data class Text(
        override val name: String,
        val value: String
    ) : HyperZoneMessagePlaceholder

    /**
     * 以 Adventure [Component] 形式替换模板占位符的值。
     *
     * @property value 最终写入模板的富文本值
     */
    data class ComponentValue(
        override val name: String,
        val value: Component
    ) : HyperZoneMessagePlaceholder

    companion object {
        /**
         * 创建一个文本型占位符。
         */
        fun text(name: String, value: Any?): HyperZoneMessagePlaceholder {
            return Text(name, value?.toString() ?: "")
        }

        /**
         * 创建一个组件型占位符。
         */
        fun component(name: String, value: Component): HyperZoneMessagePlaceholder {
            return ComponentValue(name, value)
        }
    }
}

