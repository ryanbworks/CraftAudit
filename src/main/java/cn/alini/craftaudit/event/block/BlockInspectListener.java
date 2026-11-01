package cn.alini.craftaudit.event.block;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import cn.alini.craftaudit.util.ChatUi;
import cn.alini.craftaudit.util.ItemData;
import cn.alini.craftaudit.util.Lang;
import cn.alini.craftaudit.util.NameLocalization;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber
public class BlockInspectListener {
    private static final int PAGE_SIZE = 7;
    private static final int NEAR_PAGE_SIZE = 15; // 半径查询分页大小

    private static final Map<UUID, BlockPos> auditQueryPos = new ConcurrentHashMap<>();
    private static final Map<UUID, String> auditQueryDim = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastRightClickTime = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> lastRightClickPos = new ConcurrentHashMap<>();

    // 新增：左键去重（同一坐标，短时间内只输出一次）
    private static final Map<UUID, Long> lastLeftClickTime = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> lastLeftClickPos = new ConcurrentHashMap<>();
    // 左键去重窗口：允许同一坐标 4 tick 内只触发一次（可按需调整）
    private static final long LEFT_CLICK_DEDUP_TICKS = 4;
    // 在类里加上这两个常量（可按需调整）
    private static final boolean REPLACE_PAGE = true;  // 开启“替换上一页”模式
    private static final int CLEAR_LINES = 40;         // 清屏的空白行数量

    // 环境事件写入日志时使用的固定“玩家名”，显示时翻译
    private static final Map<String, String> ENV_ACTORS = Map.of(
            "(爆炸)", "craftaudit.actor.explosion",
            "(环境)", "craftaudit.actor.environment");


    // 玩家下线时清理查询状态，避免长期运行时内存增长
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uid = event.getEntity().getUUID();
        auditQueryPos.remove(uid);
        auditQueryDim.remove(uid);
        lastRightClickTime.remove(uid);
        lastRightClickPos.remove(uid);
        lastLeftClickTime.remove(uid);
        lastLeftClickPos.remove(uid);
    }

    private static boolean requireDb(ServerPlayer player) {
        if (Database.isConnected()) return true;
        player.sendSystemMessage(Component.literal(Lang.tr(player, "craftaudit.error.no_db")));
        return false;
    }

    // 左键：只输出破坏和放置（含自然破坏）
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        BlockPos pos = event.getPos();

        // 去重：同一玩家、同一坐标、短时间窗口内不重复输出
        long nowTick = System.currentTimeMillis() / 50; // 粗略 tick
        UUID uid = player.getUUID();
        Long lastTick = lastLeftClickTime.get(uid);
        BlockPos lastPos = lastLeftClickPos.get(uid);
        if (lastTick != null && lastPos != null
                && pos.equals(lastPos)
                && (nowTick - lastTick) <= LEFT_CLICK_DEDUP_TICKS) {
            event.setCanceled(true); // 仍然阻止继续挖掘
            return;
        }
        lastLeftClickTime.put(uid, nowTick);
        lastLeftClickPos.put(uid, pos);

        String dimension = player.level().dimension().location().toString();
        auditQueryPos.put(uid, pos);
        auditQueryDim.put(uid, dimension);
        showBlockLogsPaged(player, dimension, pos.getX(), pos.getY(), pos.getZ(), 1);
        event.setCanceled(true);
    }

    // 右键：只输出交互日志（不含破坏和放置）
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        long nowTick = System.currentTimeMillis() / 50; // 以tick粗略去重
        BlockPos pos = event.getPos();
        String dimension = player.level().dimension().location().toString();

        if (lastRightClickTime.getOrDefault(player.getUUID(), -1L) == nowTick
                && pos.equals(lastRightClickPos.get(player.getUUID()))) {
            return; // 同tick同坐标只处理一次
        }
        lastRightClickTime.put(player.getUUID(), nowTick);
        lastRightClickPos.put(player.getUUID(), pos);

        auditQueryPos.put(player.getUUID(), pos);
        auditQueryDim.put(player.getUUID(), dimension);
        showInteractLogsPaged(player, dimension, pos.getX(), pos.getY(), pos.getZ(), 1);
        event.setCanceled(true);
    }

    // 左键翻页
    public static void queryBlockLogPage(ServerPlayer player, int page) {
        BlockPos pos = auditQueryPos.get(player.getUUID());
        String dim = auditQueryDim.get(player.getUUID());
        if (pos == null || dim == null) {
            player.sendSystemMessage(Component.literal(Lang.tr(player, "craftaudit.inspect.need_left_click")));
            return;
        }
        showBlockLogsPaged(player, dim, pos.getX(), pos.getY(), pos.getZ(), page);
    }

    // 右键翻页
    public static void queryInteractLogPage(ServerPlayer player, int page) {
        BlockPos pos = auditQueryPos.get(player.getUUID());
        String dim = auditQueryDim.get(player.getUUID());
        if (pos == null || dim == null) {
            player.sendSystemMessage(Component.literal(Lang.tr(player, "craftaudit.inspect.need_right_click")));
            return;
        }
        showInteractLogsPaged(player, dim, pos.getX(), pos.getY(), pos.getZ(), page);
    }

    // 左键：只显示破坏、放置、自然破坏
    public static void showBlockLogsPaged(ServerPlayer player, String dimension, int x, int y, int z, int page) {
        if (!requireDb(player)) return;
        String lang = Lang.langOf(player);
        String actionPattern = "break|place|natural_break";
        int total = Database.get().countLogsAt(dimension, x, y, z, actionPattern);
        int totalPages = Math.max((total + PAGE_SIZE - 1) / PAGE_SIZE, 1);
        List<LogEntry> logs = Database.get().queryLogsAtPaged(dimension, x, y, z, actionPattern, page, PAGE_SIZE);
        clearBeforePage(player);
        player.sendSystemMessage(Component.literal(Lang.tr(lang, "craftaudit.inspect.block_title", dimension, x, y, z)));
        if (logs.isEmpty()) {
            player.sendSystemMessage(Component.literal(Lang.tr(lang, "craftaudit.common.no_records")));
        } else {
            for (LogEntry log : logs) {
                player.sendSystemMessage(formatBlockLogCoreProtect(log, lang));
            }
        }
        player.sendSystemMessage(Component.literal("§7-----"));
        player.sendSystemMessage(ChatUi.pageLine(lang, "/ca blocklog ", page, totalPages));
    }

    // 右键：只显示交互日志（不含破坏和放置）
    // 包含：容器 put/take、告示牌、点火、按钮/拉杆/门、击杀、桶类、展示框/画
    public static void showInteractLogsPaged(ServerPlayer player, String dimension, int x, int y, int z, int page) {
        if (!requireDb(player)) return;
        String lang = Lang.langOf(player);
        String actionPattern = String.join("|", new String[]{
                "put","take","sign_edit","ignite","button_press","lever_pull","door_use","kill",
                "bucket_fill","bucket_empty","bucket_catch","bucket_milk",
                "frame_put","frame_take","frame_rotate","frame_place","frame_break",
                "painting_place","painting_break"
        });
        int total = Database.get().countLogsAt(dimension, x, y, z, actionPattern);
        int totalPages = Math.max((total + PAGE_SIZE - 1) / PAGE_SIZE, 1);
        List<LogEntry> logs = Database.get().queryLogsAtPaged(dimension, x, y, z, actionPattern, page, PAGE_SIZE);
        clearBeforePage(player);

        player.sendSystemMessage(Component.literal(Lang.tr(lang, "craftaudit.inspect.interact_title", dimension, x, y, z)));
        if (logs.isEmpty()) {
            player.sendSystemMessage(Component.literal(Lang.tr(lang, "craftaudit.common.no_records")));
        } else {
            for (LogEntry log : logs) {
                player.sendSystemMessage(formatLogCoreProtect(log, lang));
            }
        }
        player.sendSystemMessage(Component.literal("§7-----"));
        player.sendSystemMessage(ChatUi.pageLine(lang, "/ca log ", page, totalPages));
    }

    // 半径 + 时间分页查询（中心取玩家当前位置）
    public static void showNearbyLogsPaged(ServerPlayer player, int radius, long sinceMillis, int page, String timeLabel) {
        if (!requireDb(player)) return;
        String lang = Lang.langOf(player);
        BlockPos center = player.blockPosition();
        String dim = player.level().dimension().location().toString();

        int total = Database.get().countLogsNear(dim, center.getX(), center.getY(), center.getZ(), radius, sinceMillis);
        int totalPages = Math.max((total + NEAR_PAGE_SIZE - 1) / NEAR_PAGE_SIZE, 1);
        List<LogEntry> logs = Database.get().queryLogsNearPaged(dim, center.getX(), center.getY(), center.getZ(), radius, sinceMillis, page, NEAR_PAGE_SIZE);
        clearBeforePage(player);

        player.sendSystemMessage(Component.literal(Lang.tr(lang, "craftaudit.inspect.near_title",
                dim, radius, timeLabel, center.getX(), center.getY(), center.getZ())));
        if (logs.isEmpty()) {
            player.sendSystemMessage(Component.literal(Lang.tr(lang, "craftaudit.common.no_records")));
        } else {
            for (LogEntry log : logs) {
                MutableComponent line = isBlockAction(log.action())
                        ? formatBlockLogCoreProtect(log, lang)
                        : formatLogCoreProtect(log, lang);
                // 附带坐标
                line.append(Component.literal(String.format(" @ x%d/y%d/z%d", log.x(), log.y(), log.z())).withStyle(ChatFormatting.DARK_GRAY));
                player.sendSystemMessage(line);
            }
        }
        player.sendSystemMessage(Component.literal("§7-----"));
        player.sendSystemMessage(ChatUi.pageLine(lang, "/ca near " + radius + " " + timeLabel + " ", page, totalPages));
    }

    private static boolean isBlockAction(String action) {
        return action.equals("break") || action.equals("place") || action.equals("natural_break");
    }

    // 时间前缀 + 正负号 + 玩家名（悬浮 UUID）
    private static MutableComponent linePrefix(LogEntry log, String lang, boolean plus) {
        MutableComponent msg = Component.literal(formatAgoPrefix(log.timeMillis(), lang)).withStyle(ChatFormatting.GRAY);
        msg.append(Component.literal(plus ? "+" : "-").withStyle(plus ? ChatFormatting.GOLD : ChatFormatting.RED));
        msg.append(Component.literal(" "));

        String uuidText = log.playerUuid() != null ? log.playerUuid() : Lang.tr(lang, "craftaudit.common.unknown");
        msg.append(Component.literal(actorName(log.player(), lang)).withStyle(style -> style
                .withColor(ChatFormatting.BLUE)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("UUID: " + uuidText)))));
        msg.append(Component.literal(" "));
        return msg;
    }

    private static String actorName(String player, String lang) {
        String key = ENV_ACTORS.get(player);
        return key != null ? Lang.tr(lang, key) : String.valueOf(player);
    }

    // "动作 目标"，动作文本来自语言文件
    private static void appendAction(MutableComponent msg, String lang, String action, ChatFormatting color, MutableComponent target) {
        msg.append(Component.literal(Lang.tr(lang, "craftaudit.action." + action)).withStyle(color));
        msg.append(Component.literal(" "));
        msg.append(target.withStyle(ChatFormatting.AQUA));
    }

    // 方块日志（只输出放置、破坏、自然破坏；玩家名前加彩色正负号）
    private static MutableComponent formatBlockLogCoreProtect(LogEntry log, String lang) {
        String action = log.action();
        MutableComponent msg = linePrefix(log, lang, action.equals("place"));

        switch (action) {
            case "place" -> appendAction(msg, lang, action, ChatFormatting.WHITE, NameLocalization.block(log.target()));
            case "break" -> appendAction(msg, lang, action, ChatFormatting.LIGHT_PURPLE, NameLocalization.block(log.target()));
            case "natural_break" -> appendAction(msg, lang, action, ChatFormatting.DARK_PURPLE, NameLocalization.block(log.target()));
            default -> {
                msg.append(Component.literal(action).withStyle(ChatFormatting.GRAY));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(String.valueOf(log.target())).withStyle(ChatFormatting.AQUA));
            }
        }
        return msg;
    }

    // 通用交互日志（右键所有类型，玩家名前加彩色正负号）
    private static MutableComponent formatLogCoreProtect(LogEntry log, String lang) {
        String action = log.action();

        // 简单的正负号规则：增加类为 +，其余 -
        boolean isPlus = action.equals("put")
                || action.equals("transfer")
                || action.equals("sign_edit")
                || action.equals("frame_put")
                || action.equals("frame_place")
                || action.equals("painting_place")
                || action.startsWith("bucket_"); // 桶操作归类为“交互增加”
        MutableComponent msg = linePrefix(log, lang, isPlus);

        switch (action) {
            case "put" -> {
                msg.append(Component.literal(Lang.tr(lang, "craftaudit.action.put")).withStyle(ChatFormatting.YELLOW));
                msg.append(Component.literal(" "));
                appendItem(msg, log, lang);
            }
            case "take" -> {
                msg.append(Component.literal(Lang.tr(lang, "craftaudit.action.take")).withStyle(ChatFormatting.GOLD));
                msg.append(Component.literal(" "));
                appendItem(msg, log, lang);
            }
            case "drop" -> {
                msg.append(Component.literal(Lang.tr(lang, "craftaudit.action.drop")).withStyle(ChatFormatting.GOLD));
                msg.append(Component.literal(" "));
                appendItem(msg, log, lang);
            }
            case "transfer" -> {
                msg.append(Component.literal(Lang.tr(lang, "craftaudit.action.transfer")).withStyle(ChatFormatting.YELLOW));
                msg.append(Component.literal(" "));
                appendItem(msg, log, lang);
                JsonObject data = ItemData.parse(log.data());
                String from = ItemData.getString(data, "from");
                String fromUuid = ItemData.getString(data, "from_uuid");
                String unknown = Lang.tr(lang, "craftaudit.common.unknown");
                msg.append(Component.literal(Lang.tr(lang, "craftaudit.action.transfer_from")).withStyle(ChatFormatting.GRAY));
                msg.append(Component.literal(from != null ? from : unknown).withStyle(style -> style
                        .withColor(ChatFormatting.BLUE)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("UUID: " + (fromUuid != null ? fromUuid : unknown))))));
            }
            case "sign_edit" -> {
                appendAction(msg, lang, action, ChatFormatting.AQUA, NameLocalization.block(log.target()));
                msg.append(Component.literal(Lang.tr(lang, "craftaudit.action.sign_text", log.data())).withStyle(ChatFormatting.GRAY));
            }
            case "ignite" -> appendAction(msg, lang, action, ChatFormatting.RED, NameLocalization.block(log.target()));
            case "button_press", "lever_pull", "door_use" ->
                    appendAction(msg, lang, action, ChatFormatting.DARK_AQUA, NameLocalization.block(log.target()));
            case "kill" -> {
                String victim = String.valueOf(log.target());
                MutableComponent target = victim.startsWith("player:")
                        ? Component.literal(victim.substring("player:".length()))
                        : NameLocalization.any(victim);
                appendAction(msg, lang, action, ChatFormatting.RED, target);
            }
            case "bucket_fill", "bucket_empty", "bucket_catch" ->
                    appendAction(msg, lang, action, ChatFormatting.YELLOW, NameLocalization.any(log.target()));
            case "bucket_milk" -> {
                msg.append(Component.literal(Lang.tr(lang, "craftaudit.action.bucket_milk")).withStyle(ChatFormatting.YELLOW));
                msg.append(Component.literal(Lang.tr(lang, "craftaudit.action.bucket_milk_from")).withStyle(ChatFormatting.GRAY));
                msg.append(NameLocalization.any(log.target()).withStyle(ChatFormatting.AQUA));
            }
            default -> {
                msg.append(Component.literal(action).withStyle(ChatFormatting.GRAY));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(String.valueOf(log.target())).withStyle(ChatFormatting.AQUA));
            }
        }
        return msg;
    }

    // "数量x 物品名"，带 NBT 的物品追加 [NBT] 标记，悬浮显示附魔/命名/内容
    private static void appendItem(MutableComponent msg, LogEntry log, String lang) {
        JsonObject data = ItemData.parse(log.data());
        String itemId = ItemData.getString(data, "item");
        if (itemId == null) itemId = log.data(); // 无法解析时原样显示
        int count = ItemData.getInt(data, "count");
        msg.append(Component.literal(count + "x ").withStyle(ChatFormatting.WHITE));
        msg.append(NameLocalization.item(itemId).withStyle(ChatFormatting.AQUA));

        MutableComponent details = ItemData.describe(data, lang);
        boolean hasNbt = data != null && data.has("nbt");
        if (details != null || hasNbt) {
            Component hover = details != null ? details : Component.literal(Lang.tr(lang, "craftaudit.item.nbt_only"));
            msg.append(Component.literal(" [NBT]").withStyle(style -> style
                    .withColor(ChatFormatting.DARK_PURPLE)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))));
        }
    }

    // 对外：统一渲染一条日志行（供 /ca check、/ca item、案件证据复用）
    public static MutableComponent renderLogLine(LogEntry log, String lang) {
        MutableComponent line = isBlockAction(log.action())
                ? formatBlockLogCoreProtect(log, lang)
                : formatLogCoreProtect(log, lang);
        // 附带坐标
        line.append(Component.literal(String.format(" @ %s x%d/y%d/z%d", log.dimension(), log.x(), log.y(), log.z()))
                .withStyle(ChatFormatting.DARK_GRAY));
        return line;
    }

    // 工具方法：在发送新一页前调用
    private static void clearBeforePage(ServerPlayer viewer) {
        if (!REPLACE_PAGE) return;
        // 用空格而不是空字符串，确保客户端渲染出一整行
        for (int i = 0; i < CLEAR_LINES; i++) {
            viewer.sendSystemMessage(Component.literal(" "));
        }
    }

    // 时间格式化（自动 秒/分/时/天），数值零填充并保留一位小数，返回带“前 | ”的前缀
    private static String formatAgoPrefix(long timeMs, String lang) {
        long deltaMs = System.currentTimeMillis() - timeMs;
        if (deltaMs < 0) deltaMs = 0;

        double sec = deltaMs / 1000.0;
        if (sec < 60.0) return Lang.tr(lang, "craftaudit.ago.seconds", fmt1(sec));
        double min = sec / 60.0;
        if (min < 60.0) return Lang.tr(lang, "craftaudit.ago.minutes", fmt1(min));
        double hours = min / 60.0;
        if (hours < 24.0) return Lang.tr(lang, "craftaudit.ago.hours", fmt1(hours));
        return Lang.tr(lang, "craftaudit.ago.days", fmt1(hours / 24.0));
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%04.1f", v);
    }
}
