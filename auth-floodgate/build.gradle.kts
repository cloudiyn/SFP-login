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

plugins {
    id("cn.starfallplain.sfplogin.module")
}

dependencies {
    // 以独立插件形态构建：api 由主插件在运行时提供
    compileOnly(libs.velocityApi)
    compileOnly(libs.floodgateApi)
    compileOnly(libs.nettyAll)
    compileOnly(libs.exposedCore)
    compileOnly(libs.configurateHocon)

    testImplementation(libs.velocityApi)
    testImplementation(libs.floodgateApi)
    testImplementation(libs.nettyAll)
    testImplementation(libs.exposedCore)
    testImplementation(libs.exposedJdbc)
    testImplementation(libs.h2)
    testImplementation("io.mockk:mockk:1.13.17")
}
