package me.webbeck.pluginUpdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the right Modrinth project for a locally installed plugin.
 *
 * <p>The local name (from plugin.yml) almost never equals the Modrinth title:
 * titles get truncated ({@code All-in-One VeinMiner & TreeFelle}), renamed, or
 * carry suffixes the jar name does not. Exact matching therefore misses real
 * projects, while naive substring matching latches onto the wrong one. This
 * resolver tokenizes the local name, searches with a loader facet, and scores
 * candidates - and it refuses to guess when nothing scores clearly, because a
 * wrong ID committed silently breaks update checks forever.
 */
public class ModrinthResolver {

    static final int SCORE_THRESHOLD = 6;
    static final int SCORE_EXACT = 100;

    public static final class Candidate {
        public final String id;
        public final String title;
        public final String slug;
        public final String projectType;
        public final int score;

        Candidate(String id, String title, String slug, String projectType, int score) {
            this.id = id;
            this.title = title;
            this.slug = slug;
            this.projectType = projectType;
            this.score = score;
        }
    }

    public static final class Resolution {
        public final boolean found;
        public final String id;
        public final String title;
        public final int score;
        public final List<Candidate> candidates;

        private Resolution(boolean found, String id, String title, int score, List<Candidate> candidates) {
            this.found = found;
            this.id = id;
            this.title = title;
            this.score = score;
            this.candidates = candidates;
        }
    }

    private final HttpClient httpClient;
    private final String loaderFacet;

    public ModrinthResolver(HttpClient httpClient, String loaderFacet) {
        this.httpClient = httpClient;
        this.loaderFacet = loaderFacet != null && !loaderFacet.isBlank() ? loaderFacet.toLowerCase() : "paper";
    }

    /**
     * @return true when the ID exists, false on 404, null when the network failed.
     */
    public Boolean validateId(String id) {
        if (id == null || id.isBlank()) return false;
        try {
            String url = "https://api.modrinth.com/v2/project/" + URLEncoder.encode(id.trim(), StandardCharsets.UTF_8.toString());
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "PluginUpdater-WB").build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return true;
            if (response.statusCode() == 404) return false;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public Resolution resolve(String pluginName) throws Exception {
        List<String> tokens = tokenize(pluginName);
        if (tokens.isEmpty()) {
            return new Resolution(false, null, null, 0, List.of());
        }

        // Query the longest tokens; the full hyphenated name scores zero hits on Modrinth search.
        List<String> queries = tokens.stream()
                .filter(t -> t.length() >= 3)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(2)
                .toList();
        if (queries.isEmpty()) {
            queries = List.of(tokens.stream().max(Comparator.comparingInt(String::length)).orElse(pluginName));
        }

        Map<String, JsonObject> hits = new LinkedHashMap<>();
        String facets = URLEncoder.encode("[[\"categories:" + loaderFacet + "\"]]", StandardCharsets.UTF_8.toString());
        for (String query : queries) {
            String url = "https://api.modrinth.com/v2/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
                    + "&limit=10&facets=" + facets;
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "PluginUpdater-WB").build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) continue;
            JsonArray results = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("hits");
            for (JsonElement element : results) {
                JsonObject hit = element.getAsJsonObject();
                hits.putIfAbsent(hit.get("project_id").getAsString(), hit);
            }
        }

        String normQuery = normalize(pluginName);
        List<Candidate> candidates = new ArrayList<>();
        for (JsonObject hit : hits.values()) {
            String title = hit.has("title") ? hit.get("title").getAsString() : "";
            String slug = hit.has("slug") ? hit.get("slug").getAsString() : "";
            String type = hit.has("project_type") ? hit.get("project_type").getAsString() : "";
            candidates.add(new Candidate(
                    hit.get("project_id").getAsString(), title, slug, type,
                    scoreHit(tokens, normQuery, title, slug, type)));
        }
        return pickBest(candidates);
    }

    static Resolution pickBest(List<Candidate> candidates) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt((Candidate c) -> c.score).reversed());
        List<Candidate> top = sorted.stream().limit(3).toList();
        if (top.isEmpty() || top.get(0).score < SCORE_THRESHOLD) {
            return new Resolution(false, null, null, 0, top);
        }
        if (top.get(0).score < SCORE_EXACT && top.size() > 1
                && top.get(0).score - top.get(1).score < 2) {
            return new Resolution(false, null, null, 0, top);
        }
        Candidate best = top.get(0);
        return new Resolution(true, best.id, best.title, best.score, top);
    }

    static int scoreHit(List<String> queryTokens, String normQuery, String title, String slug, String projectType) {
        String normTitle = normalize(title);
        String normSlug = normalize(slug);
        if (!normTitle.isEmpty() && normTitle.equals(normQuery)) return SCORE_EXACT;
        int score = 0;
        for (String token : queryTokens) {
            if (normTitle.contains(token)) score += 2;
            if (normSlug.contains(token)) score += 1;
        }
        if (!normTitle.isEmpty() && (normTitle.contains(normQuery) || normQuery.contains(normTitle))) score += 3;
        if (!normSlug.isEmpty() && (normSlug.contains(normQuery) || normQuery.contains(normSlug))) score += 2;
        if ("plugin".equalsIgnoreCase(projectType)) score += 1;
        return score;
    }

    static List<String> tokenize(String name) {
        List<String> tokens = new ArrayList<>();
        if (name == null) return tokens;
        for (String part : name.split("[-_\\s]+")) {
            for (String word : part.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                String token = word.toLowerCase().replaceAll("[^a-z0-9]", "");
                if (token.length() >= 2 && !token.matches("\\d+")) {
                    tokens.add(token);
                }
            }
        }
        return tokens.stream().distinct().toList();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    public static String describeCandidates(java.util.List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return " No close matches found.";
        StringBuilder out = new StringBuilder(" Closest matches:");
        for (Candidate candidate : candidates) {
            out.append(" '").append(candidate.title).append("' (").append(candidate.id).append(")");
        }
        return out.toString();
    }
}
