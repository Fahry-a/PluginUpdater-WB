package me.webbeck.pluginUpdater;

import java.util.ArrayList;
import java.util.List;

public class UpdateInfo {
    public final String pluginName;
    public final String oldVersion;
    public final String newVersion;
    public final String downloadUrl;
    public final String fileName;
    public final List<String> requiredDependencies = new ArrayList<>();
    /** Expected hash of the remote file, when the source API provides one. Null otherwise. */
    public String expectedSha1;
    public String expectedSha256;
    /** Remote validators captured at check time (ETag / Last-Modified). Null when unavailable. */
    public String remoteEtag;
    public String remoteLastModified;

    public UpdateInfo(String pluginName, String oldVersion, String newVersion, String downloadUrl, String fileName) {
        this.pluginName = pluginName;
        this.oldVersion = oldVersion;
        this.newVersion = newVersion;
        this.downloadUrl = downloadUrl;
        this.fileName = fileName;
    }
}
