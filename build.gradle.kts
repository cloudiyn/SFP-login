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

import org.gradle.api.tasks.GradleBuild
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.Exec
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields

plugins {
    base
    alias(libs.plugins.spotless)
    alias(libs.plugins.kotlin) apply false
}

fun toBlockCommentHeader(headerFile: File): String {
    val body = headerFile
        .readLines()
        .dropLastWhile { it.isBlank() }
        .joinToString("\n") { line -> if (line.isBlank()) " *" else " * $line" }

    return "/*\n$body\n *\n */\n\n"
}

fun Project.requireStringProperty(name: String): String =
    findProperty(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Missing required Gradle property '$name'.")

val modVersion = requireStringProperty("modVersion")
version = modVersion

// API 版本独立维护，直接取 gradle.properties 里的 apiVersion。
val apiPublishedVersion = requireStringProperty("apiVersion")
extra["apiPublishedVersion"] = apiPublishedVersion

val kotlinLicenseHeader = toBlockCommentHeader(rootProject.file("HEADER.txt"))
val kotlinSourceHeaderDelimiter = "^(package|@file:|import)"
val kotlinGradleHeaderDelimiter = "^(import|plugins|buildscript|pluginManagement|dependencyResolutionManagement|rootProject|include)"

spotless {
    // 路径规则按"所有模块"收敛，而不是逐个模块列举：新增模块不需要再改这里，
    // 但必须落在 <module>/src/** 下并带 HEADER.txt 头注释，否则 check 会失败。
    kotlin {
        target("*/src/**/*.kt")
        targetExclude("buildSrc/**")
        licenseHeader(kotlinLicenseHeader, kotlinSourceHeaderDelimiter)
    }

    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        licenseHeader(kotlinLicenseHeader, kotlinGradleHeaderDelimiter)
    }

    // Java 入口类（各家 Bootstrap）与 Velocity 内部访问层同样走 HEADER.txt 头注释校验，
    // 避免只有 Kotlin 文件受保护、Java 文件慢慢漂移。
    // 例外：HyperDependency.java 派生自 LuckPerms（MIT），保留其原始版权头，不能覆盖成 AGPL 头。
    java {
        target("*/src/**/*.java")
        targetExclude(
            "buildSrc/**",
            "api/src/main/java/cn/starfallplain/sfplogin/api/dependency/HyperDependency.java",
        )
        licenseHeader(kotlinLicenseHeader, "^(package|@file:|import)")
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
    }

    repositories {
        val isCi = System.getenv("CI") == "true"
        if (!isCi) {
            maven("https://maven.aliyun.com/repository/central")
            maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            mavenLocal()
        }
        mavenCentral()

        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
        maven("https://repo.opencollab.dev/maven-snapshots")
        maven("https://maven.elytrium.net/repo/")
        maven {
            name = "velocityctdSnapshots"
            url = uri("https://repo.velocityctd.com/snapshots")
        }
    }

    tasks.withType(ProcessResources::class.java).configureEach {
        val pluginVersion = rootProject.version.toString()
        // Declare pluginVersion as an input for up-to-date checking.
        inputs.property("pluginVersion", pluginVersion)
        filteringCharset = "UTF-8"

        // Avoid filesMatching { expand(...) } — that pattern creates a MatchingCopyAction
        // which IntelliJ IDEA's Gradle model importer cannot resolve, flooding the sync
        // output with "Cannot resolve resource filtering of MatchingCopyAction" warnings.
        // Instead: exclude the raw template from the standard copy and inject the version
        // via a doLast action that does a direct string replacement in the output file.
        val srcTemplate = project.file("src/main/resources/velocity-plugin.json")
        if (srcTemplate.exists()) {
            inputs.file(srcTemplate)
            exclude("velocity-plugin.json")
            doLast("injectPluginVersion") {
                val dst = destinationDir.resolve("velocity-plugin.json")
                dst.parentFile.mkdirs()
                dst.writeText(srcTemplate.readText().replace("\${pluginVersion}", pluginVersion))
            }
        }
    }

    tasks.withType(Test::class.java).configureEach {
        // MockK uses Byte Buddy for mocking; Java 25 is newer than Byte Buddy's
        // officially supported range, so the experimental flag is required.
        // The additional flags suppress JVM-level runtime warnings emitted when
        // running on Java 23+:
        //   - velocity-proxy pulls jline-terminal-ffm into the test classpath;
        //     --enable-native-access=ALL-UNNAMED suppresses its FFM warning
        //   - sun.misc.Unsafe::objectFieldOffset is terminally deprecated (JEP 471)
        //   - Class-Data Sharing conflicts with bootstrap classpath injection
        jvmArgs(
            "-Dnet.bytebuddy.experimental=true",
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
            "-Xshare:off",
        )
    }
}

val pluginBundleDir = layout.buildDirectory.dir("HZL")
val splitPluginBundleDir = layout.buildDirectory.dir("HZL-split")
val ctdPluginBundleDir = layout.buildDirectory.dir("HZL-ctd")

val collectPluginJars = tasks.register<Sync>("collectPluginJars") {
    group = "build"
    description = "Collects the all-in-one SFP-login jar (plugin + CLI tool) into one distribution directory."
    into(pluginBundleDir)

    val velocityProject = project(":velocity")
    dependsOn(velocityProject.tasks.named("monolithJar"))
    from(velocityProject.tasks.named("monolithJar", Jar::class).flatMap { it.archiveFile })
}

val collectSplitPluginJars = tasks.register<Sync>("collectSplitPluginJars") {
    group = "build"
    description = "Collects the split plugin distribution with the main plugin and optional module jars."
    into(splitPluginBundleDir)

    val velocityProject = project(":velocity")
    dependsOn(velocityProject.tasks.named("jar"))
    from(velocityProject.tasks.named("jar", Jar::class).flatMap { it.archiveFile })

    subprojects
        .filter { it.path != ":api" && it.path != ":velocity" && it.path != ":vc-runtest" && it.path != ":cli" }
        .forEach { subproject ->
            val archiveTaskName = "jar"
            dependsOn(subproject.tasks.named(archiveTaskName))
            from(subproject.tasks.named(archiveTaskName, Jar::class).flatMap { it.archiveFile }) {
                rename { fileName ->
                    if (fileName.startsWith("HZL-")) fileName else "HZL-$fileName"
                }
            }
        }
}

val buildVelocityCtd = tasks.register<GradleBuild>("buildVelocityCtd") {
    group = "build"
    description = "Builds VelocityCTD-flavored SFP-login jars using com.velocityctd Velocity dependencies."
    this.dir = rootDir
    this.tasks = listOf(":velocity:jar", ":velocity:monolithJar")

    // VelocityCTD 需要把 :api 等模块对着 CTD 版 Velocity 再编译一次。早先的做法是另起一个
    // `gradlew --no-daemon` 进程，因为 Kotlin 增量编译器在 JVM 内维护一张按绝对路径索引的
    // FilePageCache（cacheable/caches-jvm/**/*.tab），同一 JVM 里第二次编译同一模块会对同一批
    // .tab 重复注册，报 "Storage for [...] is already registered"。
    //
    // 真正的冲突源是增量编译缓存，而不是"同一进程"。所以这里用同进程的 GradleBuild，
    // 只对 CTD 这次构建关闭 Kotlin 增量编译（kotlin.incremental=false），既避开缓存注册，
    // 又省掉一个全新 Gradle 守护进程的启动与冷配置开销。
    val passthroughProperties = gradle.startParameter.projectProperties
        .filterKeys { it != "velocityCtd" && it != "kotlin.incremental" }
    this.startParameter.projectProperties = passthroughProperties + mapOf(
        "velocityCtd" to "true",
        "kotlin.incremental" to "false",
    )
}

val collectCtdPluginJars = tasks.register<Sync>("collectCtdPluginJars") {
    group = "build"
    description = "Collects VelocityCTD-flavored SFP-login jars into one distribution directory."
    into(ctdPluginBundleDir)
    dependsOn(buildVelocityCtd)

    val velocityProject = project(":velocity")
    // CTD jars now live in libs-ctd/ so they never mix with the official libs/ output.
    from(velocityProject.layout.buildDirectory.file("libs-ctd/SFP-login-${version}-ctd.jar"))
    from(velocityProject.layout.buildDirectory.file("libs-ctd/SFP-login-${version}-all-ctd.jar"))
}

val buildMonolith = tasks.register("buildMonolith") {
    group = "build"
    description = "Builds the all-in-one SFP-login distribution."
    dependsOn(collectPluginJars)
}

val buildAllDistributions = tasks.register("buildAllDistributions") {
    group = "build"
    description = "Builds both the all-in-one and split SFP-login distributions."
    dependsOn(collectPluginJars)
    dependsOn(collectSplitPluginJars)
}

val buildCtdDistributions = tasks.register("buildCtdDistributions") {
    group = "build"
    description = "Builds the VelocityCTD-flavored SFP-login distribution."
    dependsOn(collectCtdPluginJars)
}

val printVersionInfo = tasks.register("printVersionInfo") {
    group = "help"
    description = "Prints the SFP-login plugin and API versions."
    doLast {
        println("SFP-login version: $modVersion")
        println("API version: $apiPublishedVersion")
    }
}

tasks.named("assemble") {
    dependsOn(collectPluginJars)
}

tasks.named("check") {
    dependsOn(tasks.named("spotlessCheck"))
}

tasks.named("build") {
    dependsOn(collectPluginJars)
}

