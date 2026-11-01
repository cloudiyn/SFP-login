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

package cn.starfallplain.sfplogin.api.dependency;

import java.nio.file.Path;

/**
 * Post-download hook for rewriting or relocating dependency jar paths before classpath load.
 */
@FunctionalInterface
public interface HyperDependencyPathProcessor {
    /**
     * No-op processor that returns the downloaded file unchanged.
     */
    HyperDependencyPathProcessor NONE = (dependency, downloadedFile) -> downloadedFile;

    /**
     * Processes a downloaded jar before it is appended to the runtime classpath.
     *
     * @param dependency dependency being processed
     * @param downloadedFile original downloaded jar path
     * @return path that should be loaded into the classpath
     * @throws HyperDependencyDownloadException if processing fails
     */
    Path process(HyperDependency dependency, Path downloadedFile) throws HyperDependencyDownloadException;
}

