package nl.smartphonemod.parkour;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /parkour (alias /pk). Everyone: list, top, leave, reset, race, accept, deny.
 * Admins (level 2): create, delete, setstart, addcheckpoint, removecheckpoint, setfinish, setreset, water,
 * hologram, info, resettimes.
 */
public final class ParkourCommands {
    private ParkourCommands() {}

    private static final SuggestionProvider<CommandSourceStack> COURSES =
            (ctx, b) -> SharedSuggestionProvider.suggest(ParkourService.courses().keySet(), b);
    private static final SuggestionProvider<CommandSourceStack> PLAYERS =
            (ctx, b) -> SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerNames(), b);

    private static void ok(CommandSourceStack src, Component msg) {
        src.sendSuccess(() -> msg.copy().withStyle(ChatFormatting.GREEN), false);
    }

    private static int fail(CommandSourceStack src, Component msg) {
        src.sendFailure(msg);
        return 0;
    }

    private static Course need(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "course");
        Course c = ParkourService.course(name);
        if (c == null) ctx.getSource().sendFailure(Component.translatable("smartphone.parkour.unknown", name));
        return c;
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        var root = d.register(build("parkour"));
        d.register(Commands.literal("pk").redirect(root));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        java.util.function.Predicate<CommandSourceStack> admin = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
        return Commands.literal(name)
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("top").then(Commands.argument("course", StringArgumentType.word()).suggests(COURSES)
                        .executes(ParkourCommands::top)))
                .then(Commands.literal("leave").executes(ctx -> {
                    ParkourService.leave(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("reset").executes(ctx -> {
                    ParkourService.reset(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("race").then(Commands.argument("course", StringArgumentType.word()).suggests(COURSES)
                        .executes(ctx -> {
                            ParkourService.openRace(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "course"));
                            return 1;
                        })
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(ctx -> {
                            ServerPlayer target = ctx.getSource().getServer().getPlayerList()
                                    .getPlayerByName(StringArgumentType.getString(ctx, "player"));
                            ParkourService.invite(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "course"), target);
                            return 1;
                        }))))
                .then(Commands.literal("accept").executes(ctx -> {
                    ParkourService.respond(ctx.getSource().getPlayerOrException(), true);
                    return 1;
                }))
                .then(Commands.literal("deny").executes(ctx -> {
                    ParkourService.respond(ctx.getSource().getPlayerOrException(), false);
                    return 1;
                }))
                // ---- admin
                .then(Commands.literal("create").requires(admin).then(Commands.argument("course", StringArgumentType.word())
                        .executes(ParkourCommands::create)))
                .then(Commands.literal("delete").requires(admin).then(Commands.argument("course", StringArgumentType.word()).suggests(COURSES)
                        .executes(ctx -> {
                            Course c = need(ctx);
                            if (c == null) return 0;
                            ParkourService.deleteCourse(c);
                            ok(ctx.getSource(), Component.translatable("smartphone.parkour.admin.deleted", c.display));
                            return 1;
                        })))
                .then(Commands.literal("setstart").requires(admin).then(courseArg(ctx -> marker(ctx, "start"))))
                .then(Commands.literal("addcheckpoint").requires(admin).then(courseArg(ctx -> marker(ctx, "checkpoint"))))
                .then(Commands.literal("setfinish").requires(admin).then(courseArg(ctx -> marker(ctx, "finish"))))
                .then(Commands.literal("removecheckpoint").requires(admin).then(Commands.argument("course", StringArgumentType.word())
                        .suggests(COURSES)
                        .executes(ctx -> removeCheckpoint(ctx, -1))
                        .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                .executes(ctx -> removeCheckpoint(ctx, IntegerArgumentType.getInteger(ctx, "number") - 1)))))
                .then(Commands.literal("setreset").requires(admin).then(Commands.argument("course", StringArgumentType.word()).suggests(COURSES)
                        .then(Commands.literal("auto").executes(ctx -> setReset(ctx, null)))
                        .then(Commands.literal("here").executes(ctx -> setReset(ctx, (Integer)
                                (int) Math.floor(ctx.getSource().getPosition().y))))
                        .then(Commands.argument("y", IntegerArgumentType.integer(-2048, 4096))
                                .executes(ctx -> setReset(ctx, Integer.valueOf(IntegerArgumentType.getInteger(ctx, "y")))))))
                .then(Commands.literal("water").requires(admin).then(Commands.argument("course", StringArgumentType.word()).suggests(COURSES)
                        .then(Commands.literal("on").executes(ctx -> water(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> water(ctx, false)))))
                .then(Commands.literal("hologram").requires(admin).then(Commands.argument("course", StringArgumentType.word()).suggests(COURSES)
                        .executes(ctx -> {
                            Course c = need(ctx);
                            if (c == null) return 0;
                            ParkourService.placeHologram(ctx.getSource().getPlayerOrException(), c);
                            ok(ctx.getSource(), Component.translatable("smartphone.parkour.admin.hologram", c.display));
                            return 1;
                        })
                        .then(Commands.literal("remove").executes(ctx -> {
                            Course c = need(ctx);
                            if (c == null) return 0;
                            ParkourService.removeHologram(c);
                            ParkourService.saveCourse(c);
                            ok(ctx.getSource(), Component.translatable("smartphone.parkour.admin.hologram_removed"));
                            return 1;
                        }))))
                .then(Commands.literal("reward").requires(admin).then(Commands.argument("course", StringArgumentType.word()).suggests(COURSES)
                        .executes(ParkourCommands::rewardShow)
                        .then(Commands.literal("additem").executes(ParkourCommands::rewardAddItem))
                        .then(Commands.literal("money").then(Commands.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(0, 1_000_000_000L))
                                .executes(ctx -> rewardMoney(ctx, com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "amount")))))
                        .then(Commands.literal("clear").executes(ParkourCommands::rewardClear))
                        .then(Commands.literal("resetclaims").executes(ctx -> rewardResetClaims(ctx, null))
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                        .executes(ctx -> rewardResetClaims(ctx, StringArgumentType.getString(ctx, "player")))))))
                .then(Commands.literal("protect").requires(admin).then(Commands.argument("course", StringArgumentType.word()).suggests(COURSES)
                        .then(Commands.literal("auto").executes(ctx -> protectAuto(ctx, 4))
                                .then(Commands.argument("margin", IntegerArgumentType.integer(0, 64))
                                        .executes(ctx -> protectAuto(ctx, IntegerArgumentType.getInteger(ctx, "margin")))))
                        .then(Commands.literal("pos1").executes(ctx -> protectCorner(ctx, true)))
                        .then(Commands.literal("pos2").executes(ctx -> protectCorner(ctx, false)))
                        .then(Commands.literal("off").executes(ParkourCommands::protectOff))))
                .then(Commands.literal("info").requires(admin).then(courseArg(ParkourCommands::info)))
                .then(Commands.literal("resettimes").requires(admin).then(Commands.argument("course", StringArgumentType.word()).suggests(COURSES)
                        .executes(ctx -> resetTimes(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(ctx -> resetTimes(ctx, StringArgumentType.getString(ctx, "player"))))));
    }

    private interface Exec {
        int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> courseArg(Exec exec) {
        return Commands.argument("course", StringArgumentType.word()).suggests(COURSES).executes(exec::run);
    }

    private static int help(CommandSourceStack src) {
        src.sendSuccess(() -> Component.translatable("smartphone.parkour.help").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int list(CommandSourceStack src) {
        if (ParkourService.courses().isEmpty()) return fail(src, Component.translatable("smartphone.parkour.none"));
        for (Course c : ParkourService.courses().values()) {
            var board = ParkourService.leaderboard(c);
            String rec = board.isEmpty() ? "-" : board.get(0).getValue().name + " " + ParkourService.time(board.get(0).getValue().ms);
            Component line = Component.literal("• " + c.display).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("  (" + c.name + ")  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("smartphone.parkour.list.line", c.checkpoints.size(), rec).withStyle(ChatFormatting.GRAY))
                    .append(c.playable() ? Component.empty() : Component.translatable("smartphone.parkour.list.unfinished").withStyle(ChatFormatting.RED))
                    .append(!c.hasReward() ? Component.empty() : Component.literal("  🎁 ").withStyle(ChatFormatting.LIGHT_PURPLE)
                            .append(ParkourService.rewardText(c).copy().withStyle(ChatFormatting.LIGHT_PURPLE)));
            src.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int top(CommandContext<CommandSourceStack> ctx) {
        Course c = need(ctx);
        if (c == null) return 0;
        ctx.getSource().sendSuccess(() -> ParkourService.boardText(c), false);
        return 1;
    }

    private static int create(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "course");
        String id = raw.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_\\-]{1,24}")) return fail(ctx.getSource(), Component.translatable("smartphone.parkour.admin.bad_name"));
        if (ParkourService.course(id) != null) return fail(ctx.getSource(), Component.translatable("smartphone.parkour.admin.exists", id));
        Course c = new Course();
        c.name = id;
        c.display = raw;
        c.dimension = ctx.getSource().getLevel().dimension().identifier().toString();
        ParkourService.courses().put(id, c);
        ParkourService.saveCourse(c);
        ok(ctx.getSource(), Component.translatable("smartphone.parkour.admin.created", raw));
        return 1;
    }

    /** The block a marker command means: a plate/carpet you stand in, otherwise the block under your feet. */
    private static BlockPos markerBlock(ServerPlayer p) {
        BlockPos feet = p.blockPosition();
        BlockState state = p.level().getBlockState(feet);
        if (!state.isAir() && state.getCollisionShape(p.level(), feet).isEmpty() && state.getFluidState().isEmpty()) return feet;
        return feet.below();
    }

    private static int marker(CommandContext<CommandSourceStack> ctx, String kind) throws CommandSyntaxException {
        Course c = need(ctx);
        if (c == null) return 0;
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        String dim = p.level().dimension().identifier().toString();
        if (!c.allMarkers().isEmpty() && !dim.equals(c.dimension)) {
            return fail(ctx.getSource(), Component.translatable("smartphone.parkour.admin.other_dimension"));
        }
        c.dimension = dim;
        BlockPos pos = markerBlock(p);
        for (Course other : ParkourService.courses().values()) {
            for (Course.Marker m : other.allMarkers()) {
                if (m.packed() == pos.asLong() && other.dimension.equals(dim)) {
                    return fail(ctx.getSource(), Component.translatable("smartphone.parkour.admin.taken", other.display));
                }
            }
        }
        float yaw = Math.round(p.getYRot() / 45f) * 45f;
        Course.Marker m = new Course.Marker(pos, yaw);
        switch (kind) {
            case "start" -> c.start = m;
            case "finish" -> c.finish = m;
            default -> c.checkpoints.add(m);
        }
        ParkourService.saveCourse(c);
        String block = p.level().getBlockState(pos).getBlock().getName().getString();
        Component what = kind.equals("checkpoint")
                ? Component.translatable("smartphone.parkour.admin.checkpoint_set", c.checkpoints.size(), block, pos.getX(), pos.getY(), pos.getZ())
                : Component.translatable("smartphone.parkour.admin." + kind + "_set", block, pos.getX(), pos.getY(), pos.getZ());
        ok(ctx.getSource(), what);
        if (c.playable() && kind.equals("finish")) ok(ctx.getSource(), Component.translatable("smartphone.parkour.admin.ready", c.display));
        return 1;
    }

    private static int removeCheckpoint(CommandContext<CommandSourceStack> ctx, int index) {
        Course c = need(ctx);
        if (c == null) return 0;
        if (c.checkpoints.isEmpty()) return fail(ctx.getSource(), Component.translatable("smartphone.parkour.admin.no_checkpoints"));
        int i = index < 0 ? c.checkpoints.size() - 1 : index;
        if (i >= c.checkpoints.size()) return fail(ctx.getSource(), Component.translatable("smartphone.parkour.admin.no_checkpoints"));
        c.checkpoints.remove(i);
        ParkourService.saveCourse(c);
        ParkourService.resetTimes(c, null); // old splits no longer fit
        ok(ctx.getSource(), Component.translatable("smartphone.parkour.admin.checkpoint_removed", i + 1, c.checkpoints.size()));
        return 1;
    }

    private static int setReset(CommandContext<CommandSourceStack> ctx, Integer y) {
        Course c = need(ctx);
        if (c == null) return 0;
        c.resetY = y;
        ParkourService.saveCourse(c);
        ok(ctx.getSource(), Component.translatable("smartphone.parkour.admin.reset_set", c.effectiveResetY()));
        return 1;
    }

    private static int water(CommandContext<CommandSourceStack> ctx, boolean on) {
        Course c = need(ctx);
        if (c == null) return 0;
        c.waterReset = on;
        ParkourService.saveCourse(c);
        ok(ctx.getSource(), Component.translatable(on ? "smartphone.parkour.admin.water_on" : "smartphone.parkour.admin.water_off"));
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        Course c = need(ctx);
        if (c == null) return 0;
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("— " + c.display + " (" + c.name + ") —").withStyle(ChatFormatting.GOLD), false);
        src.sendSuccess(() -> Component.literal("Start: " + fmt(c.start) + "   Finish: " + fmt(c.finish)).withStyle(ChatFormatting.GRAY), false);
        List<Course.Marker> cps = c.checkpoints;
        for (int i = 0; i < cps.size(); i++) {
            String line = "  CP " + (i + 1) + ": " + fmt(cps.get(i));
            src.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        src.sendSuccess(() -> Component.literal("Reset Y: " + c.effectiveResetY() + (c.resetY == null ? " (auto)" : "")
                + "   Water reset: " + c.waterReset + "   Dim: " + c.dimension).withStyle(ChatFormatting.GRAY), false);
        src.sendSuccess(() -> Component.translatableWithFallback("smartphone.parkour.reward.show", "Beloning %s: %s (alleen de eerste keer)", c.display, ParkourService.rewardText(c))
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        int[] b = c.protect;
        src.sendSuccess(() -> Component.literal("Protect: " + (b == null ? "markers only"
                : b[0] + " " + b[1] + " " + b[2] + " → " + b[3] + " " + b[4] + " " + b[5])).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    // ---------------------------------------------------------------- rewards

    private static int rewardShow(CommandContext<CommandSourceStack> ctx) {
        Course c = need(ctx);
        if (c == null) return 0;
        ctx.getSource().sendSuccess(() -> Component.translatableWithFallback("smartphone.parkour.reward.show", "Beloning %s: %s (alleen de eerste keer)", c.display, ParkourService.rewardText(c))
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        return 1;
    }

    private static int rewardAddItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Course c = need(ctx);
        if (c == null) return 0;
        var stack = ctx.getSource().getPlayerOrException().getMainHandItem();
        if (stack.isEmpty()) return fail(ctx.getSource(), Component.translatableWithFallback("smartphone.parkour.reward.hold_item", "Houd het item (of de stapel) dat je wilt geven in je hand."));
        c.rewardItems.add(ParkourService.encodeItem(stack.copy()));
        ParkourService.saveCourse(c);
        ok(ctx.getSource(), Component.translatableWithFallback("smartphone.parkour.reward.set", "Beloning %s is nu: %s", c.display, ParkourService.rewardText(c)));
        return 1;
    }

    private static int rewardMoney(CommandContext<CommandSourceStack> ctx, long amount) {
        Course c = need(ctx);
        if (c == null) return 0;
        c.rewardMoney = amount;
        ParkourService.saveCourse(c);
        ok(ctx.getSource(), Component.translatableWithFallback("smartphone.parkour.reward.set", "Beloning %s is nu: %s", c.display, ParkourService.rewardText(c)));
        return 1;
    }

    private static int rewardClear(CommandContext<CommandSourceStack> ctx) {
        Course c = need(ctx);
        if (c == null) return 0;
        c.rewardItems.clear();
        c.rewardMoney = 0;
        ParkourService.saveCourse(c);
        ok(ctx.getSource(), Component.translatableWithFallback("smartphone.parkour.reward.cleared", "Beloning van %s weggehaald.", c.display));
        return 1;
    }

    private static int rewardResetClaims(CommandContext<CommandSourceStack> ctx, String player) {
        Course c = need(ctx);
        if (c == null) return 0;
        UUID only = null;
        if (player != null) {
            var online = ctx.getSource().getServer().getPlayerList().getPlayerByName(player);
            if (online != null) only = online.getUUID();
            else for (Map.Entry<UUID, ParkourService.Best> e : ParkourService.leaderboard(c)) if (e.getValue().name.equalsIgnoreCase(player)) only = e.getKey();
            if (only == null) return fail(ctx.getSource(), Component.translatable("smartphone.admin.unknown", player));
        }
        ParkourService.resetClaims(c, only);
        ok(ctx.getSource(), Component.translatableWithFallback("smartphone.parkour.reward.claims_reset", "Beloning van %s kan weer opnieuw verdiend worden.", c.display));
        return 1;
    }

    // ---------------------------------------------------------------- protection

    private static int protectAuto(CommandContext<CommandSourceStack> ctx, int margin) {
        Course c = need(ctx);
        if (c == null) return 0;
        if (c.allMarkers().isEmpty()) return fail(ctx.getSource(), Component.translatableWithFallback("smartphone.parkour.protect.no_markers", "Zet eerst start, checkpoints en finish."));
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Course.Marker m : c.allMarkers()) {
            b[0] = Math.min(b[0], m.x); b[1] = Math.min(b[1], m.y); b[2] = Math.min(b[2], m.z);
            b[3] = Math.max(b[3], m.x); b[4] = Math.max(b[4], m.y); b[5] = Math.max(b[5], m.z);
        }
        c.protect = new int[]{b[0] - margin, b[1] - margin, b[2] - margin, b[3] + margin, b[4] + margin + 4, b[5] + margin};
        ParkourService.saveCourse(c);
        return protectDone(ctx, c);
    }

    private static int protectCorner(CommandContext<CommandSourceStack> ctx, boolean first) throws CommandSyntaxException {
        Course c = need(ctx);
        if (c == null) return 0;
        BlockPos p = ctx.getSource().getPlayerOrException().blockPosition();
        if (first || c.protect == null) {
            c.protect = new int[]{p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ()};
        } else {
            int[] b = c.protect;
            c.protect = new int[]{Math.min(b[0], p.getX()), Math.min(b[1], p.getY()), Math.min(b[2], p.getZ()),
                    Math.max(b[3], p.getX()), Math.max(b[4], p.getY()), Math.max(b[5], p.getZ())};
        }
        ParkourService.saveCourse(c);
        if (first) {
            ok(ctx.getSource(), Component.translatableWithFallback("smartphone.parkour.protect.pos1", "Hoek 1: %s %s %s. Ga naar de andere hoek en typ /parkour protect <parcours> pos2.", p.getX(), p.getY(), p.getZ()));
            return 1;
        }
        return protectDone(ctx, c);
    }

    private static int protectDone(CommandContext<CommandSourceStack> ctx, Course c) {
        int[] b = c.protect;
        ok(ctx.getSource(), Component.translatableWithFallback("smartphone.parkour.protect.set", "%s is beschermd van %s tot %s. Alleen admins in creative kunnen er nog bouwen.", c.display,
                b[0] + " " + b[1] + " " + b[2], b[3] + " " + b[4] + " " + b[5]));
        return 1;
    }

    private static int protectOff(CommandContext<CommandSourceStack> ctx) {
        Course c = need(ctx);
        if (c == null) return 0;
        c.protect = null;
        ParkourService.saveCourse(c);
        ok(ctx.getSource(), Component.translatableWithFallback("smartphone.parkour.protect.off", "Bescherming van %s staat uit (de start/checkpoint/finish-blokken blijven beschermd).", c.display));
        return 1;
    }

    private static String fmt(Course.Marker m) {
        return m == null ? "-" : m.x + " " + m.y + " " + m.z;
    }

    private static int resetTimes(CommandContext<CommandSourceStack> ctx, String player) {
        Course c = need(ctx);
        if (c == null) return 0;
        UUID only = null;
        if (player != null) {
            for (Map.Entry<UUID, ParkourService.Best> e : ParkourService.leaderboard(c)) {
                if (e.getValue().name.equalsIgnoreCase(player)) only = e.getKey();
            }
            if (only == null) return fail(ctx.getSource(), Component.translatable("smartphone.admin.unknown", player));
        }
        ParkourService.resetTimes(c, only);
        ok(ctx.getSource(), Component.translatable("smartphone.parkour.admin.times_reset", c.display));
        return 1;
    }
}
