package com.bbenshab.vocalist.brain;

import com.bbenshab.vocalist.net.WebCalls;
import com.bbenshab.vocalist.settings.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Any OpenAI-compatible chat endpoint (Grok, Ollama, llama.cpp, ...). */
final class OpenAiMind implements Mind {

    private final String label;
    private final String base;
    private final String key;
    private final String model;
    private final Settings cfg;

    OpenAiMind(Settings.ProviderSpec spec, Settings cfg) {
        this.label = spec.name;
        this.base = spec.baseUrl.endsWith("/") ? spec.baseUrl.substring(0, spec.baseUrl.length() - 1) : spec.baseUrl;
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
        JsonArray messages = new JsonArray();
        messages.add(msg("system", instructions));
        for (Line line : turns) {
            messages.add(msg(line.role(), line.text()));
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("temperature", cfg.temperature);
        body.addProperty("max_tokens", cfg.maxTokens);

        Map<String, String> headers = new HashMap<>();
        if (key != null && !key.isBlank()) {
            headers.put("Authorization", "Bearer " + key);
        }
        return WebCalls.postJson(base + "/chat/completions", headers, body.toString(), cfg.timeoutSeconds)
                .thenApply(OpenAiMind::extractText);
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static String extractText(String raw) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        if (!root.has("choices") || root.getAsJsonArray("choices").isEmpty()) {
            throw new RuntimeException("no choices in response");
        }
        String out = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString().trim();
        if (out.isEmpty()) {
            throw new RuntimeException("empty reply");
        }
        return out;
    }
}
