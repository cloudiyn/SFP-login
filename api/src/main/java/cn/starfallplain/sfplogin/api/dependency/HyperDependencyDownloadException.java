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

/**
 * Adapted from LuckPerms' dependency download exception.
 */
public final class HyperDependencyDownloadException extends Exception {
    /**
     * Creates an exception with a message only.
     *
     * @param message failure summary
     */
    public HyperDependencyDownloadException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a cause only.
     *
     * @param cause underlying cause
     */
    public HyperDependencyDownloadException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates an exception with message and cause.
     *
     * @param message failure summary
     * @param cause underlying cause
     */
    public HyperDependencyDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}

