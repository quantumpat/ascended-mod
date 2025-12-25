package net.quantumpat.ascendedmod.event.block;

import net.minecraft.core.BlockPos;
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

class TreeDetector {

    private static final int MAX_NODES = 3000;
    private static final int MAX_RADIUS = 24;
    private static final int LEAF_RADIUS = 6; // canopy spread around trunk

    private static final BlockPos[] NEIGHBORS_26 = buildNeighbors();

    private TreeDetector() {}

    public static Set<BlockPos> detectTree(LevelAccessor level, BlockPos startPos) {
        Set<BlockPos> logs = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        if (!level.getBlockState(startPos).is(BlockTags.LOGS)) return logs;

        queue.add(startPos);
        visited.add(startPos);

        // Phase 1: flood-fill logs only
        int minX = startPos.getX(), minY = startPos.getY(), minZ = startPos.getZ();
        int maxX = startPos.getX(), maxY = startPos.getY(), maxZ = startPos.getZ();

        while (!queue.isEmpty() && visited.size() < MAX_NODES) {
            BlockPos pos = queue.removeFirst();
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)) {
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
                    if (manhattan(startPos, next) > MAX_RADIUS) continue;
                    if (level.getBlockState(next).is(BlockTags.LOGS)) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        // Phase 2: BFS leaves seeded from leaves adjacent to logs, constrained by expanded log bounds
        Set<BlockPos> result = new HashSet<>(logs);
        if (logs.isEmpty()) return result;

        int boundMinX = minX - LEAF_RADIUS, boundMinY = minY - LEAF_RADIUS, boundMinZ = minZ - LEAF_RADIUS;
        int boundMaxX = maxX + LEAF_RADIUS, boundMaxY = maxY + LEAF_RADIUS, boundMaxZ = maxZ + LEAF_RADIUS;

        ArrayDeque<BlockPos> leafQ = new ArrayDeque<>();
        Set<BlockPos> leafVisited = new HashSet<>();

        // Seed leaves adjacent to logs (6-neighbors)
        for (BlockPos logPos : logs) {
            for (BlockPos adj : sixNeighbors(logPos)) {
                if (leafVisited.contains(adj)) continue;
                if (!inBounds(adj, boundMinX, boundMinY, boundMinZ, boundMaxX, boundMaxY, boundMaxZ)) continue;
                BlockState st = level.getBlockState(adj);
                if (st.is(BlockTags.LEAVES)) {
                    leafVisited.add(adj);
                    leafQ.add(adj);
                }
            }
        }

        // BFS over leaves within bounds
        while (!leafQ.isEmpty() && (logs.size() + leafVisited.size()) < MAX_NODES) {
            BlockPos leafPos = leafQ.removeFirst();
            result.add(leafPos);

            for (BlockPos adj : sixNeighbors(leafPos)) {
                if (leafVisited.contains(adj)) continue;
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

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX())
                + Math.abs(a.getY() - b.getY())
                + Math.abs(a.getZ() - b.getZ());
    }

    private static boolean inBounds(BlockPos p, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return p.getX() >= minX && p.getX() <= maxX
                && p.getY() >= minY && p.getY() <= maxY
                && p.getZ() >= minZ && p.getZ() <= maxZ;
    }

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

    private static BlockPos[] sixNeighbors(BlockPos pos) {
        return new BlockPos[] {
                pos.above(), pos.below(),
                pos.north(), pos.south(), pos.west(), pos.east()
        };
    }
}

@Mod.EventBusSubscriber(modid = "ascendedmod", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TreeEvents {

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.isCreative()) return;

        BlockPos pos = event.getPos();

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.isEmpty() || !held.canPerformAction(ToolActions.AXE_DIG)) return;

        Set<BlockPos> treeBlocks = TreeDetector.detectTree(event.getLevel(), pos);

        int broken = 0;
        for (BlockPos treePos : treeBlocks) {
            if (!treePos.equals(pos)) {
                boolean destroyed = event.getLevel().destroyBlock(treePos, true, player);
                if (destroyed) broken++;
            }
        }

        if (broken > 0) {
            held.hurtAndBreak(broken, player, EquipmentSlot.MAINHAND);
        }
    }
}
