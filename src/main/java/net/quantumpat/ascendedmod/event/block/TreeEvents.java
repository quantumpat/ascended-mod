package net.quantumpat.ascendedmod.event.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

class TreeDetector {

    /**
     * Gets all the blocks in a tree starting from a given block.
     * @param startingBlock The block to start from.
     * @param levelAccessor The level accessor.
     * @param player The player breaking the block.
     * @return All the tree blocks.
     */
    public static List<BlockPos> getTreeBlocks(BlockPos startingBlock, LevelAccessor levelAccessor, ServerPlayer player) {

        List<BlockPos> treeBlocks = new ArrayList<>();

        int currentX = startingBlock.getX();
        int currentY = startingBlock.getY();
        int currentZ = startingBlock.getZ();

        int trunkWidth = getTrunkWidth(startingBlock, levelAccessor);

        boolean logsAbove = true;
        while(logsAbove) {

            BlockPos[] currentTrunkLayer = new BlockPos[trunkWidth];
            for (int i = 0; i < trunkWidth; i++) {
                currentTrunkLayer[i] = new BlockPos(currentX, currentY, currentZ);
            }

        }

        player.sendSystemMessage(Component.literal("Trunk width: " + trunkWidth));

        return treeBlocks;

    }

    /**
     * Gets the trunk width of a tree at a given position.
     * @param blockPos - The starting block position.
     * @param levelAccessor - The level accessor.
     * @return The width of the trunk (example: regular oak tree is 1, dark oak tree is 4).
     */
    private static int getTrunkWidth(BlockPos blockPos, LevelAccessor levelAccessor) {

        int logCounts[] = new int[4];
        int originX = blockPos.getX() - 1, originY = blockPos.getY() - 1, originZ = blockPos.getZ() - 1;

        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 3; x++)
                for (int z = 0; z < 3; z++)
                    if (levelAccessor.getBlockState(new BlockPos(originX + x, originY + y, originZ + z)).is(BlockTags.LOGS)) logCounts[y]++;

        if (Math.max(Math.max(logCounts[0], logCounts[1]), Math.max(logCounts[2], logCounts[3])) >= 4) return 4;
        else return 1;

    }

    /**
     * Gets all the logs in the current layer of the trunk.
     * @param blockPos The starting block position (cornor block).
     * @param levelAccessor The level accessor.
     * @param trunkWidth The width of the trunk.
     * @return A list of all logs in a particular layer.
     */
    private static List<BlockPos> getLogsInLayer(BlockPos blockPos, LevelAccessor levelAccessor, int trunkWidth) {

        List<BlockPos> logsInLayer = new ArrayList<>();

        int originX = blockPos.getX() - 1;
        int originZ = blockPos.getZ() - 1;

        // If the trunk width if 4 the maximum amount of logs is 5
        if (trunkWidth == 4) {

        }else logsInLayer.add(blockPos);

        return logsInLayer;

    }

}

/**
 * Manages tree-related events.
 */
@Mod.EventBusSubscriber(modid = "ascendedmod", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TreeEvents {

    /**
     * Handles the block break event to implement tree felling.
     * @param event The block break event object.
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {

        // Make sure a player is breaking the block.
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        // Ignore if the player is in creative mode.
        // if (player.isCreative()) return;

        // Make sure the player is holding an axe.
        ItemStack heldItem = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (heldItem.isEmpty() || !heldItem.canPerformAction(ToolActions.AXE_DIG)) return;

        // The position of the broken block.
        BlockPos brokenBlockPos = event.getPos();

        // Get all the blocks in the tree.
        List<BlockPos> treeBlocks = TreeDetector.getTreeBlocks(brokenBlockPos, event.getLevel(), player);

        // Destroy all the blocks in the tree.
        int brokenLogCount = 0;
        for (BlockPos blockPos : treeBlocks) {
            if (blockPos.equals(brokenBlockPos)) continue; //Skip the originally broken block, as it is already being broken.

            boolean isLog = event.getLevel().getBlockState(blockPos).is(BlockTags.LOGS);
            boolean destroyed = event.getLevel().destroyBlock(blockPos, true, player);

            if (destroyed && isLog) brokenLogCount++;
        }

        // Damage the held item based on the number of logs broken.
        if (brokenLogCount > 0) heldItem.hurtAndBreak(brokenLogCount, player, EquipmentSlot.MAINHAND);

    }

}
