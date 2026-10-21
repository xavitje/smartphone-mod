package nl.smartphonemod.network;

import nl.smartphonemod.SmartphoneMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/**
 * Client-to-server packets. Every one of these is treated as an untrusted request:
 * the server re-checks that the phone is in hand and validates every field.
 */
public final class C2S {
    private C2S() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
        return new CustomPacketPayload.Type<>(SmartphoneMod.id(name));
    }

    // ------------------------------------------------------------------ bank

    public record BankRequest() implements CustomPacketPayload {
        public static final BankRequest INSTANCE = new BankRequest();
        public static final Type<BankRequest> ID = typeOf("bank_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, BankRequest> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record BankTransfer(String target, long amount, String memo) implements CustomPacketPayload {
        public static final Type<BankTransfer> ID = typeOf("bank_transfer");
        public static final StreamCodec<RegistryFriendlyByteBuf, BankTransfer> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), BankTransfer::target,
                ByteBufCodecs.VAR_LONG, BankTransfer::amount,
                ByteBufCodecs.stringUtf8(64), BankTransfer::memo,
                BankTransfer::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** "Tikkie": ask {@code target} to pay you. */
    public record PayRequestCreate(String target, long amount, String memo) implements CustomPacketPayload {
        public static final Type<PayRequestCreate> ID = typeOf("pay_request_create");
        public static final StreamCodec<RegistryFriendlyByteBuf, PayRequestCreate> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), PayRequestCreate::target,
                ByteBufCodecs.VAR_LONG, PayRequestCreate::amount,
                ByteBufCodecs.stringUtf8(64), PayRequestCreate::memo,
                PayRequestCreate::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Pay or decline a request addressed to you; or cancel one you sent (accept=false on your own). */
    public record PayRequestRespond(long id, boolean accept) implements CustomPacketPayload {
        public static final Type<PayRequestRespond> ID = typeOf("pay_request_respond");
        public static final StreamCodec<RegistryFriendlyByteBuf, PayRequestRespond> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, PayRequestRespond::id,
                ByteBufCodecs.BOOL, PayRequestRespond::accept,
                PayRequestRespond::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record StandingOrderCreate(String target, long amount, int intervalDays, String memo) implements CustomPacketPayload {
        public static final Type<StandingOrderCreate> ID = typeOf("standing_order_create");
        public static final StreamCodec<RegistryFriendlyByteBuf, StandingOrderCreate> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), StandingOrderCreate::target,
                ByteBufCodecs.VAR_LONG, StandingOrderCreate::amount,
                ByteBufCodecs.VAR_INT, StandingOrderCreate::intervalDays,
                ByteBufCodecs.stringUtf8(64), StandingOrderCreate::memo,
                StandingOrderCreate::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record StandingOrderCancel(long id) implements CustomPacketPayload {
        public static final Type<StandingOrderCancel> ID = typeOf("standing_order_cancel");
        public static final StreamCodec<RegistryFriendlyByteBuf, StandingOrderCancel> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, StandingOrderCancel::id, StandingOrderCancel::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ messages

    public record ContactsRequest() implements CustomPacketPayload {
        public static final ContactsRequest INSTANCE = new ContactsRequest();
        public static final Type<ContactsRequest> ID = typeOf("contacts_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ContactsRequest> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record OpenThread(Conv conv) implements CustomPacketPayload {
        public static final Type<OpenThread> ID = typeOf("open_thread");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenThread> CODEC = StreamCodec.composite(
                Conv.CODEC, OpenThread::conv, OpenThread::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** A message to a player or group; {@code photoId} > 0 attaches one of your photos. */
    public record SendMessage(Conv conv, String body, long photoId) implements CustomPacketPayload {
        public static final Type<SendMessage> ID = typeOf("send_message");
        public static final StreamCodec<RegistryFriendlyByteBuf, SendMessage> CODEC = StreamCodec.composite(
                Conv.CODEC, SendMessage::conv,
                ByteBufCodecs.stringUtf8(1024), SendMessage::body,
                ByteBufCodecs.VAR_LONG, SendMessage::photoId,
                SendMessage::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record GroupCreate(String name, List<UUID> members) implements CustomPacketPayload {
        public static final Type<GroupCreate> ID = typeOf("group_create");
        public static final StreamCodec<RegistryFriendlyByteBuf, GroupCreate> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), GroupCreate::name,
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(100)), GroupCreate::members,
                GroupCreate::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Add a member ({@code add=true}) or leave the group yourself ({@code add=false}, member ignored). */
    public record GroupMembership(long groupId, UUID member, boolean add) implements CustomPacketPayload {
        public static final Type<GroupMembership> ID = typeOf("group_membership");
        public static final StreamCodec<RegistryFriendlyByteBuf, GroupMembership> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, GroupMembership::groupId,
                UUIDUtil.STREAM_CODEC, GroupMembership::member,
                ByteBufCodecs.BOOL, GroupMembership::add,
                GroupMembership::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ map & privacy

    /** Map app opened (true) or closed (false). Positions are only streamed while subscribed. */
    public record GpsSubscribe(boolean active) implements CustomPacketPayload {
        public static final Type<GpsSubscribe> ID = typeOf("gps_subscribe");
        public static final StreamCodec<RegistryFriendlyByteBuf, GpsSubscribe> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, GpsSubscribe::active, GpsSubscribe::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Who may see you on the map: 0 = nobody, 1 = friends, 2 = everyone. */
    public record GpsMode(int mode) implements CustomPacketPayload {
        public static final Type<GpsMode> ID = typeOf("gps_mode");
        public static final StreamCodec<RegistryFriendlyByteBuf, GpsMode> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, GpsMode::mode, GpsMode::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record FriendSet(UUID friend, boolean add) implements CustomPacketPayload {
        public static final Type<FriendSet> ID = typeOf("friend_set");
        public static final StreamCodec<RegistryFriendlyByteBuf, FriendSet> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, FriendSet::friend,
                ByteBufCodecs.BOOL, FriendSet::add,
                FriendSet::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Ask for waypoints, friends and privacy mode. */
    public record MapRequest() implements CustomPacketPayload {
        public static final MapRequest INSTANCE = new MapRequest();
        public static final Type<MapRequest> ID = typeOf("map_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, MapRequest> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Save the player's current position as a waypoint. The server reads the position itself. */
    public record WaypointSave(String name) implements CustomPacketPayload {
        public static final Type<WaypointSave> ID = typeOf("waypoint_save");
        public static final StreamCodec<RegistryFriendlyByteBuf, WaypointSave> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(24), WaypointSave::name, WaypointSave::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record WaypointDelete(long id) implements CustomPacketPayload {
        public static final Type<WaypointDelete> ID = typeOf("waypoint_delete");
        public static final StreamCodec<RegistryFriendlyByteBuf, WaypointDelete> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, WaypointDelete::id, WaypointDelete::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ market

    public record MarketRequest(String query) implements CustomPacketPayload {
        public static final Type<MarketRequest> ID = typeOf("market_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, MarketRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), MarketRequest::query, MarketRequest::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** List the whole stack in inventory slot {@code slot} for {@code price}. */
    public record MarketSell(int slot, long price) implements CustomPacketPayload {
        public static final Type<MarketSell> ID = typeOf("market_sell");
        public static final StreamCodec<RegistryFriendlyByteBuf, MarketSell> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, MarketSell::slot,
                ByteBufCodecs.VAR_LONG, MarketSell::price,
                MarketSell::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Buy a listing ({@code buy=true}) or take back your own ({@code buy=false}). */
    public record MarketAction(long id, boolean buy) implements CustomPacketPayload {
        public static final Type<MarketAction> ID = typeOf("market_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, MarketAction> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, MarketAction::id,
                ByteBufCodecs.BOOL, MarketAction::buy,
                MarketAction::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ camera

    public static final int MAX_PHOTO_BYTES = 60_000;

    /** A JPEG taken by the Camera app. Large payload: split by Fabric. */
    public record PhotoUpload(byte[] jpeg) implements CustomPacketPayload {
        public static final Type<PhotoUpload> ID = typeOf("photo_upload");
        public static final StreamCodec<RegistryFriendlyByteBuf, PhotoUpload> CODEC = StreamCodec.composite(
                ByteBufCodecs.byteArray(MAX_PHOTO_BYTES), PhotoUpload::jpeg, PhotoUpload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record PhotoRequest(long id) implements CustomPacketPayload {
        public static final Type<PhotoRequest> ID = typeOf("photo_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, PhotoRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, PhotoRequest::id, PhotoRequest::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** List your photos ({@code deleteId} = 0), or delete one first. */
    public record Gallery(long deleteId) implements CustomPacketPayload {
        public static final Type<Gallery> ID = typeOf("gallery");
        public static final StreamCodec<RegistryFriendlyByteBuf, Gallery> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Gallery::deleteId, Gallery::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ 112

    public record Emergency() implements CustomPacketPayload {
        public static final Emergency INSTANCE = new Emergency();
        public static final Type<Emergency> ID = typeOf("emergency");
        public static final StreamCodec<RegistryFriendlyByteBuf, Emergency> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ hotkey

    /** The phone hotkey: the server opens the phone if one is in the inventory. */
    public record OpenRequest() implements CustomPacketPayload {
        public static final OpenRequest INSTANCE = new OpenRequest();
        public static final Type<OpenRequest> ID = typeOf("open_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRequest> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ storage network

    /** Take items of one type out of the network (the storage screen with this container id must be open). */
    public record StorageExtract(int containerId, ItemStack proto, int amount, boolean toInventory) implements CustomPacketPayload {
        public static final Type<StorageExtract> ID = typeOf("storage_extract");
        public static final StreamCodec<RegistryFriendlyByteBuf, StorageExtract> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, StorageExtract::containerId,
                ItemStack.OPTIONAL_STREAM_CODEC, StorageExtract::proto,
                ByteBufCodecs.VAR_INT, StorageExtract::amount,
                ByteBufCodecs.BOOL, StorageExtract::toInventory,
                StorageExtract::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Store the item on the cursor (all of it, or one). */
    public record StorageInsert(int containerId, boolean single) implements CustomPacketPayload {
        public static final Type<StorageInsert> ID = typeOf("storage_insert");
        public static final StreamCodec<RegistryFriendlyByteBuf, StorageInsert> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, StorageInsert::containerId,
                ByteBufCodecs.BOOL, StorageInsert::single,
                StorageInsert::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record StorageHubsRequest() implements CustomPacketPayload {
        public static final StorageHubsRequest INSTANCE = new StorageHubsRequest();
        public static final Type<StorageHubsRequest> ID = typeOf("storage_hubs_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, StorageHubsRequest> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Phone: open a hub from anywhere. */
    public record StorageOpenRemote(String dimension, BlockPos pos) implements CustomPacketPayload {
        public static final Type<StorageOpenRemote> ID = typeOf("storage_open_remote");
        public static final StreamCodec<RegistryFriendlyByteBuf, StorageOpenRemote> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(128), StorageOpenRemote::dimension,
                BlockPos.STREAM_CODEC, StorageOpenRemote::pos,
                StorageOpenRemote::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ parkour

    public record ParkourRequest() implements CustomPacketPayload {
        public static final ParkourRequest INSTANCE = new ParkourRequest();
        public static final Type<ParkourRequest> ID = typeOf("parkour_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ParkourRequest> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record ParkourBoardRequest(String course) implements CustomPacketPayload {
        public static final Type<ParkourBoardRequest> ID = typeOf("parkour_board_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ParkourBoardRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), ParkourBoardRequest::course, ParkourBoardRequest::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record ParkourChallenge(String course, String target) implements CustomPacketPayload {
        public static final Type<ParkourChallenge> ID = typeOf("parkour_challenge");
        public static final StreamCodec<RegistryFriendlyByteBuf, ParkourChallenge> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), ParkourChallenge::course,
                ByteBufCodecs.stringUtf8(16), ParkourChallenge::target,
                ParkourChallenge::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record ParkourRespond(boolean accept) implements CustomPacketPayload {
        public static final Type<ParkourRespond> ID = typeOf("parkour_respond");
        public static final StreamCodec<RegistryFriendlyByteBuf, ParkourRespond> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, ParkourRespond::accept, ParkourRespond::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ cctv

    public record CctvList() implements CustomPacketPayload {
        public static final CctvList INSTANCE = new CctvList();
        public static final Type<CctvList> ID = typeOf("cctv_list");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvList> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record CctvView(String dimension, BlockPos pos) implements CustomPacketPayload {
        public static final Type<CctvView> ID = typeOf("cctv_view");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvView> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(128), CctvView::dimension, BlockPos.STREAM_CODEC, CctvView::pos, CctvView::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record CctvStop() implements CustomPacketPayload {
        public static final CctvStop INSTANCE = new CctvStop();
        public static final Type<CctvStop> ID = typeOf("cctv_stop");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvStop> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record CctvRotate(float yaw, float pitch) implements CustomPacketPayload {
        public static final Type<CctvRotate> ID = typeOf("cctv_rotate");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvRotate> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, CctvRotate::yaw, ByteBufCodecs.FLOAT, CctvRotate::pitch, CctvRotate::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record CctvRename(String dimension, BlockPos pos, String name) implements CustomPacketPayload {
        public static final Type<CctvRename> ID = typeOf("cctv_rename");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvRename> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(128), CctvRename::dimension, BlockPos.STREAM_CODEC, CctvRename::pos,
                ByteBufCodecs.stringUtf8(24), CctvRename::name, CctvRename::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record CctvReplayRequest() implements CustomPacketPayload {
        public static final CctvReplayRequest INSTANCE = new CctvReplayRequest();
        public static final Type<CctvReplayRequest> ID = typeOf("cctv_replay_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvReplayRequest> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
}
