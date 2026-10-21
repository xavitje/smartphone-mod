package nl.smartphonemod.registry;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.screen.AtmMenu;
import nl.smartphonemod.screen.CurrencyEntry;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;

import java.util.List;

public final class ModMenus {
    /** The opening data is the server's currency table, so the client never trusts its own config. */
    public static final MenuType<AtmMenu> ATM = Registry.register(BuiltInRegistries.MENU,
            SmartphoneMod.id("atm"),
            new ExtendedMenuType<AtmMenu, List<CurrencyEntry>>(AtmMenu::new, CurrencyEntry.LIST_CODEC));

    public static final MenuType<nl.smartphonemod.storage.StorageMenu> STORAGE = Registry.register(BuiltInRegistries.MENU,
            SmartphoneMod.id("storage"),
            new ExtendedMenuType<nl.smartphonemod.storage.StorageMenu, nl.smartphonemod.storage.StorageMenu.OpenData>(
                    nl.smartphonemod.storage.StorageMenu::new, nl.smartphonemod.storage.StorageMenu.OpenData.CODEC));

    private ModMenus() {}

    public static void register() {}
}
