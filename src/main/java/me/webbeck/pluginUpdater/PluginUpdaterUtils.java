package me.webbeck.pluginUpdater;

public class PluginUpdaterUtils {
    public static String cleanVersion(String v) {
        if (v == null) return "";
        v = v.toLowerCase();
        if (v.startsWith("v")) v = v.substring(1);
        int metaIndex = v.indexOf('+');
        if (metaIndex != -1) v = v.substring(0, metaIndex);
        return v.replaceAll("-(paper|spigot|bukkit|purpur|folia|release|beta|alpha)", "");
    }

    public static boolean versionsMatch(String curr, String newV) {
        return compareVersions(curr, newV) == 0;
    }

    /**
     * True when the remote version is strictly newer than the current one.
     * Guards against downgrades: a remote build older than (or equal to) what is
     * running is never reported as an update.
     */
    public static boolean isNewerThan(String curr, String newV) {
        return compareVersions(curr, newV) < 0;
    }

    /**
     * Numeric-aware version ordering. Missing trailing segments count as zero, so
     * "1.0" equals "1.0.0". Non-numeric segments compare case-insensitively.
     */
    public static int compareVersions(String a, String b) {
        String[] pa = cleanVersion(a).split("[.\\-_]");
        String[] pb = cleanVersion(b).split("[.\\-_]");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int cmp = compareToken(i < pa.length ? pa[i] : "0", i < pb.length ? pb[i] : "0");
            if (cmp != 0) return cmp;
        }
        return compareMeta(a, b);
    }

    private static int compareToken(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareToIgnoreCase(b);
        }
    }

    private static int compareMeta(String a, String b) {
        String ma = extractMeta(a);
        String mb = extractMeta(b);
        if (mb == null) return 0;
        if (ma == null) return -1;
        try {
            return Integer.compare(Integer.parseInt(ma), Integer.parseInt(mb));
        } catch (NumberFormatException e) {
            return ma.compareToIgnoreCase(mb);
        }
    }

    private static String extractMeta(String v) {
        if (v == null) return null;
        int i = v.indexOf('+');
        return i == -1 ? null : v.substring(i + 1);
    }

    public static String joinArgs(String[] args, int start) {
        if (args.length <= start) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) builder.append(" ");
            builder.append(args[i]);
        }
        return builder.toString();
    }

    public static String joinArgs(String[] args, int start, int end) {
        if (args.length <= start || start >= end) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < end && i < args.length; i++) {
            if (i > start) builder.append(" ");
            builder.append(args[i]);
        }
        return builder.toString();
    }
}
