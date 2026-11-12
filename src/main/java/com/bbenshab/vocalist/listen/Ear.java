package com.bbenshab.vocalist.listen;

import java.util.concurrent.CompletableFuture;

/** Speech-to-text backend. Input is 48 kHz mono WAV. */
public interface Ear {

    CompletableFuture<String> hear(byte[] wav);
}
