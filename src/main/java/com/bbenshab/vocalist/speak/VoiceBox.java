package com.bbenshab.vocalist.speak;

import java.util.concurrent.CompletableFuture;

/** Text-to-speech backend. Output is 48 kHz mono PCM. */
public interface VoiceBox {

    CompletableFuture<short[]> say(String text);
}
