package net.quantumpat.ascendedmod.event.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.quantumpat.ascendedmod.world.PlayerPlacedData;

@Mod.EventBusSubscriber(modid = "ascendedmod", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerPlacedEvents {

    @SubscribeEvent
    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        LevelAccessor level = event.getLevel();
        if (!(level instanceof ServerLevel server)) return;
        BlockPos pos = event.getPos();
        PlayerPlacedData.get(server).markPlaced(pos);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        LevelAccessor level = event.getLevel();
        if (!(level instanceof ServerLevel server)) return;
        BlockPos pos = event.getPos();
        // Optional: clear flag when block is removed
        PlayerPlacedData.get(server).unmark(pos);
    }

    public static boolean isPlayerPlaced(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return false;
        return PlayerPlacedData.get(server).isPlayerPlaced(pos);
    }
}
