package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.ClientPhoneState;
import nl.smartphonemod.client.PhotoCache;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/** Photo library: a grid of your photos. In picker mode (from a chat), tapping one sends it. */
public class PhotosApp extends PhoneApp {
    private static final int COLUMNS = 3;
    private final boolean openNewest;
    private final LongConsumer picker;
    private boolean requested;

    public PhotosApp(boolean openNewest) {
        this.openNewest = openNewest;
        this.picker = null;
    }

    public PhotosApp(LongConsumer picker) {
        this.openNewest = false;
        this.picker = picker;
    }

    @Override
    public String id() {
        return "photos";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable(picker != null ? "smartphone.photos.pick" : "smartphone.app.photos"),
                new UiKit.Button(40, 14, Component.translatable("smartphone.app.camera"), UiKit.Style.TINTED, UiKit.BLUE,
                        () -> phone.open(new CameraApp())));
        if (!requested) {
            requested = true;
            ClientPlayNetworking.send(new C2S.Gallery(0));
        }
        UiKit.ScrollList list = list("grid", 2, true);
        col.addChild(list);
        List<UiKit.Row> rows = new ArrayList<>();
        List<Long> photos = ClientPhoneState.photos;
        if (photos.isEmpty()) rows.add(UiKit.textRow(Component.translatable("smartphone.photos.empty"), UiKit.TEXT_2));
        for (int i = 0; i < photos.size(); i += COLUMNS) {
            rows.add(gridRow(photos.subList(i, Math.min(photos.size(), i + COLUMNS))));
        }
        list.rows(rows);
    }

    private UiKit.Row gridRow(List<Long> ids) {
        return new UiKit.Row() {
            private int cell(int width) {
                return (width - (COLUMNS - 1) * 2) / COLUMNS;
            }

            public int height(int width) {
                return cell(width);
            }

            public void draw(GuiGraphicsExtractor g, int x, int y, int width, boolean hovered, int mx, int my) {
                int c = cell(width);
                for (int i = 0; i < ids.size(); i++) {
                    int px = x + i * (c + 2);
                    PhotoCache.Photo p = PhotoCache.get(ids.get(i));
                    if (p == null) {
                        g.fill(px, y, px + c, y + c, UiKit.SURFACE_2);
                    } else {
                        int side = Math.min(p.width(), p.height());
                        g.blit(RenderPipelines.GUI_TEXTURED, p.texture(), px, y, (p.width() - side) / 2f, (p.height() - side) / 2f,
                                c, c, side, side, p.width(), p.height());
                    }
                    if (hovered && mx >= px - x && mx < px - x + c) g.fill(px, y, px + c, y + c, 0x33FFFFFF);
                }
            }

            public boolean clickable() {
                return true;
            }

            public void click(int mx, int my, int width) {
                int i = mx / (cell(width) + 2);
                if (i < 0 || i >= ids.size()) return;
                long id = ids.get(i);
                if (picker != null) {
                    phone.back();
                    picker.accept(id);
                } else {
                    phone.open(new PhotoViewerApp(id, true));
                }
            }
        };
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.GALLERY) {
            long fresh = ClientPhoneState.justTakenPhoto;
            if (openNewest && fresh > 0) {
                ClientPhoneState.justTakenPhoto = 0;
                phone.open(new PhotoViewerApp(fresh, true));
                return;
            }
            phone.refresh();
        } else if (update == PhoneScreen.Update.PHOTO) {
            phone.refresh();
        }
    }
}
