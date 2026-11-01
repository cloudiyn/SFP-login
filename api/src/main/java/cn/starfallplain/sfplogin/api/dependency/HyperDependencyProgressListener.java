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
 * Reports runtime dependency download progress to the hosting platform logger.
 */
public interface HyperDependencyProgressListener {
    /**
     * No-op listener implementation.
     */
    HyperDependencyProgressListener NONE = new HyperDependencyProgressListener() {
    };

    /**
     * Called when an existing cached jar is reused.
     *
     * @param dependency dependency being reused
     * @param path cached jar path
     */
    default void onUsingCache(HyperDependency dependency, Path path) {
    }

    /**
     * Called before a repository download attempt starts.
     *
     * @param dependency dependency being downloaded
     * @param repository repository being queried
     * @param targetPath target local file path
     */
    default void onDownloadStart(HyperDependency dependency, HyperDependencyRepository repository, Path targetPath) {
    }

    /**
     * Called after a repository download attempt succeeds.
     *
     * @param dependency dependency that was downloaded
     * @param repository repository that provided the artifact
     * @param targetPath target local file path
     */
    default void onDownloadSuccess(HyperDependency dependency, HyperDependencyRepository repository, Path targetPath) {
    }

    /**
     * Called after a repository download attempt fails.
     *
     * @param dependency dependency that failed to download
     * @param repository repository that was queried
     * @param exception failure cause
     */
    default void onDownloadFailure(HyperDependency dependency, HyperDependencyRepository repository, Exception exception) {
    }

    /**
     * Called after a dependency has been fully loaded into the classpath.
     *
     * @param dependency dependency that was loaded
     * @param path final jar path that was loaded
     */
    default void onDependencyLoaded(HyperDependency dependency, Path path) {
    }
}

