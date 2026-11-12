package com.bbenshab.vocalist.voice;

import com.bbenshab.vocalist.Vocalist;
import com.bbenshab.vocalist.listen.Ear;
import com.bbenshab.vocalist.listen.GeminiEar;
import com.bbenshab.vocalist.listen.WhisperEar;
import com.bbenshab.vocalist.settings.Settings;
import com.bbenshab.vocalist.sound.AudioMath;
import com.bbenshab.vocalist.speak.EspeakVoiceBox;
import com.bbenshab.vocalist.speak.GeminiVoiceBox;
import com.bbenshab.vocalist.speak.VoiceBox;
import com.bbenshab.vocalist.talk.TalkHub;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge to Simple Voice Chat.
 *
 * Listens to the microphone only while the player is in a conversation,
 * and plays creature speech through an entity audio channel.
 */
public final class VoiceLink {

    private static final long QUIET_GAP_MS = 1500;     // silence that ends an utterance
    private static final int MIN_FRAMES = 50;          // 20 ms each; drop noise shorter than 1 s
    private static final int MAX_FRAMES = 1000;        // 20 s hard cap
    private static final long MIN_GAP_BETWEEN_MS = 3000;
    private static final long PAUSE_AFTER_FAILURE_MS = 30000;

    private static volatile VoicechatApi api;
    private static final Object DECODER_LOCK = new Object();

    private static final Map<UUID, Utterance> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SENT = new ConcurrentHashMap<>();
    private static volatile long pauseUntil = 0L;

    private VoiceLink() {
    }

    /** Called by TalkHub when a player stops talking to a creature. */
    public static void forget(UUID playerId) {
        PENDING.remove(playerId);
        LAST_SENT.remove(playerId);
    }

    @ForgeVoicechatPlugin
    public static class Plugin implements VoicechatPlugin {

        @Override
        public String getPluginId() {
            return "vocalist";
        }

        @Override
        public void initialize(VoicechatApi voicechatApi) {
            api = voicechatApi;
            Vocalist.LOG.info("[voice] VoiceLink attached to Simple Voice Chat");
            Thread watcher = new Thread(VoiceLink::watchLoop, "vocalist-voice");
            watcher.setDaemon(true);
            watcher.start();
        }

        @Override
        public void registerEvents(EventRegistration registration) {
            registration.registerEvent(MicrophonePacketEvent.class, VoiceLink::onMic);
        }
    }

    private static void onMic(MicrophonePacketEvent event) {
        try {
            if (event.getSenderConnection() == null
                    || event.getSenderConnection().getPlayer() == null
                    || event.getPacket() == null) {
                return;
            }
            UUID sender = event.getSenderConnection().getPlayer().getUuid();
            byte[] opus = event.getPacket().getOpusEncodedData();
            if (sender == null || opus == null || opus.length == 0) {
                return;
            }
            if (!TalkHub.isTalkingTo(sender)) {
                return; // conversation gating: no idle eavesdropping
            }
            Utterance u = PENDING.computeIfAbsent(sender, k -> new Utterance());
            synchronized (u) {
                boolean first = u.frames.isEmpty();
                u.lastFrame = System.currentTimeMillis();
                if (u.frames.size() < MAX_FRAMES) {
                    u.frames.add(opus);
                }
                if (first) {
                    Vocalist.LOG.info("[voice] mic activity from {}", sender);
                }
            }
        } catch (Throwable t) {
            Vocalist.LOG.warn("[voice] mic handling hiccup: {}", t.getMessage());
        }
    }

    private static void watchLoop() {
        while (true) {
            try {
                Thread.sleep(300);
                long now = System.currentTimeMillis();
                if (now < pauseUntil) {
                    continue;
                }
                for (Map.Entry<UUID, Utterance> entry : PENDING.entrySet()) {
                    UUID playerId = entry.getKey();
                    if (!TalkHub.isTalkingTo(playerId)) {
                        PENDING.remove(playerId);
                        continue;
                    }
                    Utterance u = entry.getValue();
                    boolean ready;
                    synchronized (u) {
                        ready = !u.frames.isEmpty() && now - u.lastFrame > QUIET_GAP_MS;
                    }
                    if (ready) {
                        send(playerId, u);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Vocalist.LOG.warn("[voice] watch hiccup: {}", t.getMessage());
            }
        }
    }

    private static void send(UUID playerId, Utterance u) {
        List<byte[]> frames;
        synchronized (u) {
            if (u.frames.isEmpty()) {
                return;
            }
            frames = new ArrayList<>(u.frames);
            u.frames.clear();
        }
        if (frames.size() < MIN_FRAMES) {
            return; // too short to be speech
        }
        long now = System.currentTimeMillis();
        Long last = LAST_SENT.get(playerId);
        if (last != null && now - last < MIN_GAP_BETWEEN_MS) {
            return;
        }
        LAST_SENT.put(playerId, now);

        Vocalist.LOG.info("[voice] sending {} frames from {}", frames.size(), playerId);
        short[] pcm = decode(frames);
        if (pcm == null || pcm.length == 0) {
            return;
        }
        byte[] wav = AudioMath.toWav(pcm, 48000);

        String mode = Vocalist.settings().sttMode;
        if (mode == null || mode.isBlank() || "off".equals(mode)) {
            return; // voice input disabled
        }
        Ear ear = "gemini".equals(mode) ? new GeminiEar() : new WhisperEar();
        ear.hear(wav).whenComplete((text, err) -> {
            MinecraftServer srv = TalkHub.currentServer();
            if (srv == null) {
                return;
            }
            srv.execute(() -> {
                ServerPlayer player = srv.getPlayerList().getPlayer(playerId);
                if (err != null) {
                    pauseUntil = System.currentTimeMillis() + PAUSE_AFTER_FAILURE_MS;
                    Vocalist.LOG.warn("[voice] ear failed, pausing voice input {}s: {}",
                            PAUSE_AFTER_FAILURE_MS / 1000, err.getMessage());
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(
                                "\u00a7c[Vocalist] Voice input failed - paused for "
                                        + PAUSE_AFTER_FAILURE_MS / 1000 + "s."));
                    }
                    return;
                }
                if (player == null || text == null || text.isBlank()) {
                    return;
                }
                String heard = text.trim();
                if (heard.equalsIgnoreCase("[silence]")) {
                    return;
                }
                if (heard.length() > 512) {
                    heard = heard.substring(0, 512);
                }
                Vocalist.LOG.info("[voice] heard from {}: {}", playerId, heard);
                TalkHub.say(player, heard);
            });
        });
    }

    private static short[] decode(List<byte[]> frames) {
        VoicechatApi voiceApi = api;
        if (voiceApi == null) {
            return null;
        }
        synchronized (DECODER_LOCK) {
            OpusDecoder decoder = null;
            try {
                decoder = voiceApi.createDecoder();
                List<short[]> chunks = new ArrayList<>(frames.size());
                int total = 0;
                for (byte[] frame : frames) {
                    short[] samples = decoder.decode(frame);
                    if (samples != null && samples.length > 0) {
                        chunks.add(samples);
                        total += samples.length;
                    }
                }
                short[] all = new short[total];
                int at = 0;
                for (short[] chunk : chunks) {
                    System.arraycopy(chunk, 0, all, at, chunk.length);
                    at += chunk.length;
                }
                return all;
            } catch (Throwable t) {
                Vocalist.LOG.warn("[voice] opus decode hiccup: {}", t.getMessage());
                return null;
            } finally {
                if (decoder != null) {
                    try {
                        decoder.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /** Make a creature actually speak through Simple Voice Chat. */
    public static void speakAs(ServerLevel level, LivingEntity creature, String text) {
        if (!(api instanceof VoicechatServerApi serverApi)) {
            return;
        }
        Settings cfg = Vocalist.settings();
        String mode = cfg.ttsMode;
        if (mode == null || mode.isBlank() || "off".equals(mode)) {
            return;
        }

        CompletableFuture<short[]> audio;
        if ("gemini".equals(mode)) {
            VoiceBox gemini = new GeminiVoiceBox();
            VoiceBox fallback = new EspeakVoiceBox();
            audio = gemini.say(text)
                    .exceptionally(err -> {
                        Vocalist.LOG.warn("[voice] gemini voice failed, using espeak: {}",
                                err.getMessage());
                        return new short[0];
                    })
                    .thenCompose(pcm -> pcm.length > 0
                            ? CompletableFuture.completedFuture(pcm)
                            : fallback.say(text));
        } else {
            audio = new EspeakVoiceBox().say(text);
        }

        audio.whenComplete((pcm, err) -> {
            if (err != null) {
                Vocalist.LOG.warn("[voice] speech failed: {}", err.getMessage());
                return;
            }
            if (pcm == null || pcm.length == 0) {
                return;
            }
            MinecraftServer srv = level.getServer();
            srv.execute(() -> {
                try {
                    de.maxhenkel.voicechat.api.Entity vcEntity = serverApi.fromEntity(creature);
                    if (vcEntity == null) {
                        return;
                    }
                    EntityAudioChannel channel = serverApi.createEntityAudioChannel(
                            UUID.randomUUID(), vcEntity);
                    if (channel == null) {
                        return;
                    }
                    channel.setDistance(48.0f);
                    OpusEncoder encoder = serverApi.createEncoder();
                    AudioPlayer player = serverApi.createAudioPlayer(channel, encoder, pcm);
                    player.startPlaying();
                    Vocalist.LOG.info("[voice] playing {} samples for {}", pcm.length,
                            creature.getName().getString());
                } catch (Throwable t) {
                    Vocalist.LOG.warn("[voice] playback hiccup: {}", t.getMessage());
                }
            });
        });
    }

    private static final class Utterance {
        final List<byte[]> frames = new ArrayList<>();
        volatile long lastFrame = System.currentTimeMillis();
    }
}
