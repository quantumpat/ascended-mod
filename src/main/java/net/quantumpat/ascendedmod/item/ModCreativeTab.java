package net.quantumpat.ascendedmod.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Mod creative tab class.
 */
public class ModCreativeTab {

    /**
     * The creative tabs register.
     */
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "ascendedmod");

    /**
     * The ascended mod creative tab.
     */
    public static final RegistryObject<CreativeModeTab> ASCENDED_TAB = TABS.register("ascended_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ascendedmod.ascended_tab"))
                    .icon(() -> new ItemStack(ModItems.ASCENDED_TAB_ICON.get()))
                    .displayItems((params, output) -> {

                        /*
                         * Add items to the creative tab here
                         */
                        output.accept(ModItems.IRIDIUM_INGOT.get());

                    })
                    .build()
    );

    /**
     * Registers the mod creative tab.
     * @param bus The bus used to register the creative tab.
     */
    public static void register(IEventBus bus) {
        TABS.register(bus);
    }

}
