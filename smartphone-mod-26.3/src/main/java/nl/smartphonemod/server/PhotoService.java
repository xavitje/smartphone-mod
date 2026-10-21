package nl.smartphonemod.server;

import nl.smartphonemod.SmartphoneMod;
import nl.smartphonemod.network.C2S;
import nl.smartphonemod.network.S2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Photos from the Camera app: small JPEGs stored on the server and shared through messages. */
public final class PhotoService {
    public static final int MAX_WIDTH = 320, MAX_HEIGHT = 320;

    private PhotoService() {}

    /** Accepts only real, small JPEG images. Checks dimensions from the header before decoding anything. */
    static boolean isValidJpeg(byte[] data) {
        if (data == null || data.length < 4 || data.length > C2S.MAX_PHOTO_BYTES) return false;
        if ((data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) return false; // JPEG magic
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                return w > 0 && h > 0 && w <= MAX_WIDTH && h <= MAX_HEIGHT && reader.read(0) != null;
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static void upload(ServerPlayer player, byte[] jpeg) {
        if (!Cooldowns.tryUse(player.getUUID(), "photo", 2500)) return;
        if (!isValidJpeg(jpeg)) {
            SmartphoneMod.LOGGER.warn("Rejected an invalid photo from {}", player.getName().getString());
            return;
        }
        UUID me = player.getUUID();
        int max = PhoneServer.config().maxPhotosPerPlayer;
        PhoneServer.then(PhoneServer.db().transaction(tx -> {
            long id;
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO photos(owner, data, ts) VALUES (?,?,?)")) {
                ps.setString(1, me.toString());
                ps.setBytes(2, jpeg);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement("SELECT last_insert_rowid()"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
            // Keep only the newest photos.
            try (PreparedStatement ps = tx.prepareStatement("""
                    DELETE FROM photos WHERE owner = ?1 AND id NOT IN
                        (SELECT id FROM photos WHERE owner = ?1 ORDER BY id DESC LIMIT ?2)""")) {
                ps.setString(1, me.toString());
                ps.setInt(2, max);
                ps.executeUpdate();
            }
            return id;
        }), player, (p, id) -> gallery(p, 0, id));
    }

    public static void gallery(ServerPlayer player, long deleteId) {
        gallery(player, deleteId, 0);
    }

    private static void gallery(ServerPlayer player, long deleteId, long justTaken) {
        UUID me = player.getUUID();
        PhoneServer.then(PhoneServer.db().submit(c -> {
            if (deleteId > 0) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM photos WHERE id = ? AND owner = ?")) {
                    ps.setLong(1, deleteId);
                    ps.setString(2, me.toString());
                    ps.executeUpdate();
                }
            }
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM photos WHERE owner = ? ORDER BY id DESC")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(rs.getLong(1));
                }
            }
            return ids;
        }), player, (p, ids) -> ServerPlayNetworking.send(p, new S2C.GalleryData(ids, justTaken)));
    }

    /** Sends a photo, but only to its owner or to someone who received it in a conversation. */
    public static void request(ServerPlayer player, long id) {
        if (!Cooldowns.tryUse(player.getUUID(), "photo_req_" + id, 1000)) return;
        UUID me = player.getUUID();
        PhoneServer.then(PhoneServer.db().submit(c -> {
            if (!canSee(c, me, id)) return null;
            try (PreparedStatement ps = c.prepareStatement("SELECT data FROM photos WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBytes(1) : null;
                }
            }
        }), player, (p, data) -> {
            if (data != null) ServerPlayNetworking.send(p, new S2C.PhotoData(id, data));
        });
    }

    static boolean ownsPhoto(Connection c, UUID owner, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM photos WHERE id = ? AND owner = ?")) {
            ps.setLong(1, id);
            ps.setString(2, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean canSee(Connection c, UUID me, long id) throws SQLException {
        if (ownsPhoto(c, me, id)) return true;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM messages m WHERE m.photo_id = ?1 AND (
                    (m.group_id = 0 AND (m.sender = ?2 OR m.recipient = ?2))
                    OR (m.group_id > 0 AND EXISTS (SELECT 1 FROM group_members g WHERE g.group_id = m.group_id AND g.uuid = ?2)))
                LIMIT 1""")) {
            ps.setLong(1, id);
            ps.setString(2, me.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
