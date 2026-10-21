package nl.smartphonemod.registry;

import nl.smartphonemod.SmartphoneMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class ModSounds {
    public static final SoundEvent NOTIFY = register("phone.notify");
    public static final SoundEvent MESSAGE = register("phone.message");
    public static final SoundEvent VIBRATE = register("phone.vibrate");
    public static final SoundEvent SHUTTER = register("phone.shutter");
    public static final SoundEvent ALARM = register("phone.alarm");
    public static final SoundEvent TAP = register("phone.tap");
    public static final SoundEvent ATM_BEEP = register("atm.beep");
    public static final SoundEvent ATM_CASH = register("atm.cash");
    public static final SoundEvent ATM_COIN = register("atm.coin");

    /** Selectable ringtones (Settings app). "classic" is the original message sound. */
    public static final String[] RINGTONES = {"classic", "marimba", "bells", "digital", "harp", "pop"};
    private static final SoundEvent[] RING_EVENTS = {MESSAGE, register("phone.ring.marimba"), register("phone.ring.bells"),
            register("phone.ring.digital"), register("phone.ring.harp"), register("phone.ring.pop")};

    public static SoundEvent ringtone(String name) {
        for (int i = 0; i < RINGTONES.length; i++) if (RINGTONES[i].equals(name)) return RING_EVENTS[i];
        return MESSAGE;
    }

    private ModSounds() {}

    private static SoundEvent register(String name) {
        Identifier id = SmartphoneMod.id(name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    public static void register() {}
}
