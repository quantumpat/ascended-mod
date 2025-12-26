package net.quantumpat.ascendedmod.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

public class PlayerPlacedData extends SavedData {
    // Map: chunk key -> set of packed local positions
    private final Long2ObjectOpenHashMap<LongOpenHashSet> perChunk = new Long2ObjectOpenHashMap<>();

    // Explicit no-arg constructor required by Factory create
    public PlayerPlacedData() {
    }

    // Reader for Factory: load with provider (modern signature)
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

    // Factory with correct parameter order: (create Supplier, load BiFunction, DataFixTypes)
    public static final SavedData.Factory<PlayerPlacedData> FACTORY = new SavedData.Factory<>(
            PlayerPlacedData::new,
            PlayerPlacedData::load,
            DataFixTypes.LEVEL
    );

    public static PlayerPlacedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "ascended_player_placed");
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        for (long chunkKey : perChunk.keySet()) {
            LongOpenHashSet set = perChunk.get(chunkKey);
            if (set != null && !set.isEmpty()) {
                tag.put(Long.toString(chunkKey), new LongArrayTag(set.toLongArray()));
            }
        }
        return tag;
    }

    public void markPlaced(BlockPos pos) {
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongOpenHashSet set = perChunk.computeIfAbsent(chunkKey, k -> new LongOpenHashSet());
        set.add(packLocal(pos));
        set.trim();
        setDirty();
    }

    public boolean isPlayerPlaced(BlockPos pos) {
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongOpenHashSet set = perChunk.get(chunkKey);
        return set != null && set.contains(packLocal(pos));
    }

    public void unmark(BlockPos pos) {
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongOpenHashSet set = perChunk.get(chunkKey);
        if (set != null) {
            set.remove(packLocal(pos));
            if (set.isEmpty()) perChunk.remove(chunkKey);
            setDirty();
        }
    }

    // Packs local x,z within chunk (0..15) and full y into a single long
    private static long packLocal(BlockPos pos) {
        int lx = pos.getX() & 15;
        int lz = pos.getZ() & 15;
        int y = pos.getY(); // 32 bits
        return ( ((long)lx & 0xF) << 60 ) | ( ((long)lz & 0xF) << 56 ) | ( (long)y & 0x00FFFFFFFFL );
    }
}
