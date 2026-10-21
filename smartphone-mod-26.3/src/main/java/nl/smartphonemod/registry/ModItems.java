package nl.smartphonemod.registry;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.item.SmartphoneItem;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

public final class ModItems {
    public static final ResourceKey<Item> SMARTPHONE_KEY = ResourceKey.create(Registries.ITEM, SmartphoneMod.id("smartphone"));

    public static final Item SMARTPHONE = Registry.register(BuiltInRegistries.ITEM, SMARTPHONE_KEY,
            new SmartphoneItem(new Item.Properties().setId(SMARTPHONE_KEY).stacksTo(1).rarity(Rarity.UNCOMMON)));

    public static final ResourceKey<Item> LINKER_KEY = ResourceKey.create(Registries.ITEM, SmartphoneMod.id("linker"));

    public static final Item LINKER = Registry.register(BuiltInRegistries.ITEM, LINKER_KEY,
            new nl.smartphonemod.storage.LinkerItem(new Item.Properties().setId(LINKER_KEY).stacksTo(1)));

    private ModItems() {}

    public static void register() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
            output.accept(SMARTPHONE);
            output.accept(LINKER);
        });
    }
}
