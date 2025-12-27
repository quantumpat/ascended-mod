package net.quantumpat.ascendedmod.event.block;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * SavedData class to track player-placed blocks.
 */
class PlayerPlacedData extends SavedData {

    /**
     * Map of chunk keys to sets of packed local positions of player-placed blocks.
     */
    private final Long2ObjectOpenHashMap<LongOpenHashSet> perChunk = new Long2ObjectOpenHashMap<>();

    /**
     * Constructor for PlayerPlacedData.
     */
    public PlayerPlacedData() {}

    /**
     * Loads PlayerPlacedData from a CompoundTag.
     * @param tag The CompoundTag to load from.
     * @param provider The HolderLookup provider.
     * @return The loaded PlayerPlacedData.
     */
    public static PlayerPlacedData load(CompoundTag tag, HolderLookup.Provider provider) {

        PlayerPlacedData data = new PlayerPlacedData();

        for (String chunkKeyStr : tag.getAllKeys()) {
            long chunkKey = Long.parseLong(chunkKeyStr);
            LongArrayTag arr = (LongArrayTag) tag.get(chunkKeyStr);
            if (arr != null) {
                LongOpenHashSet set = new LongOpenHashSet(arr.getAsLongArray());
                data.perChunk.put(chunkKey, set);
            }
        }

        return data;

    }

    /**
     * Factory for creating PlayerPlacedData instances.
     */
    public static final SavedData.Factory<PlayerPlacedData> FACTORY = new SavedData.Factory<>(
            PlayerPlacedData::new,
            PlayerPlacedData::load,
            DataFixTypes.LEVEL
    );

    /**
     * Gets the PlayerPlacedData for a given ServerLevel.
     * @param level The ServerLevel to get the data for.
     * @return The PlayerPlacedData instance.
     */
    public static PlayerPlacedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "ascended_player_placed");
    }

    /**
     * Saves the PlayerPlacedData to a CompoundTag.
     * @param tag The CompoundTag to save to.
     * @param provider The HolderLookup provider.
     * @return The saved CompoundTag.
     */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {

        for (long chunkKey : perChunk.keySet()) {
            LongOpenHashSet set = perChunk.get(chunkKey);
            if (set != null && !set.isEmpty())
                tag.put(Long.toString(chunkKey), new LongArrayTag(set.toLongArray()));
        }

        return tag;

    }

    /**
     * Marks a block position as player-placed.
     * @param pos The block position to mark.
     */
    public void markPlaced(BlockPos pos) {
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongOpenHashSet set = perChunk.computeIfAbsent(chunkKey, k -> new LongOpenHashSet());
        set.add(packLocal(pos));
        set.trim();
        setDirty();
    }

    /**
     * Checks if a block position was placed by a player.
     * @param pos The block position to check.
     * @return True if the block was placed by a player, false otherwise.
     */
    public boolean isPlayerPlaced(BlockPos pos) {
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongOpenHashSet set = perChunk.get(chunkKey);
        return set != null && set.contains(packLocal(pos));
    }

    /**
     * Unmarks a block position as player-placed.
     * @param pos The block position to unmark.
     */
    public void unmark(BlockPos pos) {
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongOpenHashSet set = perChunk.get(chunkKey);
        if (set != null) {
            set.remove(packLocal(pos));
            if (set.isEmpty()) perChunk.remove(chunkKey);
            setDirty();
        }
    }

    /**
     * Packs a local block position into a long.
     * @param pos The block position to pack.
     * @return The packed long representation of the local position.
     */
    private static long packLocal(BlockPos pos) {
        int lx = pos.getX() & 15;
        int lz = pos.getZ() & 15;
        int y = pos.getY(); // 32 bits
        return ( ((long)lx & 0xF) << 60 ) | ( ((long)lz & 0xF) << 56 ) | ( (long)y & 0x00FFFFFFFFL );
    }
}


/**
 * Handles events related to players placing items.
 */
@Mod.EventBusSubscriber(modid = "ascendedmod", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerPlacedEvents {

    /**
     * Handles the event when an entity places a block.
     * @param event The block place event.
     */
    @SubscribeEvent
    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {

        LevelAccessor level = event.getLevel();
        if (!(level instanceof ServerLevel server)) return;

        BlockPos pos = event.getPos();
        PlayerPlacedData.get(server).markPlaced(pos);

    }

    /**
     * Handles the event when a block is broken.
     * @param event The block break event.
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {

        LevelAccessor level = event.getLevel();
        if (!(level instanceof ServerLevel server)) return;
        BlockPos pos = event.getPos();

        PlayerPlacedData.get(server).unmark(pos);

    }

    /**
     * Checks if a block at the given position was placed by a player.
     * @param level The level accessor.
     * @param pos The block position.
     * @return True if the block was placed by a player, false otherwise.
     */
    public static boolean isPlayerPlaced(LevelAccessor level, BlockPos pos) {

        if (!(level instanceof ServerLevel server)) return false;
        return PlayerPlacedData.get(server).isPlayerPlaced(pos);

    }
}
