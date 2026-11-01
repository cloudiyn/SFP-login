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

import cn.starfallplain.sfplogin.gradle.needPackageCompileOnly

plugins {
    id("cn.starfallplain.sfplogin.module")
    id("cn.starfallplain.sfplogin.runtime-dependencies")
}

dependencies {
    // 认证模块是独立插件，只在这里引用它们的类型
    compileOnly(project(":auth-yggd"))
    compileOnly(project(":auth-offline"))
    compileOnly(project(":auth-floodgate"))

    compileOnly(libs.velocityApi)
    compileOnly(libs.brigadier)

    needPackageCompileOnly(libs.h2)

    compileOnly(libs.exposedCore)
    compileOnly(libs.exposedJdbc)
    compileOnly(libs.configurateHocon)
    compileOnly(libs.configurateExtraKotlin)
}
