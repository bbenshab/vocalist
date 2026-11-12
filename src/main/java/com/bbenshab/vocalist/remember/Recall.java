package com.bbenshab.vocalist.remember;

import com.bbenshab.vocalist.brain.Line;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/** Persistent per-NPC conversation memory, stored in the world save. */
public final class Recall extends SavedData {

    private static final String DATA_NAME = "vocalist_recall";

    private final Map<UUID, List<Line>> logs = new HashMap<>();

    private Recall() {
    }

    public static Recall of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Recall::fromNbt, Recall::new, DATA_NAME);
    }

    public static Recall fromNbt(CompoundTag root) {
        Recall recall = new Recall();
        CompoundTag npcs = root.getCompound("npcs");
        for (String id : npcs.getAllKeys()) {
            ListTag log = npcs.getCompound(id).getList("log", Tag.TAG_COMPOUND);
            List<Line> lines = new ArrayList<>(log.size());
            for (int i = 0; i < log.size(); i++) {
                CompoundTag entry = log.getCompound(i);
                lines.add(new Line(entry.getString("role"), entry.getString("text")));
            }
            recall.logs.put(UUID.fromString(id), lines);
        }
        return recall;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        CompoundTag npcs = new CompoundTag();
        logs.forEach((id, lines) -> {
            CompoundTag npc = new CompoundTag();
            ListTag log = new ListTag();
            for (Line line : lines) {
                CompoundTag entry = new CompoundTag();
                entry.putString("role", line.role());
                entry.putString("text", line.text());
                log.add(entry);
            }
            npc.put("log", log);
            npcs.put(id.toString(), npc);
        });
        root.put("npcs", npcs);
        return root;
    }

    /** Append a turn, trimming the log to the cap. */
    public void add(UUID npc, Line line, int cap) {
        List<Line> lines = logs.computeIfAbsent(npc, k -> new ArrayList<>());
        lines.add(line);
        while (lines.size() > cap) {
            lines.remove(0);
        }
        setDirty();
    }

    /** The most recent turns, oldest first. */
    public List<Line> recent(UUID npc, int count) {
        List<Line> lines = logs.get(npc);
        if (lines == null || lines.isEmpty() || count <= 0) {
            return List.of();
        }
        int from = Math.max(0, lines.size() - count);
        return new ArrayList<>(lines.subList(from, lines.size()));
    }

    /** Forget everything one creature remembers. */
    public void wipe(UUID npc) {
        if (logs.remove(npc) != null) {
            setDirty();
        }
    }

    /** Forget everything every creature remembers. Returns how many creatures were affected. */
    public int wipeAll() {
        int affected = logs.size();
        if (affected > 0) {
            logs.clear();
            setDirty();
        }
        return affected;
    }

    /**
     * Remove every remembered exchange that a given player took part in
     * (their line plus the reply that followed it). Returns exchanges removed.
     */
    public int forgetPlayer(String playerName) {
        String marker = (playerName + " says:").toLowerCase();
        int removed = 0;
        for (List<Line> lines : logs.values()) {
            List<Line> kept = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                Line line = lines.get(i);
                if ("user".equals(line.role())
                        && line.text().toLowerCase().startsWith(marker)) {
                    removed++;
                    if (i + 1 < lines.size() && "assistant".equals(lines.get(i + 1).role())) {
                        i++; // drop the paired reply too
                    }
                    continue;
                }
                kept.add(line);
            }
            if (kept.size() != lines.size()) {
                lines.clear();
                lines.addAll(kept);
            }
        }
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }
}
