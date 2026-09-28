package me.webbeck.pluginUpdater;

import java.io.File;

/**
 * Validates downloaded plugin artifacts before they are staged.
 * Keeps archive inspection and digest policy out of the downloader orchestration.
 */
public final class UpdateArtifactValidator {
    private UpdateArtifactValidator() {}

    public static Validation validate(File file, String expectedName, boolean requireMatchingName) {
        if (file == null || !file.isFile()) {
            return Validation.invalid("downloaded file does not exist");
        }

        JarInspector.Inspection inspection = JarInspector.inspect(file);
        if (!inspection.valid) {
            return Validation.invalid(inspection.error);
        }

        if (inspection.pluginName == null || inspection.pluginName.isBlank()) {
            return Validation.invalid("plugin.yml does not contain a plugin name");
        }

        if (!inspection.pluginName.equals(expectedName)) {
            if (requireMatchingName) {
                return Validation.invalid("plugin.yml name '" + inspection.pluginName
                        + "' does not match '" + expectedName + "'");
            }
            return Validation.warning(inspection.pluginName,
                    "Warning: " + file.getName() + " reports plugin name '" + inspection.pluginName
                            + "' (expected '" + expectedName + "'). Staged anyway.");
        }

        return Validation.valid(inspection.pluginName);
    }

    public static boolean matchesExpectedDigest(File file, String expectedSha1, String expectedSha256) {
        if (file == null || !file.isFile()) return false;
        if (expectedSha1 != null && !expectedSha1.isBlank()) {
            return JarHasher.matchesSha1(file.toPath(), expectedSha1);
        }
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            return JarHasher.matchesSha256(file.toPath(), expectedSha256);
        }
        return false;
    }

    public record Validation(boolean valid, String pluginName, String error, String warning) {
        static Validation valid(String pluginName) {
            return new Validation(true, pluginName, null, null);
        }

        static Validation warning(String pluginName, String warning) {
            return new Validation(true, pluginName, null, warning);
        }

        static Validation invalid(String error) {
            return new Validation(false, null, error, null);
        }
    }
}
