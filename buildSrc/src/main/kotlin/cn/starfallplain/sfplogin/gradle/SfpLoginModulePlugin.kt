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

package cn.starfallplain.sfplogin.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * SFP-login 子模块约定插件。
 *
 * 每个内置模块原先都要重复 40 多行同样的装配（Kotlin JVM、`compileOnly(:api)`、
 * JUnit 5 测试依赖、`useJUnitPlatform()`），改一个公共设置就得改七八个文件。
 * 这里把公共部分收敛为一处，模块脚本只描述"这个模块额外需要什么"。
 *
 * 约定内容：
 * - 应用 `org.jetbrains.kotlin.jvm`；
 * - `compileOnly(project(":api"))` —— 模块以独立插件形态发布，api 由主插件在运行时提供；
 * - 测试依赖 `:api`、JUnit 5（bom + jupiter + launcher）与 `useJUnitPlatform()`。
 *
 * JVM 工具链不在本插件里设置：根构建已经对所有子项目统一 `jvmToolchain`。
 */
class SfpLoginModulePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")

        val library = LibraryLookup(project)
        project.dependencies.apply {
            add("compileOnly", project.project(":api"))
            add("testImplementation", project.project(":api"))
            add("testImplementation", library.platform("junitBom"))
            add("testImplementation", library.get("junitJupiter"))
            add("testRuntimeOnly", library.get("junitPlatformLauncher"))
        }

        project.tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }

    /** 通过 `libs` 版本目录取依赖，避免在约定插件里写死坐标与版本。 */
    private class LibraryLookup(private val project: Project) {
        private val catalogs = project.extensions.getByType<VersionCatalogsExtension>()
        private val libs = catalogs.named("libs")

        fun get(alias: String): Provider<MinimalExternalModuleDependency> =
            libs.findLibrary(alias).orElseThrow {
                GradleException("版本目录 libs 中缺少依赖别名 '$alias'（gradle/libs.versions.toml）")
            }

        fun platform(alias: String): Any =
            project.dependencies.platform(get(alias))
    }
}
