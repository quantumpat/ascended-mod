package net.quantumpat.ascendedmod.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reload listener to load villager names from JSON files located in.
 */
class VillagerNamesReloadListener extends SimpleJsonResourceReloadListener {

    /**
     * GSON instance for JSON parsing.
     */
    private static final Gson GSON = new GsonBuilder().create();

    /**
     * List of loaded villager names.
     */
    private volatile List<String> names = List.of();

    /**
     * Constructor.
     */
    public VillagerNamesReloadListener() {
        super(GSON, "villager_names");
    }

    /**
     * Apply loaded JSON data.
     * @param files - The files to process.
     * @param resourceManager - Manages the resources.
     * @param profilerFiller - Profiler for performance tracking.
     */
    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profilerFiller) {

        Set<String> loadedNames = ConcurrentHashMap.newKeySet();

        for (Map.Entry<ResourceLocation, JsonElement> e : files.entrySet()) {
            JsonElement root = e.getValue();

            if (root.isJsonArray())
                for (JsonElement element : root.getAsJsonArray())
                    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
                        loadedNames.add(element.getAsString());
        }

        names = List.copyOf(loadedNames);

    }

    /**
     * Get the loaded villager names.
     * @return - The list of loaded villager names.
     */
    public List<String> getNames() {
        return names;
    }

}

/**
 * Manages villager events.
 */
@Mod.EventBusSubscriber(modid = "ascendedmod", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VillagerEvents {

    /**
     * Villager names reload listener instance.
     */
    private static final VillagerNamesReloadListener NAME_LOADER = new VillagerNamesReloadListener();

    /**
     * Add the villager names reload listener.
     * @param event - The event object.
     */
    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(NAME_LOADER);
    }

    /**
     * Handles when an entity joins the world.
     * @param event - The event object.
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {

        if (event.getEntity() instanceof Villager villager) {

            // Set the villager name to a random name from the list (villager names feature)
            String name = getRandomName();
            villager.setCustomName(Component.literal(getRandomName()));
            villager.setCustomNameVisible(true);

        }

    }

    /**
     * Returns a random villager name.
     * @return A random villager name.
     */
    private static String getRandomName() {
        return NAME_LOADER.getNames().get(new Random().nextInt(NAME_LOADER.getNames().size()));
    }

}
