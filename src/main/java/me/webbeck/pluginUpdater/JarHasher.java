package me.webbeck.pluginUpdater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;

/**
 * Hashes installed jars so update checks can compare bytes, not version strings.
 * Some authors strip build numbers from plugin.yml (e.g. ViaVersion reports
 * {@code 5.12.1-SNAPSHOT} while Modrinth lists {@code 5.12.1-SNAPSHOT+1069}),
 * which makes every string comparison a false positive. A matching hash ends that.
 *
 * <p>Results are cached per path + size + last-modified, bounded to
 * {@link #MAX_ENTRIES} entries with a {@link #TTL_MILLIS} expiry. Pure JDK, unit-testable.
 */
public final class JarHasher {

    private JarHasher() {
    }

    private static final class Entry {
        final String sha1;
        final String sha256;
        final long cachedAt;

        Entry(String sha1, String sha256, long cachedAt) {
            this.sha1 = sha1;
            this.sha256 = sha256;
            this.cachedAt = cachedAt;
        }
    }

    static final int MAX_ENTRIES = 256;
    static final long TTL_MILLIS = 5 * 60 * 1000L;

    // Bounded LRU: eldest entry evicted once size exceeds MAX_ENTRIES.
    // Guarded by its own monitor; never grows without bound.
    private static final java.util.Map<String, Entry> CACHE =
            new java.util.LinkedHashMap<String, Entry>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    public static String sha1(Path path) throws IOException {
        return entryFor(path).sha1;
    }

    public static String sha256(Path path) throws IOException {
        return entryFor(path).sha256;
    }

    static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    public static boolean matchesSha1(Path path, String expected) {
        if (expected == null || expected.isBlank()) return false;
        try {
            return expected.equalsIgnoreCase(sha1(path));
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean matchesSha256(Path path, String expected) {
        if (expected == null || expected.isBlank()) return false;
        try {
            return expected.equalsIgnoreCase(sha256(path));
        } catch (IOException e) {
            return false;
        }
    }

    private static Entry entryFor(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        String key = path.toAbsolutePath() + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis();
        long now = System.currentTimeMillis();
        synchronized (CACHE) {
            Entry cached = CACHE.get(key);
            if (cached != null && now - cached.cachedAt < TTL_MILLIS) return cached;
        }
        Entry fresh = new Entry(digest(path, "SHA-1"), digest(path, "SHA-256"), now);
        synchronized (CACHE) {
            CACHE.put(key, fresh);
        }
        return fresh;
    }

    private static String digest(Path path, String algorithm) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) != -1) {
                    md.update(buf, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("Hash algorithm unavailable: " + algorithm, e);
        }
    }
}
