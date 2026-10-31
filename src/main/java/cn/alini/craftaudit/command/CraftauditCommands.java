package cn.alini.craftaudit.command;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.config.Config;
import cn.alini.craftaudit.event.block.BlockInspectListener;
import cn.alini.craftaudit.rollback.RollbackService;
import cn.alini.craftaudit.rollback.UndoManager;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public final class CraftauditCommands {

    private static final int CHECK_PAGE_SIZE = 15;
    // 模拟“替换上一页”：翻页前清掉一些行再打印新的一页
    private static final boolean CHECK_REPLACE_MODE = true;
    private static final int CHECK_CLEAR_LINES = 40; // 清多少行即可，不需要太大

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // ca inspect 切换审计模式的权限
        boolean requirePerm = Config.COMMON.auditRequirePermission.get();
        boolean nearRequirePerm = Config.COMMON.nearRequirePermission.get();
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        // /craftaudit ...
        d.register(Commands.literal("craftaudit")
                .then(Commands.literal("status")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> report(ctx.getSource())))
                .then(Commands.literal("inspect")
                        .requires(src -> !requirePerm || src.hasPermission(2))
                        .executes(ctx -> toggleAudit(ctx.getSource())))
                // 新增：/craftaudit checkUUID <player>
                .then(Commands.literal("checkUUID")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYER_SUGGESTIONS)
                                .executes(ctx -> checkUUID(ctx.getSource(), StringArgumentType.getString(ctx, "player")))
                        )
                )
                // 新：/craftaudit check <player> <time> [page]
                .then(Commands.literal("check")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYER_SUGGESTIONS)
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> checkOps(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "time"),
                                                1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> checkOps(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))
                                        )
                                )
                        )
                )
                .then(Commands.literal("log")
                        .requires(src -> !requirePerm || src.hasPermission(2))
                        .executes(ctx -> inspectAudit(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> inspectAudit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
                .then(Commands.literal("blocklog")
                        .requires(src -> !requirePerm || src.hasPermission(2))
                        .executes(ctx -> inspectBlockAudit(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> inspectBlockAudit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
                .then(Commands.literal("near")
                        .requires(src -> !nearRequirePerm || src.hasPermission(2))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> near(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                StringArgumentType.getString(ctx, "time"),
                                                1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> near(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))
                                        )
                                )
                        )
                )
                .then(Commands.literal("rollback")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYER_SUGGESTIONS)
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> rollback(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "time"),
                                                10))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                                .executes(ctx -> rollback(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "radius")))
                                        )
                                )
                        )
                )
                // 新：restore <time> [radius] [type]
                .then(Commands.literal("restore")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("time", StringArgumentType.word())
                                .executes(ctx -> restore(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "time"),
                                        10, null))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> restore(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "time"),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                null))
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .executes(ctx -> restore(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        StringArgumentType.getString(ctx, "type")))
                                        )
                                )
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .executes(ctx -> restore(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "time"),
                                                10,
                                                StringArgumentType.getString(ctx, "type")))
                                )
                        )
                )
                // 新：purge <time> 清理早于指定时间的日志
                .then(Commands.literal("purge")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("time", StringArgumentType.word())
                                .executes(ctx -> purge(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "time")))
                        )
                )
                .then(Commands.literal("undo")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> undo(ctx.getSource()))
                )
                // /craftaudit help
                .then(Commands.literal("help")
                        .requires(src -> !requirePerm || src.hasPermission(2))
                        .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    src.sendSuccess(() -> Component.literal(
                            "§3[CraftAudit] 指令列表：\n" +
                                    "§b/craftaudit status §7- 显示插件状态\n" +
                                    "§b/craftaudit inspect §7- 切换审计模式\n" +
                                    "§b/craftaudit check <player> <time> [page] §7- 查询玩家在时间范围内的所有操作\n" +
                                    "§b/craftaudit log [page] §7- 查看交互日志\n" +
                                    "§b/craftaudit blocklog [page] §7- 查看方块日志\n" +
                                    "§b/craftaudit near <radius> <time> [page] §7- 查看附近方块日志\n" +
                                    "§b/craftaudit rollback <player> <time> [radius] §7- 回滚玩家破坏的方块\n" +
                                    "§b/craftaudit restore <time> [radius] [type] §7- 恢复破坏/击杀记录（支持 type 过滤）\n" +
                                    "§b/craftaudit purge <time> §7- 清理早于指定时间的日志（不可撤销）\n" +
                                    "§b/craftaudit undo §7- 撤销上次回滚/恢复\n" +
                                    "\n" +
                                    "§b/ca ... §7- /craftaudit 的简写"
                    ), false);
                    return 1;
                }))
        );

        // /ca ...
        d.register(Commands.literal("ca")
                .then(Commands.literal("s")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> report(ctx.getSource())))
                .then(Commands.literal("i")
                        .requires(src -> !requirePerm || src.hasPermission(2))
                        .executes(ctx -> toggleAudit(ctx.getSource())))
                // 新增：/craftaudit checkUUID <player>
                .then(Commands.literal("checkUUID")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYER_SUGGESTIONS)
                                .executes(ctx -> checkUUID(ctx.getSource(), StringArgumentType.getString(ctx, "player")))
                        )
                )
                // 新：/ca check <player> <time> [page]
                .then(Commands.literal("check")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYER_SUGGESTIONS)
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> checkOps(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "time"),
                                                1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> checkOps(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))
                                        )
                                )
                        )
                )
                .then(Commands.literal("log")
                        .requires(src -> !requirePerm || src.hasPermission(2))
                        .executes(ctx -> inspectAudit(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> inspectAudit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
                .then(Commands.literal("blocklog")
                        .requires(src -> !requirePerm || src.hasPermission(2))
                        .executes(ctx -> inspectBlockAudit(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> inspectBlockAudit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
                .then(Commands.literal("near")
                        .requires(src -> !nearRequirePerm || src.hasPermission(2))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> near(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                StringArgumentType.getString(ctx, "time"),
                                                1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> near(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))
                                        )
                                )
                        )
                )
                .then(Commands.literal("rollback")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYER_SUGGESTIONS)
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> rollback(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "time"),
                                                10))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                                .executes(ctx -> rollback(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "radius")))
                                        )
                                )
                        )
                )
                .then(Commands.literal("restore")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("time", StringArgumentType.word())
                                .executes(ctx -> restore(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "time"),
                                        10, null))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> restore(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "time"),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                null))
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .executes(ctx -> restore(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        StringArgumentType.getString(ctx, "type")))
                                        )
                                )
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .executes(ctx -> restore(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "time"),
                                                10,
                                                StringArgumentType.getString(ctx, "type")))
                                )
                        )
                )
                // 新：/ca purge <time>
                .then(Commands.literal("purge")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("time", StringArgumentType.word())
                                .executes(ctx -> purge(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "time")))
                        )
                )
                .then(Commands.literal("undo")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> undo(ctx.getSource()))
                )
                // /ca help
                .then(Commands.literal("help")
                        .requires(src -> !requirePerm || src.hasPermission(2))
                        .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    src.sendSuccess(() -> Component.literal(
                            "§3[CraftAudit] 指令列表：\n" +
                                    "§b/ca s §7- 显示插件状态\n" +
                                    "§b/ca i §7- 切换审计模式\n" +
                                    "§b/ca check <player> <time> [page] §7- 查询玩家在时间范围内的所有操作（带分页）\n" +
                                    "§b/ca log [page] §7- 查看交互日志\n" +
                                    "§b/ca blocklog [page] §7- 查看方块日志\n" +
                                    "§b/ca near <radius> <time> [page] §7- 查看附近方块日志\n" +
                                    "§b/ca rollback <player> <time> [radius] §7- 回滚玩家破坏的方块\n" +
                                    "§b/ca restore <time> [radius] [type] §7- 恢复破坏/击杀记录\n" +
                                    "§b/ca purge <time> §7- 清理旧日志\n" +
                                    "§b/ca undo §7- 撤销上次回滚/恢复\n" +
                                    "\n" +
                                    "§b/craftaudit ... §7- /ca 的全称"
                    ), false);
                    return 1;
                }))
        );
    }
    // ===== 辅助方法(在线玩家补全) =====
    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYER_SUGGESTIONS = (ctx, builder) -> {
        var list = ctx.getSource().getServer().getPlayerList().getPlayers();
        // 用 GameProfile 名称（与 /list 显示一致）
        var names = list.stream().map(p -> p.getGameProfile().getName()).toList();
        return SharedSuggestionProvider.suggest(names, builder);
    };

    // ===== /ca check 实现（带“替换上一页”模拟） =====
    private static int checkOps(CommandSourceStack src, String playerArg, String timeStr, int page) {
        if (!Database.isConnected()) {
            src.sendFailure(Component.literal("§c数据库未连接。"));
            return 0;
        }
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal("§c时间格式无效！示例：5d、12h、30m、45s"));
            return 0;
        }
        long sinceMs = System.currentTimeMillis() - durMs;

        // 在线玩家优先解析 UUID；也支持直接输入 UUID
        ServerPlayer online = src.getServer().getPlayerList().getPlayerByName(playerArg);
        String playerName = playerArg;
        String playerUuid = null;
        if (online != null) {
            playerName = online.getName().getString();
            playerUuid = online.getUUID().toString();
        } else if (looksLikeUuid(playerArg)) {
            playerUuid = playerArg;
        }

        int total = Database.get().countLogsByActorSince(playerName, playerUuid, sinceMs);
        int totalPages = Math.max((total + CHECK_PAGE_SIZE - 1) / CHECK_PAGE_SIZE, 1);
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        List<LogEntry> logs = Database.get().queryLogsByActorSince(playerName, playerUuid, sinceMs, page, CHECK_PAGE_SIZE);

        // 模拟“替换上一页”：先清几行
        ServerPlayer viewer = src.getPlayer();
        if (CHECK_REPLACE_MODE && viewer != null) {
            for (int i = 0; i < CHECK_CLEAR_LINES; i++) viewer.sendSystemMessage(Component.literal(""));
        }

        String title = String.format("§7-----§3玩家操作记录§7----- (%s | %s内 | 第 %d/%d 页)",
                (playerUuid != null ? (playerName + " §8(" + playerUuid + "§8)") : playerName),
                timeStr, page, totalPages);
        src.sendSuccess(() -> Component.literal(title), false);

        if (logs.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7无记录。"), false);
        } else {
            for (LogEntry log : logs) {
                src.sendSuccess(() -> BlockInspectListener.renderLogLine(log), false);
            }
        }

        // 分页行（数字可点击翻页）
        if (viewer != null) {
            MutableComponent pageLine = Component.literal(String.format("第 %d/%d 页 ", page, totalPages)).withStyle(net.minecraft.ChatFormatting.GRAY);
            String cmdPrefix = "/ca check " + playerArg + " " + timeStr + " ";

            if (page > 1) {
                int finalPage = page;
                pageLine.append(
                        Component.literal("◀ ").withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmdPrefix + (finalPage - 1)))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("上一页")))
                                .withColor(net.minecraft.ChatFormatting.AQUA)
                        )
                );
            }
            pageLine.append(Component.literal("("));
            for (int i = 1; i <= totalPages; i++) {
                if (i == page) {
                    pageLine.append(Component.literal(i + "").withStyle(net.minecraft.ChatFormatting.YELLOW));
                } else {
                    int finalI = i;
                    pageLine.append(
                            Component.literal(finalI + "").withStyle(style -> style
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmdPrefix + finalI))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("跳转到第 " + finalI + " 页")))
                                    .withColor(net.minecraft.ChatFormatting.AQUA)
                            )
                    );
                }
                if (i < totalPages) pageLine.append(Component.literal("|").withStyle(net.minecraft.ChatFormatting.GRAY));
            }
            pageLine.append(Component.literal(") "));
            if (page < totalPages) {
                int finalPage1 = page;
                pageLine.append(
                        Component.literal("▶").withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmdPrefix + (finalPage1 + 1)))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("下一页")))
                                .withColor(net.minecraft.ChatFormatting.AQUA)
                        )
                );
            }
            viewer.sendSystemMessage(pageLine);
        }

        src.sendSuccess(() -> Component.literal("§7-----"), false);
        return 1;
    }

    private static boolean looksLikeUuid(String s) {
        return s != null && s.matches("^[0-9a-fA-F\\-]{32,36}$");
    }
    // 新增：实现 /craftaudit checkUUID 和 /ca checkUUID
    private static int checkUUID(CommandSourceStack src, String playerName) {
        ServerPlayer online = src.getServer().getPlayerList().getPlayerByName(playerName);
        String uuid = null;
        if (online != null) {
            uuid = online.getUUID().toString();
        } else {
            uuid = Database.isConnected() ? Database.get().findLastUuidByPlayerName(playerName) : null;
        }
        if (uuid != null && !uuid.isBlank()) {
            String finalUuid = uuid;
            MutableComponent msg = Component.literal("§3[CraftAudit] 玩家 §e" + playerName + "§3 的 UUID: ")
                    .append(Component.literal(uuid)
                            .withStyle(style -> style
                                    .withColor(net.minecraft.ChatFormatting.AQUA)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, finalUuid))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("点击复制 UUID")))
                            )
                    );
            src.sendSuccess(() -> msg, false);
            return 1;
        }
        src.sendFailure(Component.literal("§c未找到该玩家的 UUID（不在线且无历史记录）。"));
        return 0;
    }
    private static int report(CommandSourceStack src) {
        boolean connected = Database.isConnected();
        boolean ok = connected && Database.get().ping();
        String mode = Config.isSqlite() ? "SQLITE" : "MYSQL";
        src.sendSuccess(() -> Component.literal("§3[CraftAudit] 模式：" + mode + " | 状态：" + ok), false);
        return ok ? 1 : 0;
    }

    private static int toggleAudit(CommandSourceStack src) {
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        UUID uuid = player.getUUID();
        if (AuditModeManager.isAuditing(uuid)) {
            AuditModeManager.exit(uuid);
            src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已退出审计模式。"), false);
        } else {
            AuditModeManager.enter(uuid);
            src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已进入审计模式。"), false);
        }
        return 1;
    }

    private static int inspectAudit(CommandSourceStack src, int page) {
        if (!requireDb(src)) return 0;
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        UUID uuid = player.getUUID();
        if (!AuditModeManager.isAuditing(uuid)) {
            AuditModeManager.enter(uuid);
            src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已进入审计模式。"), false);
        }
        BlockInspectListener.queryInteractLogPage(player, page);
        return 1;
    }

    private static int inspectBlockAudit(CommandSourceStack src, int page) {
        if (!requireDb(src)) return 0;
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        UUID uuid = player.getUUID();
        if (!AuditModeManager.isAuditing(uuid)) {
            AuditModeManager.enter(uuid);
            src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已进入审计模式。"), false);
        }
        BlockInspectListener.queryBlockLogPage(player, page);
        return 1;
    }

    // near
    private static int near(CommandSourceStack src, int radius, String timeStr, int page) {
        if (!requireDb(src)) return 0;
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal("§c时间格式无效！示例：5d、12h、30m、45s"));
            return 0;
        }
        long sinceMs = System.currentTimeMillis() - durMs;
        BlockInspectListener.showNearbyLogsPaged(player, radius, sinceMs, page, timeStr);
        return 1;
    }

    // rollback（带撤销快照）
    private static int rollback(CommandSourceStack src, String playerName, String timeStr, int radius) {
        if (!requireDb(src)) return 0;
        var executor = src.getPlayer();
        if (executor == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal("§c时间格式无效！示例：5d、12h、30m、45s"));
            return 0;
        }
        long sinceMs = System.currentTimeMillis() - durMs;

        ServerLevel level = executor.serverLevel();
        String dim = level.dimension().location().toString();
        BlockPos center = executor.blockPosition();

        int changed = RollbackService.rollback(level, dim, center, radius, sinceMs, playerName, executor.getUUID());
        src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 回滚完成：%d 个方块。输入 /ca undo 撤销本次回滚。", changed)), true);
        return changed > 0 ? 1 : 0;
    }

    // restore（带撤销快照）
    private static int restore(CommandSourceStack src, String timeStr, int radius, String typeOpt) {
        if (!requireDb(src)) return 0;
        var executor = src.getPlayer();
        if (executor == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal("§c时间格式无效！示例：5d、12h、30m、45s"));
            return 0;
        }
        long sinceMs = System.currentTimeMillis() - durMs;

        ServerLevel level = executor.serverLevel();
        String dim = level.dimension().location().toString();
        BlockPos center = executor.blockPosition();

        if (typeOpt == null) {
            int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("break","natural_break"), null, executor.getUUID());
            src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成（所有破坏）：%d 个方块。/ca undo 可撤销。", changed)), true);
            return changed > 0 ? 1 : 0;
        }

        String t = typeOpt.toLowerCase();
        switch (t) {
            case "break": {
                int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("break"), null, executor.getUUID());
                src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成（玩家破坏）：%d 个方块。/ca undo 可撤销。", changed)), true);
                return changed > 0 ? 1 : 0;
            }
            case "natural":
            case "natural_break": {
                int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("natural_break"), null, executor.getUUID());
                src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成（环境破坏）：%d 个方块。/ca undo 可撤销。", changed)), true);
                return changed > 0 ? 1 : 0;
            }
            case "explosion":
            case "fluid":
            case "gravity": {
                int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("natural_break"), t, executor.getUUID());
                src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成（环境破坏: %s）：%d 个方块。/ca undo 可撤销。", t, changed)), true);
                return changed > 0 ? 1 : 0;
            }
            default:
                if (t.equals("kill") || t.startsWith("kill:")) {
                    String entityFilter = null;
                    if (t.startsWith("kill:")) {
                        entityFilter = t.substring("kill:".length());
                        if (entityFilter.isBlank()) entityFilter = null;
                    }
                    int spawned = RollbackService.restoreKills(level, dim, center, radius, sinceMs, entityFilter, executor.getUUID());
                    String scope = (entityFilter == null) ? "所有击杀" : ("击杀: " + entityFilter);
                    src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成（%s）：生成 %d 个实体。/ca undo 可撤销。", scope, spawned)), true);
                    return spawned > 0 ? 1 : 0;
                } else {
                    src.sendFailure(Component.literal("§c未知的 type！可选：break | natural | explosion | fluid | gravity | kill | kill:<entity_id>"));
                    return 0;
                }
        }
    }

    // purge：清理早于指定时间的日志（不可撤销）
    private static int purge(CommandSourceStack src, String timeStr) {
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal("§c时间格式无效！示例：30d、12h、90m、300s"));
            return 0;
        }
        long beforeMs = System.currentTimeMillis() - durMs;

        if (!Database.isConnected()) {
            src.sendFailure(Component.literal("§c数据库未连接，无法清理。"));
            return 0;
        }
        int deleted = Database.get().deleteLogsBefore(beforeMs);
        if (deleted < 0) {
            src.sendFailure(Component.literal("§c清理日志失败，请查看控制台。"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已清理 " + deleted + " 条早于 " + timeStr + " 的日志记录。"), true);
        return 1;
    }

    // undo
    private static int undo(CommandSourceStack src) {
        var executor = src.getPlayer();
        if (executor == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        if (!UndoManager.hasUndo(executor.getUUID())) {
            src.sendFailure(Component.literal("§c没有可撤销的回滚/恢复记录。"));
            return 0;
        }
        int changed = UndoManager.applyUndo(executor);
        src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 撤销完成：%d 项更改已恢复。", changed)), true);
        return changed > 0 ? 1 : 0;
    }

    private static boolean requireDb(CommandSourceStack src) {
        if (Database.isConnected()) return true;
        src.sendFailure(Component.literal("§c数据库未连接。"));
        return false;
    }

    // parse duration
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)([smhdSMHD])$");
    private static long parseDurationMillis(String s) {
        if (s == null) return -1L;
        Matcher m = DURATION_PATTERN.matcher(s.trim());
        if (!m.matches()) return -1L;
        char unit = Character.toLowerCase(m.group(2).charAt(0));
        long factor = switch (unit) {
            case 's' -> 1000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            default -> -1L;
        };
        if (factor < 0) return -1L;
        // 数字过大时 parseLong / 乘法会溢出，按无效处理
        try {
            return Math.multiplyExact(Long.parseLong(m.group(1)), factor);
        } catch (NumberFormatException | ArithmeticException e) {
            return -1L;
        }
    }
}