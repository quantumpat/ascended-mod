package net.quantumpat.ascendedmod.event.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.quantumpat.ascendedmod.AscendedMod;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Leaf-only accelerated decay.
 *
 * Goal: feel like randomTickSpeed=120 but only for leaves.
 *
 * Random sampling often doesn't *look* fast because you may miss the canopy you're staring at.
 * This implementation is queue-driven: after trees are chopped we enqueue the entire nearby canopy region
 * and process a fixed number of leaf candidates per tick, which guarantees many leaves disappear per second.
 */
@Mod.EventBusSubscriber(modid = AscendedMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FastLeafDecayEvents {

    /**
     * How many leaf candidates to evaluate per tick in each loaded level.
     * Increase for faster visible decay; decreases TPS if too high.
     */
    private static final int CHECKS_PER_TICK = 2500;

    /**
     * How many new positions we enqueue per tick from the background sweep.
     */
    private static final int ENQUEUE_PER_TICK = 1200;

    /**
     * Radius around each player for background queue fill (loaded chunks).
     */
    private static final int BACKGROUND_RADIUS_XZ = 64;

    /**
     * Vertical range above player to consider for leaves.
     */
    private static final int BACKGROUND_Y_UP = 96;

    /**
     * Vanilla decay distance is 6.
     */
    private static final int LOG_SEARCH_RADIUS = 6;

    /**
     * Burst region radius (XZ) when a tree falls.
     */
    private static final int BURST_RADIUS_XZ = 12;

    /**
     * Burst region radius (Y) when a tree falls.
     */
    private static final int BURST_RADIUS_Y = 16;

    private static final BooleanProperty PERSISTENT = BooleanProperty.create("persistent");

    /**
     * Scheduled leaf removal job.
     */
    private record ScheduledLeaf(String dimensionKey, BlockPos pos, long removeAtGameTime) {}

    // Simple global queue + dedupe set.
    private static final ArrayDeque<BlockPos> QUEUE = new ArrayDeque<>();
    private static final HashSet<BlockPos> ENQUEUED = new HashSet<>();

    /**
     * Burst job: a region to process plus the specific log positions belonging to the felled tree.
     * Leaves will only be kept if they are within range of THESE logs.
     */
    private record BurstJob(String dimensionKey, BlockPos center, Set<BlockPos> treeLogs) {}

    private static final ArrayDeque<BurstJob> BURSTS = new ArrayDeque<>();

    /**
     * If true, leaves decay fast purely based on being natural (not player-placed),
     * ignoring nearby logs entirely.
     */
    private static final boolean LOG_INDEPENDENT_DECAY = true;

    /**
     * Minimum delay (in ticks) before a natural leaf is eligible to be removed.
     */
    private static final int MIN_DECAY_DELAY_TICKS = 10;

    /**
     * Maximum delay (in ticks) before a natural leaf is eligible to be removed.
     */
    private static final int MAX_DECAY_DELAY_TICKS = 40;


    /**
     * Maximum leaves removed per tick (hard cap so we don't tank TPS).
     */
    private static final int MAX_REMOVALS_PER_TICK = 300;

    /**
     * Time wheel size (must be > MAX_DECAY_DELAY_TICKS).
     */
    private static final int WHEEL_SIZE = 64;

    /**
     * Ring buffer of due leaf positions.
     */
    private static final ArrayDeque<ScheduledLeaf>[] WHEEL = createWheel();

    /**
     * De-dupe scheduled leaves so we don't schedule the same leaf 100 times.
     */
    private static final HashSet<ScheduledKey> SCHEDULED_KEYS = new HashSet<>();

    private static ArrayDeque<ScheduledLeaf>[] createWheel() {
        @SuppressWarnings("unchecked")
        ArrayDeque<ScheduledLeaf>[] w = new ArrayDeque[WHEEL_SIZE];
        for (int i = 0; i < WHEEL_SIZE; i++) w[i] = new ArrayDeque<>();
        return w;
    }

    private record ScheduledKey(String dimensionKey, BlockPos pos) {}


    /**
     * Called by tree-felling logic to request faster leaf cleanup around a tree.
     *
     * We enqueue a cube around the felled area so you get immediate visible decay.
     */
    public static void enqueueBurst(ServerLevel level, Iterable<BlockPos> treeBlocks) {
        // Compute center + collect tree logs only
        long sx = 0, sy = 0, sz = 0;
        int n = 0;
        HashSet<BlockPos> logs = new HashSet<>();

        for (BlockPos p : treeBlocks) {
            sx += p.getX();
            sy += p.getY();
            sz += p.getZ();
            n++;

            BlockState st = level.getBlockState(p);
            if (st.is(BlockTags.LOGS)) {
                logs.add(p.immutable());
            }
        }
        if (n <= 0) return;

        BlockPos center = new BlockPos((int) (sx / n), (int) (sy / n), (int) (sz / n));
        BURSTS.addLast(new BurstJob(level.dimension().location().toString(), center, logs));

        // Also enqueue the region immediately so it starts processing this tick.
        enqueueBurstRegion(level, center);
    }

    private static void enqueueBurstRegion(ServerLevel level, BlockPos center) {
        int minX = center.getX() - BURST_RADIUS_XZ;
        int maxX = center.getX() + BURST_RADIUS_XZ;
        int minY = center.getY() - BURST_RADIUS_Y;
        int maxY = center.getY() + BURST_RADIUS_Y;
        int minZ = center.getZ() - BURST_RADIUS_XZ;
        int maxZ = center.getZ() + BURST_RADIUS_XZ;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) continue;
                    enqueue(pos);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        // Execute due removals for this tick via time wheel
        runScheduledWheel(level);

        // 1) Background enqueue around players
        if (!level.players().isEmpty()) {
            backgroundEnqueue(level);
        }

        // Process queue
        int processed = 0;
        while (processed < CHECKS_PER_TICK && !QUEUE.isEmpty()) {
            BlockPos pos = QUEUE.removeFirst();
            ENQUEUED.remove(pos);
            processed++;

            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) continue;

            // When in log-independent mode, schedule natural leaves for fast removal.
            if (LOG_INDEPENDENT_DECAY) {
                maybeScheduleLeafRemoval(level, pos);
                continue;
            }

            // Prefer burst-specific behavior if the position lies within an active burst region.
            BurstJob burst = findBurstFor(level, pos);
            if (burst != null) {
                maybeDecayLeafForBurst(level, pos, burst.treeLogs);
            } else {
                maybeDecayLeaf(level, pos);
            }
        }
    }

    private static void runScheduledWheel(ServerLevel level) {
        String dimKey = level.dimension().location().toString();
        long now = level.getGameTime();

        int slot = (int) (now % WHEEL_SIZE);
        ArrayDeque<ScheduledLeaf> bucket = WHEEL[slot];

        int removed = 0;
        while (removed < MAX_REMOVALS_PER_TICK && !bucket.isEmpty()) {
            ScheduledLeaf job = bucket.removeFirst();

            // Different dimension? ignore (also un-dedupe)
            if (!job.dimensionKey.equals(dimKey)) {
                SCHEDULED_KEYS.remove(new ScheduledKey(job.dimensionKey, job.pos));
                continue;
            }

            // Not due yet (we allow collisions in the wheel). Reinsert for its real due slot.
            if (job.removeAtGameTime > now) {
                int realSlot = (int) (job.removeAtGameTime % WHEEL_SIZE);
                WHEEL[realSlot].addLast(job);
                continue;
            }

            // Skip unloaded chunks
            if (level.getChunkSource().getChunkNow(job.pos.getX() >> 4, job.pos.getZ() >> 4) == null) {
                SCHEDULED_KEYS.remove(new ScheduledKey(job.dimensionKey, job.pos));
                continue;
            }

            BlockState state = level.getBlockState(job.pos);
            if (!state.is(BlockTags.LEAVES)) {
                SCHEDULED_KEYS.remove(new ScheduledKey(job.dimensionKey, job.pos));
                continue;
            }

            if (state.getProperties().contains(PERSISTENT) && state.getValue(PERSISTENT)) {
                SCHEDULED_KEYS.remove(new ScheduledKey(job.dimensionKey, job.pos));
                continue;
            }
            if (PlayerPlacedEvents.isPlayerPlaced(level, job.pos)) {
                SCHEDULED_KEYS.remove(new ScheduledKey(job.dimensionKey, job.pos));
                continue;
            }

            level.destroyBlock(job.pos, true);
            SCHEDULED_KEYS.remove(new ScheduledKey(job.dimensionKey, job.pos));
            removed++;
        }

        // If bucket still has items, we'll continue next tick.
    }

    private static void backgroundEnqueue(ServerLevel level) {
        RandomSource rand = level.random;

        // Spread enqueue across players so we tend to fill around loaded areas.
        int perPlayer = Math.max(1, ENQUEUE_PER_TICK / Math.max(1, level.players().size()));

        for (ServerPlayer player : level.players()) {
            BlockPos base = player.blockPosition();

            for (int i = 0; i < perPlayer; i++) {
                int x = base.getX() + rand.nextInt(BACKGROUND_RADIUS_XZ * 2 + 1) - BACKGROUND_RADIUS_XZ;
                int z = base.getZ() + rand.nextInt(BACKGROUND_RADIUS_XZ * 2 + 1) - BACKGROUND_RADIUS_XZ;

                int minY = Math.max(level.getMinBuildHeight(), base.getY() - 4);
                int maxY = Math.min(level.getMaxBuildHeight() - 1, base.getY() + BACKGROUND_Y_UP);
                if (maxY <= minY) continue;
                int y = rand.nextInt(maxY - minY + 1) + minY;

                BlockPos pos = new BlockPos(x, y, z);
                if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) continue;
                enqueue(pos);
            }
        }
    }

    private static void enqueue(BlockPos pos) {
        // keep queue bounded
        if (ENQUEUED.add(pos)) {
            QUEUE.addLast(pos);
        }
    }

    private static void maybeDecayLeaf(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.LEAVES)) return;

        // Skip persistent leaves when present.
        if (state.getProperties().contains(PERSISTENT) && state.getValue(PERSISTENT)) return;

        // Do NOT accelerate decay for player-placed leaves.
        if (PlayerPlacedEvents.isPlayerPlaced(level, pos)) return;

        // If no logs are nearby, remove the leaf (drop items like vanilla decay does).
        if (!hasNearbyLogCube(level, pos, LOG_SEARCH_RADIUS)) {
            level.destroyBlock(pos, true);
        }
    }

    private static BurstJob findBurstFor(ServerLevel level, BlockPos pos) {
        if (BURSTS.isEmpty()) return null;
        String dimKey = level.dimension().location().toString();

        // Keep only a few recent bursts to avoid unbounded growth
        while (BURSTS.size() > 8) BURSTS.removeFirst();

        for (BurstJob b : BURSTS) {
            if (!b.dimensionKey.equals(dimKey)) continue;
            if (Math.abs(pos.getX() - b.center.getX()) <= BURST_RADIUS_XZ
                    && Math.abs(pos.getY() - b.center.getY()) <= BURST_RADIUS_Y
                    && Math.abs(pos.getZ() - b.center.getZ()) <= BURST_RADIUS_XZ) {
                return b;
            }
        }
        return null;
    }

    private static void maybeDecayLeafForBurst(ServerLevel level, BlockPos pos, Set<BlockPos> treeLogs) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.LEAVES)) return;
        if (state.getProperties().contains(PERSISTENT) && state.getValue(PERSISTENT)) return;
        if (PlayerPlacedEvents.isPlayerPlaced(level, pos)) return;

        // Only keep leaves if they are within vanilla radius of THIS tree's logs.
        if (!hasNearbyTreeLog(pos, treeLogs, LOG_SEARCH_RADIUS)) {
            level.destroyBlock(pos, true);
        }
    }

    private static boolean hasNearbyLogCube(ServerLevel level, BlockPos start, int radius) {
        int sx = start.getX();
        int sy = start.getY();
        int sz = start.getZ();

        // Cube scan; no allocations.
        for (int dx = -radius; dx <= radius; dx++) {
            int x = sx + dx;
            for (int dy = -radius; dy <= radius; dy++) {
                int y = sy + dy;
                for (int dz = -radius; dz <= radius; dz++) {
                    int z = sz + dz;

                    BlockPos p = new BlockPos(x, y, z);
                    if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) continue;

                    if (level.getBlockState(p).is(BlockTags.LOGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasNearbyTreeLog(BlockPos leafPos, Set<BlockPos> treeLogs, int radius) {
        if (treeLogs == null || treeLogs.isEmpty()) return false;
        int lx = leafPos.getX();
        int ly = leafPos.getY();
        int lz = leafPos.getZ();

        int r2 = radius * radius;
        for (BlockPos log : treeLogs) {
            int dx = log.getX() - lx;
            int dy = log.getY() - ly;
            int dz = log.getZ() - lz;
            if (dx * dx + dy * dy + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    private static void maybeScheduleLeafRemoval(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.LEAVES)) return;
        if (state.getProperties().contains(PERSISTENT) && state.getValue(PERSISTENT)) return;
        if (PlayerPlacedEvents.isPlayerPlaced(level, pos)) return;

        String dimKey = level.dimension().location().toString();
        BlockPos imm = pos.immutable();
        ScheduledKey key = new ScheduledKey(dimKey, imm);

        // Don't schedule the same leaf multiple times.
        if (!SCHEDULED_KEYS.add(key)) return;

        long now = level.getGameTime();
        int delay = MIN_DECAY_DELAY_TICKS + level.random.nextInt(MAX_DECAY_DELAY_TICKS - MIN_DECAY_DELAY_TICKS + 1);
        long due = now + delay;

        int slot = (int) (due % WHEEL_SIZE);
        WHEEL[slot].addLast(new ScheduledLeaf(dimKey, imm, due));
    }
}
