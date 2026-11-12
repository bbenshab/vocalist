package com.bbenshab.vocalist.listen;

import com.bbenshab.vocalist.Vocalist;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Local faster-whisper transcription. Free, offline, ~1-2 s. */
public final class WhisperEar implements Ear {

    @Override
    public CompletableFuture<String> hear(byte[] wav) {
        return CompletableFuture.supplyAsync(() -> {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("vocalist_ear_", ".wav");
                Files.write(tmp, wav);

                ProcessBuilder pb = new ProcessBuilder("python3",
                        Vocalist.settings().whisperScript, tmp.toString());
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = proc.getInputStream().read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                proc.waitFor();

                String response = out.toString("UTF-8").trim();
                if (response.isEmpty()) {
                    return "";
                }
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                if (json.has("error") && !json.get("error").isJsonNull()
                        && !json.get("error").getAsString().isEmpty()) {
                    throw new RuntimeException(json.get("error").getAsString());
                }
                return json.has("text") ? json.get("text").getAsString().trim() : "";
            } catch (Exception e) {
                throw new RuntimeException("whisper failed: " + e.getMessage(), e);
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                    }
                }
            }
        });
    }
}
