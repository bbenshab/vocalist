package com.bbenshab.vocalist.talk;

import com.bbenshab.vocalist.Vocalist;
import com.bbenshab.vocalist.brain.Line;
import com.bbenshab.vocalist.brain.Minds;
import com.bbenshab.vocalist.remember.Recall;
import com.bbenshab.vocalist.settings.Settings;
import com.bbenshab.vocalist.voice.VoiceLink;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One conversation per creature, one creature per player. Routes chat text
 * through the mind chain and hands the reply to the speaker, nearby players,
 * and the voice link.
 */
public final class TalkHub {

    private record Session(UUID player, UUID creature, long openedAt, long lastActivity) {
    }

    private static final long IDLE_SESSION_MS = 5 * 60 * 1000L;
    private static final long BUSY_TIMEOUT_MS = 30 * 1000L;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> CREATURE_LOCKS = new ConcurrentHashMap<>(); // creature -> player
    private static final Map<UUID, Long> BUSY_SINCE = new ConcurrentHashMap<>();     // creature -> epoch ms
    private static final Map<UUID, Integer> LAST_CHAT_TICK = new ConcurrentHashMap<>();

    private static volatile MinecraftServer server;
    private static Thread janitor;

    private TalkHub() {
    }

    public static void attach(MinecraftServer srv) {
        server = srv;
        if (janitor == null || !janitor.isAlive()) {
            janitor = new Thread(TalkHub::janitorLoop, "vocalist-janitor");
            janitor.setDaemon(true);
            janitor.start();
        }
    }

    public static void detach() {
        server = null;
    }

    public static MinecraftServer currentServer() {
        return server;
    }

    public static boolean isTalking(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    public static boolean isTalkingTo(UUID playerId) {
        return SESSIONS.containsKey(playerId);
    }

    /** The creature the player is talking to, or null. */
    public static UUID talkingTo(UUID playerId) {
        Session session = SESSIONS.get(playerId);
        return session == null ? null : session.creature();
    }

    public static int sessionCount() {
        return SESSIONS.size();
    }

    public static void open(ServerPlayer player, LivingEntity creature) {
        UUID creatureId = creature.getUUID();
        // tapping the creature you are already talking to ends the conversation
        Session current = SESSIONS.get(player.getUUID());
        if (current != null && current.creature().equals(creatureId)) {
            end(player, true);
            return;
        }
        if (creature.isRemoved() || creature.isDeadOrDying()) {
            fail(player, "That creature is gone.");
            return;
        }
        if (!withinReach(player, creature)) {
            fail(player, "You are too far away to talk.");
            return;
        }
        UUID holder = CREATURE_LOCKS.get(creatureId);
        if (holder != null && !holder.equals(player.getUUID())) {
            Session other = SESSIONS.get(holder);
            if (other != null && System.currentTimeMillis() - other.lastActivity() < IDLE_SESSION_MS) {
                fail(player, "Someone else is already talking to " + creature.getName().getString() + ".");
                return;
            }
            CREATURE_LOCKS.remove(creatureId); // stale lock
        }

        // release whatever this player was in before
        end(player, false);

        SESSIONS.put(player.getUUID(), new Session(player.getUUID(), creatureId,
                System.currentTimeMillis(), System.currentTimeMillis()));
        CREATURE_LOCKS.put(creatureId, player.getUUID());
        drawIntro(player, creature);
    }

    public static void end(ServerPlayer player, boolean announce) {
        Session session = SESSIONS.remove(player.getUUID());
        VoiceLink.forget(player.getUUID());
        LAST_CHAT_TICK.remove(player.getUUID());
        if (session == null) {
            return;
        }
        UUID holder = CREATURE_LOCKS.get(session.creature());
        if (player.getUUID().equals(holder)) {
            CREATURE_LOCKS.remove(session.creature());
        }
        BUSY_SINCE.remove(session.creature());
        if (announce) {
            player.sendSystemMessage(Component.literal("\u00a76[Vocalist] \u00a77Conversation ended."));
        }
    }

    /** The main entry point for everything a player says while talking. */
    public static void say(ServerPlayer player, String raw) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }
        String lower = text.toLowerCase();
        if (text.startsWith("/") || lower.equals("stop") || lower.equals("bye")
                || lower.equals("end") || lower.equals("goodbye")) {
            end(player, true);
            return;
        }
        if (text.length() > 512) {
            text = text.substring(0, 512);
        }

        Entity found = findCreature(player, session.creature());
        if (!(found instanceof LivingEntity creature) || creature.isRemoved() || creature.isDeadOrDying()) {
            end(player, false);
            fail(player, "That creature is gone. Conversation ended.");
            return;
        }
        if (!withinReach(player, creature)) {
            end(player, false);
            fail(player, "You are too far away. Conversation ended.");
            return;
        }

        Long busy = BUSY_SINCE.get(session.creature());
        if (busy != null) {
            if (System.currentTimeMillis() - busy < BUSY_TIMEOUT_MS) {
                fail(player, creature.getName().getString() + " is still responding. Wait a moment.");
                return;
            }
            BUSY_SINCE.remove(session.creature()); // stale busy flag
        }
        if (!cooldownPassed(player)) {
            fail(player, "Wait a moment before speaking again.");
            return;
        }

        SESSIONS.put(player.getUUID(), new Session(session.player(), session.creature(),
                session.openedAt(), System.currentTimeMillis()));
        BUSY_SINCE.put(session.creature(), System.currentTimeMillis());

        player.sendSystemMessage(Component.literal("\u00a76[You] \u00a7f" + text));
        Settings cfg = Vocalist.settings();
        String speaker = player.getName().getString();
        String creatureName = creature.getName().getString();
        String typeId = typeIdOf(creature);

        Recall recall = Recall.of(player.serverLevel());
        recall.add(session.creature(), Line.user(speaker + " says: " + text), cfg.maxMemoryMessages);

        List<Line> turns = recall.recent(session.creature(), Math.max(2, cfg.contextMessages));
        String marker = speaker + " says:";
        boolean knowsPlayer = turns.stream().anyMatch(
                l -> "user".equals(l.role()) && l.text().startsWith(marker));
        String instructions = instructionsFor(creature, speaker, typeId, knowsPlayer);
        if (cfg.logConversations) {
            Vocalist.LOG.info("[talk] {} -> {}: {}", speaker, creatureName, text);
        }

        Minds.converse(instructions, turns).whenComplete((reply, err) -> {
            MinecraftServer srv = server;
            if (srv == null) {
                return;
            }
            srv.execute(() -> {
                BUSY_SINCE.remove(session.creature());
                if (err != null) {
                    Vocalist.LOG.warn("[talk] mind chain failed: {}", err.getMessage());
                    fail(player, "The creature stares blankly. (" + err.getMessage() + ")");
                    return;
                }
                if (creature.isRemoved() || creature.isDeadOrDying()) {
                    return;
                }
                String clean = tidy(reply);
                if (clean.isEmpty()) {
                    fail(player, "The creature has nothing to say.");
                    return;
                }
                recall.add(session.creature(), Line.assistant(clean), cfg.maxMemoryMessages);
                if (cfg.logConversations) {
                    Vocalist.LOG.info("[talk] {}: {}", creatureName, clean);
                }
                deliver(player, creature, creatureName, clean);
            });
        });
    }

    private static void deliver(ServerPlayer player, LivingEntity creature,
            String creatureName, String reply) {
        player.sendSystemMessage(Component.literal("\u00a76[" + creatureName + "] \u00a7f" + reply));
        Settings cfg = Vocalist.settings();
        if (cfg.broadcastToNearby) {
            double r2 = cfg.broadcastRadius * cfg.broadcastRadius;
            for (ServerPlayer nearby : player.serverLevel().players()) {
                if (nearby != player && nearby.distanceToSqr(creature.position()) < r2) {
                    nearby.sendSystemMessage(Component.literal(
                            "\u00a76[" + creatureName + "] \u00a7f" + reply));
                }
            }
        }
        VoiceLink.speakAs(player.serverLevel(), creature, reply);
    }

    private static String instructionsFor(LivingEntity creature, String playerName,
            String typeId, boolean knowsPlayer) {
        Settings cfg = Vocalist.settings();
        String biome = "unknown";
        String clock = "daytime";
        String dimension = "overworld";
        if (creature.level() instanceof ServerLevel level) {
            dimension = level.dimension().location().getPath();
            clock = level.isDay() ? "daytime" : "night";
            biome = level.getBiome(creature.blockPosition()).unwrapKey()
                    .map(k -> k.location().getPath()).orElse("unknown");
        }
        String prompt = cfg.systemPrompt
                .replace("{persona}", cfg.personaFor(typeId))
                .replace("{name}", creature.getName().getString())
                .replace("{player}", playerName == null ? "a traveller" : playerName)
                .replace("{biome}", biome)
                .replace("{time}", clock)
                .replace("{dimension}", dimension);
        // memory awareness is factual, not assumed: only claim memories that exist
        prompt += knowsPlayer
                ? " You remember earlier meetings with this player."
                : " You have never spoken with this player before - treat them as a stranger.";
        return prompt;
    }

    private static void drawIntro(ServerPlayer player, LivingEntity creature) {
        String name = creature.getName().getString();
        player.sendSystemMessage(Component.literal(""));
        player.sendSystemMessage(Component.literal("\u00a76\u2026\u2026\u2026 Talking to \u00a7f" + name + " \u00a76\u2026\u2026\u2026"));
        List<Line> history = Recall.of(player.serverLevel())
                .recent(creature.getUUID(), 10);
        if (history.isEmpty()) {
            player.sendSystemMessage(Component.literal("\u00a77 \u2026 (it has nothing to say yet)"));
        } else {
            for (Line line : history) {
                String text = tidy(line.text());
                if (text.length() > 120) {
                    text = text.substring(0, 117) + "...";
                }
                if ("assistant".equals(line.role())) {
                    player.sendSystemMessage(Component.literal("\u00a77 \u00a7f" + name + ":\u00a77 " + text));
                } else {
                    player.sendSystemMessage(Component.literal("\u00a77 \u00a78" + text));
                }
            }
        }
        boolean voiceOn = !"off".equals(Vocalist.settings().sttMode);
        player.sendSystemMessage(Component.literal(voiceOn
                ? "\u00a77 \u00a7fIt is listening. Speak or write - sneak-tap again to leave."
                : "\u00a77 \u00a7fIt is listening. Write below - sneak-tap again to leave."));
        player.sendSystemMessage(Component.literal("\u00a76\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026\u2026"));
        player.sendSystemMessage(Component.literal(""));
    }

    private static boolean withinReach(ServerPlayer player, LivingEntity creature) {
        double reach = Vocalist.settings().interactionRange + 2.0;
        return player.distanceToSqr(creature) <= reach * reach;
    }

    private static boolean cooldownPassed(ServerPlayer player) {
        MinecraftServer srv = player.getServer();
        if (srv == null) {
            return true;
        }
        int now = srv.getTickCount();
        Integer last = LAST_CHAT_TICK.get(player.getUUID());
        if (last != null && now - last < Vocalist.settings().cooldownTicks) {
            return false;
        }
        LAST_CHAT_TICK.put(player.getUUID(), now);
        return true;
    }

    private static Entity findCreature(ServerPlayer player, UUID creatureId) {
        for (Entity e : player.serverLevel().getAllEntities()) {
            if (e.getUUID().equals(creatureId)) {
                return e;
            }
        }
        return null;
    }

    private static String typeIdOf(LivingEntity creature) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(creature.getType());
        return id == null ? "minecraft:unknown" : id.toString();
    }

    private static String tidy(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", " ").replace("*", "").trim();
    }

    private static void fail(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("\u00a7c[Vocalist] " + message));
    }

    /** Ends sessions that went quiet and releases stale busy flags. */
    private static void janitorLoop() {
        while (true) {
            try {
                Thread.sleep(30_000);
                MinecraftServer srv = server;
                long now = System.currentTimeMillis();
                SESSIONS.values().removeIf(session -> {
                    if (now - session.lastActivity() < IDLE_SESSION_MS) {
                        return false;
                    }
                    UUID holder = CREATURE_LOCKS.get(session.creature());
                    if (session.player().equals(holder)) {
                        CREATURE_LOCKS.remove(session.creature());
                    }
                    VoiceLink.forget(session.player());
                    LAST_CHAT_TICK.remove(session.player());
                    if (srv != null) {
                        srv.execute(() -> {
                            ServerPlayer p = srv.getPlayerList().getPlayer(session.player());
                            if (p != null) {
                                p.sendSystemMessage(Component.literal(
                                        "\u00a76[Vocalist] \u00a77The conversation drifted away (idle)."));
                            }
                        });
                    }
                    return true;
                });
                BUSY_SINCE.values().removeIf(since -> now - since > BUSY_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Vocalist.LOG.warn("[talk] janitor hiccup: {}", t.getMessage());
            }
        }
    }
}
