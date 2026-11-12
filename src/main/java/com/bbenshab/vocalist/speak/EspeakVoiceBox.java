package com.bbenshab.vocalist.speak;

import com.bbenshab.vocalist.sound.AudioMath;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Offline espeak-ng TTS. Rough quality, zero cost, always available. */
public final class EspeakVoiceBox implements VoiceBox {

    private static final String HOME = "/home/minecraft/piper";
    private static final String VOICE = "he";

    @Override
    public CompletableFuture<short[]> say(String text) {
        return CompletableFuture.supplyAsync(() -> {
            String safe = text.replace("\"", "'").replace("\\", "").replace("$", "").trim();
            if (safe.isEmpty()) {
                return new short[0];
            }
            if (safe.length() > 500) {
                safe = safe.substring(0, 500);
            }
            Path tmp = null;
            try {
                tmp = Files.createTempFile("vocalist_mouth_", ".wav");
                ProcessBuilder pb = new ProcessBuilder(
                        "./espeak-ng", "-v", VOICE, "-w", tmp.toString(), "-s", "150", safe);
                pb.directory(new File(HOME));
                pb.environment().put("LD_LIBRARY_PATH", HOME);
                pb.environment().put("ESPEAK_DATA_PATH", HOME + "/espeak-ng-data");
                pb.redirectErrorStream(true);
                int code = pb.start().waitFor();
                if (code != 0) {
                    throw new RuntimeException("espeak-ng exited with " + code);
                }
                AudioMath.Clip clip = AudioMath.fromWav(Files.readAllBytes(tmp));
                return AudioMath.resample(clip.samples(), clip.sampleRate(), 48000);
            } catch (Exception e) {
                throw new RuntimeException("espeak failed: " + e.getMessage(), e);
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }
}
