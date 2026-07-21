/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.game;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.jackhuang.hmcl.util.io.Unzipper;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.Lang.toIterable;

/// Extracts a manually packaged `.minecraft` archive into an external game directory.
@NotNullByDefault
public class ManuallyCreatedModpackInstallTask extends Task<Path> {

    /// Game-directory entries that indicate a version-isolated running directory.
    private static final @Unmodifiable Set<String> VERSION_DIRECTORY_MARKERS = Set.of(
            "mods",
            "config",
            "defaultconfigs",
            "saves",
            "resourcepacks",
            "shaderpacks",
            "screenshots",
            "logs",
            "crash-reports",
            "server-resource-packs",
            "options.txt",
            "optionsof.txt",
            "optionsshaders.txt",
            "servers.dat"
    );

    /// The source archive.
    private final Path zipFile;

    /// The character set used to decode archive entry names.
    private final Charset charset;

    /// The display name of the imported game directory.
    private final String name;

    /// Creates a task for importing a manually packaged game directory.
    ///
    /// @param zipFile the source archive
    /// @param charset the archive entry character set
    /// @param name the imported game directory name
    public ManuallyCreatedModpackInstallTask(Path zipFile, Charset charset, String name) {
        this.zipFile = zipFile;
        this.charset = charset;
        this.name = name;

        setName(i18n("modpack.installing"));
    }

    /// Extracts the archive and persists the detected isolation mode for every version it contains.
    @Override
    public void execute() throws Exception {
        Path subdirectory;
        @Unmodifiable Map<String, HMCLGameRepository.LockedVersionIsolation> isolationByVersion;
        try (FileSystem fs = CompressingUtils.readonly(zipFile).setEncoding(charset).build()) {
            subdirectory = ModpackHelper.findMinecraftDirectoryInManuallyCreatedModpack(zipFile.toString(), fs);
            isolationByVersion = detectVersionIsolation(subdirectory);
        }

        Path dest = Paths.get("externalgames").resolve(name);

        new Unzipper(zipFile, dest)
                .setSubDirectory(subdirectory.toString())
                .setTerminateIfSubDirectoryNotExists()
                .setEncoding(charset)
                .unzip();

        for (Map.Entry<String, HMCLGameRepository.LockedVersionIsolation> entry : isolationByVersion.entrySet()) {
            HMCLGameRepository.writeVersionIsolationLock(
                    dest.resolve("versions").resolve(entry.getKey()),
                    entry.getValue());
        }

        setResult(dest);
    }

    /// Detects the fixed running-directory layout for each valid version in a `.minecraft` directory.
    ///
    /// @param minecraftDirectory the archive's `.minecraft` directory
    /// @return an immutable map from version ID to its detected isolation mode
    @VisibleForTesting
    static @Unmodifiable Map<String, HMCLGameRepository.LockedVersionIsolation> detectVersionIsolation(Path minecraftDirectory) throws IOException {
        Path versionsDirectory = minecraftDirectory.resolve("versions");
        Map<String, HMCLGameRepository.LockedVersionIsolation> result = new HashMap<>();
        if (!Files.isDirectory(versionsDirectory)) {
            return Map.of();
        }

        try (Stream<Path> versions = Files.list(versionsDirectory)) {
            for (Path versionDirectory : toIterable(versions)) {
                @Nullable Path fileName = versionDirectory.getFileName();
                if (!Files.isDirectory(versionDirectory) || fileName == null) {
                    continue;
                }

                String version = fileName.toString();
                if (!Files.isRegularFile(versionDirectory.resolve(version + ".json"))) {
                    continue;
                }

                @Nullable HMCLGameRepository.LockedVersionIsolation detected =
                        readConfiguredIsolation(versionDirectory);
                if (detected == null) {
                    detected = hasVersionDirectoryContent(versionDirectory)
                            ? HMCLGameRepository.LockedVersionIsolation.VERSION_FOLDER
                            : HMCLGameRepository.LockedVersionIsolation.ROOT_FOLDER;
                }
                result.put(version, detected);
            }
        }
        return Map.copyOf(result);
    }

    /// Reads an explicit isolation choice embedded by HMCL in a manually packaged instance.
    private static @Nullable HMCLGameRepository.LockedVersionIsolation readConfiguredIsolation(Path versionDirectory) {
        @Nullable HMCLGameRepository.LockedVersionIsolation lock =
                HMCLGameRepository.readVersionIsolationLock(versionDirectory);
        if (lock != null) {
            return lock;
        }

        Path instanceSettings = versionDirectory.resolve(".hmcl/config/instance-game-settings.json");
        if (Files.isRegularFile(instanceSettings)) {
            try {
                @Nullable JsonObject settings = JsonUtils.fromJsonFile(instanceSettings, JsonObject.class);
                if (settings != null && hasRunningDirectoryOverride(settings)
                        && StringUtils.isBlank(JsonUtils.getString(settings, GameSettings.PROPERTY_RUNNING_DIRECTORY, ""))) {
                    return HMCLGameRepository.LockedVersionIsolation.VERSION_FOLDER;
                }
            } catch (IOException | RuntimeException ignored) {
                // Fall back to the physical layout when the embedded settings are malformed.
            }
        }

        Path legacySettings = versionDirectory.resolve("hmclversion.cfg");
        if (Files.isRegularFile(legacySettings)) {
            try {
                @Nullable JsonObject settings = JsonUtils.fromJsonFile(legacySettings, JsonObject.class);
                if (settings != null && !JsonUtils.getBoolean(settings, "usesGlobal", false)) {
                    @Nullable Integer gameDirectoryType = JsonUtils.getNullableInt(settings, "gameDirType");
                    if (gameDirectoryType != null) {
                        return switch (gameDirectoryType) {
                            case 0 -> HMCLGameRepository.LockedVersionIsolation.ROOT_FOLDER;
                            case 1 -> HMCLGameRepository.LockedVersionIsolation.VERSION_FOLDER;
                            default -> null;
                        };
                    }

                    @Nullable String gameDirectoryTypeName = JsonUtils.getString(settings, "gameDirType");
                    if (gameDirectoryTypeName != null) {
                        return switch (gameDirectoryTypeName) {
                            case "ROOT_FOLDER" -> HMCLGameRepository.LockedVersionIsolation.ROOT_FOLDER;
                            case "VERSION_FOLDER" -> HMCLGameRepository.LockedVersionIsolation.VERSION_FOLDER;
                            default -> null;
                        };
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Fall back to the physical layout when the embedded settings are malformed.
            }
        }

        return null;
    }

    /// Returns whether a version directory contains files characteristic of an isolated game directory.
    private static boolean hasVersionDirectoryContent(Path versionDirectory) {
        for (String marker : VERSION_DIRECTORY_MARKERS) {
            if (Files.exists(versionDirectory.resolve(marker))) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether an instance setting explicitly selects its own running directory.
    private static boolean hasRunningDirectoryOverride(JsonObject settings) {
        if (!settings.has("overrideProperties") || !settings.get("overrideProperties").isJsonArray()) {
            return false;
        }

        for (var property : settings.getAsJsonArray("overrideProperties")) {
            if (GameSettings.PROPERTY_RUNNING_DIRECTORY.equals(property.getAsString())) {
                return true;
            }
        }
        return false;
    }
}
