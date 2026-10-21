package nl.smartphonemod.network;

import nl.smartphonemod.SmartphoneMod;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/** Server-to-client packets. The client only ever displays what these contain. */
public final class S2C {
    private S2C() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
        return new CustomPacketPayload.Type<>(SmartphoneMod.id(name));
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, String> STR = ByteBufCodecs.STRING_UTF8.cast();

    // ------------------------------------------------------------------ general

    /** Server rules the client needs to show (fees, limits). Sent on join. */
    public record ServerInfo(double transferFeePercent, long transferFeeMinimum, double marketFeePercent,
                             double dailyInterestPercent, boolean emergencyToAll, int maxMessageLength)
            implements CustomPacketPayload {
        public static final Type<ServerInfo> ID = typeOf("server_info");
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerInfo> CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, ServerInfo::transferFeePercent,
                ByteBufCodecs.VAR_LONG, ServerInfo::transferFeeMinimum,
                ByteBufCodecs.DOUBLE, ServerInfo::marketFeePercent,
                ByteBufCodecs.DOUBLE, ServerInfo::dailyInterestPercent,
                ByteBufCodecs.BOOL, ServerInfo::emergencyToAll,
                ByteBufCodecs.VAR_INT, ServerInfo::maxMessageLength,
                ServerInfo::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }

        public long feeFor(long amount) {
            if (transferFeePercent <= 0) return 0;
            return Math.max(transferFeeMinimum, (long) Math.floor(amount * transferFeePercent / 100.0));
        }
    }

    /** The server validated the right-click: open the phone with this initial state. */
    public record OpenPhone(long balance, int unread, int gpsMode) implements CustomPacketPayload {
        public static final Type<OpenPhone> ID = typeOf("open_phone");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPhone> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, OpenPhone::balance,
                ByteBufCodecs.VAR_INT, OpenPhone::unread,
                ByteBufCodecs.VAR_INT, OpenPhone::gpsMode,
                OpenPhone::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** A notification for the corner pop-up (and the phone's banner). {@code app} picks the icon. */
    public record Notify(String app, Component title, Component body) implements CustomPacketPayload {
        public static final Type<Notify> ID = typeOf("notify");
        public static final StreamCodec<RegistryFriendlyByteBuf, Notify> CODEC = StreamCodec.composite(
                STR, Notify::app,
                ComponentSerialization.TRUSTED_STREAM_CODEC, Notify::title,
                ComponentSerialization.TRUSTED_STREAM_CODEC, Notify::body,
                Notify::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ bank

    /** One line on the bank statement. {@code amount} is signed from the viewer's perspective. */
    public record TxEntry(String type, String counterparty, long amount, long fee, String memo, long timestamp) {
        public static final StreamCodec<RegistryFriendlyByteBuf, TxEntry> CODEC = StreamCodec.composite(
                STR, TxEntry::type,
                STR, TxEntry::counterparty,
                ByteBufCodecs.VAR_LONG, TxEntry::amount,
                ByteBufCodecs.VAR_LONG, TxEntry::fee,
                STR, TxEntry::memo,
                ByteBufCodecs.VAR_LONG, TxEntry::timestamp,
                TxEntry::new);
    }

    /** An open payment request. {@code incoming} means the viewer is asked to pay. */
    public record RequestEntry(long id, String otherName, long amount, String memo, boolean incoming, long timestamp) {
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, RequestEntry::id,
                STR, RequestEntry::otherName,
                ByteBufCodecs.VAR_LONG, RequestEntry::amount,
                STR, RequestEntry::memo,
                ByteBufCodecs.BOOL, RequestEntry::incoming,
                ByteBufCodecs.VAR_LONG, RequestEntry::timestamp,
                RequestEntry::new);
    }

    /** A standing order. {@code incoming} means the viewer receives the money. */
    public record OrderEntry(long id, String otherName, long amount, int intervalDays, long daysUntilNext,
                             String memo, boolean incoming) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OrderEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, OrderEntry::id,
                STR, OrderEntry::otherName,
                ByteBufCodecs.VAR_LONG, OrderEntry::amount,
                ByteBufCodecs.VAR_INT, OrderEntry::intervalDays,
                ByteBufCodecs.VAR_LONG, OrderEntry::daysUntilNext,
                STR, OrderEntry::memo,
                ByteBufCodecs.BOOL, OrderEntry::incoming,
                OrderEntry::new);
    }

    /**
     * Balance, statement, requests and standing orders. {@code statusKey} is an optional translation key
     * describing the result of the last action (empty when there is none), with '|'-separated arguments.
     */
    public record BankData(long balance, List<TxEntry> history, List<RequestEntry> requests, List<OrderEntry> orders,
                           String statusKey, String statusArg, boolean ok) implements CustomPacketPayload {
        public static final Type<BankData> ID = typeOf("bank_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, BankData> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, BankData::balance,
                TxEntry.CODEC.apply(ByteBufCodecs.list()), BankData::history,
                RequestEntry.CODEC.apply(ByteBufCodecs.list()), BankData::requests,
                OrderEntry.CODEC.apply(ByteBufCodecs.list()), BankData::orders,
                STR, BankData::statusKey,
                STR, BankData::statusArg,
                ByteBufCodecs.BOOL, BankData::ok,
                BankData::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ messages

    public record ContactEntry(UUID uuid, String name, boolean online, int unread, boolean friend) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ContactEntry> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ContactEntry::uuid,
                STR, ContactEntry::name,
                ByteBufCodecs.BOOL, ContactEntry::online,
                ByteBufCodecs.VAR_INT, ContactEntry::unread,
                ByteBufCodecs.BOOL, ContactEntry::friend,
                ContactEntry::new);
    }

    public record GroupEntry(long id, String name, int members, int unread) {
        public static final StreamCodec<RegistryFriendlyByteBuf, GroupEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, GroupEntry::id,
                STR, GroupEntry::name,
                ByteBufCodecs.VAR_INT, GroupEntry::members,
                ByteBufCodecs.VAR_INT, GroupEntry::unread,
                GroupEntry::new);
    }

    public record Contacts(List<ContactEntry> contacts, List<GroupEntry> groups) implements CustomPacketPayload {
        public static final Type<Contacts> ID = typeOf("contacts");
        public static final StreamCodec<RegistryFriendlyByteBuf, Contacts> CODEC = StreamCodec.composite(
                ContactEntry.CODEC.apply(ByteBufCodecs.list()), Contacts::contacts,
                GroupEntry.CODEC.apply(ByteBufCodecs.list()), Contacts::groups,
                Contacts::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record MessageEntry(boolean mine, String sender, String body, long photoId, long timestamp) {
        public static final StreamCodec<RegistryFriendlyByteBuf, MessageEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, MessageEntry::mine,
                STR, MessageEntry::sender,
                STR, MessageEntry::body,
                ByteBufCodecs.VAR_LONG, MessageEntry::photoId,
                ByteBufCodecs.VAR_LONG, MessageEntry::timestamp,
                MessageEntry::new);
    }

    /** History of one conversation (oldest first). For groups, {@code members} lists everyone. */
    public record ChatThread(Conv conv, String title, boolean online, List<String> members, List<MessageEntry> messages)
            implements CustomPacketPayload {
        public static final Type<ChatThread> ID = typeOf("thread");
        public static final StreamCodec<RegistryFriendlyByteBuf, ChatThread> CODEC = StreamCodec.composite(
                Conv.CODEC, ChatThread::conv,
                STR, ChatThread::title,
                ByteBufCodecs.BOOL, ChatThread::online,
                STR.apply(ByteBufCodecs.list()), ChatThread::members,
                MessageEntry.CODEC.apply(ByteBufCodecs.list()), ChatThread::messages,
                ChatThread::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Push: someone just sent you (or one of your groups) a message. */
    public record NewMessage(Conv conv, String fromName, String groupName, String body, boolean photo, long timestamp)
            implements CustomPacketPayload {
        public static final Type<NewMessage> ID = typeOf("new_message");
        public static final StreamCodec<RegistryFriendlyByteBuf, NewMessage> CODEC = StreamCodec.composite(
                Conv.CODEC, NewMessage::conv,
                STR, NewMessage::fromName,
                STR, NewMessage::groupName,
                STR, NewMessage::body,
                ByteBufCodecs.BOOL, NewMessage::photo,
                ByteBufCodecs.VAR_LONG, NewMessage::timestamp,
                NewMessage::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Total unread messages (drives the badge on the app icon and the HUD). */
    public record UnreadCount(int count, boolean onLogin) implements CustomPacketPayload {
        public static final Type<UnreadCount> ID = typeOf("unread_count");
        public static final StreamCodec<RegistryFriendlyByteBuf, UnreadCount> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, UnreadCount::count,
                ByteBufCodecs.BOOL, UnreadCount::onLogin,
                UnreadCount::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ map

    /** Coordinates are sent as whole blocks: plenty for a map dot and smaller on the wire. */
    public record PlayerPos(UUID uuid, String name, int x, int z, float yaw, boolean friend) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerPos> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, PlayerPos::uuid,
                STR, PlayerPos::name,
                ByteBufCodecs.VAR_INT, PlayerPos::x,
                ByteBufCodecs.VAR_INT, PlayerPos::z,
                ByteBufCodecs.FLOAT, PlayerPos::yaw,
                ByteBufCodecs.BOOL, PlayerPos::friend,
                PlayerPos::new);
    }

    public record Positions(List<PlayerPos> players) implements CustomPacketPayload {
        public static final Type<Positions> ID = typeOf("positions");
        public static final StreamCodec<RegistryFriendlyByteBuf, Positions> CODEC = StreamCodec.composite(
                PlayerPos.CODEC.apply(ByteBufCodecs.list()), Positions::players, Positions::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record FriendEntry(UUID uuid, String name) {
        public static final StreamCodec<RegistryFriendlyByteBuf, FriendEntry> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, FriendEntry::uuid,
                STR, FriendEntry::name,
                FriendEntry::new);
    }

    public record WaypointEntry(long id, String name, String dimension, int x, int y, int z) {
        public static final StreamCodec<RegistryFriendlyByteBuf, WaypointEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, WaypointEntry::id,
                STR, WaypointEntry::name,
                STR, WaypointEntry::dimension,
                ByteBufCodecs.VAR_INT, WaypointEntry::x,
                ByteBufCodecs.VAR_INT, WaypointEntry::y,
                ByteBufCodecs.VAR_INT, WaypointEntry::z,
                WaypointEntry::new);
    }

    /** Privacy mode, friends and waypoints. */
    public record MapData(int gpsMode, List<FriendEntry> friends, List<WaypointEntry> waypoints) implements CustomPacketPayload {
        public static final Type<MapData> ID = typeOf("map_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, MapData> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, MapData::gpsMode,
                FriendEntry.CODEC.apply(ByteBufCodecs.list()), MapData::friends,
                WaypointEntry.CODEC.apply(ByteBufCodecs.list()), MapData::waypoints,
                MapData::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ market

    public record ListingEntry(long id, String seller, ItemStack item, long price, boolean mine) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ListingEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, ListingEntry::id,
                STR, ListingEntry::seller,
                ItemStack.OPTIONAL_STREAM_CODEC, ListingEntry::item,
                ByteBufCodecs.VAR_LONG, ListingEntry::price,
                ByteBufCodecs.BOOL, ListingEntry::mine,
                ListingEntry::new);
    }

    public record MarketData(List<ListingEntry> listings, String statusKey, String statusArg, boolean ok)
            implements CustomPacketPayload {
        public static final Type<MarketData> ID = typeOf("market_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, MarketData> CODEC = StreamCodec.composite(
                ListingEntry.CODEC.apply(ByteBufCodecs.list()), MarketData::listings,
                STR, MarketData::statusKey,
                STR, MarketData::statusArg,
                ByteBufCodecs.BOOL, MarketData::ok,
                MarketData::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ camera

    public record PhotoData(long id, byte[] jpeg) implements CustomPacketPayload {
        public static final Type<PhotoData> ID = typeOf("photo_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, PhotoData> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, PhotoData::id,
                ByteBufCodecs.byteArray(C2S.MAX_PHOTO_BYTES), PhotoData::jpeg,
                PhotoData::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** Your photos, newest first. {@code justTaken} is the id of a photo that was just saved (0 if none). */
    public record GalleryData(List<Long> photos, long justTaken) implements CustomPacketPayload {
        public static final Type<GalleryData> ID = typeOf("gallery_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, GalleryData> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), GalleryData::photos,
                ByteBufCodecs.VAR_LONG, GalleryData::justTaken,
                GalleryData::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ 112

    public record EmergencyAlert(UUID from, String name, int x, int y, int z, String dimension) implements CustomPacketPayload {
        public static final Type<EmergencyAlert> ID = typeOf("emergency_alert");
        public static final StreamCodec<RegistryFriendlyByteBuf, EmergencyAlert> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, EmergencyAlert::from,
                STR, EmergencyAlert::name,
                ByteBufCodecs.VAR_INT, EmergencyAlert::x,
                ByteBufCodecs.VAR_INT, EmergencyAlert::y,
                ByteBufCodecs.VAR_INT, EmergencyAlert::z,
                STR, EmergencyAlert::dimension,
                EmergencyAlert::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ storage network

    public record StorageEntry(ItemStack item, long count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, StorageEntry> CODEC = StreamCodec.composite(
                ItemStack.OPTIONAL_STREAM_CODEC, StorageEntry::item,
                ByteBufCodecs.VAR_LONG, StorageEntry::count,
                StorageEntry::new);
    }

    /** Everything in a hub's network, for the open storage screen with this container id. */
    public record StorageContents(int containerId, List<StorageEntry> entries, int linked, int unavailable,
                                  int usedSlots, int totalSlots) implements CustomPacketPayload {
        public static final Type<StorageContents> ID = typeOf("storage_contents");
        public static final StreamCodec<RegistryFriendlyByteBuf, StorageContents> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, StorageContents::containerId,
                StorageEntry.CODEC.apply(ByteBufCodecs.list()), StorageContents::entries,
                ByteBufCodecs.VAR_INT, StorageContents::linked,
                ByteBufCodecs.VAR_INT, StorageContents::unavailable,
                ByteBufCodecs.VAR_INT, StorageContents::usedSlots,
                ByteBufCodecs.VAR_INT, StorageContents::totalSlots,
                StorageContents::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record HubEntry(String dimension, int x, int y, int z, String name, String owner, boolean mine) {
        public static final StreamCodec<RegistryFriendlyByteBuf, HubEntry> CODEC = StreamCodec.composite(
                STR, HubEntry::dimension,
                ByteBufCodecs.VAR_INT, HubEntry::x,
                ByteBufCodecs.VAR_INT, HubEntry::y,
                ByteBufCodecs.VAR_INT, HubEntry::z,
                STR, HubEntry::name,
                STR, HubEntry::owner,
                ByteBufCodecs.BOOL, HubEntry::mine,
                HubEntry::new);
    }

    public record StorageHubs(List<HubEntry> hubs, boolean remoteEnabled, long fee) implements CustomPacketPayload {
        public static final Type<StorageHubs> ID = typeOf("storage_hubs");
        public static final StreamCodec<RegistryFriendlyByteBuf, StorageHubs> CODEC = StreamCodec.composite(
                HubEntry.CODEC.apply(ByteBufCodecs.list()), StorageHubs::hubs,
                ByteBufCodecs.BOOL, StorageHubs::remoteEnabled,
                ByteBufCodecs.VAR_LONG, StorageHubs::fee,
                StorageHubs::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ parkour

    public record ParkourCourse(String name, String display, int checkpoints, int x, int y, int z, String dimension,
                                long myBest, String recordName, long recordMs, int myRank, int players) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParkourCourse> CODEC = StreamCodec.composite(
                STR, ParkourCourse::name,
                STR, ParkourCourse::display,
                ByteBufCodecs.VAR_INT, ParkourCourse::checkpoints,
                ByteBufCodecs.VAR_INT, ParkourCourse::x,
                ByteBufCodecs.VAR_INT, ParkourCourse::y,
                ByteBufCodecs.VAR_INT, ParkourCourse::z,
                STR, ParkourCourse::dimension,
                ByteBufCodecs.VAR_LONG, ParkourCourse::myBest,
                STR, ParkourCourse::recordName,
                ByteBufCodecs.VAR_LONG, ParkourCourse::recordMs,
                ByteBufCodecs.VAR_INT, ParkourCourse::myRank,
                ByteBufCodecs.VAR_INT, ParkourCourse::players,
                ParkourCourse::new);
    }

    /** All courses, plus a pending race invite (empty strings if none). */
    public record ParkourCourses(List<ParkourCourse> courses, String inviteFrom, String inviteCourse) implements CustomPacketPayload {
        public static final Type<ParkourCourses> ID = typeOf("parkour_courses");
        public static final StreamCodec<RegistryFriendlyByteBuf, ParkourCourses> CODEC = StreamCodec.composite(
                ParkourCourse.CODEC.apply(ByteBufCodecs.list()), ParkourCourses::courses,
                STR, ParkourCourses::inviteFrom,
                STR, ParkourCourses::inviteCourse,
                ParkourCourses::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record ParkourBoardEntry(int rank, String name, long ms, int wins, boolean me) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParkourBoardEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ParkourBoardEntry::rank,
                STR, ParkourBoardEntry::name,
                ByteBufCodecs.VAR_LONG, ParkourBoardEntry::ms,
                ByteBufCodecs.VAR_INT, ParkourBoardEntry::wins,
                ByteBufCodecs.BOOL, ParkourBoardEntry::me,
                ParkourBoardEntry::new);
    }

    public record ParkourBoard(String course, String display, List<ParkourBoardEntry> entries, int myRank, long myBest,
                               int myRuns, int myWins) implements CustomPacketPayload {
        public static final Type<ParkourBoard> ID = typeOf("parkour_board");
        public static final StreamCodec<RegistryFriendlyByteBuf, ParkourBoard> CODEC = StreamCodec.composite(
                STR, ParkourBoard::course,
                STR, ParkourBoard::display,
                ParkourBoardEntry.CODEC.apply(ByteBufCodecs.list()), ParkourBoard::entries,
                ByteBufCodecs.VAR_INT, ParkourBoard::myRank,
                ByteBufCodecs.VAR_LONG, ParkourBoard::myBest,
                ByteBufCodecs.VAR_INT, ParkourBoard::myRuns,
                ByteBufCodecs.VAR_INT, ParkourBoard::myWins,
                ParkourBoard::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ------------------------------------------------------------------ cctv

    /** Whether this player may see the CCTV app / manage cameras. */
    public record CctvAccess(boolean view, boolean manage) implements CustomPacketPayload {
        public static final Type<CctvAccess> ID = typeOf("cctv_access");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvAccess> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, CctvAccess::view, ByteBufCodecs.BOOL, CctvAccess::manage, CctvAccess::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record CctvCamera(String dimension, net.minecraft.core.BlockPos pos, String name, boolean online, int viewers, boolean sameDim) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvCamera> CODEC = StreamCodec.composite(
                STR, CctvCamera::dimension,
                net.minecraft.core.BlockPos.STREAM_CODEC, CctvCamera::pos,
                STR, CctvCamera::name,
                ByteBufCodecs.BOOL, CctvCamera::online,
                ByteBufCodecs.VAR_INT, CctvCamera::viewers,
                ByteBufCodecs.BOOL, CctvCamera::sameDim,
                CctvCamera::new);
    }

    public record CctvCameras(List<CctvCamera> cameras) implements CustomPacketPayload {
        public static final Type<CctvCameras> ID = typeOf("cctv_cameras");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvCameras> CODEC = StreamCodec.composite(
                CctvCamera.CODEC.apply(ByteBufCodecs.list()), CctvCameras::cameras, CctvCameras::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** The server now tracks the world from this camera: switch the view. */
    public record CctvViewStart(net.minecraft.core.BlockPos pos, String name, boolean canControl) implements CustomPacketPayload {
        public static final Type<CctvViewStart> ID = typeOf("cctv_view_start");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvViewStart> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, CctvViewStart::pos,
                STR, CctvViewStart::name,
                ByteBufCodecs.BOOL, CctvViewStart::canControl,
                CctvViewStart::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    public record CctvViewEnd(String reasonKey, String reasonFallback) implements CustomPacketPayload {
        public static final Type<CctvViewEnd> ID = typeOf("cctv_view_end");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvViewEnd> CODEC = StreamCodec.composite(
                STR, CctvViewEnd::reasonKey, STR, CctvViewEnd::reasonFallback, CctvViewEnd::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** The last 30 seconds around a camera (compressed, see CctvRecorder). */
    public record CctvRecording(net.minecraft.core.BlockPos pos, byte[] data) implements CustomPacketPayload {
        public static final Type<CctvRecording> ID = typeOf("cctv_recording");
        public static final StreamCodec<RegistryFriendlyByteBuf, CctvRecording> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, CctvRecording::pos,
                ByteBufCodecs.byteArray(8 * 1024 * 1024), CctvRecording::data,
                CctvRecording::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }
}
