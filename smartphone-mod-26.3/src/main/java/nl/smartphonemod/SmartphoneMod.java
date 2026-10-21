package nl.smartphonemod;

import nl.smartphonemod.config.SmartphoneConfig;
import nl.smartphonemod.network.ModNetworking;
import nl.smartphonemod.registry.ModBlockEntities;
import nl.smartphonemod.registry.ModBlocks;
import nl.smartphonemod.registry.ModItems;
import nl.smartphonemod.registry.ModMenus;
import nl.smartphonemod.registry.ModSounds;
import nl.smartphonemod.server.AdminCommands;
import nl.smartphonemod.server.EconomyService;
import nl.smartphonemod.server.GpsService;
import nl.smartphonemod.server.PhoneDatabase;
import nl.smartphonemod.server.PhoneServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmartphoneMod implements ModInitializer {
    public static final String MOD_ID = "smartphone";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        SmartphoneConfig.load();

        ModSounds.register();
        ModItems.register();
        ModBlocks.register();
        ModBlockEntities.register();
        ModMenus.register();
        ModNetworking.registerPayloadTypes();
        ModNetworking.registerServerReceivers();
        nl.smartphonemod.storage.StorageService.register();
        nl.smartphonemod.parkour.ParkourService.register();
        nl.smartphonemod.cctv.CctvService.register();

        // One database per world, kept inside the world folder: <world>/smartphone/smartphone.db
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            var dir = server.getWorldPath(LevelResource.ROOT).resolve(MOD_ID);
            PhoneServer.start(server, PhoneDatabase.open(dir.resolve("smartphone.db")));
        });
        // Day tracking needs the overworld, which only exists once the server has started.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            EconomyService.start();
            nl.smartphonemod.server.BackupService.start();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PhoneServer.stop());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> PhoneServer.onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PhoneServer.onLeave(handler.getPlayer()));
        ServerTickEvents.END_SERVER_TICK.register(GpsService::tick);
        ServerTickEvents.END_SERVER_TICK.register(EconomyService::tick);
        ServerTickEvents.END_SERVER_TICK.register(nl.smartphonemod.server.BackupService::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            AdminCommands.register(dispatcher);
            nl.smartphonemod.parkour.ParkourCommands.register(dispatcher);
        });

        LOGGER.info("Smartphone initialised");
    }
}
