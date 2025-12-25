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

/**
 * Utility class for detecting trees in the world.
 */
class TreeDetector {

    private static final int MAX_NODES = 3000;
    private static final int MAX_RADIUS = 24;

    // 26-direction neighborhood to catch diagonals (for logs connectivity)
    private static final BlockPos[] NEIGHBORS_26 = buildNeighbors();

    private TreeDetector() {}

    /**
     * Detect a single tree: first collect connected logs only, then add leaves adjacent to those logs.
     * This prevents leaf-only bridges from linking adjacent trees.
     */
    public static Set<BlockPos> detectTree(LevelAccessor level, BlockPos startPos) {
        Set<BlockPos> logs = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        if (!level.getBlockState(startPos).is(BlockTags.LOGS)) return logs; // must start on a log

        queue.add(startPos);
        visited.add(startPos);

        // Phase 1: flood-fill logs only
        while (!queue.isEmpty() && visited.size() < MAX_NODES) {
            BlockPos pos = queue.removeFirst();
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)) {
                logs.add(pos);
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

        // Phase 2: include leaves adjacent to any collected log (6-direction neighbors)
        Set<BlockPos> result = new HashSet<>(logs);
        if (!logs.isEmpty()) {
            for (BlockPos logPos : logs) {
                for (BlockPos adj : sixNeighbors(logPos)) {
                    if (manhattan(startPos, adj) > MAX_RADIUS) continue;
                    BlockState st = level.getBlockState(adj);
                    if (st.is(BlockTags.LEAVES)) {
                        result.add(adj);
                    }
                }
            }
        }

        return result;
    }

    private static boolean isTreeBlock(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX())
                + Math.abs(a.getY() - b.getY())
                + Math.abs(a.getZ() - b.getZ());
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
            // Apply durability using EquipmentSlot for current mappings
            held.hurtAndBreak(broken, player, EquipmentSlot.MAINHAND);
        }
    }
}
