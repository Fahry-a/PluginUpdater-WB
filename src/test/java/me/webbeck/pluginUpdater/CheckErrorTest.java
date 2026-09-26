package me.webbeck.pluginUpdater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CheckErrorTest {

    @Test
    void labels() {
        assertEquals("SOURCE NOT FOUND",
                new CheckError(SourceException.Kind.NOT_FOUND, "X", "d").label());
        assertEquals("RATE LIMITED",
                new CheckError(SourceException.Kind.RATE_LIMITED, "X", "d").label());
        assertEquals("NO SOURCE",
                new CheckError(SourceException.Kind.NO_SOURCE, "X", "d").label());
        assertEquals("CHECK FAILED",
                new CheckError(SourceException.Kind.SERVER_ERROR, "X", "d").label());
    }

    @Test
    void fixHintsPointAtConcreteCommands() {
        CheckError missing = new CheckError(SourceException.Kind.NOT_FOUND, "Foo", "404");
        assertTrue(missing.fixHint().contains("/upd plugin id Foo"));

        CheckError noSource = new CheckError(SourceException.Kind.NO_SOURCE, "Foo", "blank");
        assertTrue(noSource.fixHint().contains("/upd plugin id Foo"));

        CheckError limited = new CheckError(SourceException.Kind.RATE_LIMITED, "Foo", "403");
        assertTrue(limited.fixHint().contains("github-token"));
    }
}
