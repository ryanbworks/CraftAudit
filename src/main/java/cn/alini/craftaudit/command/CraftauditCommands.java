package cn.alini.craftaudit.command;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.config.Config;
import cn.alini.craftaudit.event.block.BlockInspectListener;
import cn.alini.craftaudit.rollback.RollbackService;
import cn.alini.craftaudit.rollback.UndoManager;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import cn.alini.craftaudit.util.ChatUi;
import cn.alini.craftaudit.util.ItemData;
import cn.alini.craftaudit.util.Lang;
import cn.alini.craftaudit.util.NameLocalization;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                .then(itemNode())
                .then(CaseCommands.node())
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
                .then(Commands.literal("reloadlang")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> reloadLang(ctx.getSource())))
                // /craftaudit help
                .then(Commands.literal("help")
                        .requires(src -> !requirePerm || src.hasPermission(2))
                        .executes(ctx -> help(ctx.getSource(), false)))
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
                .then(itemNode())
                .then(CaseCommands.node())
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
                .then(Commands.literal("reloadlang")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> reloadLang(ctx.getSource())))
                // /ca help
                .then(Commands.literal("help")
                        .requires(src -> !requirePerm || src.hasPermission(2))
                        .executes(ctx -> help(ctx.getSource(), true)))
        );
    }
    // ===== 帮助 =====
    // 短别名 /ca 用 s / i，完整命令 /craftaudit 用 status / inspect
    private static int help(CommandSourceStack src, boolean shortAlias) {
        String lang = Lang.langOf(src);
        String root = shortAlias ? "/ca" : "/craftaudit";
        String[][] entries = {
                {shortAlias ? "s" : "status", "status"},
                {shortAlias ? "i" : "inspect", "inspect"},
                {"check <player> <time> [page]", "check"},
                {"item <item> <time> [player <player>] [page]", "item"},
                {"case", "case"},
                {"log [page]", "log"},
                {"blocklog [page]", "blocklog"},
                {"near <radius> <time> [page]", "near"},
                {"rollback <player> <time> [radius]", "rollback"},
                {"restore <time> [radius] [type]", "restore"},
                {"purge <time>", "purge"},
                {"undo", "undo"},
                {"reloadlang", "reloadlang"},
        };
        StringBuilder sb = new StringBuilder(Lang.tr(lang, "craftaudit.help.title"));
        for (String[] e : entries) {
            sb.append("\n§b").append(root).append(' ').append(e[0])
                    .append(" §7- ").append(Lang.tr(lang, "craftaudit.help." + e[1]));
        }
        sb.append("\n\n§b").append(shortAlias ? "/craftaudit ..." : "/ca ...")
                .append(" §7- ").append(Lang.tr(lang, shortAlias ? "craftaudit.help.alias_full" : "craftaudit.help.alias_short"));
        String text = sb.toString();
        src.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int reloadLang(CommandSourceStack src) {
        Lang.reload();
        src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.lang.reloaded", String.join(", ", Lang.available()))), true);
        return 1;
    }

    // ===== 辅助方法(在线玩家补全) =====
    static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYER_SUGGESTIONS = (ctx, builder) -> {
        var list = ctx.getSource().getServer().getPlayerList().getPlayers();
        // 用 GameProfile 名称（与 /list 显示一致）
        var names = list.stream().map(p -> p.getGameProfile().getName()).toList();
        return SharedSuggestionProvider.suggest(names, builder);
    };

    // ===== /ca item：物品追踪 =====
    private static final int ITEM_PAGE_SIZE = 15;
    private static final int ITEM_TRACE_LIMIT = 5000; // 单次最多取出的记录数，避免大表卡服
    private static final int ITEM_SUMMARY_TOP = 10;

    static final SuggestionProvider<CommandSourceStack> ITEM_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggestResource(ForgeRegistries.ITEMS.getKeys(), builder);

    // /ca item <item> <time> [page] | /ca item <item> <time> player <player> [page]
    private static LiteralArgumentBuilder<CommandSourceStack> itemNode() {
        return Commands.literal("item")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("item", ResourceLocationArgument.id())
                        .suggests(ITEM_SUGGESTIONS)
                        .then(Commands.argument("time", StringArgumentType.word())
                                .executes(ctx -> itemTrace(ctx.getSource(),
                                        ResourceLocationArgument.getId(ctx, "item"),
                                        StringArgumentType.getString(ctx, "time"),
                                        null, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> itemTrace(ctx.getSource(),
                                                ResourceLocationArgument.getId(ctx, "item"),
                                                StringArgumentType.getString(ctx, "time"),
                                                null,
                                                IntegerArgumentType.getInteger(ctx, "page"))))
                                .then(Commands.literal("player")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(ONLINE_PLAYER_SUGGESTIONS)
                                                .executes(ctx -> itemTrace(ctx.getSource(),
                                                        ResourceLocationArgument.getId(ctx, "item"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        StringArgumentType.getString(ctx, "player"), 1))
                                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> itemTrace(ctx.getSource(),
                                                                ResourceLocationArgument.getId(ctx, "item"),
                                                                StringArgumentType.getString(ctx, "time"),
                                                                StringArgumentType.getString(ctx, "player"),
                                                                IntegerArgumentType.getInteger(ctx, "page"))))))));
    }

    private static int itemTrace(CommandSourceStack src, ResourceLocation itemId, String timeStr, String playerArg, int page) {
        if (!requireDb(src)) return 0;
        long sinceMs = sinceOrFail(src, timeStr);
        if (sinceMs < 0) return 0;
        String lang = Lang.langOf(src);

        // 与 /ca check 相同：在线玩家同时按名字和 UUID 匹配，也支持直接输入 UUID
        String playerName = playerArg;
        String playerUuid = null;
        if (playerArg != null) {
            ServerPlayer online = src.getServer().getPlayerList().getPlayerByName(playerArg);
            if (online != null) {
                playerName = online.getName().getString();
                playerUuid = online.getUUID().toString();
            } else if (looksLikeUuid(playerArg)) {
                playerUuid = playerArg;
            }
        }

        String id = itemId.toString();
        List<LogEntry> logs = Database.get().queryItemLogsSince(id, sinceMs, playerName, playerUuid, ITEM_TRACE_LIMIT);
        boolean truncated = logs.size() >= ITEM_TRACE_LIMIT;
        int totalPages = Math.max((logs.size() + ITEM_PAGE_SIZE - 1) / ITEM_PAGE_SIZE, 1);
        int p = Math.min(page, totalPages);

        ServerPlayer viewer = src.getPlayer();
        clearChat(viewer);

        MutableComponent title = Component.literal(Lang.tr(lang, "craftaudit.item.title_head"))
                .append(NameLocalization.item(id))
                .append(Component.literal(Lang.tr(lang, "craftaudit.item.title_tail", id, timeStr,
                        playerArg != null ? Lang.tr(lang, "craftaudit.item.title_player", playerArg) : "",
                        logs.size(),
                        truncated ? Lang.tr(lang, "craftaudit.item.title_truncated", ITEM_TRACE_LIMIT) : "")));
        src.sendSuccess(() -> title, false);

        if (logs.isEmpty()) {
            src.sendSuccess(() -> Component.literal(Lang.tr(lang, "craftaudit.common.no_records")), false);
            src.sendSuccess(() -> Component.literal("§7-----"), false);
            return 1;
        }

        // 第一页显示按玩家汇总，按净变化绝对值排序（拿得最多/给出最多的人排在前面）
        if (p == 1) {
            for (Component line : itemSummaryLines(logs, lang)) {
                src.sendSuccess(() -> line, false);
            }
            src.sendSuccess(() -> Component.literal(Lang.tr(lang, "craftaudit.item.details")), false);
        }

        int from = (p - 1) * ITEM_PAGE_SIZE;
        int to = Math.min(from + ITEM_PAGE_SIZE, logs.size());
        for (LogEntry log : logs.subList(from, to)) {
            src.sendSuccess(() -> BlockInspectListener.renderLogLine(log, lang), false);
        }

        if (viewer != null) {
            String cmdPrefix = "/ca item " + id + " " + timeStr + (playerArg != null ? " player " + playerArg : "") + " ";
            viewer.sendSystemMessage(ChatUi.pageLine(lang, cmdPrefix, p, totalPages));
        }
        src.sendSuccess(() -> Component.literal("§7-----"), false);
        return 1;
    }

    // 每位玩家：取出 / 存入 / 拾取他人物品 / 丢出，净变化 = 取出 + 拾取 - 存入 - 丢出
    private static List<Component> itemSummaryLines(List<LogEntry> logs, String lang) {
        Map<String, long[]> stats = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (LogEntry log : logs) {
            String key = log.playerUuid() != null ? log.playerUuid() : log.player();
            if (key == null) continue;
            names.putIfAbsent(key, log.player());
            long[] st = stats.computeIfAbsent(key, k -> new long[4]);
            int count = ItemData.getInt(ItemData.parse(log.data()), "count");
            switch (log.action()) {
                case "take" -> st[0] += count;
                case "put" -> st[1] += count;
                case "transfer" -> st[2] += count;
                case "drop" -> st[3] += count;
                default -> { }
            }
        }

        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(stats.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> Math.abs(net(e.getValue()))).reversed());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(Lang.tr(lang, "craftaudit.item.summary_header")));
        for (Map.Entry<String, long[]> e : sorted.subList(0, Math.min(ITEM_SUMMARY_TOP, sorted.size()))) {
            long[] st = e.getValue();
            long net = net(st);
            String netText = net > 0 ? "§a+" + net : net < 0 ? "§c" + net : "§70";
            lines.add(Component.literal(Lang.tr(lang, "craftaudit.item.summary_line",
                    names.get(e.getKey()), st[0], st[1], st[2], st[3], netText)));
        }
        if (sorted.size() > ITEM_SUMMARY_TOP) {
            lines.add(Component.literal(Lang.tr(lang, "craftaudit.item.summary_more", sorted.size() - ITEM_SUMMARY_TOP)));
        }
        return lines;
    }

    private static long net(long[] st) {
        return st[0] + st[2] - st[1] - st[3];
    }

    // ===== /ca check 实现（带“替换上一页”模拟） =====
    private static int checkOps(CommandSourceStack src, String playerArg, String timeStr, int page) {
        if (!requireDb(src)) return 0;
        long sinceMs = sinceOrFail(src, timeStr);
        if (sinceMs < 0) return 0;
        String lang = Lang.langOf(src);

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
        int p = Math.max(1, Math.min(page, totalPages));

        List<LogEntry> logs = Database.get().queryLogsByActorSince(playerName, playerUuid, sinceMs, p, CHECK_PAGE_SIZE);

        // 模拟“替换上一页”：先清几行
        ServerPlayer viewer = src.getPlayer();
        clearChat(viewer);

        String who = playerUuid != null ? (playerName + " §8(" + playerUuid + "§8)") : playerName;
        String title = Lang.tr(lang, "craftaudit.check.title", who, timeStr, p, totalPages);
        src.sendSuccess(() -> Component.literal(title), false);

        if (logs.isEmpty()) {
            src.sendSuccess(() -> Component.literal(Lang.tr(lang, "craftaudit.common.no_records")), false);
        } else {
            for (LogEntry log : logs) {
                src.sendSuccess(() -> BlockInspectListener.renderLogLine(log, lang), false);
            }
        }

        // 分页行（数字可点击翻页）
        if (viewer != null) {
            viewer.sendSystemMessage(ChatUi.pageLine(lang, "/ca check " + playerArg + " " + timeStr + " ", p, totalPages));
        }

        src.sendSuccess(() -> Component.literal("§7-----"), false);
        return 1;
    }

    private static void clearChat(ServerPlayer viewer) {
        if (CHECK_REPLACE_MODE && viewer != null) {
            for (int i = 0; i < CHECK_CLEAR_LINES; i++) viewer.sendSystemMessage(Component.literal(""));
        }
    }

    static boolean looksLikeUuid(String s) {
        return s != null && s.matches("^[0-9a-fA-F\\-]{32,36}$");
    }

    // 新增：实现 /craftaudit checkUUID 和 /ca checkUUID
    private static int checkUUID(CommandSourceStack src, String playerName) {
        ServerPlayer online = src.getServer().getPlayerList().getPlayerByName(playerName);
        String uuid;
        if (online != null) {
            uuid = online.getUUID().toString();
        } else {
            uuid = Database.isConnected() ? Database.get().findLastUuidByPlayerName(playerName) : null;
        }
        if (uuid != null && !uuid.isBlank()) {
            String finalUuid = uuid;
            MutableComponent msg = Component.literal(Lang.tr(src, "craftaudit.uuid.result", playerName))
                    .append(Component.literal(uuid)
                            .withStyle(style -> style
                                    .withColor(net.minecraft.ChatFormatting.AQUA)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, finalUuid))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            Component.literal(Lang.tr(src, "craftaudit.uuid.copy_hover"))))
                            )
                    );
            src.sendSuccess(() -> msg, false);
            return 1;
        }
        src.sendFailure(Component.literal(Lang.tr(src, "craftaudit.uuid.not_found")));
        return 0;
    }

    private static int report(CommandSourceStack src) {
        boolean connected = Database.isConnected();
        boolean ok = connected && Database.get().ping();
        String mode = Config.isSqlite() ? "SQLITE" : "MYSQL";
        String state = Lang.tr(src, ok ? "craftaudit.status.ok" : "craftaudit.status.down");
        src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.status.report", mode, state)), false);
        return ok ? 1 : 0;
    }

    private static int toggleAudit(CommandSourceStack src) {
        var player = src.getPlayer();
        if (player == null) return playerOnly(src);
        UUID uuid = player.getUUID();
        if (AuditModeManager.isAuditing(uuid)) {
            AuditModeManager.exit(uuid);
            src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.audit.exit")), false);
        } else {
            AuditModeManager.enter(uuid);
            src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.audit.enter")), false);
        }
        return 1;
    }

    private static int inspectAudit(CommandSourceStack src, int page) {
        if (!requireDb(src)) return 0;
        var player = src.getPlayer();
        if (player == null) return playerOnly(src);
        enterAuditIfNeeded(src, player);
        BlockInspectListener.queryInteractLogPage(player, page);
        return 1;
    }

    private static int inspectBlockAudit(CommandSourceStack src, int page) {
        if (!requireDb(src)) return 0;
        var player = src.getPlayer();
        if (player == null) return playerOnly(src);
        enterAuditIfNeeded(src, player);
        BlockInspectListener.queryBlockLogPage(player, page);
        return 1;
    }

    private static void enterAuditIfNeeded(CommandSourceStack src, ServerPlayer player) {
        if (!AuditModeManager.isAuditing(player.getUUID())) {
            AuditModeManager.enter(player.getUUID());
            src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.audit.enter")), false);
        }
    }

    // near
    private static int near(CommandSourceStack src, int radius, String timeStr, int page) {
        if (!requireDb(src)) return 0;
        var player = src.getPlayer();
        if (player == null) return playerOnly(src);
        long sinceMs = sinceOrFail(src, timeStr);
        if (sinceMs < 0) return 0;
        BlockInspectListener.showNearbyLogsPaged(player, radius, sinceMs, page, timeStr);
        return 1;
    }

    // rollback（带撤销快照）
    private static int rollback(CommandSourceStack src, String playerName, String timeStr, int radius) {
        if (!requireDb(src)) return 0;
        var executor = src.getPlayer();
        if (executor == null) return playerOnly(src);
        long sinceMs = sinceOrFail(src, timeStr);
        if (sinceMs < 0) return 0;

        ServerLevel level = executor.serverLevel();
        String dim = level.dimension().location().toString();
        BlockPos center = executor.blockPosition();

        int changed = RollbackService.rollback(level, dim, center, radius, sinceMs, playerName, executor.getUUID());
        src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.rollback.done", changed)), true);
        return changed > 0 ? 1 : 0;
    }

    // restore（带撤销快照）
    private static int restore(CommandSourceStack src, String timeStr, int radius, String typeOpt) {
        if (!requireDb(src)) return 0;
        var executor = src.getPlayer();
        if (executor == null) return playerOnly(src);
        long sinceMs = sinceOrFail(src, timeStr);
        if (sinceMs < 0) return 0;

        ServerLevel level = executor.serverLevel();
        String dim = level.dimension().location().toString();
        BlockPos center = executor.blockPosition();

        if (typeOpt == null) {
            int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("break","natural_break"), null, executor.getUUID());
            return restored(src, Lang.tr(src, "craftaudit.restore.scope_all"), changed);
        }

        String t = typeOpt.toLowerCase();
        switch (t) {
            case "break": {
                int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("break"), null, executor.getUUID());
                return restored(src, Lang.tr(src, "craftaudit.restore.scope_player"), changed);
            }
            case "natural":
            case "natural_break": {
                int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("natural_break"), null, executor.getUUID());
                return restored(src, Lang.tr(src, "craftaudit.restore.scope_natural"), changed);
            }
            case "explosion":
            case "fluid":
            case "gravity": {
                int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("natural_break"), t, executor.getUUID());
                return restored(src, Lang.tr(src, "craftaudit.restore.scope_cause", t), changed);
            }
            default:
                if (t.equals("kill") || t.startsWith("kill:")) {
                    String entityFilter = null;
                    if (t.startsWith("kill:")) {
                        entityFilter = t.substring("kill:".length());
                        if (entityFilter.isBlank()) entityFilter = null;
                    }
                    int spawned = RollbackService.restoreKills(level, dim, center, radius, sinceMs, entityFilter, executor.getUUID());
                    String scope = (entityFilter == null)
                            ? Lang.tr(src, "craftaudit.restore.scope_all_kills")
                            : Lang.tr(src, "craftaudit.restore.scope_kill", entityFilter);
                    src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.restore.done_entities", scope, spawned)), true);
                    return spawned > 0 ? 1 : 0;
                } else {
                    src.sendFailure(Component.literal(Lang.tr(src, "craftaudit.restore.unknown_type")));
                    return 0;
                }
        }
    }

    private static int restored(CommandSourceStack src, String scope, int changed) {
        src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.restore.done_blocks", scope, changed)), true);
        return changed > 0 ? 1 : 0;
    }

    // purge：清理早于指定时间的日志（不可撤销）
    private static int purge(CommandSourceStack src, String timeStr) {
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal(Lang.tr(src, "craftaudit.error.bad_time")));
            return 0;
        }
        long beforeMs = System.currentTimeMillis() - durMs;

        if (!requireDb(src)) return 0;
        int deleted = Database.get().deleteLogsBefore(beforeMs);
        if (deleted < 0) {
            src.sendFailure(Component.literal(Lang.tr(src, "craftaudit.purge.failed")));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.purge.done", deleted, timeStr)), true);
        return 1;
    }

    // undo
    private static int undo(CommandSourceStack src) {
        var executor = src.getPlayer();
        if (executor == null) return playerOnly(src);
        if (!UndoManager.hasUndo(executor.getUUID())) {
            src.sendFailure(Component.literal(Lang.tr(src, "craftaudit.undo.none")));
            return 0;
        }
        int changed = UndoManager.applyUndo(executor);
        src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.undo.done", changed)), true);
        return changed > 0 ? 1 : 0;
    }

    static boolean requireDb(CommandSourceStack src) {
        if (Database.isConnected()) return true;
        src.sendFailure(Component.literal(Lang.tr(src, "craftaudit.error.no_db")));
        return false;
    }

    static int playerOnly(CommandSourceStack src) {
        src.sendFailure(Component.literal(Lang.tr(src, "craftaudit.error.player_only")));
        return 0;
    }

    // 解析时间参数并换算为起始时间；格式无效时发送提示并返回 -1
    static long sinceOrFail(CommandSourceStack src, String timeStr) {
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal(Lang.tr(src, "craftaudit.error.bad_time")));
            return -1L;
        }
        return Math.max(System.currentTimeMillis() - durMs, 0L);
    }

    // parse duration
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)([smhdSMHD])$");
    static long parseDurationMillis(String s) {
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
