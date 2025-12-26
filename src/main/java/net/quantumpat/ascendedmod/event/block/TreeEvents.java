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
    private static final int MAX_RADIUS = 24;

    /**
     * Radius to expand leaf search beyond log bounds.
     */
    private static final int LEAF_RADIUS = 6;

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

        queue.add(startPos);
        visited.add(startPos);

        // Phase 1: flood-fill logs only, but never go below startY
        int minX = startPos.getX(), minY = startY, minZ = startPos.getZ();
        int maxX = startPos.getX(), maxY = startY, maxZ = startPos.getZ();

        while (!queue.isEmpty() && visited.size() < MAX_NODES) {
            BlockPos pos = queue.removeFirst();
            if (pos.getY() < startY) continue;

            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)) {
                // Skip player-placed logs to avoid felling houses and custom builds
                if (PlayerPlacedEvents.isPlayerPlaced(level, pos)) {
                    continue;
                }
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
                    if (ns.is(BlockTags.LOGS) && !PlayerPlacedEvents.isPlayerPlaced(level, next)) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        // Phase 2: BFS leaves seeded from leaves adjacent to logs, constrained by expanded log bounds, and never below startY
        Set<BlockPos> result = new HashSet<>(logs);
        if (logs.isEmpty()) return result;

        int boundMinX = minX - LEAF_RADIUS, boundMinY = startY, boundMinZ = minZ - LEAF_RADIUS;
        int boundMaxX = maxX + LEAF_RADIUS, boundMaxY = maxY + LEAF_RADIUS, boundMaxZ = maxZ + LEAF_RADIUS;

        ArrayDeque<BlockPos> leafQ = new ArrayDeque<>();
        Set<BlockPos> leafVisited = new HashSet<>();

        // Seed leaves adjacent to logs (6-neighbors), only above or at startY
        for (BlockPos logPos : logs) {
            for (BlockPos adj : sixNeighbors(logPos)) {
                if (leafVisited.contains(adj)) continue;
                if (adj.getY() < startY) continue;
                if (!inBounds(adj, boundMinX, boundMinY, boundMinZ, boundMaxX, boundMaxY, boundMaxZ)) continue;
                BlockState st = level.getBlockState(adj);
                if (st.is(BlockTags.LEAVES)) {
                    leafVisited.add(adj);
                    leafQ.add(adj);
                }
            }
        }

        // BFS over leaves within bounds and above startY
        while (!leafQ.isEmpty() && (logs.size() + leafVisited.size()) < MAX_NODES) {
            BlockPos leafPos = leafQ.removeFirst();
            result.add(leafPos);

            for (BlockPos adj : sixNeighbors(leafPos)) {
                if (leafVisited.contains(adj)) continue;
                if (adj.getY() < startY) continue;
                if (!inBounds(adj, boundMinX, boundMinY, boundMinZ, boundMaxX, boundMaxY, boundMaxZ)) continue;
                if (manhattan(startPos, adj) > MAX_RADIUS) continue;

                BlockState st = level.getBlockState(adj);
                if (st.is(BlockTags.LEAVES)) {
                    leafVisited.add(adj);
                    leafQ.add(adj);
                }
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

        // Heuristic gate: must look like a natural tree (leaves near trunk / crown)
        if (!looksLikeTree(event.getLevel(), pos)) return;

        Set<BlockPos> treeBlocks = TreeDetector.detectTree(event.getLevel(), pos);

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
        // Scan upward up to a reasonable height to find leaves touching the trunk
        int maxScan = 32; // supports tall spruce
        int leavesTouches = 0;
        BlockPos pos = origin;
        for (int i = 0; i < maxScan; i++) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.LOGS)) break; // stop when trunk ends
            // Check adjacent positions for leaves
            for (Direction d : Direction.values()) {
                if (d == Direction.UP || d == Direction.DOWN) continue; // prefer side leaves
                BlockPos adj = pos.relative(d);
                if (level.getBlockState(adj).is(BlockTags.LEAVES)) {
                    leavesTouches++;
                    if (leavesTouches >= 3) return true; // threshold: at least 3 side leaf contacts
                }
            }
            pos = pos.above();
        }
        // Also check a small leaf cluster above
        int crownLeaves = 0;
        BlockPos topCheck = origin.above(2);
        for (BlockPos check : neighborsCube(topCheck, 1)) {
            if (level.getBlockState(check).is(BlockTags.LEAVES)) crownLeaves++;
        }
        return crownLeaves >= 5; // fallback: crown cluster present
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
