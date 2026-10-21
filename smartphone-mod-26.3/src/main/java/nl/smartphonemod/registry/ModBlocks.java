package nl.smartphonemod.registry;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.block.AtmBlock;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ModBlocks {
    public static final ResourceKey<Block> ATM_KEY = ResourceKey.create(Registries.BLOCK, SmartphoneMod.id("atm"));
    public static final ResourceKey<Item> ATM_ITEM_KEY = ResourceKey.create(Registries.ITEM, SmartphoneMod.id("atm"));

    public static final Block ATM = Registry.register(BuiltInRegistries.BLOCK, ATM_KEY,
            new AtmBlock(BlockBehaviour.Properties.of()
                    .setId(ATM_KEY)
                    .mapColor(MapColor.METAL)
                    .strength(5.0f, 1200.0f) // blast-proof: it holds money
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 4)
                    .sound(SoundType.METAL)));

    public static final Item ATM_ITEM = Registry.register(BuiltInRegistries.ITEM, ATM_ITEM_KEY,
            new BlockItem(ATM, new Item.Properties().setId(ATM_ITEM_KEY).useBlockDescriptionPrefix()));

    public static final ResourceKey<Block> STORAGE_HUB_KEY = ResourceKey.create(Registries.BLOCK, SmartphoneMod.id("storage_hub"));
    public static final ResourceKey<Item> STORAGE_HUB_ITEM_KEY = ResourceKey.create(Registries.ITEM, SmartphoneMod.id("storage_hub"));

    public static final Block STORAGE_HUB = Registry.register(BuiltInRegistries.BLOCK, STORAGE_HUB_KEY,
            new nl.smartphonemod.storage.StorageHubBlock(BlockBehaviour.Properties.of()
                    .setId(STORAGE_HUB_KEY)
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(3.5f, 6.0f)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 7)
                    .sound(SoundType.COPPER)));

    public static final Item STORAGE_HUB_ITEM = Registry.register(BuiltInRegistries.ITEM, STORAGE_HUB_ITEM_KEY,
            new BlockItem(STORAGE_HUB, new Item.Properties().setId(STORAGE_HUB_ITEM_KEY).useBlockDescriptionPrefix()));

    public static final ResourceKey<Block> CCTV_KEY = ResourceKey.create(Registries.BLOCK, SmartphoneMod.id("cctv_camera"));
    public static final ResourceKey<Item> CCTV_ITEM_KEY = ResourceKey.create(Registries.ITEM, SmartphoneMod.id("cctv_camera"));

    public static final Block CCTV_CAMERA = Registry.register(BuiltInRegistries.BLOCK, CCTV_KEY,
            new nl.smartphonemod.cctv.CctvCameraBlock(BlockBehaviour.Properties.of()
                    .setId(CCTV_KEY)
                    .mapColor(MapColor.METAL)
                    .strength(1.5f, 6.0f)
                    .noOcclusion()
                    .sound(SoundType.METAL)));

    public static final Item CCTV_ITEM = Registry.register(BuiltInRegistries.ITEM, CCTV_ITEM_KEY,
            new BlockItem(CCTV_CAMERA, new Item.Properties().setId(CCTV_ITEM_KEY).useBlockDescriptionPrefix()));

    private ModBlocks() {}

    public static void register() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> {
            output.accept(ATM_ITEM);
            output.accept(STORAGE_HUB_ITEM);
            output.accept(CCTV_ITEM);
        });
    }
}
