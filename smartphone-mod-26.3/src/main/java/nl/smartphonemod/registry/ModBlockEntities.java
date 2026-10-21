package nl.smartphonemod.registry;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.block.AtmBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;

public final class ModBlockEntities {
    public static final BlockEntityType<AtmBlockEntity> ATM = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
            SmartphoneMod.id("atm"),
            new BlockEntityType<>(AtmBlockEntity::new, Set.of(ModBlocks.ATM)));

    public static final BlockEntityType<nl.smartphonemod.storage.StorageHubBlockEntity> STORAGE_HUB = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, SmartphoneMod.id("storage_hub"),
            new BlockEntityType<>(nl.smartphonemod.storage.StorageHubBlockEntity::new, Set.of(ModBlocks.STORAGE_HUB)));

    public static final BlockEntityType<nl.smartphonemod.cctv.CctvCameraBlockEntity> CCTV_CAMERA = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, SmartphoneMod.id("cctv_camera"),
            new BlockEntityType<>(nl.smartphonemod.cctv.CctvCameraBlockEntity::new, Set.of(ModBlocks.CCTV_CAMERA)));

    private ModBlockEntities() {}

    public static void register() {}
}
