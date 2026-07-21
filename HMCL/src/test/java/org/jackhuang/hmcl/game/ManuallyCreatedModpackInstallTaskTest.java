/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Tests isolation detection for manually packaged `.minecraft` directories.
@NotNullByDefault
public final class ManuallyCreatedModpackInstallTaskTest {
    /// Detects shared and version-specific running directories from their physical layouts.
    @Test
    public void detectsIsolationFromDirectoryLayout(@TempDir Path tempDirectory) throws IOException {
        Path minecraftDirectory = tempDirectory.resolve(".minecraft");
        createVersion(minecraftDirectory, "shared");
        Path isolatedVersion = createVersion(minecraftDirectory, "isolated");
        Path invalidVersion = minecraftDirectory.resolve("versions/invalid");

        Files.createDirectories(minecraftDirectory.resolve("mods"));
        Files.createDirectories(isolatedVersion.resolve("config"));
        Files.createDirectories(invalidVersion.resolve("mods"));

        @Unmodifiable Map<String, HMCLGameRepository.LockedVersionIsolation> detected =
                ManuallyCreatedModpackInstallTask.detectVersionIsolation(minecraftDirectory);

        assertEquals(HMCLGameRepository.LockedVersionIsolation.ROOT_FOLDER, detected.get("shared"));
        assertEquals(HMCLGameRepository.LockedVersionIsolation.VERSION_FOLDER, detected.get("isolated"));
        assertFalse(detected.containsKey("invalid"));
    }

    /// Prefers persisted HMCL choices over ambiguous or contradictory directory contents.
    @Test
    public void prefersEmbeddedHmclIsolationSettings(@TempDir Path tempDirectory) throws IOException {
        Path minecraftDirectory = tempDirectory.resolve(".minecraft");
        Path currentSettingsVersion = createVersion(minecraftDirectory, "current-settings");
        Path legacySettingsVersion = createVersion(minecraftDirectory, "legacy-settings");
        Path lockedRootVersion = createVersion(minecraftDirectory, "locked-root");

        Path currentSettings = currentSettingsVersion.resolve(".hmcl/config/instance-game-settings.json");
        Files.createDirectories(currentSettingsVersion.resolve(".hmcl/config"));
        Files.writeString(currentSettings, """
                {
                  "overrideProperties": ["runningDirectory"],
                  "runningDirectory": ""
                }
                """);
        Files.writeString(legacySettingsVersion.resolve("hmclversion.cfg"), """
                {
                  "usesGlobal": false,
                  "gameDirType": 1
                }
                """);
        Files.createDirectories(lockedRootVersion.resolve("mods"));
        HMCLGameRepository.writeVersionIsolationLock(
                lockedRootVersion,
                HMCLGameRepository.LockedVersionIsolation.ROOT_FOLDER);

        @Unmodifiable Map<String, HMCLGameRepository.LockedVersionIsolation> detected =
                ManuallyCreatedModpackInstallTask.detectVersionIsolation(minecraftDirectory);

        assertEquals(HMCLGameRepository.LockedVersionIsolation.VERSION_FOLDER, detected.get("current-settings"));
        assertEquals(HMCLGameRepository.LockedVersionIsolation.VERSION_FOLDER, detected.get("legacy-settings"));
        assertEquals(HMCLGameRepository.LockedVersionIsolation.ROOT_FOLDER, detected.get("locked-root"));
    }

    /// Creates a minimal valid version directory.
    private static Path createVersion(Path minecraftDirectory, String version) throws IOException {
        Path versionDirectory = minecraftDirectory.resolve("versions").resolve(version);
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(version + ".json"), "{\"id\":\"" + version + "\"}");
        return versionDirectory;
    }
}
