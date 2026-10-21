package nl.smartphonemod.screen;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.config.SmartphoneConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One physical item the ATM accepts, and what it is worth in balance units. */
public record CurrencyEntry(Item item, long value) {
    public static final StreamCodec<RegistryFriendlyByteBuf, CurrencyEntry> CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.ITEM), CurrencyEntry::item,
            ByteBufCodecs.VAR_LONG, CurrencyEntry::value,
            CurrencyEntry::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, List<CurrencyEntry>> LIST_CODEC = CODEC.apply(ByteBufCodecs.list());

    /** Resolves the server config into items, skipping unknown ids. */
    public static List<CurrencyEntry> fromConfig() {
        List<CurrencyEntry> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : SmartphoneConfig.get().currency.entrySet()) {
            Identifier id = Identifier.tryParse(e.getKey());
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
            if (item == null || item == Items.AIR) {
                SmartphoneMod.LOGGER.warn("Unknown currency item '{}' in config/smartphone.json", e.getKey());
                continue;
            }
            out.add(new CurrencyEntry(item, e.getValue()));
        }
        return out;
    }

    public static long valueOf(List<CurrencyEntry> currencies, Item item) {
        for (CurrencyEntry c : currencies) if (c.item() == item) return c.value();
        return 0;
    }
}
