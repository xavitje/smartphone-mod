package nl.smartphonemod.client;

import nl.smartphonemod.network.S2C;

import java.util.ArrayList;
import java.util.List;

/**
 * What the client currently knows. Everything here comes from the server; the UI only reads it.
 */
public final class ClientPhoneState {
    public static S2C.ServerInfo serverInfo = new S2C.ServerInfo(0, 0, 0, 0, true, 256);

    public static long balance;
    public static int unread;
    public static int gpsMode = 2;

    public static List<S2C.TxEntry> history = List.of();
    public static List<S2C.RequestEntry> requests = List.of();
    public static List<S2C.OrderEntry> orders = List.of();
    public static String statusKey = "";
    public static String statusArg = "";
    public static boolean statusOk = true;
    public static long statusTime;

    /** null until the server answered the first contacts request. */
    public static List<S2C.ContactEntry> contacts;
    public static List<S2C.GroupEntry> groups = List.of();
    public static S2C.ChatThread thread;

    public static List<S2C.PlayerPos> positions = List.of();
    public static long positionsTime;
    public static List<S2C.FriendEntry> friends = List.of();
    public static List<S2C.WaypointEntry> waypoints = List.of();
    /** Waypoint the HUD compass points to (0 = none). */
    public static long navigationTarget;

    public static List<S2C.ListingEntry> listings = List.of();
    public static String marketStatusKey = "";
    public static String marketStatusArg = "";
    public static boolean marketStatusOk = true;
    public static long marketStatusTime;

    public static List<Long> photos = List.of();
    public static long justTakenPhoto;

    /** null until the server answered the Storage app's first request. */
    public static S2C.StorageHubs storageHubs;

    public static S2C.ParkourCourses parkour;
    public static S2C.ParkourBoard parkourBoard;

    /** CCTV permissions (the app is hidden without view). */
    public static boolean cctvView, cctvManage;
    public static List<S2C.CctvCamera> cctvCameras;

    /** Recent 112 alerts, shown on the map for a few minutes. */
    public static final List<Alert> alerts = new ArrayList<>();

    public record Alert(S2C.EmergencyAlert alert, long time) {}

    private ClientPhoneState() {}

    public static Object[] args(String joined) {
        return joined == null || joined.isEmpty() ? new Object[0] : (Object[]) joined.split("\\|");
    }

    public static void reset() {
        balance = 0;
        unread = 0;
        gpsMode = 2;
        history = List.of();
        requests = List.of();
        orders = List.of();
        statusKey = "";
        statusArg = "";
        contacts = null;
        groups = List.of();
        thread = null;
        positions = List.of();
        friends = List.of();
        waypoints = List.of();
        navigationTarget = 0;
        listings = List.of();
        marketStatusKey = "";
        photos = List.of();
        justTakenPhoto = 0;
        storageHubs = null;
        parkour = null;
        parkourBoard = null;
        cctvView = false;
        cctvManage = false;
        cctvCameras = null;
        alerts.clear();
    }
}
