package nl.smartphonemod.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * /phone admin commands (permission level 2):
 * balance, give, take, set, log, export, suspicious.
 */
public final class AdminCommands {
    private static final SimpleDateFormat TIME = new SimpleDateFormat("dd-MM-yyyy HH:mm");

    private AdminCommands() {}

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> PLAYERS =
            (ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerNames(), builder);

    private static int backup(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!ready(source)) return 0;
        BackupService.backup().whenComplete((file, error) -> {
            if (error != null) fail(source, Component.literal("Backup failed: " + error.getMessage()));
            else reply(source, Component.translatable("smartphone.admin.backup_done", file.toString()));
        });
        return 1;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("phone")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("balance")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(ctx -> balance(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("give").then(amountArgs("give")))
                .then(Commands.literal("take").then(amountArgs("take")))
                .then(Commands.literal("set").then(amountArgs("set")))
                .then(Commands.literal("log")
                        .executes(ctx -> log(ctx, null, 15))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(ctx -> log(ctx, StringArgumentType.getString(ctx, "player"), 15))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> log(ctx, StringArgumentType.getString(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "limit"))))))
                .then(Commands.literal("export").executes(AdminCommands::export))
                .then(Commands.literal("suspicious").executes(AdminCommands::suspicious))
                .then(Commands.literal("backup").executes(AdminCommands::backup)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> amountArgs(String action) {
        return Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                .then(Commands.argument("amount", LongArgumentType.longArg(0, 1_000_000_000L))
                        .executes(ctx -> adjust(ctx, action, StringArgumentType.getString(ctx, "player"),
                                LongArgumentType.getLong(ctx, "amount"))));
    }

    private static boolean ready(CommandSourceStack source) {
        if (PhoneServer.isRunning()) return true;
        source.sendFailure(Component.translatable("smartphone.error.offline"));
        return false;
    }

    private static void reply(CommandSourceStack source, Component message) {
        source.getServer().execute(() -> source.sendSuccess(() -> message, false));
    }

    private static void fail(CommandSourceStack source, Component message) {
        source.getServer().execute(() -> source.sendFailure(message));
    }

    // ---------------------------------------------------------------- balance & adjustments

    private static int balance(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        if (!ready(source)) return 0;
        PhoneServer.db().submit(c -> {
            UUID uuid = PhoneDatabase.uuidByName(c, name);
            return uuid == null ? null : PhoneDatabase.balanceSync(c, uuid);
        }).whenComplete((balance, error) -> {
            if (error != null || balance == null) fail(source, Component.translatable("smartphone.admin.unknown", name));
            else reply(source, Component.translatable("smartphone.admin.balance", name, balance));
        });
        return 1;
    }

    private static int adjust(CommandContext<CommandSourceStack> ctx, String action, String name, long amount) {
        CommandSourceStack source = ctx.getSource();
        if (!ready(source)) return 0;
        String admin = source.getTextName();
        PhoneServer.db().transaction(tx -> {
            UUID uuid = PhoneDatabase.uuidByName(tx, name);
            if (uuid == null) return null;
            long before = PhoneDatabase.balanceSync(tx, uuid);
            long target = switch (action) {
                case "give" -> Math.addExact(before, amount);
                case "take" -> Math.max(0, before - amount);
                default -> amount;
            };
            long diff = target - before;
            if (diff > 0) {
                PhoneDatabase.credit(tx, uuid, diff);
                PhoneDatabase.log(tx, "ADMIN", null, uuid, diff, 0, "by " + admin);
            } else if (diff < 0) {
                if (!PhoneDatabase.debit(tx, uuid, -diff)) throw new SQLException("Balance changed");
                PhoneDatabase.log(tx, "ADMIN", uuid, null, -diff, 0, "by " + admin);
            }
            return new long[]{before, target};
        }).whenComplete((result, error) -> {
            if (error != null || result == null) {
                fail(source, Component.translatable("smartphone.admin.unknown", name));
                return;
            }
            reply(source, Component.translatable("smartphone.admin.adjusted", name, result[0], result[1]));
        });
        return 1;
    }

    // ---------------------------------------------------------------- log & export

    private record LogRow(long id, String type, String from, String to, long amount, long fee, String memo, long ts) {
        String line() {
            return "#" + id + " " + TIME.format(new Date(ts)) + " " + type + " " + (from == null ? "-" : from)
                    + " -> " + (to == null ? "-" : to) + " " + amount + (fee > 0 ? " (+" + fee + " fee)" : "")
                    + (memo.isEmpty() ? "" : " \"" + memo + "\"");
        }
    }

    private static List<LogRow> rows(Connection c, UUID filter, int limit, long sinceTs) throws SQLException {
        List<LogRow> out = new ArrayList<>();
        String sql = """
                SELECT t.id, t.type, fa.name, ta.name, t.amount, t.fee, t.memo, t.ts FROM transactions t
                LEFT JOIN accounts fa ON fa.uuid = t.from_uuid
                LEFT JOIN accounts ta ON ta.uuid = t.to_uuid
                WHERE t.ts >= ?1 AND (?2 IS NULL OR t.from_uuid = ?2 OR t.to_uuid = ?2)
                ORDER BY t.id DESC LIMIT ?3""";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, sinceTs);
            ps.setString(2, filter == null ? null : filter.toString());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LogRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getLong(8)));
                }
            }
        }
        return out;
    }

    private static int log(CommandContext<CommandSourceStack> ctx, String name, int limit) {
        CommandSourceStack source = ctx.getSource();
        if (!ready(source)) return 0;
        PhoneServer.db().submit(c -> {
            UUID filter = null;
            if (name != null) {
                filter = PhoneDatabase.uuidByName(c, name);
                if (filter == null) return null;
            }
            return rows(c, filter, limit, 0);
        }).whenComplete((rows, error) -> {
            if (error != null || rows == null) {
                fail(source, Component.translatable("smartphone.admin.unknown", String.valueOf(name)));
                return;
            }
            reply(source, Component.translatable("smartphone.admin.log_header", rows.size()).withStyle(ChatFormatting.GOLD));
            for (LogRow r : rows) reply(source, Component.literal(r.line()).withStyle(ChatFormatting.GRAY));
        });
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!ready(source)) return 0;
        Path dir = source.getServer().getWorldPath(LevelResource.ROOT).resolve("smartphone");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path file = dir.resolve("transactions-" + stamp + ".csv");
        PhoneServer.db().submit(c -> {
            int count = 0;
            try (Writer w = Files.newBufferedWriter(file);
                 PreparedStatement ps = c.prepareStatement("""
                         SELECT t.id, t.ts, t.type, fa.name, t.from_uuid, ta.name, t.to_uuid, t.amount, t.fee, t.memo
                         FROM transactions t
                         LEFT JOIN accounts fa ON fa.uuid = t.from_uuid
                         LEFT JOIN accounts ta ON ta.uuid = t.to_uuid ORDER BY t.id""");
                 ResultSet rs = ps.executeQuery()) {
                w.write("id,time,type,from_name,from_uuid,to_name,to_uuid,amount,fee,memo\n");
                while (rs.next()) {
                    w.write(rs.getLong(1) + "," + csv(TIME.format(new Date(rs.getLong(2)))) + "," + csv(rs.getString(3)) + ","
                            + csv(rs.getString(4)) + "," + csv(rs.getString(5)) + "," + csv(rs.getString(6)) + ","
                            + csv(rs.getString(7)) + "," + rs.getLong(8) + "," + rs.getLong(9) + "," + csv(rs.getString(10)) + "\n");
                    count++;
                }
            } catch (IOException e) {
                throw new SQLException(e);
            }
            return count;
        }).whenComplete((count, error) -> {
            if (error != null) fail(source, Component.literal("Export failed: " + error.getMessage()));
            else reply(source, Component.translatable("smartphone.admin.exported", count, file.toString()));
        });
        return 1;
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    // ---------------------------------------------------------------- suspicious activity

    private record Suspicious(List<LogRow> large, List<String> busy) {}

    private static int suspicious(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!ready(source)) return 0;
        var cfg = PhoneServer.config();
        long dayAgo = System.currentTimeMillis() - 24L * 3600 * 1000;
        long hourAgo = System.currentTimeMillis() - 3600L * 1000;
        PhoneServer.db().submit(c -> {
            List<LogRow> large = new ArrayList<>();
            for (LogRow r : rows(c, null, 500, dayAgo)) {
                if (r.amount() >= cfg.suspiciousAmount && !r.type().equals("DEPOSIT") && !r.type().equals("WITHDRAW")) large.add(r);
            }
            List<String> busy = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT a.name, COUNT(*) FROM transactions t JOIN accounts a ON a.uuid = t.from_uuid
                    WHERE t.ts >= ? AND t.type IN ('TRANSFER','REQUEST','MARKET')
                    GROUP BY t.from_uuid HAVING COUNT(*) > ? ORDER BY 2 DESC""")) {
                ps.setLong(1, hourAgo);
                ps.setInt(2, cfg.suspiciousTransfersPerHour);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) busy.add(rs.getString(1) + ": " + rs.getInt(2));
                }
            }
            return new Suspicious(large, busy);
        }).whenComplete((s, error) -> {
            if (error != null) {
                fail(source, Component.translatable("smartphone.error.offline"));
                return;
            }
            reply(source, Component.translatable("smartphone.admin.suspicious_large", cfg.suspiciousAmount, s.large().size())
                    .withStyle(ChatFormatting.GOLD));
            for (LogRow r : s.large()) reply(source, Component.literal(r.line()).withStyle(ChatFormatting.GRAY));
            reply(source, Component.translatable("smartphone.admin.suspicious_busy", cfg.suspiciousTransfersPerHour, s.busy().size())
                    .withStyle(ChatFormatting.GOLD));
            for (String line : s.busy()) reply(source, Component.literal(line).withStyle(ChatFormatting.GRAY));
        });
        return 1;
    }
}
