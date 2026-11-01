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

package cn.starfallplain.sfplogin.reflect

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 模糊反射查找工具。
 *
 * 提供：
 * - 字段按候选名顺序查找（[fieldByNames]）
 * - 字段按类型查找（[fieldByType]）
 * - 方法按候选名顺序查找（[methodByNames]）
 * - 构造函数按精确签名顺序查找，首个成功者返回（[constructorByParamCandidates]）
 * - 构造函数按必须出现的类型集合模糊匹配（[constructorFuzzy]）
 *
 * 所有返回的成员均已调用 [java.lang.reflect.AccessibleObject.isAccessible] = true。
 */
object FuzzyLookup {

    // ──────────────────────────────────────────────────────────────────────────
    // Field
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 按候选名顺序在 [clazz] 及其父类中查找字段，返回第一个找到的字段（已设置可访问）。
     * @throws NoSuchFieldException 所有候选名均未找到
     */
    fun fieldByNames(clazz: Class<*>, vararg names: String): Field {
        var cur: Class<*>? = clazz
        while (cur != null) {
            for (name in names) {
                runCatching { cur.getDeclaredField(name) }
                    .getOrNull()
                    ?.also { it.isAccessible = true; return it }
            }
            cur = cur.superclass
        }
        throw NoSuchFieldException(
            "None of ${names.toList()} found in ${clazz.name} hierarchy"
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Method
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 按候选名顺序查找具有指定参数签名的方法。
     * @throws NoSuchMethodException 所有候选名均未找到
     */
    fun methodByNames(clazz: Class<*>, paramTypes: Array<Class<*>>, vararg names: String): Method {
        var cur: Class<*>? = clazz
        while (cur != null) {
            for (name in names) {
                runCatching { cur.getDeclaredMethod(name, *paramTypes) }
                    .getOrNull()
                    ?.also { it.isAccessible = true; return it }
            }
            cur = cur.superclass
        }
        throw NoSuchMethodException(
            "None of ${names.toList()} with params ${paramTypes.map { it.name }} found in ${clazz.name} hierarchy"
        )
    }

    /**
     * 查找无参方法，按候选名顺序。
     */
    fun methodByNames(clazz: Class<*>, vararg names: String): Method =
        methodByNames(clazz, emptyArray(), *names)

    // ──────────────────────────────────────────────────────────────────────────
    // Constructor
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 按多个精确参数类型签名依次尝试，返回第一个成功的构造函数。
     * @throws NoSuchMethodException 所有签名均未找到
     */
    fun constructorByParamCandidates(clazz: Class<*>, vararg paramTypeLists: Array<Class<*>>): Constructor<*> {
        for (params in paramTypeLists) {
            runCatching { clazz.getDeclaredConstructor(*params) }
                .getOrNull()
                ?.also { it.isAccessible = true; return it }
        }
        throw NoSuchMethodException(
            "No matching constructor found in ${clazz.name} for any of the provided signatures"
        )
    }

    /**
     * 模糊构造函数查找：返回参数列表中 **包含所有** [requiredTypes] 的第一个构造函数
     * （顺序不限，允许额外参数）。
     *
     * 适用于跨版本构造函数参数顺序/数量可能变化的场景。
     * @throws NoSuchMethodException 未找到
     */
    fun constructorFuzzy(clazz: Class<*>, vararg requiredTypes: Class<*>): Constructor<*> {
        val ctors = clazz.declaredConstructors.sortedByDescending { it.parameterCount }
        for (ctor in ctors) {
            val matched = requiredTypes.all { req ->
                ctor.parameterTypes.any { p -> req.isAssignableFrom(p) }
            }
            if (matched) {
                ctor.isAccessible = true
                return ctor
            }
        }
        throw NoSuchMethodException(
            "No constructor in ${clazz.name} containing all required types: ${requiredTypes.map { it.name }}"
        )
    }
}

