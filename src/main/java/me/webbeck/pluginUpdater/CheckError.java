package me.webbeck.pluginUpdater;

/**
 * One recorded per-plugin check failure. Pure data, no Bukkit dependency.
 */
public final class CheckError {

    public final SourceException.Kind kind;
    public final String pluginName;
    public final String detail;

    public CheckError(SourceException.Kind kind, String pluginName, String detail) {
        this.kind = kind;
        this.pluginName = pluginName;
        this.detail = detail;
    }

    public String label() {
        switch (kind) {
            case NOT_FOUND:
                return "SOURCE NOT FOUND";
            case RATE_LIMITED:
                return "RATE LIMITED";
            case NO_SOURCE:
                return "NO SOURCE";
            case SERVER_ERROR:
            default:
                return "CHECK FAILED";
        }
    }

    public String fixHint() {
        switch (kind) {
            case NOT_FOUND:
            case NO_SOURCE:
                return "Set a source: /upd plugin id " + pluginName + " <Modrinth|Hangar|Spigot|GitHub|Custom> <id/repo/url>";
            case RATE_LIMITED:
                return "Set a github-token in config.yml (GitHub) or wait a while and run /upd check again";
            case SERVER_ERROR:
            default:
                return "Run /upd check again later; if it persists, verify the source ID";
        }
    }
}
