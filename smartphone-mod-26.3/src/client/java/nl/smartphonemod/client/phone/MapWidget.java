package nl.smartphonemod.client.phone;

import com.mojang.blaze3d.platform.NativeImage;
import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.network.S2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Top-down minimap drawn from the client's loaded chunks, using each block's map colour with
 * vanilla-style height shading. It is redrawn progressively (a band of rows per frame) so it never
 * costs more than a few thousand block lookups per frame. Player dots come from the server.
 */
public class MapWidget extends AbstractWidget {
    private static final int RES = 128;           // texture resolution
    private static final int ROWS_PER_FRAME = 32; // full refresh every 4 frames
    private static final int UNLOADED = 0xFF14141A;
    private static final Identifier TEXTURE_ID = SmartphoneMod.id("dynamic/phone_map");

    private final DynamicTexture texture = new DynamicTexture(() -> "Smartphone map", RES, RES, true);
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private int zoom = 1; // blocks per pixel
    private int nextRow = 0;
    private int passCenterX, passCenterZ;   // centre used while drawing the current pass
    private int shownCenterX, shownCenterZ; // centre of the last completed pass (what is on screen)

    public MapWidget(int width, int height) {
        super(0, 0, width, height, Component.empty());
        this.active = false;
        NativeImage image = texture.getPixels();
        if (image != null) image.fillRect(0, 0, RES, RES, UNLOADED);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            passCenterX = shownCenterX = player.getBlockX();
            passCenterZ = shownCenterZ = player.getBlockZ();
        }
    }

    public int zoom() {
        return zoom;
    }

    public void zoom(int zoom) {
        this.zoom = Mth.clamp(zoom, 1, 8);
        nextRow = 0; // restart the pass at the new scale
    }

    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public void dispose() {
        Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        renderRows(mc.level);
        int x = getX(), y = getY();
        double scale = width / (double) RES;
        // The texture is square; a wider-than-tall widget shows the middle band.
        int srcH = (int) Math.min(RES, Math.round(height / scale));
        int srcV = (RES - srcH) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, x, y, 0, srcV, width, height, RES, srcH, RES, RES);
        double offsetY = srcV * scale;
        g.enableScissor(x, y, x + width, y + height);
        String dim = mc.level.dimension().identifier().toString();

        // Waypoints: blue diamonds (the navigation target in orange).
        for (S2C.WaypointEntry wp : ClientPhoneState.waypoints) {
            if (!wp.dimension().equals(dim)) continue;
            int px = x + (int) Math.round(((wp.x() - shownCenterX) / (double) zoom + RES / 2.0) * scale);
            int py = y + (int) Math.round(((wp.z() - shownCenterZ) / (double) zoom + RES / 2.0) * scale - offsetY);
            boolean nav = wp.id() == ClientPhoneState.navigationTarget;
            px = Mth.clamp(px, x + 3, x + width - 4);
            py = Mth.clamp(py, y + 3, y + height - 4);
            diamond(g, px, py, 4, 0xFF000000);
            diamond(g, px, py, 3, nav ? 0xFFFF9F0A : 0xFF0A84FF);
            label(g, mc, wp.name(), px + 5, py - 3);
        }
        // Recent 112 alerts: pulsing red.
        long now = System.currentTimeMillis();
        for (ClientPhoneState.Alert alert : ClientPhoneState.alerts) {
            if (!alert.alert().dimension().equals(dim)) continue;
            int px = Mth.clamp(x + (int) Math.round(((alert.alert().x() - shownCenterX) / (double) zoom + RES / 2.0) * scale), x + 3, x + width - 4);
            int py = Mth.clamp(y + (int) Math.round(((alert.alert().z() - shownCenterZ) / (double) zoom + RES / 2.0) * scale - offsetY), y + 3, y + height - 4);
            int r = 3 + (int) ((now / 120) % 4);
            g.fill(px - r, py - r, px + r, py + r, 0x66FF453A);
            g.fill(px - 2, py - 2, px + 2, py + 2, 0xFFFF453A);
            label(g, mc, "SOS " + alert.alert().name(), px + 5, py - 3);
        }

        // Other players, from the server's last GPS broadcast.
        var self = mc.player.getUUID();
        for (S2C.PlayerPos pos : ClientPhoneState.positions) {
            if (pos.uuid().equals(self)) continue;
            int px = x + (int) Math.round(((pos.x() - shownCenterX) / (double) zoom + RES / 2.0) * scale);
            int py = y + (int) Math.round(((pos.z() - shownCenterZ) / (double) zoom + RES / 2.0) * scale - offsetY);
            int color = pos.friend() ? 0xFF30D158 : 0xFF000000 | colorFor(pos);
            boolean clamped = px < x + 2 || py < y + 2 || px > x + width - 3 || py > y + height - 3;
            px = Mth.clamp(px, x + 2, x + width - 3);
            py = Mth.clamp(py, y + 2, y + height - 3);
            g.fill(px - 3, py - 3, px + 3, py + 3, 0xFF000000);
            g.fill(px - 2, py - 2, px + 2, py + 2, clamped ? (color & 0x00FFFFFF) | 0x99000000 : color);

            label(g, mc, (pos.friend() ? "★ " : "") + pos.name(), px + 4, py - 3);
        }

        // Yourself: drawn from the live client position so it moves smoothly, with a heading tick.
        int cx = (int) (x + ((mc.player.getX() - shownCenterX) / zoom + RES / 2.0) * scale);
        int cy = (int) (y + ((mc.player.getZ() - shownCenterZ) / zoom + RES / 2.0) * scale - offsetY);
        float yaw = (float) Math.toRadians(mc.player.getYRot());
        for (int i = 2; i <= 6; i++) {
            int hx = cx + Math.round(-Mth.sin(yaw) * i);
            int hy = cy + Math.round(Mth.cos(yaw) * i);
            g.fill(hx, hy, hx + 1, hy + 1, 0xFFFFFFFF);
        }
        g.fill(cx - 3, cy - 3, cx + 3, cy + 3, 0xFF000000);
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFFFFFFFF);

        g.disableScissor();
        nl.smartphonemod.client.ui.UiKit.cornerMask(g, x, y, width, height, 8, nl.smartphonemod.client.ui.UiKit.BG);
    }

    private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(cx - half, cy + i, cx + half + 1, cy + i + 1, color);
        }
    }

    private static void label(GuiGraphicsExtractor g, Minecraft mc, String text, int x, int y) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.5f, 0.5f);
        g.text(mc.font, text, 0, 0, 0xFFFFFFFF, true);
        g.pose().popMatrix();
    }

    private static int colorFor(S2C.PlayerPos pos) {
        float hue = (pos.uuid().hashCode() & 0xFFFF) / 65535f;
        return Mth.hsvToRgb(hue, 0.75f, 1f);
    }

    /** Draws the next band of rows; when a pass finishes, it becomes the visible map. */
    private void renderRows(ClientLevel level) {
        NativeImage image = texture.getPixels();
        if (image == null) return;
        var player = Minecraft.getInstance().player;
        if (nextRow == 0) {
            passCenterX = player.getBlockX();
            passCenterZ = player.getBlockZ();
        }
        boolean ceiling = level.dimensionType().hasCeiling();
        int startY = player.getBlockY() + 2;
        int end = Math.min(RES, nextRow + ROWS_PER_FRAME);
        for (int row = nextRow; row < end; row++) {
            int wz = passCenterZ + (row - RES / 2) * zoom;
            for (int col = 0; col < RES; col++) {
                int wx = passCenterX + (col - RES / 2) * zoom;
                image.setPixel(col, row, sample(level, wx, wz, ceiling, startY));
            }
        }
        nextRow = end >= RES ? 0 : end;
        if (nextRow == 0) {
            shownCenterX = passCenterX;
            shownCenterZ = passCenterZ;
        }
        texture.upload();
    }

    /** Colour (ARGB) of the top block at (x, z), shaded by the height step to the north. */
    private int sample(ClientLevel level, int wx, int wz, boolean ceiling, int startY) {
        if (!level.getChunkSource().hasChunk(wx >> 4, wz >> 4)) return UNLOADED;
        int h = surfaceY(level, wx, wz, ceiling, startY);
        if (h == Integer.MIN_VALUE) return UNLOADED;
        BlockState state = level.getBlockState(cursor.set(wx, h, wz));
        MapColor color = state.getMapColor(level, cursor);
        if (color == MapColor.NONE) return UNLOADED;

        int hn = level.getChunkSource().hasChunk(wx >> 4, (wz - zoom) >> 4)
                ? surfaceY(level, wx, wz - zoom, ceiling, startY) : h;
        MapColor.Brightness brightness;
        if (color == MapColor.WATER || hn == Integer.MIN_VALUE || h == hn) brightness = MapColor.Brightness.NORMAL;
        else brightness = h > hn ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
        return color.calculateARGBColor(brightness);
    }

    private int surfaceY(ClientLevel level, int wx, int wz, boolean ceiling, int startY) {
        if (!ceiling) {
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
            return top < level.getMinY() ? Integer.MIN_VALUE : top;
        }
        // Nether-style worlds: scan down from just above the player instead of from the roof.
        boolean seenAir = false;
        for (int yy = startY; yy > startY - 48 && yy >= level.getMinY(); yy--) {
            boolean air = level.getBlockState(cursor.set(wx, yy, wz)).isAir();
            if (air) seenAir = true;
            else if (seenAir) return yy; // first floor below an open space
        }
        return Integer.MIN_VALUE;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
