package nl.smartphonemod.server;

import nl.smartphonemod.SmartphoneMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite storage, keyed on player UUIDs.
 *
 * <p>All SQL runs on one dedicated thread, so the server tick never waits on disk and every
 * operation is naturally serialised. Money always moves inside SQL transactions with a guarded
 * {@code UPDATE ... WHERE balance >= ?}, so a balance can never go negative or be spent twice.
 *
 * <p>The schema is versioned: {@link #migrate} upgrades older databases in place.
 */
public final class PhoneDatabase {
    static final int SCHEMA_VERSION = 5;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        java.lang.Thread t = new java.lang.Thread(r, "Smartphone-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;
    private Path file;

    @FunctionalInterface
    public interface SqlTask<T> {
        T run(Connection c) throws SQLException;
    }

    private PhoneDatabase() {}

    public static PhoneDatabase open(Path file) {
        PhoneDatabase db = new PhoneDatabase();
        db.file = file;
        db.executor.submit(() -> {
            try {
                Files.createDirectories(file.getParent());
                // Instantiate the driver directly: DriverManager's service lookup is unreliable under Fabric's class loader.
                db.conn = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
                try (Statement s = db.conn.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=NORMAL");
                }
                migrate(db.conn, file);
                SmartphoneMod.LOGGER.info("Smartphone database opened at {}", file);
            } catch (Exception e) {
                SmartphoneMod.LOGGER.error("Could not open the smartphone database at {}", file, e);
            }
        });
        return db;
    }

    /** Opens an existing connection (used by tests). */
    public static PhoneDatabase forTesting(Path file) {
        return open(file);
    }

    public void close() {
        executor.submit(() -> {
            try {
                if (conn != null) conn.close();
            } catch (SQLException e) {
                SmartphoneMod.LOGGER.warn("Error closing smartphone database", e);
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            java.lang.Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- backups

    static Path backupDir(Path dbFile) {
        return dbFile.toAbsolutePath().getParent().resolve("backups");
    }

    Path backupDir() {
        return backupDir(file);
    }

    /** Writes a consistent copy of the live database (safe while the server keeps running). */
    static void vacuumInto(Connection c, Path target) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
            ps.setString(1, target.toAbsolutePath().toString());
            ps.execute();
        }
    }

    /** Runs a task on the database thread. Failures complete the future exceptionally. */
    public <T> CompletableFuture<T> submit(SqlTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.run(need(conn)));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (Exception rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    /** Runs {@code body} inside a transaction on the database thread. */
    public <T> CompletableFuture<T> transaction(SqlTask<T> body) {
        return submit(c -> inTransaction(c, body));
    }

    static <T> T inTransaction(Connection c, SqlTask<T> body) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T result = body.run(c);
            c.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static Connection need(Connection c) throws SQLException {
        if (c == null) throw new SQLException("Database is not available");
        return c;
    }

    // ---------------------------------------------------------------- schema

    private static void migrate(Connection c, Path file) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
        int version = Integer.parseInt(meta(c, "schema_version", "0"));
        boolean fresh = !tableExists(c, "accounts");
        if (!fresh && version < SCHEMA_VERSION) {
            // Keep a copy of the old database before changing its structure.
            Path copy = backupDir(file).resolve("before-upgrade-v" + version + "-" + System.currentTimeMillis() + ".db");
            try {
                Files.createDirectories(copy.getParent());
                vacuumInto(c, copy);
                SmartphoneMod.LOGGER.info("Smartphone database backed up to {} before upgrading", copy);
            } catch (Exception e) {
                SmartphoneMod.LOGGER.warn("Could not back up the smartphone database before upgrading", e);
            }
        }

        inTransaction(c, tx -> {
            try (Statement s = tx.createStatement()) {
                // --- version 1 (the original mod)
                s.execute("""
                        CREATE TABLE IF NOT EXISTS accounts (
                            uuid        TEXT PRIMARY KEY,
                            name        TEXT NOT NULL,
                            name_lower  TEXT NOT NULL,
                            balance     INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0),
                            gps_visible INTEGER NOT NULL DEFAULT 1,
                            last_seen   INTEGER NOT NULL
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name_lower)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS transactions (
                            id        INTEGER PRIMARY KEY AUTOINCREMENT,
                            type      TEXT NOT NULL,
                            from_uuid TEXT,
                            to_uuid   TEXT,
                            amount    INTEGER NOT NULL CHECK (amount > 0),
                            ts        INTEGER NOT NULL
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_tx_from ON transactions(from_uuid, id)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_tx_to ON transactions(to_uuid, id)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS messages (
                            id        INTEGER PRIMARY KEY AUTOINCREMENT,
                            sender    TEXT NOT NULL,
                            recipient TEXT NOT NULL,
                            body      TEXT NOT NULL,
                            ts        INTEGER NOT NULL,
                            is_read   INTEGER NOT NULL DEFAULT 0
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_msg_pair ON messages(sender, recipient, id)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_msg_unread ON messages(recipient, is_read)");

                // --- version 2: groups, requests, standing orders, waypoints, friends, market, photos
                if (version < 2) {
                    addColumn(tx, "accounts", "gps_mode", "INTEGER NOT NULL DEFAULT 2");
                    if (!fresh) s.execute("UPDATE accounts SET gps_mode = CASE gps_visible WHEN 1 THEN 2 ELSE 0 END");
                    addColumn(tx, "transactions", "memo", "TEXT NOT NULL DEFAULT ''");
                    addColumn(tx, "transactions", "fee", "INTEGER NOT NULL DEFAULT 0");
                    addColumn(tx, "messages", "group_id", "INTEGER NOT NULL DEFAULT 0");
                    addColumn(tx, "messages", "photo_id", "INTEGER NOT NULL DEFAULT 0");
                }
                s.execute("CREATE INDEX IF NOT EXISTS idx_msg_group ON messages(group_id, id)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_tx_ts ON transactions(ts)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS chat_groups (
                            id      INTEGER PRIMARY KEY AUTOINCREMENT,
                            name    TEXT NOT NULL,
                            owner   TEXT NOT NULL,
                            created INTEGER NOT NULL
                        )""");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS group_members (
                            group_id  INTEGER NOT NULL,
                            uuid      TEXT NOT NULL,
                            last_read INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY (group_id, uuid)
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_members_uuid ON group_members(uuid)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS pay_requests (
                            id        INTEGER PRIMARY KEY AUTOINCREMENT,
                            requester TEXT NOT NULL,
                            payer     TEXT NOT NULL,
                            amount    INTEGER NOT NULL CHECK (amount > 0),
                            memo      TEXT NOT NULL DEFAULT '',
                            status    TEXT NOT NULL DEFAULT 'OPEN',
                            ts        INTEGER NOT NULL
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_req_payer ON pay_requests(payer, status)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_req_requester ON pay_requests(requester, status)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS standing_orders (
                            id            INTEGER PRIMARY KEY AUTOINCREMENT,
                            payer         TEXT NOT NULL,
                            payee         TEXT NOT NULL,
                            amount        INTEGER NOT NULL CHECK (amount > 0),
                            interval_days INTEGER NOT NULL CHECK (interval_days > 0),
                            next_day      INTEGER NOT NULL,
                            memo          TEXT NOT NULL DEFAULT '',
                            active        INTEGER NOT NULL DEFAULT 1
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_orders_due ON standing_orders(active, next_day)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS waypoints (
                            id    INTEGER PRIMARY KEY AUTOINCREMENT,
                            owner TEXT NOT NULL,
                            name  TEXT NOT NULL,
                            dim   TEXT NOT NULL,
                            x     INTEGER NOT NULL,
                            y     INTEGER NOT NULL,
                            z     INTEGER NOT NULL
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_waypoints_owner ON waypoints(owner)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS friends (
                            owner  TEXT NOT NULL,
                            friend TEXT NOT NULL,
                            PRIMARY KEY (owner, friend)
                        )""");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS listings (
                            id        INTEGER PRIMARY KEY AUTOINCREMENT,
                            seller    TEXT NOT NULL,
                            item      TEXT NOT NULL,
                            item_name TEXT NOT NULL,
                            price     INTEGER NOT NULL CHECK (price > 0),
                            status    TEXT NOT NULL DEFAULT 'ACTIVE',
                            buyer     TEXT,
                            ts        INTEGER NOT NULL
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_listings_status ON listings(status, id)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS photos (
                            id    INTEGER PRIMARY KEY AUTOINCREMENT,
                            owner TEXT NOT NULL,
                            data  BLOB NOT NULL,
                            ts    INTEGER NOT NULL
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_photos_owner ON photos(owner, id)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS pending_items (
                            id     INTEGER PRIMARY KEY AUTOINCREMENT,
                            owner  TEXT NOT NULL,
                            item   TEXT NOT NULL,
                            reason TEXT NOT NULL
                        )""");
                // --- version 3: storage hubs (for the phone's Storage app)
                s.execute("""
                        CREATE TABLE IF NOT EXISTS storage_hubs (
                            dim   TEXT NOT NULL,
                            x     INTEGER NOT NULL,
                            y     INTEGER NOT NULL,
                            z     INTEGER NOT NULL,
                            owner TEXT NOT NULL,
                            name  TEXT NOT NULL,
                            PRIMARY KEY (dim, x, y, z)
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS idx_storage_hubs_owner ON storage_hubs(owner)");
                // --- version 4: parkour courses and times
                s.execute("CREATE TABLE IF NOT EXISTS parkour_courses (name TEXT PRIMARY KEY, data TEXT NOT NULL)");
                // --- version 5: security cameras
                s.execute("""
                        CREATE TABLE IF NOT EXISTS cctv_cameras (
                            dim TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                            name TEXT NOT NULL, owner TEXT NOT NULL, PRIMARY KEY (dim, x, y, z)
                        )""");
                s.execute("CREATE TABLE IF NOT EXISTS parkour_rewards (course TEXT NOT NULL, uuid TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (course, uuid))");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS parkour_times (
                            course  TEXT NOT NULL,
                            uuid    TEXT NOT NULL,
                            best_ms INTEGER,
                            splits  TEXT NOT NULL DEFAULT '',
                            runs    INTEGER NOT NULL DEFAULT 0,
                            wins    INTEGER NOT NULL DEFAULT 0,
                            ts      INTEGER NOT NULL,
                            PRIMARY KEY (course, uuid)
                        )""");
            }
            setMeta(tx, "schema_version", Integer.toString(SCHEMA_VERSION));
            return null;
        });
        if (version < SCHEMA_VERSION && !fresh) {
            SmartphoneMod.LOGGER.info("Smartphone database upgraded from schema {} to {}", version, SCHEMA_VERSION);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void addColumn(Connection c, String table, String column, String definition) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    static String meta(Connection c, String key, String fallback) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : fallback;
            }
        }
    }

    static void setMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- shared helpers (call on the DB thread)

    public record Account(long balance, int gpsMode) {}

    /** Parses a stored UUID; a damaged row returns null and is skipped instead of breaking the whole list. */
    public static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Creates or refreshes the account and returns its balance and GPS mode. */
    public CompletableFuture<Account> upsertAccount(UUID uuid, String name, long startingBalance, boolean gpsDefault) {
        return transaction(tx -> {
            try (PreparedStatement ps = tx.prepareStatement("""
                    INSERT INTO accounts(uuid, name, name_lower, balance, gps_visible, gps_mode, last_seen) VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower,
                                                    last_seen = excluded.last_seen""")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, name.toLowerCase(Locale.ROOT));
                ps.setLong(4, startingBalance);
                ps.setInt(5, gpsDefault ? 1 : 0);
                ps.setInt(6, gpsDefault ? 2 : 0);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement("SELECT balance, gps_mode FROM accounts WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Account(rs.getLong(1), rs.getInt(2));
                }
            }
        });
    }

    public CompletableFuture<Long> balance(UUID uuid) {
        return submit(c -> balanceSync(c, uuid));
    }

    static long balanceSync(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    static String nameSync(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM accounts WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Finds a player by name (case-insensitive); null if they never joined. */
    static UUID uuidByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid FROM accounts WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        }
    }

    /** Takes {@code amount} if the balance allows it. Returns false (and changes nothing) otherwise. */
    public static boolean debit(Connection tx, UUID uuid, long amount) throws SQLException {
        if (amount <= 0) return amount == 0;
        try (PreparedStatement ps = tx.prepareStatement("UPDATE accounts SET balance = balance - ?1 WHERE uuid = ?2 AND balance >= ?1")) {
            ps.setLong(1, amount);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() == 1;
        }
    }

    public static void credit(Connection tx, UUID uuid, long amount) throws SQLException {
        if (amount <= 0) return;
        try (PreparedStatement ps = tx.prepareStatement("UPDATE accounts SET balance = balance + ? WHERE uuid = ?")) {
            ps.setLong(1, amount);
            ps.setString(2, uuid.toString());
            if (ps.executeUpdate() != 1) throw new SQLException("No account for " + uuid);
        }
    }

    public static void log(Connection tx, String type, UUID from, UUID to, long amount, long fee, String memo) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "INSERT INTO transactions(type, from_uuid, to_uuid, amount, ts, memo, fee) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, type);
            ps.setString(2, from == null ? null : from.toString());
            ps.setString(3, to == null ? null : to.toString());
            ps.setLong(4, amount);
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, memo == null ? "" : memo);
            ps.setLong(7, fee);
            ps.executeUpdate();
        }
    }

    public enum MoveStatus { OK, INSUFFICIENT }

    /**
     * Moves {@code amount} from one account to another inside the caller's transaction.
     * The payer also pays {@code fee}, which leaves the economy.
     */
    static MoveStatus move(Connection tx, String type, UUID from, UUID to, long amount, long fee, String memo) throws SQLException {
        if (!debit(tx, from, Math.addExact(amount, fee))) return MoveStatus.INSUFFICIENT;
        credit(tx, to, amount);
        log(tx, type, from, to, amount, fee, memo);
        return MoveStatus.OK;
    }

    static int unreadDirect(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM messages WHERE recipient = ? AND group_id = 0 AND is_read = 0")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    static int unreadGroups(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM messages m JOIN group_members gm ON gm.group_id = m.group_id
                WHERE gm.uuid = ?1 AND m.group_id > 0 AND m.id > gm.last_read AND m.sender <> ?1""")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Total unread messages: direct ones plus group messages after the player's read marker. */
    public CompletableFuture<Integer> unreadCount(UUID uuid) {
        return submit(c -> unreadDirect(c, uuid) + unreadGroups(c, uuid));
    }
}
