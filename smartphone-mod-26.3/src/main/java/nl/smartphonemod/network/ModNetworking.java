package nl.smartphonemod.network;

import nl.smartphonemod.server.BankService;
import nl.smartphonemod.server.EmergencyService;
import nl.smartphonemod.server.GpsService;
import nl.smartphonemod.server.MarketService;
import nl.smartphonemod.server.MessageService;
import nl.smartphonemod.server.PhoneServer;
import nl.smartphonemod.server.PhotoService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

public final class ModNetworking {
    private ModNetworking() {}

    public static void registerPayloadTypes() {
        var c2s = PayloadTypeRegistry.serverboundPlay();
        c2s.register(C2S.BankRequest.ID, C2S.BankRequest.CODEC);
        c2s.register(C2S.BankTransfer.ID, C2S.BankTransfer.CODEC);
        c2s.register(C2S.PayRequestCreate.ID, C2S.PayRequestCreate.CODEC);
        c2s.register(C2S.PayRequestRespond.ID, C2S.PayRequestRespond.CODEC);
        c2s.register(C2S.StandingOrderCreate.ID, C2S.StandingOrderCreate.CODEC);
        c2s.register(C2S.StandingOrderCancel.ID, C2S.StandingOrderCancel.CODEC);
        c2s.register(C2S.ContactsRequest.ID, C2S.ContactsRequest.CODEC);
        c2s.register(C2S.OpenThread.ID, C2S.OpenThread.CODEC);
        c2s.register(C2S.SendMessage.ID, C2S.SendMessage.CODEC);
        c2s.register(C2S.GroupCreate.ID, C2S.GroupCreate.CODEC);
        c2s.register(C2S.GroupMembership.ID, C2S.GroupMembership.CODEC);
        c2s.register(C2S.GpsSubscribe.ID, C2S.GpsSubscribe.CODEC);
        c2s.register(C2S.GpsMode.ID, C2S.GpsMode.CODEC);
        c2s.register(C2S.FriendSet.ID, C2S.FriendSet.CODEC);
        c2s.register(C2S.MapRequest.ID, C2S.MapRequest.CODEC);
        c2s.register(C2S.WaypointSave.ID, C2S.WaypointSave.CODEC);
        c2s.register(C2S.WaypointDelete.ID, C2S.WaypointDelete.CODEC);
        c2s.register(C2S.MarketRequest.ID, C2S.MarketRequest.CODEC);
        c2s.register(C2S.MarketSell.ID, C2S.MarketSell.CODEC);
        c2s.register(C2S.MarketAction.ID, C2S.MarketAction.CODEC);
        c2s.registerLarge(C2S.PhotoUpload.ID, C2S.PhotoUpload.CODEC, C2S.MAX_PHOTO_BYTES + 64);
        c2s.register(C2S.PhotoRequest.ID, C2S.PhotoRequest.CODEC);
        c2s.register(C2S.Gallery.ID, C2S.Gallery.CODEC);
        c2s.register(C2S.Emergency.ID, C2S.Emergency.CODEC);
        c2s.register(C2S.OpenRequest.ID, C2S.OpenRequest.CODEC);
        c2s.register(C2S.StorageExtract.ID, C2S.StorageExtract.CODEC);
        c2s.register(C2S.StorageInsert.ID, C2S.StorageInsert.CODEC);
        c2s.register(C2S.StorageHubsRequest.ID, C2S.StorageHubsRequest.CODEC);
        c2s.register(C2S.StorageOpenRemote.ID, C2S.StorageOpenRemote.CODEC);
        c2s.register(C2S.ParkourRequest.ID, C2S.ParkourRequest.CODEC);
        c2s.register(C2S.ParkourBoardRequest.ID, C2S.ParkourBoardRequest.CODEC);
        c2s.register(C2S.ParkourChallenge.ID, C2S.ParkourChallenge.CODEC);
        c2s.register(C2S.ParkourRespond.ID, C2S.ParkourRespond.CODEC);
        c2s.register(C2S.CctvList.ID, C2S.CctvList.CODEC);
        c2s.register(C2S.CctvView.ID, C2S.CctvView.CODEC);
        c2s.register(C2S.CctvStop.ID, C2S.CctvStop.CODEC);
        c2s.register(C2S.CctvRotate.ID, C2S.CctvRotate.CODEC);
        c2s.register(C2S.CctvRename.ID, C2S.CctvRename.CODEC);
        c2s.register(C2S.CctvReplayRequest.ID, C2S.CctvReplayRequest.CODEC);
        Protocol.register();

        var s2c = PayloadTypeRegistry.clientboundPlay();
        s2c.register(S2C.ServerInfo.ID, S2C.ServerInfo.CODEC);
        s2c.register(S2C.OpenPhone.ID, S2C.OpenPhone.CODEC);
        s2c.register(S2C.Notify.ID, S2C.Notify.CODEC);
        s2c.register(S2C.BankData.ID, S2C.BankData.CODEC);
        s2c.register(S2C.Contacts.ID, S2C.Contacts.CODEC);
        s2c.register(S2C.ChatThread.ID, S2C.ChatThread.CODEC);
        s2c.register(S2C.NewMessage.ID, S2C.NewMessage.CODEC);
        s2c.register(S2C.UnreadCount.ID, S2C.UnreadCount.CODEC);
        s2c.register(S2C.Positions.ID, S2C.Positions.CODEC);
        s2c.register(S2C.MapData.ID, S2C.MapData.CODEC);
        s2c.register(S2C.MarketData.ID, S2C.MarketData.CODEC);
        s2c.registerLarge(S2C.PhotoData.ID, S2C.PhotoData.CODEC, C2S.MAX_PHOTO_BYTES + 64);
        s2c.register(S2C.GalleryData.ID, S2C.GalleryData.CODEC);
        s2c.register(S2C.EmergencyAlert.ID, S2C.EmergencyAlert.CODEC);
        s2c.registerLarge(S2C.StorageContents.ID, S2C.StorageContents.CODEC, 8 * 1024 * 1024);
        s2c.register(S2C.StorageHubs.ID, S2C.StorageHubs.CODEC);
        s2c.register(S2C.ParkourCourses.ID, S2C.ParkourCourses.CODEC);
        s2c.register(S2C.ParkourBoard.ID, S2C.ParkourBoard.CODEC);
        s2c.register(S2C.CctvAccess.ID, S2C.CctvAccess.CODEC);
        s2c.register(S2C.CctvCameras.ID, S2C.CctvCameras.CODEC);
        s2c.register(S2C.CctvViewStart.ID, S2C.CctvViewStart.CODEC);
        s2c.register(S2C.CctvViewEnd.ID, S2C.CctvViewEnd.CODEC);
        s2c.registerLarge(S2C.CctvRecording.ID, S2C.CctvRecording.CODEC, 8 * 1024 * 1024 + 64);
    }

    /** Fabric runs these receivers on the server thread. Each one first checks the phone is in hand. */
    public static void registerServerReceivers() {
        // Bank
        on(C2S.BankRequest.ID, (p, player) -> BankService.sendBankData(player, "", "", true));
        on(C2S.BankTransfer.ID, (p, player) -> BankService.transfer(player, p.target(), p.amount(), p.memo()));
        on(C2S.PayRequestCreate.ID, (p, player) -> BankService.createRequest(player, p.target(), p.amount(), p.memo()));
        on(C2S.PayRequestRespond.ID, (p, player) -> BankService.respondRequest(player, p.id(), p.accept()));
        on(C2S.StandingOrderCreate.ID, (p, player) -> BankService.createOrder(player, p.target(), p.amount(), p.intervalDays(), p.memo()));
        on(C2S.StandingOrderCancel.ID, (p, player) -> BankService.cancelOrder(player, p.id()));
        // Messages
        on(C2S.ContactsRequest.ID, (p, player) -> MessageService.sendContacts(player));
        on(C2S.OpenThread.ID, (p, player) -> MessageService.openThread(player, p.conv()));
        on(C2S.SendMessage.ID, (p, player) -> MessageService.send(player, p.conv(), p.body(), p.photoId()));
        on(C2S.GroupCreate.ID, (p, player) -> MessageService.createGroup(player, p.name(), p.members()));
        on(C2S.GroupMembership.ID, (p, player) -> MessageService.membership(player, p.groupId(), p.member(), p.add()));
        // Map & privacy
        ServerPlayNetworking.registerGlobalReceiver(C2S.GpsSubscribe.ID, (p, ctx) -> {
            if (!p.active()) GpsService.unsubscribe(ctx.player()); // closing is always allowed
            else withPhone(ctx.player(), GpsService::subscribe);
        });
        on(C2S.GpsMode.ID, (p, player) -> GpsService.setMode(player, p.mode()));
        on(C2S.FriendSet.ID, (p, player) -> GpsService.setFriend(player, p.friend(), p.add()));
        on(C2S.MapRequest.ID, (p, player) -> GpsService.sendMapData(player));
        on(C2S.WaypointSave.ID, (p, player) -> GpsService.saveWaypoint(player, p.name()));
        on(C2S.WaypointDelete.ID, (p, player) -> GpsService.deleteWaypoint(player, p.id()));
        // Market
        on(C2S.MarketRequest.ID, (p, player) -> MarketService.sendMarket(player, p.query(), "", "", true));
        on(C2S.MarketSell.ID, (p, player) -> MarketService.sell(player, p.slot(), p.price()));
        on(C2S.MarketAction.ID, (p, player) -> {
            if (p.buy()) MarketService.buy(player, p.id());
            else MarketService.cancel(player, p.id());
        });
        // Camera
        on(C2S.PhotoUpload.ID, (p, player) -> PhotoService.upload(player, p.jpeg()));
        on(C2S.PhotoRequest.ID, (p, player) -> PhotoService.request(player, p.id()));
        on(C2S.Gallery.ID, (p, player) -> PhotoService.gallery(player, p.deleteId()));
        // 112
        on(C2S.Emergency.ID, (p, player) -> EmergencyService.trigger(player));
        // Storage network. Extract/insert work at the hub block too, so they are not phone-gated;
        // the open StorageMenu (matched by container id) checks distance or phone itself.
        ServerPlayNetworking.registerGlobalReceiver(C2S.StorageExtract.ID, (p, ctx) -> {
            if (ctx.player().containerMenu instanceof nl.smartphonemod.storage.StorageMenu menu
                    && menu.containerId == p.containerId() && menu.stillValid(ctx.player())) {
                menu.extract(ctx.player(), p.proto(), p.amount(), p.toInventory());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(C2S.StorageInsert.ID, (p, ctx) -> {
            if (ctx.player().containerMenu instanceof nl.smartphonemod.storage.StorageMenu menu
                    && menu.containerId == p.containerId() && menu.stillValid(ctx.player())) {
                menu.insertCarried(p.single());
            }
        });
        on(C2S.StorageHubsRequest.ID, (p, player) -> nl.smartphonemod.storage.StorageService.sendHubs(player));
        on(C2S.StorageOpenRemote.ID, (p, player) -> {
            if (nl.smartphonemod.server.Cooldowns.tryUse(player.getUUID(), "storage_open", 500)) {
                nl.smartphonemod.storage.StorageService.openRemote(player, p.dimension(), p.pos());
            }
        });
        // Parkour (phone app)
        on(C2S.ParkourRequest.ID, (p, player) -> nl.smartphonemod.parkour.ParkourService.sendCourses(player));
        on(C2S.ParkourBoardRequest.ID, (p, player) -> nl.smartphonemod.parkour.ParkourService.sendBoard(player, p.course()));
        on(C2S.ParkourChallenge.ID, (p, player) -> {
            if (nl.smartphonemod.server.Cooldowns.tryUse(player.getUUID(), "parkour_challenge", 3000)) {
                nl.smartphonemod.parkour.ParkourService.challenge(player, p.course(), p.target());
            }
        });
        on(C2S.ParkourRespond.ID, (p, player) -> {
            nl.smartphonemod.parkour.ParkourService.respond(player, p.accept());
            nl.smartphonemod.parkour.ParkourService.sendCourses(player);
        });
        // CCTV
        on(C2S.CctvList.ID, (p, player) -> nl.smartphonemod.cctv.CctvService.sendList(player));
        on(C2S.CctvView.ID, (p, player) -> {
            if (nl.smartphonemod.server.Cooldowns.tryUse(player.getUUID(), "cctv_view", 300)) {
                nl.smartphonemod.cctv.CctvService.view(player, p.dimension(), p.pos());
            }
        });
        on(C2S.CctvRename.ID, (p, player) -> nl.smartphonemod.cctv.CctvService.rename(player, p.dimension(), p.pos(), p.name()));
        ServerPlayNetworking.registerGlobalReceiver(C2S.CctvReplayRequest.ID, (p, ctx) -> {
            if (nl.smartphonemod.server.Cooldowns.tryUse(ctx.player().getUUID(), "cctv_replay", 1000)) {
                nl.smartphonemod.cctv.CctvService.sendRecording(ctx.player());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(C2S.CctvStop.ID, (p, ctx) -> nl.smartphonemod.cctv.CctvService.stop(ctx.player(), null, null));
        ServerPlayNetworking.registerGlobalReceiver(C2S.CctvRotate.ID, (p, ctx) -> {
            if (Float.isFinite(p.yaw()) && Float.isFinite(p.pitch())) nl.smartphonemod.cctv.CctvService.rotate(ctx.player(), p.yaw(), p.pitch());
        });
        // Hotkey
        on(C2S.OpenRequest.ID, (p, player) -> {
            if (nl.smartphonemod.server.Cooldowns.tryUse(player.getUUID(), "open", 250)) PhoneServer.openPhone(player);
        });
    }

    @FunctionalInterface
    private interface Handler<T> {
        void handle(T payload, ServerPlayer player);
    }

    private static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void on(
            net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type, Handler<T> handler) {
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, ctx) ->
                withPhone(ctx.player(), player -> handler.handle(payload, player)));
    }

    private static void withPhone(ServerPlayer player, Consumer<ServerPlayer> action) {
        if (PhoneServer.isRunning() && PhoneServer.hasPhone(player)) {
            action.accept(player);
        }
        // Silently drop anything else: a modified client gains nothing by sending these without a phone.
    }
}
