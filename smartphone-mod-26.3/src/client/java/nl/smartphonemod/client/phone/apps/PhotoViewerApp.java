package nl.smartphonemod.client.phone.apps;

import nl.smartphonemod.client.PhotoCache;
import nl.smartphonemod.client.phone.PhoneApp;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.client.ui.UiKit;
import nl.smartphonemod.network.C2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Full photo, with share (to a contact or group) and delete for your own photos. */
public class PhotoViewerApp extends PhoneApp {
    private final long photo;
    private final boolean own;
    private boolean confirmDelete;
    private String sharedWith;

    public PhotoViewerApp(long photo, boolean own) {
        this.photo = photo;
        this.own = own;
    }

    @Override
    public String id() {
        return "photos";
    }

    @Override
    protected void build(LinearLayout col) {
        header(col, Component.translatable("smartphone.photos.photo"), null);
        PhotoCache.Photo p = PhotoCache.get(photo);
        int ph = p == null ? w * 9 / 16 : Math.min(h - 80, w * p.height() / Math.max(1, p.width()));
        col.addChild(new UiKit.Canvas(w, ph, (g, x, y, cw, ch, hov, mx, my) -> {
            PhotoCache.Photo pp = PhotoCache.get(photo);
            if (pp == null) {
                UiKit.roundRect(g, x, y, cw, ch, 8, UiKit.SURFACE_2);
                UiKit.textCentered(g, Component.translatable("smartphone.photos.loading"), x + cw / 2, y + ch / 2 - 4, UiKit.TEXT_3);
                return;
            }
            g.blit(RenderPipelines.GUI_TEXTURED, pp.texture(), x, y, 0, 0, cw, ch, pp.width(), pp.height(), pp.width(), pp.height());
            UiKit.cornerMask(g, x, y, cw, ch, 8, UiKit.BG);
        }));
        if (!own) return;
        LinearLayout actions = LinearLayout.horizontal().spacing(4);
        int bw = (w - 4) / 2;
        actions.addChild(new UiKit.Button(bw, 18, Component.translatable("smartphone.photos.share"), UiKit.Style.FILLED, UiKit.BLUE,
                () -> phone.open(new ContactPickerApp(Component.translatable("smartphone.photos.share_with"), true, null, (conv, name) -> {
                    ClientPlayNetworking.send(new C2S.SendMessage(conv, "", photo));
                    sharedWith = name;
                    phone.refresh();
                }))));
        actions.addChild(new UiKit.Button(bw, 18, Component.translatable(confirmDelete ? "smartphone.photos.delete_confirm"
                : "smartphone.photos.delete"), confirmDelete ? UiKit.Style.FILLED : UiKit.Style.TINTED, UiKit.RED, () -> {
            if (!confirmDelete) {
                confirmDelete = true;
                phone.refresh();
                return;
            }
            ClientPlayNetworking.send(new C2S.Gallery(photo));
            phone.back();
        }));
        col.addChild(actions);
        if (sharedWith != null) col.addChild(new UiKit.Label(Component.translatable("smartphone.photos.shared", sharedWith), UiKit.GREEN, w));
    }

    @Override
    protected void onUpdate(PhoneScreen.Update update) {
        if (update == PhoneScreen.Update.PHOTO) phone.refresh();
    }
}
