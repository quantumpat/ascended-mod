package net.quantumpat.ascendedmod.item;

import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Manages mod items.
 */
public class ModItems {

    /**
     * The items register.
     */
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "ascendedmod");

    // Simple item used as the icon for the creative tab
    public static final RegistryObject<Item> ASCENDED_TAB_ICON = ITEMS.register("ascended_tab_icon",
            () -> new Item(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> IRIDIUM_INGOT = ITEMS.register("iridium_ingot",
            () -> new Item(new Item.Properties().stacksTo(64)));

    /**
     * Registers the mod items.
     * @param bus The bus used to register the items.
     */
    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

}
