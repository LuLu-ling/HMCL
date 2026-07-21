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

import org.jackhuang.hmcl.modpack.UnsupportedModpackException;
import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests directory discovery and isolation detection for manually packaged Minecraft archives.
@NotNullByDefault
public final class ManuallyCreatedModpackInstallTaskTest {
    /// Finds a dragged game directory regardless of how compression software wraps it.
    @Test
    public void findsDraggedMinecraftDirectoryInRealArchives(@TempDir Path tempDirectory) throws Exception {
        Path rootArchive = createArchive(
                tempDirectory.resolve("root.zip"),
                "versions/root/root.json");
        Path dotMinecraftArchive = createArchive(
                tempDirectory.resolve("dot-minecraft.zip"),
                ".minecraft/versions/dot-minecraft/dot-minecraft.json");
        Path arbitraryWrapperArchive = createArchive(
                tempDirectory.resolve("arbitrary-wrapper.zip"),
                "My Pack/versions/wrapped/wrapped.json");

        assertEquals("/", findMinecraftDirectory(rootArchive));
        assertEquals("/.minecraft", findMinecraftDirectory(dotMinecraftArchive));
        assertEquals("/My Pack", findMinecraftDirectory(arbitraryWrapperArchive));
        assertThrows(
                ManuallyCreatedModpackException.class,
                () -> ModpackHelper.readModpackManifest(
                        arbitraryWrapperArchive, StandardCharsets.UTF_8));
    }

    /// Prefers an explicit `.minecraft` directory over unrelated launcher data.
    @Test
    public void prefersExplicitMinecraftDirectoryInRealArchive(@TempDir Path tempDirectory) throws Exception {
        Path archive = createArchive(
                tempDirectory.resolve("explicit-minecraft.zip"),
                "Launcher Data/versions/unrelated/unrelated.json",
                ".minecraft/versions/selected/selected.json");

        assertEquals("/.minecraft", findMinecraftDirectory(archive));
    }

    /// Rejects an archive with multiple arbitrary game-directory candidates.
    @Test
    public void rejectsAmbiguousMinecraftDirectoriesInRealArchive(@TempDir Path tempDirectory) throws Exception {
        Path archive = createArchive(
                tempDirectory.resolve("ambiguous.zip"),
                "Pack A/versions/a/a.json",
                "Pack B/versions/b/b.json");

        try (FileSystem fileSystem = CompressingUtils.readonly(archive)
                .setEncoding(StandardCharsets.UTF_8)
                .build()) {
            assertThrows(
                    UnsupportedModpackException.class,
                    () -> ModpackHelper.findMinecraftDirectoryInManuallyCreatedModpack(
                            archive.toString(), fileSystem));
        }
    }

    /// Detects shared and version-specific running directories from unambiguous physical layouts.
    @Test
    public void detectsIsolationFromDirectoryLayout(@TempDir Path tempDirectory) throws IOException {
        Path sharedMinecraftDirectory = tempDirectory.resolve("shared-minecraft");
        createVersion(sharedMinecraftDirectory, "shared");
        Path isolatedMinecraftDirectory = tempDirectory.resolve("isolated-minecraft");
        Path isolatedVersion = createVersion(isolatedMinecraftDirectory, "isolated");
        Path invalidVersion = sharedMinecraftDirectory.resolve("versions/invalid");

        Files.createDirectories(sharedMinecraftDirectory.resolve("mods"));
        Files.createDirectories(isolatedVersion.resolve("config"));
        Files.createDirectories(invalidVersion.resolve("mods"));

        @Unmodifiable Map<String, HMCLGameRepository.LockedVersionIsolation> shared =
                ManuallyCreatedModpackInstallTask.detectVersionIsolation(sharedMinecraftDirectory);
        @Unmodifiable Map<String, HMCLGameRepository.LockedVersionIsolation> isolated =
                ManuallyCreatedModpackInstallTask.detectVersionIsolation(isolatedMinecraftDirectory);

        assertEquals(HMCLGameRepository.LockedVersionIsolation.ROOT_FOLDER, shared.get("shared"));
        assertEquals(HMCLGameRepository.LockedVersionIsolation.VERSION_FOLDER, isolated.get("isolated"));
        assertFalse(shared.containsKey("invalid"));
    }

    /// Uses stable fallbacks when both or neither physical location contains runtime data.
    @Test
    public void detectsIsolationFromAmbiguousDirectoryLayout(@TempDir Path tempDirectory) throws IOException {
        Path conflictingMinecraftDirectory = tempDirectory.resolve("conflicting-minecraft");
        Path conflictingVersion = createVersion(conflictingMinecraftDirectory, "conflicting");
        Path emptyMinecraftDirectory = tempDirectory.resolve("empty-minecraft");
        createVersion(emptyMinecraftDirectory, "empty");

        Files.createDirectories(conflictingMinecraftDirectory.resolve("mods"));
        Files.createDirectories(conflictingVersion.resolve("config"));

        @Unmodifiable Map<String, HMCLGameRepository.LockedVersionIsolation> conflicting =
                ManuallyCreatedModpackInstallTask.detectVersionIsolation(conflictingMinecraftDirectory);
        @Unmodifiable Map<String, HMCLGameRepository.LockedVersionIsolation> empty =
                ManuallyCreatedModpackInstallTask.detectVersionIsolation(emptyMinecraftDirectory);

        assertEquals(HMCLGameRepository.LockedVersionIsolation.VERSION_FOLDER, conflicting.get("conflicting"));
        assertEquals(HMCLGameRepository.LockedVersionIsolation.ROOT_FOLDER, empty.get("empty"));
    }

    /// Prefers persisted HMCL choices over ambiguous or contradictory directory contents.
    @Test
    public void prefersEmbeddedHmclIsolationSettings(@TempDir Path tempDirectory) throws IOException {
        Path minecraftDirectory = tempDirectory.resolve(".minecraft");
        Path currentSettingsVersion = createVersion(minecraftDirectory, "current-settings");
        Path currentRootSettingsVersion = createVersion(minecraftDirectory, "current-root-settings");
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
        Path currentRootSettings = currentRootSettingsVersion.resolve(
                ".hmcl/config/instance-game-settings.json");
        Files.createDirectories(currentRootSettingsVersion.resolve(".hmcl/config"));
        Files.writeString(currentRootSettings, """
                {
                  "overrideProperties": []
                }
                """);
        Files.writeString(legacySettingsVersion.resolve("hmclversion.cfg"), """
                {
                  "usesGlobal": false,
                  "gameDirType": 1
                }
                """);
        Files.createDirectories(currentRootSettingsVersion.resolve("mods"));
        Files.createDirectories(lockedRootVersion.resolve("mods"));
        HMCLGameRepository.writeVersionIsolationLock(
                lockedRootVersion,
                HMCLGameRepository.LockedVersionIsolation.ROOT_FOLDER);

        @Unmodifiable Map<String, HMCLGameRepository.LockedVersionIsolation> detected =
                ManuallyCreatedModpackInstallTask.detectVersionIsolation(minecraftDirectory);

        assertEquals(HMCLGameRepository.LockedVersionIsolation.VERSION_FOLDER, detected.get("current-settings"));
        assertEquals(HMCLGameRepository.LockedVersionIsolation.ROOT_FOLDER, detected.get("current-root-settings"));
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

    /// Creates a UTF-8 ZIP containing the requested file entries.
    private static Path createArchive(Path archive, String... entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            for (String entry : entries) {
                output.putNextEntry(new ZipEntry(entry));
                output.write("{}".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }

    /// Returns the discovered Minecraft directory path inside an archive.
    private static String findMinecraftDirectory(Path archive) throws Exception {
        try (FileSystem fileSystem = CompressingUtils.readonly(archive)
                .setEncoding(StandardCharsets.UTF_8)
                .build()) {
            return ModpackHelper.findMinecraftDirectoryInManuallyCreatedModpack(
                    archive.toString(), fileSystem).toString();
        }
    }
}
