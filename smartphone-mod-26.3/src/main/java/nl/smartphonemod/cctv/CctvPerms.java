package nl.smartphonemod.cctv;

import nl.smartphonemod.SmartphoneMod;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.Permissions;

import java.lang.reflect.Method;

/**
 * CCTV permissions: {@code smartphone.cctv.view} and {@code smartphone.cctv.manage}.
 * Checked through LuckPerms' Fabric permissions API when present, otherwise Fabric's own permission API,
 * and finally operator level 2.
 */
public final class CctvPerms {
    private CctvPerms() {}

    public static final String VIEW = "smartphone.cctv.view";
    public static final String MANAGE = "smartphone.cctv.manage";

    private static Method lucko;
    private static boolean luckoChecked;

    private static boolean op(ServerPlayer p) {
        return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static boolean has(ServerPlayer p, String node) {
        // 1) me.lucko.fabric.api.permissions.v0.Permissions.check(Entity, String, int) — what LuckPerms provides
        if (!luckoChecked) {
            luckoChecked = true;
            try {
                Class<?> c = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
                lucko = c.getMethod("check", net.minecraft.world.entity.Entity.class, String.class, int.class);
                SmartphoneMod.LOGGER.info("CCTV permissions via fabric-permissions-api (LuckPerms)");
            } catch (ReflectiveOperationException | LinkageError e) {
                lucko = null;
            }
        }
        if (lucko != null) {
            try {
                return (Boolean) lucko.invoke(null, p, node, 2);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fall through
            }
        }
        // 2) Fabric API's permission API (smartphone:cctv.view)
        try {
            String path = node.substring("smartphone.".length());
            return ((net.fabricmc.fabric.api.permission.v1.PermissionContextOwner) p)
                    .checkPermission(Identifier.fromNamespaceAndPath(SmartphoneMod.MOD_ID, path), PermissionLevel.GAMEMASTERS);
        } catch (ClassCastException | LinkageError e) {
            return op(p);
        }
    }

    public static boolean canView(ServerPlayer p) {
        return has(p, VIEW) || canManage(p);
    }

    public static boolean canManage(ServerPlayer p) {
        return has(p, MANAGE);
    }
}
