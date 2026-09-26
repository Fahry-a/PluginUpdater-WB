package me.webbeck.pluginUpdater;

/**
 * A failed update-source lookup that says <em>why</em> it failed.
 * Thrown by the source checkers instead of collapsing every failure into null
 * (which the caller used to misread as "up to date").
 */
public class SourceException extends Exception {

    public enum Kind {
        /** The project does not exist at the source (HTTP 404). */
        NOT_FOUND,
        /** Refused: rate limit or forbidden (HTTP 403/429). */
        RATE_LIMITED,
        /** Server error, network failure, or unparsable response. */
        SERVER_ERROR,
        /** Nothing to query: blank project-id / repo / url in plugins.yml. */
        NO_SOURCE
    }

    private final Kind kind;
    private final String pluginName;

    public SourceException(Kind kind, String pluginName, String detail) {
        super(detail);
        this.kind = kind;
        this.pluginName = pluginName;
    }

    public Kind getKind() {
        return kind;
    }

    public String getPluginName() {
        return pluginName;
    }
}
