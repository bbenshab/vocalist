package com.bbenshab.vocalist.brain;

import com.bbenshab.vocalist.net.WebCalls;
import com.bbenshab.vocalist.settings.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Google Generative Language API (Gemini). */
final class GeminiMind implements Mind {

    private final String label;
    private final String base;
    private final String key;
    private final String model;
    private final Settings cfg;

    GeminiMind(Settings.ProviderSpec spec, Settings cfg) {
        this.label = spec.name;
        this.base = spec.baseUrl.isBlank() ? "https://generativelanguage.googleapis.com" : spec.baseUrl;
        this.key = spec.apiKey;
        this.model = spec.model;
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return label;
    }

    @Override
    public CompletableFuture<String> converse(String instructions, List<Line> turns) {
        JsonObject body = new JsonObject();
        JsonObject sys = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysText = new JsonObject();
        sysText.addProperty("text", instructions);
        sysParts.add(sysText);
        sys.add("parts", sysParts);
        body.add("system_instruction", sys);

        JsonArray contents = new JsonArray();
        for (Line line : turns) {
            JsonObject turn = new JsonObject();
            turn.addProperty("role", "assistant".equals(line.role()) ? "model" : "user");
            JsonObject part = new JsonObject();
            part.addProperty("text", line.text());
            JsonArray parts = new JsonArray();
            parts.add(part);
            turn.add("parts", parts);
            contents.add(turn);
        }
        body.add("contents", contents);

        JsonObject gen = new JsonObject();
        gen.addProperty("temperature", cfg.temperature);
        gen.addProperty("maxOutputTokens", cfg.maxTokens);
        body.add("generationConfig", gen);

        String url = base + "/v1beta/models/" + model + ":generateContent?key=" + key;
        return WebCalls.postJson(url, Map.of(), body.toString(), cfg.timeoutSeconds)
                .thenApply(GeminiMind::extractText);
    }

    private static String extractText(String raw) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        if (!root.has("candidates") || root.getAsJsonArray("candidates").isEmpty()) {
            throw new RuntimeException("gemini returned no candidates");
        }
        JsonObject first = root.getAsJsonArray("candidates").get(0).getAsJsonObject();
        StringBuilder sb = new StringBuilder();
        for (JsonElement partEl : first.getAsJsonObject("content").getAsJsonArray("parts")) {
            JsonObject part = partEl.getAsJsonObject();
            if (part.has("text")) {
                sb.append(part.get("text").getAsString());
            }
        }
        String out = sb.toString().trim();
        if (out.isEmpty()) {
            throw new RuntimeException("gemini returned empty text");
        }
        return out;
    }
}
