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

package cn.starfallplain.sfplogin

import cn.starfallplain.sfplogin.config.CoreConfig
import cn.starfallplain.sfplogin.runtime.HyperZoneContext
import cn.starfallplain.sfplogin.runtime.HyperZoneRuntime

/**
 * 当前运行时上下文；插件尚未初始化或已关闭时抛错。
 */
fun runtimeContext(): HyperZoneContext = HyperZoneRuntime.require()

/**
 * 当前主插件实例。
 */
fun sfpLoginMain(): SFPLoginMain = runtimeContext().main

/**
 * 当前核心配置快照（`core.conf`）。
 */
fun coreConfig(): CoreConfig = runtimeContext().requireCoreConfig()
