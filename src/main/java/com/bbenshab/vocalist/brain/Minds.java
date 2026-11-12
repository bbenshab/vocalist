package com.bbenshab.vocalist.brain;

import com.bbenshab.vocalist.Vocalist;
import com.bbenshab.vocalist.settings.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Ordered chain of minds; tries each in turn until one answers. */
public final class Minds {

    private static volatile List<Mind> chain = List.of();

    private Minds() {
    }

    public static synchronized void rebuild() {
        Settings cfg = Vocalist.settings();
        List<Mind> built = new ArrayList<>();
        for (Settings.ProviderSpec spec : cfg.providers) {
            switch (spec.kind) {
                case "gemini", "google" -> built.add(new GeminiMind(spec, cfg));
                case "openai", "ollama", "grok" -> built.add(new OpenAiMind(spec, cfg));
                default -> Vocalist.LOG.warn("Unknown provider type {} for {}, skipping",
                        spec.kind, spec.name);
            }
        }
        chain = List.copyOf(built);
    }

    public static String describe() {
        if (chain.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (Mind m : chain) {
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(m.name());
        }
        return sb.toString();
    }

    public static int size() {
        return chain.size();
    }

    public static CompletableFuture<String> converse(String instructions, List<Line> turns) {
        List<Mind> minds = chain;
        if (minds.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("no providers configured"));
        }
        return tryNext(minds, 0, instructions, turns, new ArrayList<>());
    }

    private static CompletableFuture<String> tryNext(List<Mind> minds, int index,
            String instructions, List<Line> turns, List<String> errors) {
        if (index >= minds.size()) {
            return CompletableFuture.failedFuture(
                    new RuntimeException(String.join(" | ", errors)));
        }
        Mind mind = minds.get(index);
        return mind.converse(instructions, turns)
                .thenApply(reply -> reply)
                .exceptionallyCompose(err -> {
                    errors.add(mind.name() + ": " + err.getMessage());
                    Vocalist.LOG.warn("Mind {} failed ({}), trying next", mind.name(), err.getMessage());
                    return tryNext(minds, index + 1, instructions, turns, errors);
                });
    }
}
