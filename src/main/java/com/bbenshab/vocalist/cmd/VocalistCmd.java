package com.bbenshab.vocalist.cmd;

import com.bbenshab.vocalist.Vocalist;
import com.bbenshab.vocalist.brain.Line;
import com.bbenshab.vocalist.brain.Minds;
import com.bbenshab.vocalist.remember.Recall;
import com.bbenshab.vocalist.settings.Settings;
import com.bbenshab.vocalist.talk.TalkHub;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/** /vocalist reload | status | stop | voice tts|stt <gemini|local|off> | test */
public final class VocalistCmd {

    private VocalistCmd() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vocalist")
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("stop")
                        .executes(ctx -> stop(ctx.getSource())))
                .then(Commands.literal("test")
                        .executes(ctx -> test(ctx.getSource())))
                .then(Commands.literal("use")
                        .then(Commands.argument("provider", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (Settings.ProviderSpec spec : Vocalist.settings().providers) {
                                        builder.suggest(spec.name);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> use(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "provider")))))
                .then(Commands.literal("forget")
                        .executes(ctx -> forgetCurrent(ctx.getSource()))
                        .then(Commands.literal("all")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> forgetAll(ctx.getSource())))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> forgetPlayer(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("voice")
                        .then(Commands.literal("tts")
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("gemini");
                                            builder.suggest("local");
                                            builder.suggest("off");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> setMode(ctx.getSource(),
                                                "tts", StringArgumentType.getString(ctx, "mode")))))
                        .then(Commands.literal("stt")
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("gemini");
                                            builder.suggest("local");
                                            builder.suggest("off");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> setMode(ctx.getSource(),
                                                "stt", StringArgumentType.getString(ctx, "mode")))))));
    }

    private static int reload(CommandSourceStack source) {
        Settings cfg = Vocalist.settings();
        cfg.reloadFromDisk();
        Minds.rebuild();
        source.sendSuccess(() -> Component.literal(
                "\u00a76[Vocalist] \u00a77Reloaded. Minds: " + Minds.describe()), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        Settings cfg = Vocalist.settings();
        String text = "\u00a76[Vocalist] \u00a77minds=" + Minds.describe()
                + " \u00a7f| \u00a77tts=" + cfg.ttsMode
                + " \u00a7f| \u00a77stt=" + cfg.sttMode
                + " \u00a7f| \u00a77talks=" + TalkHub.sessionCount();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            TalkHub.end(player, true);
        } else {
            source.sendFailure(Component.literal("Only a player in a conversation can stop one."));
        }
        return 1;
    }

    private static int setMode(CommandSourceStack source, String which, String mode) {
        if (!mode.equals("gemini") && !mode.equals("local") && !mode.equals("off")) {
            source.sendFailure(Component.literal("Mode must be gemini, local, or off."));
            return 0;
        }
        Settings cfg = Vocalist.settings();
        if ("tts".equals(which)) {
            cfg.ttsMode = mode;
        } else {
            cfg.sttMode = mode;
        }
        cfg.save();
        source.sendSuccess(() -> Component.literal(
                "\u00a76[Vocalist] \u00a77" + which + " set to " + mode + "."), true);
        return 1;
    }

    private static int use(CommandSourceStack source, String name) {
        Settings cfg = Vocalist.settings();
        if (!cfg.promote(name)) {
            String known = cfg.providers.stream()
                    .map(p -> p.name)
                    .collect(java.util.stream.Collectors.joining(", "));
            source.sendFailure(Component.literal(
                    "Unknown provider '" + name + "'. Known: " + known));
            return 0;
        }
        cfg.save();
        Minds.rebuild();
        source.sendSuccess(() -> Component.literal(
                "\u00a76[Vocalist] \u00a77Now using " + name + ". Chain: " + Minds.describe()), true);
        return 1;
    }

    /** /vocalist forget - wipe the memory of the creature you are talking to. */
    private static int forgetCurrent(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Start a conversation first, then forget."));
            return 0;
        }
        UUID creature = TalkHub.talkingTo(player.getUUID());
        if (creature == null) {
            source.sendFailure(Component.literal("You are not talking to anyone."));
            return 0;
        }
        Recall.of(player.serverLevel()).wipe(creature);
        source.sendSuccess(() -> Component.literal(
                "\u00a76[Vocalist] \u00a77The creature forgets everything you ever told it."), false);
        return 1;
    }

    /** /vocalist forget all - wipe every creature's memory in every dimension. */
    private static int forgetAll(CommandSourceStack source) {
        int creatures = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            creatures += Recall.of(level).wipeAll();
        }
        int finalCreatures = creatures;
        source.sendSuccess(() -> Component.literal(
                "\u00a76[Vocalist] \u00a77All memories wiped (" + finalCreatures + " creatures)."), true);
        return 1;
    }

    /** /vocalist forget <player> - remove that player's exchanges from all memories. */
    private static int forgetPlayer(CommandSourceStack source, String name) {
        int removed = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            removed += Recall.of(level).forgetPlayer(name);
        }
        int finalRemoved = removed;
        source.sendSuccess(() -> Component.literal(
                "\u00a76[Vocalist] \u00a77Removed " + finalRemoved
                        + " remembered exchanges with " + name + "."), true);
        return 1;
    }

    private static int test(CommandSourceStack source) {
        Minds.converse("You are a test echo. Reply with the single word: ready.", List.of())
                .whenComplete((reply, err) -> {
                    var server = source.getServer();
                    if (server == null) {
                        return;
                    }
                    server.execute(() -> {
                        if (err != null) {
                            source.sendFailure(Component.literal(
                                    "\u00a7c[Vocalist] Mind chain failed: " + err.getMessage()));
                        } else {
                            source.sendSuccess(() -> Component.literal(
                                    "\u00a76[Vocalist] \u00a7aMinds OK: \u00a7f" + reply), false);
                        }
                    });
                });
        return 1;
    }
}
