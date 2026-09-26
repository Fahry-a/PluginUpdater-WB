package me.webbeck.pluginUpdater;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Inspects a downloaded jar before it is staged into the update folder.
 * Paper matches staged files by the {@code name:} field inside
 * {@code paper-plugin.yml} / {@code plugin.yml} (exact, case-sensitive) and
 * silently ignores everything else, so every failure here must be loud.
 *
 * <p>Deliberately free of Bukkit dependencies so it stays unit-testable.
 */
public final class JarInspector {

    private JarInspector() {
    }

    private static final byte[] ZIP_MAGIC = {(byte) 0x50, (byte) 0x4B, 0x03, 0x04};

    private static final Pattern NAME_PATTERN =
            Pattern.compile("(?m)^name\\s*:\\s*['\"]?([^'\"\\r\\n]+?)['\"]?\\s*$");

    public static final class Inspection {
        public final boolean valid;
        public final String pluginName;
        public final String descriptor;
        public final String error;

        private Inspection(boolean valid, String pluginName, String descriptor, String error) {
            this.valid = valid;
            this.pluginName = pluginName;
            this.descriptor = descriptor;
            this.error = error;
        }

        public static Inspection ok(String pluginName, String descriptor) {
            return new Inspection(true, pluginName, descriptor, null);
        }

        public static Inspection fail(String error) {
            return new Inspection(false, null, null, error);
        }
    }

    public static Inspection inspect(File file) {
        return inspect(file.toPath());
    }

    public static Inspection inspect(Path path) {
        if (!Files.isRegularFile(path)) {
            return Inspection.fail("not a regular file");
        }
        String fileName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        if (!fileName.toLowerCase().endsWith(".jar")) {
            return Inspection.fail("file name does not end with .jar");
        }

        try (InputStream in = Files.newInputStream(path)) {
            byte[] magic = new byte[4];
            int read = in.read(magic);
            if (read < 4 || magic[0] != ZIP_MAGIC[0] || magic[1] != ZIP_MAGIC[1]
                    || magic[2] != ZIP_MAGIC[2] || magic[3] != ZIP_MAGIC[3]) {
                return Inspection.fail("not a zip/jar file (bad magic bytes - likely an HTML error page)");
            }
        } catch (IOException e) {
            return Inspection.fail("unreadable: " + e.getMessage());
        }

        try (JarFile jar = new JarFile(path.toFile())) {
            // Same precedence Paper uses: paper-plugin.yml first, then plugin.yml.
            for (String descriptor : new String[]{"paper-plugin.yml", "plugin.yml"}) {
                ZipEntry entry = jar.getJarEntry(descriptor);
                if (entry == null) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    String name = extractName(text);
                    if (name == null || name.isBlank()) {
                        return Inspection.fail(descriptor + " has no usable name: field");
                    }
                    return Inspection.ok(name.trim(), descriptor);
                }
            }
            return Inspection.fail("no paper-plugin.yml or plugin.yml inside (Paper would ignore this file)");
        } catch (IOException e) {
            return Inspection.fail("corrupt jar: " + e.getMessage());
        }
    }

    static String extractName(String descriptorText) {
        Matcher matcher = NAME_PATTERN.matcher(descriptorText);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * Validates a Geyser extension jar. Extensions are not Paper plugins, so no
     * {@code plugin.yml} is required — only a real zip/jar that opens cleanly.
     * The returned {@link Inspection#pluginName} is the file base name.
     */
    public static Inspection inspectExtension(File file) {
        return inspectExtension(file.toPath());
    }

    public static Inspection inspectExtension(Path path) {
        if (!Files.isRegularFile(path)) {
            return Inspection.fail("not a regular file");
        }
        String fileName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        if (!fileName.toLowerCase().endsWith(".jar")) {
            return Inspection.fail("file name does not end with .jar");
        }

        try (InputStream in = Files.newInputStream(path)) {
            byte[] magic = new byte[4];
            int read = in.read(magic);
            if (read < 4 || magic[0] != ZIP_MAGIC[0] || magic[1] != ZIP_MAGIC[1]
                    || magic[2] != ZIP_MAGIC[2] || magic[3] != ZIP_MAGIC[3]) {
                return Inspection.fail("not a zip/jar file (bad magic bytes - likely an HTML error page)");
            }
        } catch (IOException e) {
            return Inspection.fail("unreadable: " + e.getMessage());
        }

        try (JarFile jar = new JarFile(path.toFile())) {
            // Force full central-directory parse so truncated downloads fail here, not at load time.
            jar.stream().count();
            String base = fileName.substring(0, fileName.length() - 4);
            return Inspection.ok(base, "geyser-extension");
        } catch (IOException e) {
            return Inspection.fail("corrupt jar: " + e.getMessage());
        }
    }
}
