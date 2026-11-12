package com.bbenshab.vocalist.speak;

import com.bbenshab.vocalist.Vocalist;
import com.bbenshab.vocalist.net.WebCalls;
import com.bbenshab.vocalist.settings.Settings;
import com.bbenshab.vocalist.sound.AudioMath;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Neural TTS through the Gemini API. Returns 48 kHz mono PCM. */
public final class GeminiVoiceBox implements VoiceBox {

    @Override
    public CompletableFuture<short[]> say(String text) {
        Settings cfg = Vocalist.settings();
        String key = cfg.geminiKey();
        if (key == null) {
            return CompletableFuture.failedFuture(new RuntimeException("no gemini api key configured"));
        }

        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject turn = new JsonObject();
        turn.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(turn);

        JsonObject prebuilt = new JsonObject();
        prebuilt.addProperty("voiceName", cfg.ttsVoice);
        JsonObject voiceCfg = new JsonObject();
        voiceCfg.add("prebuiltVoiceConfig", prebuilt);
        JsonObject speech = new JsonObject();
        speech.add("voiceConfig", voiceCfg);

        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        JsonObject gen = new JsonObject();
        gen.add("responseModalities", modalities);
        gen.add("speechConfig", speech);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", gen);

        String url = cfg.geminiBase() + "/v1beta/models/" + cfg.ttsModel
                + ":generateContent?key=" + key;
        return WebCalls.postJson(url, Map.of(), body.toString(), cfg.timeoutSeconds)
                .thenApply(GeminiVoiceBox::pcmFrom);
    }

    private static short[] pcmFrom(String raw) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        JsonObject part = root.getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject();
        if (!part.has("inlineData")) {
            throw new RuntimeException("no audio in gemini tts response");
        }
        String b64 = part.getAsJsonObject("inlineData").get("data").getAsString();
        byte[] raw24k = Base64.getDecoder().decode(b64);
        short[] pcm = AudioMath.bytesToPcm(raw24k);
        pcm = trimNoiseTail(pcm);
        return AudioMath.resample(pcm, 24000, 48000);
    }

    /**
     * Gemini TTS audio sometimes ends with a short burst of near-full-scale
     * white noise. Walk 50 ms windows from the end and cut while they are
     * louder than real speech ever gets, then fade out to kill the click.
     */
    private static short[] trimNoiseTail(short[] pcm) {
        final int window = 1200;              // 50 ms at 24 kHz
        final double threshold = 4000.0;      // loud speech peaks ~2000 RMS
        if (pcm.length < window * 4) {
            return pcm;
        }
        int end = pcm.length;
        while (end >= window) {
            double sum = 0.0;
            for (int i = end - window; i < end; i++) {
                sum += (double) pcm[i] * pcm[i];
            }
            if (Math.sqrt(sum / window) < threshold) {
                break;
            }
            end -= window;
        }
        if (end < pcm.length) {
            Vocalist.LOG.info("[voice] trimmed {} noise samples from tts tail",
                    pcm.length - end);
            pcm = java.util.Arrays.copyOf(pcm, end);
        }
        int fade = Math.min(480, pcm.length); // 20 ms fade-out
        for (int i = 0; i < fade; i++) {
            pcm[pcm.length - 1 - i] = (short) (pcm[pcm.length - 1 - i] * (i / (double) fade));
        }
        return pcm;
    }
}
