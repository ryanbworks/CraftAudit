package cn.alini.craftaudit.command;

import cn.alini.craftaudit.event.block.BlockInspectListener;
import cn.alini.craftaudit.storage.CaseRepository;
import cn.alini.craftaudit.storage.CaseRepository.CaseEntry;
import cn.alini.craftaudit.storage.CaseRepository.CaseInfo;
import cn.alini.craftaudit.storage.CaseRepository.CasePlayer;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import cn.alini.craftaudit.util.ChatUi;
import cn.alini.craftaudit.util.Lang;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /ca case —— 调查案件
 *
 * /ca case create <标题>                       创建案件（如：#001 佩德罗基地失窃）
 * /ca case list [open|closed|all] [page]       案件列表
 * /ca case <id>                                案件概览
 * /ca case <id> timeline|evidence [page]       时间线 / 证据
 * /ca case <id> attach near|player|item ...    附加日志作为证据（复制保存）
 * /ca case <id> player <玩家> <角色>            标记涉及玩家
 * /ca case <id> removeplayer <玩家>
 * /ca case <id> loc [说明]                      记录当前位置
 * /ca case <id> note <内容>
 * /ca case <id> rename <标题>
 * /ca case <id> close <结论>                    结案（结案后只读）
 * /ca case <id> reopen
 *
 * 所有修改都会在时间线中自动生成事件（操作者 + 时间），便于追溯管理员行为。
 * 事件保存为「翻译 key + 参数」，参数以 @ 开头时表示需要再翻译的 key（如角色名）。
 */
public final class CaseCommands {

    private static final int LIST_PAGE_SIZE = 10;
    private static final int TIMELINE_PAGE_SIZE = 12;
    private static final int EVIDENCE_PAGE_SIZE = 12;
    private static final int ATTACH_LIMIT = 200; // 单次附加证据上限
    private static final int VIEW_LOCATIONS = 10;

    private static final List<String> TIMELINE_TYPES =
            List.of(CaseRepository.NOTE, CaseRepository.EVENT, CaseRepository.LOCATION);
    private static final List<String> EVIDENCE_TYPES = List.of(CaseRepository.LOG);

    private static final List<String> ROLES = List.of("suspect", "victim", "witness", "other");
    private static final Map<String, ChatFormatting> ROLE_COLORS = Map.of(
            "suspect", ChatFormatting.RED, "victim", ChatFormatting.GOLD,
            "witness", ChatFormatting.GREEN, "other", ChatFormatting.GRAY);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final SuggestionProvider<CommandSourceStack> STATUS_SUGGESTIONS =
            (ctx, b) -> SharedSuggestionProvider.suggest(List.of("open", "closed", "all"), b);
    private static final SuggestionProvider<CommandSourceStack> ROLE_SUGGESTIONS =
            (ctx, b) -> SharedSuggestionProvider.suggest(ROLES, b);
    // removeplayer 只补全该案件中已有的玩家
    private static final SuggestionProvider<CommandSourceStack> CASE_PLAYER_SUGGESTIONS = (ctx, b) -> {
        if (!Database.isConnected()) return b.buildFuture();
        int id = IntegerArgumentType.getInteger(ctx, "id");
        return SharedSuggestionProvider.suggest(
                CaseRepository.players(id).stream().map(CasePlayer::player).toList(), b);
    };

    private CaseCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("case")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("create")
                        .then(Commands.argument("title", StringArgumentType.greedyString())
                                .executes(ctx -> create(ctx.getSource(), StringArgumentType.getString(ctx, "title")))))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource(), "open", 1))
                        .then(Commands.argument("status", StringArgumentType.word())
                                .suggests(STATUS_SUGGESTIONS)
                                .executes(ctx -> list(ctx.getSource(), StringArgumentType.getString(ctx, "status"), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> list(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "status"),
                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> view(ctx.getSource(), id(ctx)))
                        .then(Commands.literal("timeline")
                                .executes(ctx -> timeline(ctx.getSource(), id(ctx), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> timeline(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "page")))))
                        .then(Commands.literal("evidence")
                                .executes(ctx -> evidence(ctx.getSource(), id(ctx), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> evidence(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "page")))))
                        .then(Commands.literal("attach")
                                .then(Commands.literal("near")
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                                .then(Commands.argument("time", StringArgumentType.word())
                                                        .executes(ctx -> attachNear(ctx.getSource(), id(ctx),
                                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                                StringArgumentType.getString(ctx, "time"))))))
                                .then(Commands.literal("player")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(CraftauditCommands.ONLINE_PLAYER_SUGGESTIONS)
                                                .then(Commands.argument("time", StringArgumentType.word())
                                                        .executes(ctx -> attachPlayer(ctx.getSource(), id(ctx),
                                                                StringArgumentType.getString(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "time"))))))
                                .then(Commands.literal("item")
                                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                                .suggests(CraftauditCommands.ITEM_SUGGESTIONS)
                                                .then(Commands.argument("time", StringArgumentType.word())
                                                        .executes(ctx -> attachItem(ctx.getSource(), id(ctx),
                                                                ResourceLocationArgument.getId(ctx, "item"),
                                                                StringArgumentType.getString(ctx, "time")))))))
                        .then(Commands.literal("player")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(CraftauditCommands.ONLINE_PLAYER_SUGGESTIONS)
                                        .then(Commands.argument("role", StringArgumentType.word())
                                                .suggests(ROLE_SUGGESTIONS)
                                                .executes(ctx -> setPlayer(ctx.getSource(), id(ctx),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "role"))))))
                        .then(Commands.literal("removeplayer")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(CASE_PLAYER_SUGGESTIONS)
                                        .executes(ctx -> removePlayer(ctx.getSource(), id(ctx),
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("loc")
                                .executes(ctx -> addLocation(ctx.getSource(), id(ctx), null))
                                .then(Commands.argument("label", StringArgumentType.greedyString())
                                        .executes(ctx -> addLocation(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "label")))))
                        .then(Commands.literal("note")
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> note(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "text")))))
                        .then(Commands.literal("rename")
                                .then(Commands.argument("title", StringArgumentType.greedyString())
                                        .executes(ctx -> rename(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "title")))))
                        .then(Commands.literal("close")
                                .then(Commands.argument("conclusion", StringArgumentType.greedyString())
                                        .executes(ctx -> close(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "conclusion")))))
                        .then(Commands.literal("reopen")
                                .executes(ctx -> reopen(ctx.getSource(), id(ctx)))));
    }

    private static int id(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "id");
    }

    // ===== 帮助 / 创建 / 列表 =====

    private static int help(CommandSourceStack src) {
        String lang = Lang.langOf(src);
        String[][] entries = {
                {"create <title>", "create"},
                {"list [open|closed|all] [page]", "list"},
                {"<id>", "view"},
                {"<id> timeline|evidence [page]", "pages"},
                {"<id> attach near <radius> <time>", "attach_near"},
                {"<id> attach player <player> <time>", "attach_player"},
                {"<id> attach item <item> <time>", "attach_item"},
                {"<id> player <player> <suspect|victim|witness|other>", "player"},
                {"<id> removeplayer <player>", "removeplayer"},
                {"<id> loc [label]", "loc"},
                {"<id> note <text>", "note"},
                {"<id> rename <title>", "rename"},
                {"<id> close <conclusion>", "close"},
                {"<id> reopen", "reopen"},
        };
        StringBuilder sb = new StringBuilder(Lang.tr(lang, "craftaudit.case.help.title"));
        for (String[] e : entries) {
            sb.append("\n§b/ca case ").append(e[0]).append(" §7- ").append(Lang.tr(lang, "craftaudit.case.help." + e[1]));
        }
        send(src, sb.toString());
        return 1;
    }

    private static int create(CommandSourceStack src, String title) {
        if (!CraftauditCommands.requireDb(src)) return 0;
        long id = CaseRepository.create(title, author(src), authorUuid(src));
        if (id < 0) return fail(src, Lang.tr(src, "craftaudit.case.create_failed"));
        event(src, id, "craftaudit.case.event.created");
        src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.case.created", caseNo(id), title))
                .append(ChatUi.button(Lang.tr(src, "craftaudit.case.btn.view"), "/ca case " + id, true,
                        Lang.tr(src, "craftaudit.case.hover.view"))), true);
        return 1;
    }

    private static int list(CommandSourceStack src, String statusArg, int page) {
        if (!CraftauditCommands.requireDb(src)) return 0;
        String lang = Lang.langOf(src);
        String filter = statusArg.toLowerCase();
        String status = switch (filter) {
            case "open" -> CaseRepository.OPEN;
            case "closed" -> CaseRepository.CLOSED;
            case "all" -> null;
            default -> "?";
        };
        if ("?".equals(status)) return fail(src, Lang.tr(lang, "craftaudit.case.bad_status"));

        int total = CaseRepository.count(status);
        int totalPages = Math.max((total + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE, 1);
        int p = Math.min(page, totalPages);
        List<CaseInfo> cases = CaseRepository.list(status, p, LIST_PAGE_SIZE);

        send(src, Lang.tr(lang, "craftaudit.case.list.title", filter, total));
        if (cases.isEmpty()) send(src, Lang.tr(lang, "craftaudit.case.list.empty"));
        for (CaseInfo c : cases) {
            MutableComponent line = ChatUi.button(caseNo(c.id()), "/ca case " + c.id(), true, Lang.tr(lang, "craftaudit.case.hover.view"))
                    .append(Component.literal(" " + statusTag(c, lang) + " §f" + c.title()
                            + Lang.tr(lang, "craftaudit.case.list.meta", c.createdBy(), fmt(c.createdMs()),
                            c.playerCount(), c.evidenceCount())));
            src.sendSuccess(() -> line, false);
        }
        if (totalPages > 1) {
            src.sendSuccess(() -> ChatUi.pageLine(lang, "/ca case list " + filter + " ", p, totalPages), false);
        }
        return 1;
    }

    // ===== 查看 =====

    private static int view(CommandSourceStack src, int id) {
        CaseInfo c = load(src, id);
        if (c == null) return 0;
        String lang = Lang.langOf(src);
        List<CasePlayer> players = CaseRepository.players(id);
        List<CaseEntry> locations = CaseRepository.entries(id, List.of(CaseRepository.LOCATION), 1, VIEW_LOCATIONS);
        int timelineCount = CaseRepository.countEntries(id, TIMELINE_TYPES);

        send(src, Lang.tr(lang, "craftaudit.case.view.header", caseNo(id), statusTag(c, lang)));
        send(src, Lang.tr(lang, "craftaudit.case.view.title", c.title()));
        send(src, Lang.tr(lang, "craftaudit.case.view.created", c.createdBy(), fmt(c.createdMs())));

        if (players.isEmpty()) {
            send(src, Lang.tr(lang, "craftaudit.case.view.players_none", id));
        } else {
            MutableComponent line = Component.literal(Lang.tr(lang, "craftaudit.case.view.players"));
            String unknown = Lang.tr(lang, "craftaudit.common.unknown");
            for (CasePlayer p : players) {
                line.append(Component.literal(" [" + roleName(lang, p.role()) + "]")
                        .withStyle(ROLE_COLORS.getOrDefault(p.role(), ChatFormatting.GRAY)));
                line.append(Component.literal(" " + p.player()).withStyle(style -> style
                        .withColor(ChatFormatting.BLUE)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("UUID: " + (p.uuid() != null ? p.uuid() : unknown))))));
            }
            src.sendSuccess(() -> line, false);
        }

        if (locations.isEmpty()) {
            send(src, Lang.tr(lang, "craftaudit.case.view.locations_none", id));
        } else {
            send(src, Lang.tr(lang, "craftaudit.case.view.locations"));
            for (CaseEntry loc : locations) {
                src.sendSuccess(() -> locationLine(loc, lang), false);
            }
        }

        MutableComponent counts = Component.literal(Lang.tr(lang, "craftaudit.case.view.evidence", c.evidenceCount()))
                .append(ChatUi.button(Lang.tr(lang, "craftaudit.case.btn.view"), "/ca case " + id + " evidence", true,
                        Lang.tr(lang, "craftaudit.case.hover.evidence")))
                .append(Component.literal(Lang.tr(lang, "craftaudit.case.view.timeline", timelineCount)))
                .append(ChatUi.button(Lang.tr(lang, "craftaudit.case.btn.view"), "/ca case " + id + " timeline", true,
                        Lang.tr(lang, "craftaudit.case.hover.timeline")));
        src.sendSuccess(() -> counts, false);

        if (c.isOpen()) {
            MutableComponent actions = Component.literal(Lang.tr(lang, "craftaudit.case.view.actions"))
                    .append(actionButton(lang, "note", "/ca case " + id + " note "))
                    .append(Component.literal(" "))
                    .append(actionButton(lang, "loc", "/ca case " + id + " loc "))
                    .append(Component.literal(" "))
                    .append(actionButton(lang, "attach", "/ca case " + id + " attach "))
                    .append(Component.literal(" "))
                    .append(actionButton(lang, "player", "/ca case " + id + " player "))
                    .append(Component.literal(" "))
                    .append(actionButton(lang, "close", "/ca case " + id + " close "));
            src.sendSuccess(() -> actions, false);
        } else {
            send(src, Lang.tr(lang, "craftaudit.case.view.closed", c.closedBy(), fmt(c.closedMs())));
            send(src, Lang.tr(lang, "craftaudit.case.view.conclusion", c.conclusion()));
        }
        send(src, "§7-----");
        return 1;
    }

    private static int timeline(CommandSourceStack src, int id, int page) {
        CaseInfo c = load(src, id);
        if (c == null) return 0;
        String lang = Lang.langOf(src);
        int total = CaseRepository.countEntries(id, TIMELINE_TYPES);
        int totalPages = Math.max((total + TIMELINE_PAGE_SIZE - 1) / TIMELINE_PAGE_SIZE, 1);
        int p = Math.min(page, totalPages);

        send(src, Lang.tr(lang, "craftaudit.case.timeline.title", caseNo(id), c.title()));
        List<CaseEntry> entries = CaseRepository.entries(id, TIMELINE_TYPES, p, TIMELINE_PAGE_SIZE);
        if (entries.isEmpty()) send(src, Lang.tr(lang, "craftaudit.common.no_records"));
        for (CaseEntry e : entries) {
            MutableComponent line = Component.literal("§8[" + fmt(e.timeMs()) + "] §9" + e.author() + " ");
            switch (e.type()) {
                case CaseRepository.NOTE -> line
                        .append(Component.literal(Lang.tr(lang, "craftaudit.case.timeline.note")).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(e.text()).withStyle(ChatFormatting.WHITE));
                case CaseRepository.LOCATION -> line
                        .append(Component.literal(Lang.tr(lang, "craftaudit.case.timeline.location")).withStyle(ChatFormatting.GRAY))
                        .append(locationLine(e, lang));
                default -> line.append(Component.literal(renderEvent(e, lang)).withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC));
            }
            src.sendSuccess(() -> line, false);
        }
        src.sendSuccess(() -> ChatUi.pageLine(lang, "/ca case " + id + " timeline ", p, totalPages), false);
        return 1;
    }

    private static int evidence(CommandSourceStack src, int id, int page) {
        CaseInfo c = load(src, id);
        if (c == null) return 0;
        String lang = Lang.langOf(src);
        int total = c.evidenceCount();
        int totalPages = Math.max((total + EVIDENCE_PAGE_SIZE - 1) / EVIDENCE_PAGE_SIZE, 1);
        int p = Math.min(page, totalPages);

        send(src, Lang.tr(lang, "craftaudit.case.evidence.title", caseNo(id), c.title(), total));
        List<CaseEntry> entries = CaseRepository.entries(id, EVIDENCE_TYPES, p, EVIDENCE_PAGE_SIZE);
        if (entries.isEmpty()) send(src, Lang.tr(lang, "craftaudit.case.evidence.empty", id));
        for (CaseEntry e : entries) {
            LogEntry log = CaseRepository.toLogEntry(e);
            if (log == null) continue;
            // 证据用绝对时间，便于和其它来源（截图、聊天记录）对照
            MutableComponent line = Component.literal("§8[" + fmt(log.timeMillis()) + "] ")
                    .append(BlockInspectListener.renderLogLine(log, lang));
            src.sendSuccess(() -> line, false);
        }
        src.sendSuccess(() -> ChatUi.pageLine(lang, "/ca case " + id + " evidence ", p, totalPages), false);
        return 1;
    }

    // ===== 附加证据 =====

    private static int attachNear(CommandSourceStack src, int id, int radius, String timeStr) {
        CaseInfo c = loadOpen(src, id);
        if (c == null) return 0;
        ServerPlayer player = src.getPlayer();
        if (player == null) return CraftauditCommands.playerOnly(src);
        long sinceMs = CraftauditCommands.sinceOrFail(src, timeStr);
        if (sinceMs < 0) return 0;

        String dim = player.level().dimension().location().toString();
        BlockPos center = player.blockPosition();
        List<LogEntry> logs = Database.get().queryLogsNearPaged(dim, center.getX(), center.getY(), center.getZ(),
                radius, sinceMs, 1, ATTACH_LIMIT);
        return finishAttach(src, c, logs, "craftaudit.case.event.attach_near",
                radius, timeStr, dim, center.getX(), center.getY(), center.getZ());
    }

    private static int attachPlayer(CommandSourceStack src, int id, String playerArg, String timeStr) {
        CaseInfo c = loadOpen(src, id);
        if (c == null) return 0;
        long sinceMs = CraftauditCommands.sinceOrFail(src, timeStr);
        if (sinceMs < 0) return 0;

        String name = playerArg;
        String uuid = null;
        ServerPlayer online = src.getServer().getPlayerList().getPlayerByName(playerArg);
        if (online != null) {
            name = online.getName().getString();
            uuid = online.getUUID().toString();
        } else if (CraftauditCommands.looksLikeUuid(playerArg)) {
            uuid = playerArg;
        }
        List<LogEntry> logs = Database.get().queryLogsByActorSince(name, uuid, sinceMs, 1, ATTACH_LIMIT);
        return finishAttach(src, c, logs, "craftaudit.case.event.attach_player", name, timeStr);
    }

    private static int attachItem(CommandSourceStack src, int id, ResourceLocation item, String timeStr) {
        CaseInfo c = loadOpen(src, id);
        if (c == null) return 0;
        long sinceMs = CraftauditCommands.sinceOrFail(src, timeStr);
        if (sinceMs < 0) return 0;

        List<LogEntry> logs = Database.get().queryItemLogsSince(item.toString(), sinceMs, null, null, ATTACH_LIMIT);
        return finishAttach(src, c, logs, "craftaudit.case.event.attach_item", item.toString(), timeStr);
    }

    // eventKey 的参数：新增条数 + 查询描述参数 + 截断提示
    private static int finishAttach(CommandSourceStack src, CaseInfo c, List<LogEntry> logs, String eventKey, Object... queryArgs) {
        if (logs.isEmpty()) return fail(src, Lang.tr(src, "craftaudit.case.attach.none"));
        int added = CaseRepository.addLogs(c.id(), logs, author(src), authorUuid(src));
        if (added < 0) return fail(src, Lang.tr(src, "craftaudit.case.attach.failed"));

        boolean truncated = logs.size() >= ATTACH_LIMIT;
        if (added > 0) {
            List<Object> args = new ArrayList<>();
            args.add(added);
            args.addAll(List.of(queryArgs));
            args.add(truncated ? "@craftaudit.case.attach.truncated_note" : "");
            event(src, c.id(), eventKey, args.toArray());
        }
        int duplicates = logs.size() - added;
        String hint = truncated ? Lang.tr(src, "craftaudit.case.attach.truncated_hint", ATTACH_LIMIT) : "";
        src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.case.attach.done",
                        caseNo(c.id()), added, logs.size(), duplicates, hint))
                .append(ChatUi.button(Lang.tr(src, "craftaudit.case.btn.view"), "/ca case " + c.id() + " evidence", true,
                        Lang.tr(src, "craftaudit.case.hover.evidence"))), false);
        return 1;
    }

    // ===== 玩家 / 地点 / 备注 =====

    private static int setPlayer(CommandSourceStack src, int id, String playerArg, String roleArg) {
        CaseInfo c = loadOpen(src, id);
        if (c == null) return 0;
        String role = roleArg.toLowerCase();
        if (!ROLES.contains(role)) return fail(src, Lang.tr(src, "craftaudit.case.bad_role"));

        String name = playerArg;
        String uuid;
        ServerPlayer online = src.getServer().getPlayerList().getPlayerByName(playerArg);
        if (online != null) {
            name = online.getName().getString();
            uuid = online.getUUID().toString();
        } else if (CraftauditCommands.looksLikeUuid(playerArg)) {
            uuid = playerArg;
        } else {
            uuid = Database.get().findLastUuidByPlayerName(playerArg); // 离线玩家：取日志中最近一次的 UUID
        }

        if (!CaseRepository.setPlayer(id, name, uuid, role, author(src))) return fail(src, Lang.tr(src, "craftaudit.error.save_failed"));
        event(src, id, "craftaudit.case.event.player_set", name, "@craftaudit.case.role." + role);
        ok(src, Lang.tr(src, "craftaudit.case.player_set", caseNo(id), name, roleName(Lang.langOf(src), role),
                uuid == null ? Lang.tr(src, "craftaudit.case.no_uuid") : ""));
        return 1;
    }

    private static int removePlayer(CommandSourceStack src, int id, String player) {
        CaseInfo c = loadOpen(src, id);
        if (c == null) return 0;
        if (!CaseRepository.removePlayer(id, player)) return fail(src, Lang.tr(src, "craftaudit.case.player_not_in", caseNo(id)));
        event(src, id, "craftaudit.case.event.player_removed", player);
        ok(src, Lang.tr(src, "craftaudit.case.player_removed", caseNo(id), player));
        return 1;
    }

    private static int addLocation(CommandSourceStack src, int id, String label) {
        CaseInfo c = loadOpen(src, id);
        if (c == null) return 0;
        ServerPlayer player = src.getPlayer();
        if (player == null) return CraftauditCommands.playerOnly(src);

        String dim = player.level().dimension().location().toString();
        BlockPos pos = player.blockPosition();
        String text = (label == null || label.isBlank()) ? Lang.tr(src, "craftaudit.case.loc_default") : label;
        if (!CaseRepository.addEntry(id, author(src), authorUuid(src), CaseRepository.LOCATION,
                dim, pos.getX(), pos.getY(), pos.getZ(), text, null)) {
            return fail(src, Lang.tr(src, "craftaudit.error.save_failed"));
        }
        ok(src, Lang.tr(src, "craftaudit.case.loc_added", caseNo(id), text, dim, pos.getX(), pos.getY(), pos.getZ()));
        return 1;
    }

    private static int note(CommandSourceStack src, int id, String text) {
        CaseInfo c = loadOpen(src, id);
        if (c == null) return 0;
        if (!CaseRepository.addEntry(id, author(src), authorUuid(src), CaseRepository.NOTE, null, null, null, null, text, null)) {
            return fail(src, Lang.tr(src, "craftaudit.error.save_failed"));
        }
        ok(src, Lang.tr(src, "craftaudit.case.note_added", caseNo(id)));
        return 1;
    }

    // ===== 标题 / 结案 =====

    private static int rename(CommandSourceStack src, int id, String title) {
        CaseInfo c = loadOpen(src, id);
        if (c == null) return 0;
        if (!CaseRepository.rename(id, title)) return fail(src, Lang.tr(src, "craftaudit.error.save_failed"));
        event(src, id, "craftaudit.case.event.renamed", c.title(), title);
        ok(src, Lang.tr(src, "craftaudit.case.renamed", caseNo(id)));
        return 1;
    }

    private static int close(CommandSourceStack src, int id, String conclusion) {
        CaseInfo c = loadOpen(src, id);
        if (c == null) return 0;
        if (!CaseRepository.close(id, author(src), conclusion)) return fail(src, Lang.tr(src, "craftaudit.error.save_failed"));
        event(src, id, "craftaudit.case.event.closed", conclusion);
        src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.case.closed", caseNo(id), conclusion)), true);
        return 1;
    }

    private static int reopen(CommandSourceStack src, int id) {
        CaseInfo c = load(src, id);
        if (c == null) return 0;
        if (c.isOpen()) return fail(src, Lang.tr(src, "craftaudit.case.not_closed", caseNo(id)));
        if (!CaseRepository.reopen(id)) return fail(src, Lang.tr(src, "craftaudit.error.save_failed"));
        // 原结论保留在时间线中，重新打开不会抹掉历史
        event(src, id, "craftaudit.case.event.reopened", c.conclusion(), c.closedBy());
        src.sendSuccess(() -> Component.literal(Lang.tr(src, "craftaudit.case.reopened", caseNo(id))), true);
        return 1;
    }

    // ===== 工具 =====

    private static CaseInfo load(CommandSourceStack src, int id) {
        if (!CraftauditCommands.requireDb(src)) return null;
        CaseInfo c = CaseRepository.find(id);
        if (c == null) src.sendFailure(Component.literal(Lang.tr(src, "craftaudit.case.not_found", caseNo(id))));
        return c;
    }

    // 结案后只读，必须先 reopen（reopen 本身会留下记录）
    private static CaseInfo loadOpen(CommandSourceStack src, int id) {
        CaseInfo c = load(src, id);
        if (c != null && !c.isOpen()) {
            src.sendFailure(Component.literal(Lang.tr(src, "craftaudit.case.read_only", caseNo(id), id)));
            return null;
        }
        return c;
    }

    // 事件保存为 key + 参数（JSON 字符串数组）
    private static void event(CommandSourceStack src, long id, String key, Object... args) {
        JsonArray arr = new JsonArray();
        for (Object a : args) arr.add(String.valueOf(a));
        CaseRepository.addEntry(id, author(src), authorUuid(src), CaseRepository.EVENT,
                null, null, null, null, key, arr.toString());
    }

    private static String renderEvent(CaseEntry e, String lang) {
        List<Object> args = new ArrayList<>();
        if (e.data() != null) {
            try {
                for (JsonElement el : JsonParser.parseString(e.data()).getAsJsonArray()) {
                    String a = el.getAsString();
                    args.add(a.startsWith("@") ? Lang.tr(lang, a.substring(1)) : a);
                }
            } catch (Exception ignored) {
                // 数据损坏时只显示 key
            }
        }
        return Lang.tr(lang, e.text(), args.toArray());
    }

    private static String roleName(String lang, String role) {
        return Lang.tr(lang, "craftaudit.case.role." + role);
    }

    private static MutableComponent actionButton(String lang, String action, String command) {
        return ChatUi.button(Lang.tr(lang, "craftaudit.case.btn." + action), command, false,
                Lang.tr(lang, "craftaudit.case.hover." + action));
    }

    private static MutableComponent locationLine(CaseEntry loc, String lang) {
        String tp = String.format("/execute in %s run tp @s %d %d %d", loc.dimension(), loc.x(), loc.y(), loc.z());
        return Component.literal(String.format("§f%s §7%s x%d/y%d/z%d ", loc.text(), loc.dimension(), loc.x(), loc.y(), loc.z()))
                .append(ChatUi.button(Lang.tr(lang, "craftaudit.case.btn.tp"), tp, false, Lang.tr(lang, "craftaudit.case.hover.tp")));
    }

    private static String statusTag(CaseInfo c, String lang) {
        return Lang.tr(lang, c.isOpen() ? "craftaudit.case.status.open" : "craftaudit.case.status.closed");
    }

    private static String caseNo(long id) {
        return String.format("#%03d", id);
    }

    private static String fmt(long ms) {
        return TIME_FMT.format(Instant.ofEpochMilli(ms));
    }

    private static String author(CommandSourceStack src) {
        return src.getTextName();
    }

    private static String authorUuid(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        return p != null ? p.getUUID().toString() : null;
    }

    private static void send(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    private static void ok(CommandSourceStack src, String text) {
        send(src, "§3[CraftAudit] " + text);
    }

    private static int fail(CommandSourceStack src, String text) {
        src.sendFailure(Component.literal(text));
        return 0;
    }
}
