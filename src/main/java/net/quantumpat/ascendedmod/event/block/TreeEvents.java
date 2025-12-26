package net.quantumpat.ascendedmod.event.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for detecting trees in the world.
 */
class TreeDetector {

    /**
     * Maximum number of nodes (logs + leaves) to consider part of a tree.
     */
    private static final int MAX_NODES = 3000;

    /**
     * Maximum radius from the starting position to consider blocks part of the tree.
     */
    private static final int MAX_RADIUS = 32;

    /**
     * Maximum horizontal branch spread allowed from the origin column to follow side branches (e.g., acacia).
     */
    private static final int BRANCH_XZ_SPREAD = 2;

    /**
     * Radius around the origin to seed trunk columns.
     * Keep this small so we don't accidentally seed a neighboring tree.
     */
    private static final int TRUNK_CLUSTER_RADIUS = 1;

    /**
     * Allowed horizontal drift from the nearest trunk seed column when following logs.
     * Setting this too high causes cross-tree log capture in dense forests.
     */
    private static final int COLUMN_HORIZONTAL_SPREAD = 1;

    /**
     * How far we allow branches to drift horizontally once the trunk is high enough.
     */
    private static final int BRANCH_DRIFT_LIMIT = 3;

    /**
     * Trunk height (in blocks above the broken log) before we allow extra branch drift.
     */
    private static final int MIN_TRUNK_HEIGHT_FOR_BRANCH_DRIFT = 4;

    /**
     * Radius to expand leaf search beyond log bounds.
     */
    private static final int LEAF_RADIUS = 6;

    /**
     * Only include leaves that are close to a detected log in the same tree.
     * This prevents leaf BFS from walking into neighboring canopies in dense forests.
     */
    private static final int LEAF_LOG_MAX_DIST = 4;

    /**
     * Limit leaf BFS growth around any log to a local radius.
     */
    private static final int LEAF_LOCAL_RADIUS = 5;

    /**
     * 26-directional neighbor offsets.
     */
    private static final BlockPos[] NEIGHBORS_26 = buildNeighbors();

    /**
     * Detects a tree structure starting from the given position.
     * @param level - The level to search in.
     * @param startPos - The starting position (should be a log).
     * @return A set of BlockPos representing the detected tree (logs and leaves).
     */
    public static Set<BlockPos> detectTree(LevelAccessor level, BlockPos startPos) {

        Set<BlockPos> logs = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        if (!level.getBlockState(startPos).is(BlockTags.LOGS)) return logs;

        final int startY = startPos.getY();

        // Phase 0: seed trunk cluster at origin Y to capture multi-column trunks (e.g., dark oak)
        // IMPORTANT: keep it tight so we don't accidentally include another nearby trunk.
        Set<BlockPos> trunkSeeds = new HashSet<>();
        for (int dx = -TRUNK_CLUSTER_RADIUS; dx <= TRUNK_CLUSTER_RADIUS; dx++) {
            for (int dz = -TRUNK_CLUSTER_RADIUS; dz <= TRUNK_CLUSTER_RADIUS; dz++) {
                BlockPos seed = new BlockPos(startPos.getX() + dx, startY, startPos.getZ() + dz);
                BlockState st = level.getBlockState(seed);
                if (st.is(BlockTags.LOGS) && !PlayerPlacedEvents.isPlayerPlaced(level, seed)) {
                    trunkSeeds.add(seed);
                }
            }
        }
        if (trunkSeeds.isEmpty()) trunkSeeds.add(startPos);

        // Initialize BFS with all trunk seeds
        for (BlockPos seed : trunkSeeds) {
            queue.add(seed);
            visited.add(seed);
        }

        // Phase 1: flood-fill logs from trunk cluster, only above origin, limited horizontal spread from each column
        int minX = startPos.getX(), minY = startY, minZ = startPos.getZ();
        int maxX = startPos.getX(), maxY = startY, maxZ = startPos.getZ();

        while (!queue.isEmpty() && visited.size() < MAX_NODES) {
            BlockPos pos = queue.removeFirst();
            if (pos.getY() < startY) continue;

            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)) {
                if (PlayerPlacedEvents.isPlayerPlaced(level, pos)) continue; // skip player builds
                logs.add(pos);

                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());

                for (BlockPos d : NEIGHBORS_26) {
                    BlockPos next = pos.offset(d);
                    if (visited.contains(next)) continue;
                    if (next.getY() < startY) continue;
                    if (manhattan(startPos, next) > MAX_RADIUS) continue;

                    BlockState ns = level.getBlockState(next);
                    if (!ns.is(BlockTags.LOGS) || PlayerPlacedEvents.isPlayerPlaced(level, next)) continue;

                    // Limit horizontal drift relative to nearest trunk seed to avoid crossing into neighboring trees.
                    BlockPos nearestSeed = nearest(trunkSeeds, next);
                    int driftXZ = Math.abs(next.getX() - nearestSeed.getX()) + Math.abs(next.getZ() - nearestSeed.getZ());

                    int height = next.getY() - startY;
                    int allowedDrift = (height >= MIN_TRUNK_HEIGHT_FOR_BRANCH_DRIFT) ? BRANCH_DRIFT_LIMIT : COLUMN_HORIZONTAL_SPREAD;

                    if (driftXZ <= allowedDrift) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        // Phase 2: BFS leaves seeded from leaves adjacent to logs.
        // To avoid eating neighboring canopies (dark oak forests), we:
        //  1) start leaf search at/above the first height where logs touch leaves,
        //  2) constrain by overall bounds,
        //  3) constrain by a local radius from the nearest log,
        //  4) require each leaf to be within LEAF_LOG_MAX_DIST of some detected log.
        Set<BlockPos> result = new HashSet<>(logs);
        if (logs.isEmpty()) return result;

        // Determine which leaf block types belong to THIS tree.
        // We collect leaf blocks that are directly adjacent to detected logs.
        // This prevents us from deleting nearby oak leaves when chopping dark oak,
        // and works with modded trees (custom leaves) automatically.
        Set<net.minecraft.world.level.block.Block> allowedLeafBlocks = collectAllowedLeafBlocks(level, logs, startY);

        int boundMinX = minX - LEAF_RADIUS, boundMinY = startY, boundMinZ = minZ - LEAF_RADIUS;
        int boundMaxX = maxX + LEAF_RADIUS, boundMaxY = maxY + LEAF_RADIUS, boundMaxZ = maxZ + LEAF_RADIUS;

        int firstLeafY = findFirstLeafTouchY(level, logs, startY);

        ArrayDeque<BlockPos> leafQ = new ArrayDeque<>();
        Set<BlockPos> leafVisited = new HashSet<>();

        // Seed leaves adjacent to logs (6-neighbors), only above firstLeafY
        for (BlockPos logPos : logs) {
            for (BlockPos adj : sixNeighbors(logPos)) {
                if (leafVisited.contains(adj)) continue;
                if (adj.getY() < firstLeafY) continue;
                if (!inBounds(adj, boundMinX, boundMinY, boundMinZ, boundMaxX, boundMaxY, boundMaxZ)) continue;

                BlockState st = level.getBlockState(adj);
                if (!isAllowedLeaf(st, allowedLeafBlocks)) continue;
                if (!isLeafEligible(adj, startPos, logPos, logs)) continue;

                leafVisited.add(adj);
                leafQ.add(adj);
            }
        }

        // BFS over leaves within bounds and above firstLeafY
        while (!leafQ.isEmpty() && (logs.size() + leafVisited.size()) < MAX_NODES) {
            BlockPos leafPos = leafQ.removeFirst();
            result.add(leafPos);

            for (BlockPos adj : sixNeighbors(leafPos)) {
                if (leafVisited.contains(adj)) continue;
                if (adj.getY() < firstLeafY) continue;
                if (!inBounds(adj, boundMinX, boundMinY, boundMinZ, boundMaxX, boundMaxY, boundMaxZ)) continue;
                if (manhattan(startPos, adj) > MAX_RADIUS) continue;

                BlockState st = level.getBlockState(adj);
                if (!isAllowedLeaf(st, allowedLeafBlocks)) continue;

                BlockPos nearestLog = nearest(logs, adj);
                if (!isLeafEligible(adj, startPos, nearestLog, logs)) continue;

                leafVisited.add(adj);
                leafQ.add(adj);
            }
        }

        return result;

    }

    /**
     * Calculates the Manhattan distance between two BlockPos.
     * @param a - First BlockPos.
     * @param b - Second BlockPos.
     * @return - The Manhattan distance.
     */
    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX())
                + Math.abs(a.getY() - b.getY())
                + Math.abs(a.getZ() - b.getZ());
    }

    /**
     * Checks if a BlockPos is within given bounds.
     * @param p - The BlockPos to check.
     * @param minX - Minimum X bound.
     * @param minY - Minimum Y bound.
     * @param minZ - Minimum Z bound.
     * @param maxX - Maximum X bound.
     * @param maxY - Maximum Y bound.
     * @param maxZ - Maximum Z bound.
     * @return - True if in bounds, false otherwise.
     */
    private static boolean inBounds(BlockPos p, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return p.getX() >= minX && p.getX() <= maxX
                && p.getY() >= minY && p.getY() <= maxY
                && p.getZ() >= minZ && p.getZ() <= maxZ;
    }

    /**
     * Builds the 26-directional neighbor offsets.
     * @return - An array of BlockPos representing the 26 neighbors.
     */
    private static BlockPos[] buildNeighbors() {

        BlockPos[] dirs = new BlockPos[26];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    dirs[i++] = new BlockPos(dx, dy, dz);
                }
            }
        }

        return dirs;

    }

    /**
     * Returns the six orthogonal neighbors of a BlockPos.
     * @param pos - The BlockPos.
     * @return - An array of the six neighboring BlockPos.
     */
    private static BlockPos[] sixNeighbors(BlockPos pos) {
        return new BlockPos[] {
                pos.above(), pos.below(),
                pos.north(), pos.south(), pos.west(), pos.east()
        };
    }

    private static BlockPos nearest(Set<BlockPos> seeds, BlockPos to) {
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (BlockPos s : seeds) {
            int d = Math.abs(to.getX() - s.getX()) + Math.abs(to.getZ() - s.getZ());
            if (d < bestDist) { bestDist = d; best = s; }
        }
        return best != null ? best : to;
    }

    private static int findFirstLeafTouchY(LevelAccessor level, Set<BlockPos> logs, int startY) {
        int best = Integer.MAX_VALUE;
        for (BlockPos logPos : logs) {
            if (logPos.getY() < startY) continue;
            for (BlockPos adj : sixNeighbors(logPos)) {
                if (adj.getY() < startY) continue;
                if (level.getBlockState(adj).is(BlockTags.LEAVES)) {
                    best = Math.min(best, logPos.getY());
                }
            }
        }
        // If we never find a leaf touch (e.g., dead trees), fall back to startY
        return best == Integer.MAX_VALUE ? startY : best;
    }

    private static boolean isLeafEligible(BlockPos leafPos, BlockPos origin, BlockPos nearestLog, Set<BlockPos> logs) {
        // Keep leaves above origin log.
        if (leafPos.getY() < origin.getY()) return false;

        // Local radius around nearest log: stops spreading into other crowns.
        int localXz = Math.abs(leafPos.getX() - nearestLog.getX()) + Math.abs(leafPos.getZ() - nearestLog.getZ());
        if (localXz > LEAF_LOCAL_RADIUS) return false;

        // Must be near SOME log in the detected set.
        BlockPos best = nearest(logs, leafPos);
        int dist = Math.abs(leafPos.getX() - best.getX())
                + Math.abs(leafPos.getY() - best.getY())
                + Math.abs(leafPos.getZ() - best.getZ());
        return dist <= LEAF_LOG_MAX_DIST;
    }

    private static boolean isAllowedLeaf(BlockState state, Set<net.minecraft.world.level.block.Block> allowedLeafBlocks) {
        // If we couldn't determine a leaf set (rare), fall back to vanilla tag.
        if (allowedLeafBlocks == null || allowedLeafBlocks.isEmpty()) {
            return state.is(BlockTags.LEAVES);
        }
        return state.is(BlockTags.LEAVES) && allowedLeafBlocks.contains(state.getBlock());
    }

    private static Set<net.minecraft.world.level.block.Block> collectAllowedLeafBlocks(LevelAccessor level, Set<BlockPos> logs, int startY) {
        Set<net.minecraft.world.level.block.Block> out = new HashSet<>();
        for (BlockPos logPos : logs) {
            if (logPos.getY() < startY) continue;
            for (BlockPos adj : sixNeighbors(logPos)) {
                if (adj.getY() < startY) continue;
                BlockState st = level.getBlockState(adj);
                if (st.is(BlockTags.LEAVES)) {
                    out.add(st.getBlock());
                }
            }
        }
        return out;
    }
}

/**
 * Manages tree-related events.
 */
@Mod.EventBusSubscriber(modid = "ascendedmod", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TreeEvents {

    /**
     * Handles block break events to implement tree felling.
     * @param event - The block break event.
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {

        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.isCreative()) return;

        BlockPos pos = event.getPos();

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.isEmpty() || !held.canPerformAction(ToolActions.AXE_DIG)) return;

        // Skip if player-placed origin
        if (PlayerPlacedEvents.isPlayerPlaced(event.getLevel(), pos)) return;

        // Detect connected logs/leaves first (above origin, player-placed filtered inside)
        Set<BlockPos> treeBlocks = TreeDetector.detectTree(event.getLevel(), pos);
        if (treeBlocks.isEmpty()) return;

        // Evaluate the detected structure: accept if there are enough logs or leaves anywhere in the cluster
        int logCount = 0;
        int leafCount = 0;
        for (BlockPos p : treeBlocks) {
            BlockState st = event.getLevel().getBlockState(p);
            if (st.is(BlockTags.LOGS)) logCount++;
            else if (st.is(BlockTags.LEAVES)) leafCount++;
        }
        // Acacia-safe acceptance: either enough logs overall, or some leaves present in the cluster
        boolean accept = (logCount >= 6) || (leafCount >= 4);
        if (!accept) return;

        int broken = 0;
        for (BlockPos treePos : treeBlocks) {
            if (!treePos.equals(pos)) {
                boolean destroyed = event.getLevel().destroyBlock(treePos, true, player);
                if (destroyed) broken++;
            }
        }

        if (broken > 0) held.hurtAndBreak(broken, player, EquipmentSlot.MAINHAND);

    }

    // Natural-tree heuristic used to avoid felling house beams and builds
    private static boolean looksLikeTree(LevelAccessor level, BlockPos origin) {
        int maxScan = 48; // extend scan for branched/tricky trees
        int leavesTouches = 0;
        int logCount = 0;
        int branchContacts = 0;
        BlockPos pos = origin;
        for (int i = 0; i < maxScan; i++) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.LOGS)) break; // stop when trunk ends
            logCount++;
            // Check adjacent side positions for leaves and logs (branch presence)
            for (Direction d : Direction.values()) {
                if (d == Direction.UP || d == Direction.DOWN) continue;
                BlockPos adj = pos.relative(d);
                BlockState adjState = level.getBlockState(adj);
                if (adjState.is(BlockTags.LEAVES)) {
                    leavesTouches++;
                } else if (adjState.is(BlockTags.LOGS) && !PlayerPlacedEvents.isPlayerPlaced(level, adj)) {
                    branchContacts++;
                }
            }
            pos = pos.above();
        }
        // Crown cluster above the origin
        int crownLeaves = 0;
        BlockPos topCheck = origin.above(2);
        for (BlockPos check : neighborsCube(topCheck, 1)) {
            if (level.getBlockState(check).is(BlockTags.LEAVES)) crownLeaves++;
        }
        // Relaxed thresholds further for acacia: accept with branches and minimal leaves
        boolean sparseTreeOk = (logCount >= 5) && (leavesTouches >= 1 || crownLeaves >= 2 || branchContacts >= 2);
        boolean ampleLeavesOk = leavesTouches >= 2 || crownLeaves >= 4;
        return sparseTreeOk || ampleLeavesOk;
    }

    private static Iterable<BlockPos> neighborsCube(BlockPos center, int radius) {
        Set<BlockPos> out = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++)
                    out.add(center.offset(dx, dy, dz));
        return out;
    }

}
