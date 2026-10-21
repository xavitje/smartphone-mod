package nl.smartphonemod.client;

import nl.smartphonemod.client.hud.Notifications;
import nl.smartphonemod.client.phone.PhoneScreen;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Receivers run on the client thread. The client only stores what the server says and redraws. */
public final class ClientNetworking {
    private ClientNetworking() {}

    public static void register() {
        // Version handshake: having this channel tells the server we run the same protocol (see Protocol).
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking.registerGlobalReceiver(
                nl.smartphonemod.network.Protocol.Check.ID, (payload, ctx) -> {});
        // DISCONNECT may fire on a network thread; textures may only be released on the render thread.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            ClientPhoneState.reset();
            nl.smartphonemod.client.cctv.CctvView.reset();
            PhotoCache.clear();
        }));

        ClientPlayNetworking.registerGlobalReceiver(S2C.ServerInfo.ID, (p, ctx) -> ClientPhoneState.serverInfo = p);

        ClientPlayNetworking.registerGlobalReceiver(S2C.OpenPhone.ID, (p, ctx) -> {
            ClientPhoneState.balance = p.balance();
            ClientPhoneState.unread = p.unread();
            ClientPhoneState.gpsMode = p.gpsMode();
            if (!(ctx.client().gui.screen() instanceof PhoneScreen)) {
                ctx.client().gui.setScreen(new PhoneScreen());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.BankData.ID, (p, ctx) -> {
            ClientPhoneState.balance = p.balance();
            ClientPhoneState.history = p.history();
            ClientPhoneState.requests = p.requests();
            ClientPhoneState.orders = p.orders();
            if (!p.statusKey().isEmpty()) {
                ClientPhoneState.statusKey = p.statusKey();
                ClientPhoneState.statusArg = p.statusArg();
                ClientPhoneState.statusOk = p.ok();
                ClientPhoneState.statusTime = System.currentTimeMillis();
            }
            update(PhoneScreen.Update.BANK);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.Contacts.ID, (p, ctx) -> {
            ClientPhoneState.contacts = p.contacts();
            ClientPhoneState.groups = p.groups();
            update(PhoneScreen.Update.CONTACTS);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.ChatThread.ID, (p, ctx) -> {
            ClientPhoneState.thread = p;
            update(PhoneScreen.Update.THREAD);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.NewMessage.ID, (p, ctx) -> {
            Minecraft client = ctx.client();
            if (client.gui.screen() instanceof PhoneScreen phone && phone.isViewingThread(p.conv())) {
                // Re-open the thread: the server marks it read and sends the fresh history.
                ClientPlayNetworking.send(new C2S.OpenThread(p.conv()));
                return;
            }
            ClientPhoneState.unread++;
            Component title = p.conv().isGroup()
                    ? Component.translatable("smartphone.notify.group_message", p.fromName(), p.groupName())
                    : Component.literal(p.fromName());
            Component body = p.photo() && p.body().isEmpty()
                    ? Component.translatable("smartphone.messages.photo")
                    : Component.literal((p.photo() ? "📷 " : "") + p.body());
            Notifications.post("messages", title, body, false);
            update(PhoneScreen.Update.NEW_MESSAGE);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.UnreadCount.ID, (p, ctx) -> {
            ClientPhoneState.unread = p.count();
            if (p.onLogin() && p.count() > 0) {
                Notifications.post("messages", Component.translatable("smartphone.notify.offline_title"),
                        Component.translatable("smartphone.notify.offline_body", p.count()), false);
            }
            update(PhoneScreen.Update.UNREAD);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.Positions.ID, (p, ctx) -> {
            ClientPhoneState.positions = p.players();
            ClientPhoneState.positionsTime = System.currentTimeMillis();
            update(PhoneScreen.Update.POSITIONS);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.MapData.ID, (p, ctx) -> {
            ClientPhoneState.gpsMode = p.gpsMode();
            ClientPhoneState.friends = p.friends();
            ClientPhoneState.waypoints = p.waypoints();
            if (ClientPhoneState.navigationTarget != 0
                    && p.waypoints().stream().noneMatch(w -> w.id() == ClientPhoneState.navigationTarget)) {
                ClientPhoneState.navigationTarget = 0;
            }
            update(PhoneScreen.Update.MAP);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.MarketData.ID, (p, ctx) -> {
            ClientPhoneState.listings = p.listings();
            if (!p.statusKey().isEmpty()) {
                ClientPhoneState.marketStatusKey = p.statusKey();
                ClientPhoneState.marketStatusArg = p.statusArg();
                ClientPhoneState.marketStatusOk = p.ok();
                ClientPhoneState.marketStatusTime = System.currentTimeMillis();
            }
            update(PhoneScreen.Update.MARKET);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.PhotoData.ID, (p, ctx) -> {
            PhotoCache.receive(p.id(), p.jpeg());
            update(PhoneScreen.Update.PHOTO);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.GalleryData.ID, (p, ctx) -> {
            ClientPhoneState.photos = p.photos();
            if (p.justTaken() != 0) ClientPhoneState.justTakenPhoto = p.justTaken();
            update(PhoneScreen.Update.GALLERY);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.EmergencyAlert.ID, (p, ctx) -> {
            ClientPhoneState.alerts.removeIf(a -> a.alert().from().equals(p.from())
                    || System.currentTimeMillis() - a.time() > 10 * 60_000);
            ClientPhoneState.alerts.add(0, new ClientPhoneState.Alert(p, System.currentTimeMillis()));
            Notifications.post("emergency", Component.translatable("smartphone.emergency.alert_title", p.name()),
                    Component.translatable("smartphone.emergency.alert_body", p.x(), p.y(), p.z()), true);
            update(PhoneScreen.Update.ALERT);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.StorageContents.ID, (p, ctx) -> {
            if (ctx.player() != null && ctx.player().containerMenu instanceof nl.smartphonemod.storage.StorageMenu menu
                    && menu.containerId == p.containerId()) {
                menu.entries = p.entries();
                menu.linked = p.linked();
                menu.unavailable = p.unavailable();
                menu.usedSlots = p.usedSlots();
                menu.totalSlots = p.totalSlots();
                menu.contentsVersion++;
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.StorageHubs.ID, (p, ctx) -> {
            ClientPhoneState.storageHubs = p;
            update(PhoneScreen.Update.STORAGE);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.ParkourCourses.ID, (p, ctx) -> {
            ClientPhoneState.parkour = p;
            update(PhoneScreen.Update.PARKOUR);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.ParkourBoard.ID, (p, ctx) -> {
            ClientPhoneState.parkourBoard = p;
            update(PhoneScreen.Update.PARKOUR);
        });

        ClientPlayNetworking.registerGlobalReceiver(S2C.CctvAccess.ID, (p, ctx) -> {
            boolean changed = ClientPhoneState.cctvView != p.view();
            ClientPhoneState.cctvView = p.view();
            ClientPhoneState.cctvManage = p.manage();
            if (changed) update(PhoneScreen.Update.CCTV);
        });
        ClientPlayNetworking.registerGlobalReceiver(S2C.CctvCameras.ID, (p, ctx) -> {
            ClientPhoneState.cctvCameras = p.cameras();
            update(PhoneScreen.Update.CCTV);
        });
        ClientPlayNetworking.registerGlobalReceiver(S2C.CctvRecording.ID, (p, ctx) -> nl.smartphonemod.client.cctv.CctvView.onRecording(p));
        ClientPlayNetworking.registerGlobalReceiver(S2C.CctvViewStart.ID, (p, ctx) -> nl.smartphonemod.client.cctv.CctvView.start(p));
        ClientPlayNetworking.registerGlobalReceiver(S2C.CctvViewEnd.ID, (p, ctx) -> nl.smartphonemod.client.cctv.CctvView.end(false, false,
                p.reasonKey().isEmpty() ? null : Component.translatableWithFallback(p.reasonKey(), p.reasonFallback())));

        ClientPlayNetworking.registerGlobalReceiver(S2C.Notify.ID, (p, ctx) -> {
            Notifications.post(p);
            // Something changed server-side: refresh what's on screen.
            if (ctx.client().gui.screen() instanceof PhoneScreen phone && phone.current() != null) {
                String app = phone.current().id();
                switch (p.app()) {
                    case "bank", "market" -> {
                        if (app.equals("bank") || app.equals("home")) ClientPlayNetworking.send(C2S.BankRequest.INSTANCE);
                        if (app.equals("market")) ClientPlayNetworking.send(new C2S.MarketRequest(""));
                    }
                    case "messages" -> ClientPlayNetworking.send(C2S.ContactsRequest.INSTANCE);
                    case "settings" -> ClientPlayNetworking.send(C2S.MapRequest.INSTANCE);
                    case "parkour" -> {
                        if (app.equals("parkour")) ClientPlayNetworking.send(C2S.ParkourRequest.INSTANCE);
                    }
                    default -> {}
                }
            }
        });
    }

    private static void update(PhoneScreen.Update update) {
        if (Minecraft.getInstance().gui.screen() instanceof PhoneScreen phone) phone.onUpdate(update);
    }
}
