package com.bbenshab.vocalist.listen;

import com.bbenshab.vocalist.Vocalist;
import com.bbenshab.vocalist.net.WebCalls;
import com.bbenshab.vocalist.settings.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Transcription through the Gemini API. */
public final class GeminiEar implements Ear {

    @Override
    public CompletableFuture<String> hear(byte[] wav) {
        Settings cfg = Vocalist.settings();
        String key = cfg.geminiKey();
        if (key == null) {
            return CompletableFuture.failedFuture(new RuntimeException("no gemini api key configured"));
        }

        JsonObject ask = new JsonObject();
        ask.addProperty("text", "Transcribe this audio exactly as spoken. "
                + "Reply with the transcription only. If it is Hebrew, write it in Hebrew letters.");

        JsonObject audioData = new JsonObject();
        audioData.addProperty("mime_type", "audio/wav");
        audioData.addProperty("data", Base64.getEncoder().encodeToString(wav));
        JsonObject audioPart = new JsonObject();
        audioPart.add("inline_data", audioData);

        JsonArray parts = new JsonArray();
        parts.add(ask);
        parts.add(audioPart);
        JsonObject turn = new JsonObject();
        turn.add("parts", parts);
        turn.addProperty("role", "user");
        JsonArray contents = new JsonArray();
        contents.add(turn);

        JsonObject body = new JsonObject();
        body.add("contents", contents);

        String url = cfg.geminiBase() + "/v1beta/models/" + cfg.sttModel
                + ":generateContent?key=" + key;
        return WebCalls.postJson(url, Map.of(), body.toString(), cfg.timeoutSeconds)
                .thenApply(GeminiEar::extractText);
    }

    private static String extractText(String raw) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        if (!root.has("candidates") || root.getAsJsonArray("candidates").isEmpty()) {
            throw new RuntimeException("gemini returned no candidates");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement partEl : root.getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("content").getAsJsonArray("parts")) {
            JsonObject part = partEl.getAsJsonObject();
            if (part.has("text")) {
                sb.append(part.get("text").getAsString());
            }
        }
        return sb.toString().trim();
    }
}
