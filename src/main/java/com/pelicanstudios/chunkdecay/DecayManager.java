package com.pelicanstudios.chunkdecay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs the Chunk Decay minigame for one loaded world.
 *
 * Flow: a player leaves a chunk -> that chunk is queued with a countdown ->
 * when the countdown ends the chunk collapses top-down into air, a few layers per tick.
 */
public class DecayManager {
    /** Sync to clients, but skip neighbour updates (no water flooding / sand cascades into the void) and skip drops. */
    private static final int SET_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;
    /** Hard cap on blocks removed per server tick across all decaying chunks, to protect TPS. */
    private static final int MAX_BLOCKS_PER_TICK = 24000;
    private static final int AUTOSAVE_TICKS = 20 * 60;

    private static final SoundEvent SOUND_START = sound("entity.wither.spawn");
    private static final SoundEvent SOUND_COLLAPSE = sound("entity.wither.break_block");
    private static final SoundEvent SOUND_TICK = sound("block.stone_button.click_on");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final MinecraftServer server;
    private final Path saveFile;

    // Settings (saved per world)
    private boolean enabled = false;
    private int delaySeconds = 5;
    private int layersPerTick = 4;
    private boolean particles = true;
    private long chunksDecayed = 0;

    // Runtime state
    private long tick = 0;
    /** chunk key -> tick at which it starts collapsing */
    private final Map<String, Long> pending = new LinkedHashMap<>();
    private final List<Job> active = new ArrayList<>();
    private final Set<String> activeKeys = new HashSet<>();
    /** player -> chunk key they were standing in last tick */
    private final Map<UUID, String> lastChunk = new HashMap<>();
    private boolean dirty = false;

    public DecayManager(MinecraftServer server) {
        this.server = server;
        this.saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("chunkdecay.json").normalize();
    }

    // ------------------------------------------------------------------ ticking

    public void tick() {
        tick++;
        if (tick % AUTOSAVE_TICKS == 0 && dirty) {
            save();
        }
        if (!enabled) {
            return;
        }

        trackPlayers();
        startDueChunks();
        processJobs();
    }

    private void trackPlayers() {
        // Walk dimension by dimension so we always know which world each player is in.
        for (ServerWorld world : server.getWorlds()) {
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (player.isSpectator()) {
                    // Spectators can fly around freely without wrecking the world.
                    lastChunk.remove(player.getUuid());
                    continue;
                }
                if (!player.isAlive()) {
                    continue; // on the death screen - keep their last chunk until they respawn
                }

                String key = key(world, player.getChunkPos().toLong());
                String previous = lastChunk.put(player.getUuid(), key);
                if (previous != null && !previous.equals(key)) {
                    schedule(previous);
                }

                if (tick % 10 == 0) {
                    warn(player, world, key);
                }
            }
        }
    }

    /** The world a player is currently in (found by checking each dimension's player list). */
    public ServerWorld worldOf(ServerPlayerEntity player) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getPlayers().contains(player)) {
                return world;
            }
        }
        return null;
    }

    private void warn(ServerPlayerEntity player, ServerWorld world, String key) {
        Long due = pending.get(key);
        if (due != null) {
            long left = Math.max(0, due - tick);
            int seconds = (int) Math.ceil(left / 20.0);
            player.sendMessage(Text.literal("⚠ This chunk decays in " + seconds + "s - get out!")
                    .formatted(Formatting.RED, Formatting.BOLD), true);
            if (left <= 60 && (left % 20) < 10) {
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SOUND_TICK, SoundCategory.MASTER, 0.6f, 2.0f);
            }
        } else if (activeKeys.contains(key)) {
            player.sendMessage(Text.literal("☠ The ground is collapsing!")
                    .formatted(Formatting.DARK_RED, Formatting.BOLD), true);
        }
    }

    private void schedule(String key) {
        if (pending.containsKey(key) || activeKeys.contains(key)) {
            return;
        }
        pending.put(key, tick + delaySeconds * 20L);
        dirty = true;
    }

    private void startDueChunks() {
        Iterator<Map.Entry<String, Long>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() > tick) {
                continue;
            }
            String key = entry.getKey();
            ServerWorld world = worldFor(key);
            if (world == null) {
                it.remove();
                continue;
            }
            ChunkPos pos = new ChunkPos(chunkOf(key));
            if (!world.isChunkLoaded(pos.x, pos.z)) {
                continue; // wait until it loads again - it still decays the moment it does
            }
            it.remove();
            startJob(world, key, pos);
        }
    }

    private void startJob(ServerWorld world, String key, ChunkPos pos) {
        WorldChunk chunk = world.getChunk(pos.x, pos.z);
        int highest = chunk.getHighestNonEmptySection();
        if (highest < 0) {
            return; // already empty
        }
        int startY = (world.sectionIndexToCoord(highest) << 4) + 15;
        active.add(new Job(key, world.getRegistryKey(), pos.x, pos.z, startY));
        activeKeys.add(key);
        dirty = true;

        double cx = pos.getCenterX() + 0.5;
        double cz = pos.getCenterZ() + 0.5;
        for (ServerPlayerEntity player : world.getPlayers()) {
            double dx = player.getX() - cx;
            double dz = player.getZ() - cz;
            if (dx * dx + dz * dz < 64 * 64) {
                world.playSound(null, cx, player.getY(), cz, SOUND_COLLAPSE, SoundCategory.BLOCKS, 1.4f, 0.55f);
                break;
            }
        }
    }

    private void processJobs() {
        int budget = MAX_BLOCKS_PER_TICK;
        Iterator<Job> it = active.iterator();
        while (it.hasNext() && budget > 0) {
            Job job = it.next();
            ServerWorld world = server.getWorld(job.dim);
            if (world == null) {
                it.remove();
                activeKeys.remove(job.key);
                continue;
            }
            if (!world.isChunkLoaded(job.cx, job.cz)) {
                // Chunk unloaded mid-collapse: re-queue so it finishes when it loads again.
                it.remove();
                activeKeys.remove(job.key);
                pending.put(job.key, tick);
                continue;
            }

            budget = collapse(world, job, budget);

            if (job.y < world.getBottomY()) {
                it.remove();
                activeKeys.remove(job.key);
                chunksDecayed++;
                dirty = true;
            }
        }
    }

    /** Removes up to {@link #layersPerTick} layers of a chunk, top-down. Returns the remaining block budget. */
    private int collapse(ServerWorld world, Job job, int budget) {
        WorldChunk chunk = world.getChunk(job.cx, job.cz);
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = world.getBottomY();
        int baseX = job.cx << 4;
        int baseZ = job.cz << 4;
        BlockState air = Blocks.AIR.getDefaultState();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        int layers = 0;
        while (job.y >= bottom && layers < layersPerTick && budget > 0) {
            int index = world.getSectionIndex(job.y);
            ChunkSection section = (index >= 0 && index < sections.length) ? sections[index] : null;
            if (section == null || section.isEmpty()) {
                // Skip the whole empty section in one go.
                job.y = ((job.y >> 4) << 4) - 1;
                continue;
            }

            int localY = job.y & 15;
            int removed = 0;
            BlockState sample = null;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    BlockState state = section.getBlockState(lx, localY, lz);
                    if (state.isAir()) {
                        continue;
                    }
                    pos.set(baseX + lx, job.y, baseZ + lz);
                    if (state.hasBlockEntity()) {
                        // Remove first so chests etc. don't spill their items into the void.
                        world.removeBlockEntity(pos);
                    }
                    world.setBlockState(pos, air, SET_FLAGS);
                    removed++;
                    if (random.nextInt(removed) == 0) {
                        sample = state; // random pick for the particle colour
                    }
                }
            }

            if (particles && removed > 0 && sample != null && random.nextInt(2) == 0) {
                int count = Math.min(14, 2 + removed / 24);
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, sample),
                        baseX + 8.0, job.y + 0.5, baseZ + 8.0, count, 4.0, 0.25, 4.0, 0.05);
            }

            budget -= Math.max(removed, 16);
            job.y--;
            layers++;
        }
        return budget;
    }

    // ------------------------------------------------------------------ respawn safety

    /**
     * If the player respawns over the void (their spawn chunk decayed), give them a small
     * "lifeboat" platform so they don't fall straight back in. It decays too once they leave.
     */
    public void onRespawn(ServerPlayerEntity player) {
        if (!enabled) {
            return;
        }
        ServerWorld world = worldOf(player);
        if (world == null) {
            return;
        }
        BlockPos at = player.getBlockPos();
        int bottom = world.getBottomY();
        int top = world.getTopYInclusive();

        BlockPos.Mutable check = new BlockPos.Mutable();
        for (int y = Math.min(at.getY(), top); y >= bottom; y--) {
            check.set(at.getX(), y, at.getZ());
            if (!world.getBlockState(check).isAir()) {
                return; // there is ground below - nothing to do
            }
        }

        int platformY = at.getY() - 1;
        if (platformY <= bottom + 1) {
            platformY = 63; // spawned at the very bottom of the void - lift them to sea level
        }
        platformY = Math.max(bottom, Math.min(platformY, top - 2));

        BlockState grass = Blocks.GRASS_BLOCK.getDefaultState();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(new BlockPos(at.getX() + dx, platformY, at.getZ() + dz), grass, Block.NOTIFY_ALL);
            }
        }
        player.requestTeleport(at.getX() + 0.5, platformY + 1, at.getZ() + 0.5);
        player.sendMessage(Text.literal("Your respawn point had decayed, so you got a lifeboat platform. It decays too once you leave!")
                .formatted(Formatting.GOLD), false);
    }

    // ------------------------------------------------------------------ commands API

    public void start() {
        enabled = true;
        pending.clear();
        active.clear();
        activeKeys.clear();
        lastChunk.clear();
        // Everyone's current chunk is their starting point - nothing decays until they leave it.
        for (ServerWorld world : server.getWorlds()) {
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (!player.isSpectator()) {
                    lastChunk.put(player.getUuid(), key(world, player.getChunkPos().toLong()));
                }
            }
        }
        dirty = true;
        save();
    }

    public void stop() {
        enabled = false;
        pending.clear();
        active.clear();
        activeKeys.clear();
        lastChunk.clear();
        dirty = true;
        save();
    }

    public boolean isEnabled() { return enabled; }
    public int getDelaySeconds() { return delaySeconds; }
    public int getLayersPerTick() { return layersPerTick; }
    public boolean hasParticles() { return particles; }
    public long getChunksDecayed() { return chunksDecayed; }
    public int getQueuedCount() { return pending.size(); }
    public int getActiveCount() { return active.size(); }

    public void setDelaySeconds(int seconds) { delaySeconds = seconds; dirty = true; }
    public void setLayersPerTick(int layers) { layersPerTick = layers; dirty = true; }
    public void setParticles(boolean on) { particles = on; dirty = true; }

    public void resetStats() { chunksDecayed = 0; dirty = true; }

    public static SoundEvent startSound() { return SOUND_START; }

    // ------------------------------------------------------------------ save / load

    public void load() {
        if (!Files.exists(saveFile)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(saveFile, StandardCharsets.UTF_8)).getAsJsonObject();
            enabled = getBool(root, "enabled", false);
            delaySeconds = getInt(root, "delaySeconds", 5);
            layersPerTick = getInt(root, "layersPerTick", 4);
            particles = getBool(root, "particles", true);
            chunksDecayed = root.has("chunksDecayed") ? root.get("chunksDecayed").getAsLong() : 0;

            if (root.has("pending")) {
                for (JsonElement el : root.getAsJsonArray("pending")) {
                    JsonObject o = el.getAsJsonObject();
                    String key = o.get("key").getAsString();
                    long remaining = o.get("remainingTicks").getAsLong();
                    pending.put(key, tick + Math.max(0, remaining));
                }
            }
        } catch (Exception e) {
            ChunkDecay.LOGGER.error("[Chunk Decay] Could not read {}", saveFile, e);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        root.addProperty("delaySeconds", delaySeconds);
        root.addProperty("layersPerTick", layersPerTick);
        root.addProperty("particles", particles);
        root.addProperty("chunksDecayed", chunksDecayed);

        JsonArray list = new JsonArray();
        for (Map.Entry<String, Long> entry : pending.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", entry.getKey());
            o.addProperty("remainingTicks", Math.max(0, entry.getValue() - tick));
            list.add(o);
        }
        // Chunks mid-collapse restart (from the top, skipping what's already gone) on next load.
        for (Job job : active) {
            JsonObject o = new JsonObject();
            o.addProperty("key", job.key);
            o.addProperty("remainingTicks", 0);
            list.add(o);
        }
        root.add("pending", list);

        try {
            Path tmp = saveFile.resolveSibling("chunkdecay.json.tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, saveFile, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            ChunkDecay.LOGGER.error("[Chunk Decay] Could not save {}", saveFile, e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String key(ServerWorld world, long chunk) {
        return world.getRegistryKey().getValue().toString() + "|" + chunk;
    }

    private static long chunkOf(String key) {
        return Long.parseLong(key.substring(key.lastIndexOf('|') + 1));
    }

    private ServerWorld worldFor(String key) {
        Identifier id = Identifier.tryParse(key.substring(0, key.lastIndexOf('|')));
        if (id == null) {
            return null;
        }
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
    }

    private static SoundEvent sound(String path) {
        return SoundEvent.of(Identifier.of("minecraft", path));
    }

    private static boolean getBool(JsonObject o, String name, boolean fallback) {
        return o.has(name) ? o.get(name).getAsBoolean() : fallback;
    }

    private static int getInt(JsonObject o, String name, int fallback) {
        return o.has(name) ? o.get(name).getAsInt() : fallback;
    }

    private static final class Job {
        final String key;
        final RegistryKey<World> dim;
        final int cx;
        final int cz;
        /** Next block Y to clear (counts down). */
        int y;

        Job(String key, RegistryKey<World> dim, int cx, int cz, int y) {
            this.key = key;
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
            this.y = y;
        }
    }
}
