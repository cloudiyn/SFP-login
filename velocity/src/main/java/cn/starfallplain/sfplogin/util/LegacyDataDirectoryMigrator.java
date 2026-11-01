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

package cn.starfallplain.sfplogin.util;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 旧插件数据目录（{@code plugins/hyperzonelogin}）到新数据目录（{@code plugins/sfplogin}）的一次性迁移。
 *
 * <p>规则（见 todo.md M0-5）：仅在启动早期、读取配置之前执行；新目录为空且旧目录存在时递归复制；
 * 新旧目录都存在且新目录非空时只使用新目录并告警；任何情况下都不删除旧目录。</p>
 */
public final class LegacyDataDirectoryMigrator {

    /** 旧插件 id 对应的数据目录名。 */
    public static final String LEGACY_DIRECTORY_NAME = "hyperzonelogin";

    private LegacyDataDirectoryMigrator() {
    }

    /**
     * 执行迁移。失败只记录日志，绝不抛出异常阻断启动。
     *
     * @param dataDirectory 新数据目录（Velocity 注入的 {@code @DataDirectory}）
     * @param logger 插件日志
     * @return {@code true} 表示本次确实发生了复制
     */
    public static boolean migrate(Path dataDirectory, ComponentLogger logger) {
        if (dataDirectory == null) {
            return false;
        }
        Path pluginsDirectory = dataDirectory.getParent();
        if (pluginsDirectory == null) {
            return false;
        }
        Path legacyDirectory = pluginsDirectory.resolve(LEGACY_DIRECTORY_NAME);
        if (!Files.isDirectory(legacyDirectory)) {
            return false;
        }
        try {
            Path normalizedLegacy = legacyDirectory.toAbsolutePath().normalize();
            Path normalizedTarget = dataDirectory.toAbsolutePath().normalize();
            if (normalizedLegacy.equals(normalizedTarget)) {
                return false;
            }
            if (Files.isDirectory(normalizedTarget) && !isEmptyDirectory(normalizedTarget)) {
                logger.warn(
                    "旧数据目录 {} 仍存在，但新数据目录 {} 已有数据，本次不迁移（旧目录保留，可手动删除）。",
                    normalizedLegacy,
                    normalizedTarget
                );
                return false;
            }
            Files.createDirectories(normalizedTarget);
            copyRecursively(normalizedLegacy, normalizedTarget);
            logger.info("已从旧数据目录 {} 迁移数据到 {}（旧目录保留，确认无误后可手动删除）。", normalizedLegacy, normalizedTarget);
            return true;
        } catch (Exception ex) {
            logger.warn("从旧数据目录迁移到新数据目录失败，将继续使用新目录：" + dataDirectory, ex);
            return false;
        }
    }

    private static boolean isEmptyDirectory(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isEmpty();
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path destination = target.resolve(source.relativize(file));
                if (Files.exists(destination)) {
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, destination);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
