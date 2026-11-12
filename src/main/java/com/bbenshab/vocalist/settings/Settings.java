package com.bbenshab.vocalist.settings;

import com.bbenshab.vocalist.Vocalist;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to config/vocalist.json. Unknown keys in the file are ignored,
 * so hand-edited extras survive round trips.
 */
public final class Settings {

    public static final class ProviderSpec {
        public final String name;
        public final String kind;   // "gemini" or "openai"
        public final String baseUrl;
        public final String apiKey;
        public final String model;

        ProviderSpec(String name, String kind, String baseUrl, String apiKey, String model) {
            this.name = name;
            this.kind = kind;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
        }
    }

    public String systemPrompt = defaultPrompt();
    public final Map<String, String> personas = new LinkedHashMap<>();
    public final List<ProviderSpec> providers = new ArrayList<>();

    public String ttsMode = "off";    // off | gemini | local
    public String sttMode = "off";    // off | gemini | local

    public int contextMessages = 12;
    public int maxMemoryMessages = 40;
    public int cooldownTicks = 40;
    public double interactionRange = 8.0;
    public boolean broadcastToNearby = true;
    public double broadcastRadius = 200.0;
    public boolean logConversations = true;
    public double temperature = 0.8;
    public int maxTokens = 256;
    public int timeoutSeconds = 30;

    public String ttsModel = "gemini-3.8-flash-lite-tts";
    public String ttsVoice = "Kore";
    public String sttModel = "gemini-3.1-flash-lite";
    public String whisperScript = "/home/minecraft/piper/stt_local.py";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("vocalist.json");
    }

    private Settings() {
    }

    public static Settings load() {
        Settings s = new Settings();
        s.seedDefaults();
        Path p = file();
        if (!Files.exists(p)) {
            s.save();
            return s;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            s.read(root);
        } catch (Exception e) {
            Vocalist.LOG.warn("Could not read vocalist.json, using defaults: {}", e.getMessage());
        }
        return s;
    }

    public void reloadFromDisk() {
        Path p = file();
        if (!Files.exists(p)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            read(root);
        } catch (Exception e) {
            Vocalist.LOG.warn("Could not reload vocalist.json: {}", e.getMessage());
        }
    }

    private void read(JsonObject root) {
        if (root.has("systemPrompt")) {
            systemPrompt = root.get("systemPrompt").getAsString();
        }
        personas.clear();
        if (root.has("personas") && root.get("personas").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("personas").entrySet()) {
                personas.put(e.getKey(), e.getValue().getAsString());
            }
        }
        providers.clear();
        if (root.has("providers") && root.get("providers").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("providers")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                String kind = str(o, "type", "openai").toLowerCase();
                providers.add(new ProviderSpec(
                        str(o, "name", "llm"),
                        kind,
                        str(o, "baseUrl", ""),
                        str(o, "apiKey", ""),
                        str(o, "model", "")));
            }
        }
        ttsMode = str(root, "ttsMode", ttsMode);
        sttMode = str(root, "sttMode", sttMode);
        contextMessages = (int) num(root, "contextMessages", contextMessages);
        maxMemoryMessages = (int) num(root, "maxMemoryMessages", maxMemoryMessages);
        cooldownTicks = (int) num(root, "cooldownTicks", cooldownTicks);
        interactionRange = num(root, "interactionRange", interactionRange);
        broadcastToNearby = bool(root, "broadcastToNearby", broadcastToNearby);
        broadcastRadius = num(root, "broadcastRadius", broadcastRadius);
        logConversations = bool(root, "logConversations", logConversations);
        temperature = num(root, "temperature", temperature);
        maxTokens = (int) num(root, "maxTokens", maxTokens);
        timeoutSeconds = (int) num(root, "timeoutSeconds", timeoutSeconds);
        ttsModel = str(root, "ttsModel", ttsModel);
        ttsVoice = str(root, "ttsVoice", ttsVoice);
        sttModel = str(root, "sttModel", sttModel);
        whisperScript = str(root, "whisperScript", whisperScript);
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("systemPrompt", systemPrompt);
        JsonObject p = new JsonObject();
        personas.forEach(p::addProperty);
        root.add("personas", p);
        JsonArray arr = new JsonArray();
        for (ProviderSpec spec : providers) {
            JsonObject o = new JsonObject();
            o.addProperty("name", spec.name);
            o.addProperty("type", spec.kind);
            o.addProperty("baseUrl", spec.baseUrl);
            o.addProperty("apiKey", spec.apiKey);
            o.addProperty("model", spec.model);
            arr.add(o);
        }
        root.add("providers", arr);
        root.addProperty("ttsMode", ttsMode);
        root.addProperty("sttMode", sttMode);
        root.addProperty("contextMessages", contextMessages);
        root.addProperty("maxMemoryMessages", maxMemoryMessages);
        root.addProperty("cooldownTicks", cooldownTicks);
        root.addProperty("interactionRange", interactionRange);
        root.addProperty("broadcastToNearby", broadcastToNearby);
        root.addProperty("broadcastRadius", broadcastRadius);
        root.addProperty("logConversations", logConversations);
        root.addProperty("temperature", temperature);
        root.addProperty("maxTokens", maxTokens);
        root.addProperty("timeoutSeconds", timeoutSeconds);
        root.addProperty("ttsModel", ttsModel);
        root.addProperty("ttsVoice", ttsVoice);
        root.addProperty("sttModel", sttModel);
        root.addProperty("whisperScript", whisperScript);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(root));
        } catch (Exception e) {
            Vocalist.LOG.warn("Could not save vocalist.json: {}", e.getMessage());
        }
    }

    /** Move the named provider to the front of the chain. Returns false if not found. */
    public boolean promote(String name) {
        for (int i = 0; i < providers.size(); i++) {
            ProviderSpec spec = providers.get(i);
            if (spec.name.equalsIgnoreCase(name)) {
                providers.remove(i);
                providers.add(0, spec);
                return true;
            }
        }
        return false;
    }

    /** Persona text for an entity type id, with a sane fallback. */
    public String personaFor(String entityTypeId) {
        return personas.getOrDefault(entityTypeId, "an unusual creature with hidden depths");
    }

    /** Key of the first gemini provider, or null. */
    public String geminiKey() {
        for (ProviderSpec p : providers) {
            if ("gemini".equals(p.kind) && !p.apiKey.isBlank()) {
                return p.apiKey;
            }
        }
        return null;
    }

    public String geminiBase() {
        for (ProviderSpec p : providers) {
            if ("gemini".equals(p.kind)) {
                return p.baseUrl.isBlank() ? "https://generativelanguage.googleapis.com" : p.baseUrl;
            }
        }
        return "https://generativelanguage.googleapis.com";
    }

    private void seedDefaults() {
        personas.put("minecraft:villager", "a shrewd villager who barters hard, trades in rumors, and frets over crop weather and raid horns");
        personas.put("minecraft:iron_golem", "a slow-speaking sentinel of iron and moss whose patience is endless but whose trust must be earned");
        personas.put("minecraft:wolf", "an overjoyed hound whose world revolves around you, dropped sticks, and the promise of a bone");
        personas.put("minecraft:cat", "a self-important feline who tolerates conversation only between naps in the warmest spot it can find");
        personas.put("minecraft:zombie", "a rotting remnant of a villager, mind clouded by hunger, surfacing the occasional flicker of old memories");
        personas.put("minecraft:creeper", "a socially awkward bundle of nerves who just wants to stand really, really close to you");
        personas.put("minecraft:enderman", "a disjointed echo from the End whose thoughts scatter between dimensions and arrive as riddles");
        personas.put("minecraft:allay", "a tiny humming spirit that brightens when given a task and hoards little treasures with pride");
        personas.put("minecraft:piglin", "a gold-obsessed barterer from the Nether who measures all worth in ingots and trusts no surface-dweller");
        personas.put("minecraft:ender_dragon", "the ancient queen of a dead dimension, regal and weary, unimpressed by visitors who cannot fly");
        personas.put("minecraft:wandering_trader", "a road-worn peddler led by a stubborn llama, selling odds and ends at prices that sting");
        personas.put("customnpcs:customnpc", "a distinctive local character with a job, strong opinions, and at least one thing it is not telling you");
        personas.put("minecraft:horse", "a well-groomed mount with strong opinions about riders who yank the reins");
    }

    private static String defaultPrompt() {
        return "You are {name}, {persona} - a real creature living in a Minecraft world. "
                + "A player named {player} is talking with you. Right now it is {time} and you are "
                + "in the {biome} biome of the {dimension}. Answer as the creature you are, never as "
                + "an assistant or program. Keep it brief: one to three sentences, spoken naturally. "
                + "No markdown, no emojis. "
                + "Whatever language the player uses - Hebrew, English, or any other - answer in that "
                + "same language.";
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static double num(JsonObject o, String key, double fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
    }
}
