package com.bbenshab.vocalist.brain;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A single chat-completion backend. */
public interface Mind {

    String name();

    CompletableFuture<String> converse(String instructions, List<Line> turns);
}
