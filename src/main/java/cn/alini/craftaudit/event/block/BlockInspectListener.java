package cn.alini.craftaudit.event.block;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import cn.alini.craftaudit.util.NameLocalization;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
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
        player.sendSystemMessage(Component.literal("§c数据库未连接。"));
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
            player.sendSystemMessage(Component.literal("§c请先左键方块进行查询！"));
            return;
        }
        showBlockLogsPaged(player, dim, pos.getX(), pos.getY(), pos.getZ(), page);
    }

    // 右键翻页
    public static void queryInteractLogPage(ServerPlayer player, int page) {
        BlockPos pos = auditQueryPos.get(player.getUUID());
        String dim = auditQueryDim.get(player.getUUID());
        if (pos == null || dim == null) {
            player.sendSystemMessage(Component.literal("§c请先右键方块进行查询！"));
            return;
        }
        showInteractLogsPaged(player, dim, pos.getX(), pos.getY(), pos.getZ(), page);
    }

    // 左键：只显示破坏、放置、自然破坏
    public static void showBlockLogsPaged(ServerPlayer player, String dimension, int x, int y, int z, int page) {
        if (!requireDb(player)) return;
        String actionPattern = "break|place|natural_break";
        int total = Database.get().countLogsAt(dimension, x, y, z, actionPattern);
        int totalPages = Math.max((total + PAGE_SIZE - 1) / PAGE_SIZE, 1);
        List<LogEntry> logs = Database.get().queryLogsAtPaged(dimension, x, y, z, actionPattern, page, PAGE_SIZE);
        clearBeforePage(player.createCommandSourceStack());
        player.sendSystemMessage(Component.literal(
                String.format("§7-----§3方块变更记录§7-----(%s x%d/y%d/z%d)", dimension, x, y, z)
        ));
        if (logs.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7无记录。"));
        } else {
            for (LogEntry log : logs) {
                player.sendSystemMessage(formatBlockLogCoreProtect(log));
            }
        }
        player.sendSystemMessage(Component.literal("§7-----"));
        sendPageLine(player, x, y, z, page, totalPages, "block");
    }

    // 右键：只显示交互日志（不含破坏和放置）
    // 包含：容器 put/take、告示牌、点火、按钮/拉杆/门、击杀、桶类、展示框/画
    public static void showInteractLogsPaged(ServerPlayer player, String dimension, int x, int y, int z, int page) {
        if (!requireDb(player)) return;
        String actionPattern = String.join("|", new String[]{
                "put","take","sign_edit","ignite","button_press","lever_pull","door_use","kill",
                "bucket_fill","bucket_empty","bucket_catch","bucket_milk",
                "frame_put","frame_take","frame_rotate","frame_place","frame_break",
                "painting_place","painting_break"
        });
        int total = Database.get().countLogsAt(dimension, x, y, z, actionPattern);
        int totalPages = Math.max((total + PAGE_SIZE - 1) / PAGE_SIZE, 1);
        List<LogEntry> logs = Database.get().queryLogsAtPaged(dimension, x, y, z, actionPattern, page, PAGE_SIZE);
        clearBeforePage(player.createCommandSourceStack());

        player.sendSystemMessage(Component.literal(
                String.format("§7-----§3交互记录§7-----(%s x%d/y%d/z%d)", dimension, x, y, z)
        ));
        if (logs.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7无记录。"));
        } else {
            for (LogEntry log : logs) {
                player.sendSystemMessage(formatLogCoreProtect(log));
            }
        }
        player.sendSystemMessage(Component.literal("§7-----"));
        sendPageLine(player, x, y, z, page, totalPages, "interact");
    }

    // 半径 + 时间分页查询（中心取玩家当前位置）
    public static void showNearbyLogsPaged(ServerPlayer player, int radius, long sinceMillis, int page, String timeLabel) {
        if (!requireDb(player)) return;
        BlockPos center = player.blockPosition();
        String dim = player.level().dimension().location().toString();

        int total = Database.get().countLogsNear(dim, center.getX(), center.getY(), center.getZ(), radius, sinceMillis);
        int totalPages = Math.max((total + NEAR_PAGE_SIZE - 1) / NEAR_PAGE_SIZE, 1);
        List<LogEntry> logs = Database.get().queryLogsNearPaged(dim, center.getX(), center.getY(), center.getZ(), radius, sinceMillis, page, NEAR_PAGE_SIZE);
        clearBeforePage(player.createCommandSourceStack());

        player.sendSystemMessage(Component.literal(
                String.format("§7-----§3半径查询§7----- (维度: %s | 半径: %d | 时间: %s内 | 中心: x%d/y%d/z%d)", dim, radius, timeLabel, center.getX(), center.getY(), center.getZ())
        ));
        if (logs.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7无记录。"));
        } else {
            for (LogEntry log : logs) {
                MutableComponent line = (log.action().equals("break") || log.action().equals("place") || log.action().equals("natural_break"))
                        ? formatBlockLogCoreProtect(log)
                        : formatLogCoreProtect(log);
                // 附带坐标
                line.append(Component.literal(String.format(" @ x%d/y%d/z%d", log.x(), log.y(), log.z())).withStyle(ChatFormatting.DARK_GRAY));
                player.sendSystemMessage(line);
            }
        }
        player.sendSystemMessage(Component.literal("§7-----"));
        sendNearPageLine(player, radius, timeLabel, page, totalPages);
    }

    // 方块日志（只输出放置、破坏、自然破坏；玩家名前加彩色正负号）
    private static MutableComponent formatBlockLogCoreProtect(LogEntry log) {
        MutableComponent msg = Component.literal(formatAgoPrefix(log.timeMillis())).withStyle(ChatFormatting.GRAY);

        String action = log.action();
        String sign = action.equals("place") ? "+" : "-";
        ChatFormatting signColor = sign.equals("+") ? ChatFormatting.GOLD : ChatFormatting.RED;

        msg.append(Component.literal(sign).withStyle(signColor));
        msg.append(Component.literal(" "));
        // 玩家名 + 悬浮 UUID
        String uuidText = log.playerUuid() != null ? log.playerUuid() : "未知";
        MutableComponent nameComp = Component.literal(log.player()).withStyle(ChatFormatting.BLUE);
        nameComp.withStyle(style -> style.withHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("UUID: " + uuidText))
        ));
        msg.append(nameComp);
        msg.append(Component.literal(" "));

        switch (action) {
            case "place":
                msg.append(Component.literal("放置了").withStyle(ChatFormatting.WHITE));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "break":
                msg.append(Component.literal("破坏了").withStyle(ChatFormatting.LIGHT_PURPLE));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "natural_break":
                msg.append(Component.literal("自然破坏了").withStyle(ChatFormatting.DARK_PURPLE));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            default:
                msg.append(Component.literal(action).withStyle(ChatFormatting.GRAY));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(log.target()).withStyle(ChatFormatting.AQUA));
        }
        return msg;
    }

    // 通用交互日志（右键所有类型，玩家名前加彩色正负号）
    private static MutableComponent formatLogCoreProtect(LogEntry log) {
        MutableComponent msg = Component.literal(formatAgoPrefix(log.timeMillis())).withStyle(ChatFormatting.GRAY);
        String action = log.action();

        // 简单的正负号规则：增加类为 +，其余 -
        boolean isPlus = action.equals("put")
                || action.equals("sign_edit")
                || action.equals("frame_put")
                || action.equals("frame_place")
                || action.equals("painting_place")
                || action.startsWith("bucket_"); // 桶操作归类为“交互增加”
        String sign = isPlus ? "+" : "-";
        ChatFormatting signColor = isPlus ? ChatFormatting.GOLD : ChatFormatting.RED;

        msg.append(Component.literal(sign).withStyle(signColor));
        msg.append(Component.literal(" "));
        // 玩家名 + 悬浮 UUID
        String uuidText = log.playerUuid() != null ? log.playerUuid() : "未知";
        MutableComponent nameComp = Component.literal(log.player()).withStyle(ChatFormatting.BLUE);
        nameComp.withStyle(style -> style.withHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("UUID: " + uuidText))
        ));
        msg.append(nameComp);
        msg.append(Component.literal(" "));

        switch (action) {
            case "put":
            case "take": {
                String actionText = action.equals("put") ? "存入" : "取出";
                ChatFormatting actionColor = action.equals("put") ? ChatFormatting.YELLOW : ChatFormatting.GOLD;
                String itemId = "";
                int count = 0;
                try {
                    String data = log.data();
                    int i1 = data.indexOf("\"item\":\"") + 8;
                    int i2 = data.indexOf("\"", i1);
                    itemId = data.substring(i1, i2);
                    int c1 = data.indexOf("\"count\":") + 8;
                    int c2 = data.indexOf("}", c1);
                    count = Integer.parseInt(data.substring(c1, c2).trim());
                } catch (Exception e) {
                    itemId = log.data();
                }
                String itemName = NameLocalization.itemName(itemId);
                msg.append(Component.literal(actionText).withStyle(actionColor));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(count + "x ").withStyle(ChatFormatting.WHITE));
                msg.append(Component.literal(itemName).withStyle(ChatFormatting.AQUA));
                break;
            }
            case "sign_edit":
                msg.append(Component.literal("编辑了告示牌").withStyle(ChatFormatting.AQUA));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                msg.append(Component.literal(" 文本: " + log.data()).withStyle(ChatFormatting.GRAY));
                break;
            case "ignite":
                msg.append(Component.literal("点燃了").withStyle(ChatFormatting.RED));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "button_press":
                msg.append(Component.literal("按下了按钮").withStyle(ChatFormatting.DARK_AQUA));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "lever_pull":
                msg.append(Component.literal("拉动了拉杆").withStyle(ChatFormatting.DARK_AQUA));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "door_use":
                msg.append(Component.literal("使用了门").withStyle(ChatFormatting.DARK_AQUA));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "kill": {
                msg.append(Component.literal("杀死了").withStyle(ChatFormatting.RED));
                msg.append(Component.literal(" "));
                String victim = log.target();
                if (victim.startsWith("player:")) victim = victim.substring("player:".length());
                msg.append(Component.literal(NameLocalization.localize(victim)).withStyle(ChatFormatting.AQUA));
                break;
            }
            // 桶类
            case "bucket_fill":
                msg.append(Component.literal("用桶装取了").withStyle(ChatFormatting.YELLOW));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.localize(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "bucket_empty":
                msg.append(Component.literal("用桶倒出了").withStyle(ChatFormatting.YELLOW));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.localize(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "bucket_catch":
                msg.append(Component.literal("用桶装起了").withStyle(ChatFormatting.YELLOW));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.localize(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "bucket_milk":
                msg.append(Component.literal("用桶挤奶").withStyle(ChatFormatting.YELLOW));
                msg.append(Component.literal(" 自 ").withStyle(ChatFormatting.GRAY));
                msg.append(Component.literal(NameLocalization.localize(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            default:
                msg.append(Component.literal(action).withStyle(ChatFormatting.GRAY));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(log.target()).withStyle(ChatFormatting.AQUA));
        }
        return msg;
    }

    // 对外：统一渲染一条日志行（供命令 /ca check 复用）
    public static MutableComponent renderLogLine(LogEntry log) {
        MutableComponent line = (log.action().equals("break") || log.action().equals("place") || log.action().equals("natural_break"))
                ? formatBlockLogCoreProtect(log)
                : formatLogCoreProtect(log);
        // 附带坐标
        line.append(Component.literal(String.format(" @ %s x%d/y%d/z%d", log.dimension(), log.x(), log.y(), log.z()))
                .withStyle(ChatFormatting.DARK_GRAY));
        return line;
    }

    // 分页按钮（坐标查询）
    private static void sendPageLine(ServerPlayer player, int x, int y, int z, int page, int totalPages, String type) {
        MutableComponent pageLine = Component.literal(String.format("第 %d/%d 页 ", page, totalPages)).withStyle(ChatFormatting.GRAY);

        // 选择命令前缀
        String cmd = type.equals("block") ? "/ca blocklog " : "/ca log ";

        if (page > 1) {
            pageLine.append(
                    Component.literal("◀ ").withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd + (page - 1)))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("上一页")))
                            .withColor(ChatFormatting.AQUA)
                    )
            );
        }
        pageLine.append(Component.literal("("));
        for (int i = 1; i <= totalPages; i++) {
            if (i == page) {
                pageLine.append(Component.literal(i + "").withStyle(ChatFormatting.YELLOW));
            } else {
                int finalI = i;
                pageLine.append(
                        Component.literal(finalI + "").withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd + finalI))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("跳转到第 " + finalI + " 页")))
                                .withColor(ChatFormatting.AQUA)
                        )
                );
            }
            if (i < totalPages) pageLine.append(Component.literal("|").withStyle(ChatFormatting.GRAY));
        }
        pageLine.append(Component.literal(") "));
        if (page < totalPages) {
            pageLine.append(
                    Component.literal("▶").withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd + (page + 1)))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("下一页")))
                            .withColor(ChatFormatting.AQUA)
                    )
            );
        }
        player.sendSystemMessage(pageLine);
    }

    // 分页按钮（near 查询）
    private static void sendNearPageLine(ServerPlayer player, int radius, String timeLabel, int page, int totalPages) {
        MutableComponent pageLine = Component.literal(String.format("第 %d/%d 页 ", page, totalPages)).withStyle(ChatFormatting.GRAY);
        String cmdPrefix = "/ca near " + radius + " " + timeLabel + " ";
        if (page > 1) {
            pageLine.append(
                    Component.literal("◀ ").withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmdPrefix + (page - 1)))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("上一页")))
                            .withColor(ChatFormatting.AQUA)
                    )
            );
        }
        pageLine.append(Component.literal("("));
        for (int i = 1; i <= totalPages; i++) {
            if (i == page) {
                pageLine.append(Component.literal(i + "").withStyle(ChatFormatting.YELLOW));
            } else {
                int finalI = i;
                pageLine.append(
                        Component.literal(finalI + "").withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmdPrefix + finalI))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("跳转到第 " + finalI + " 页")))
                                .withColor(ChatFormatting.AQUA)
                        )
                );
            }
            if (i < totalPages) pageLine.append(Component.literal("|").withStyle(ChatFormatting.GRAY));
        }
        pageLine.append(Component.literal(") "));
        if (page < totalPages) {
            pageLine.append(
                    Component.literal("▶").withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmdPrefix + (page + 1)))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("下一页")))
                            .withColor(ChatFormatting.AQUA)
                    )
            );
        }
        player.sendSystemMessage(pageLine);
    }
    // 工具方法：在发送新一页前调用
    private static void clearBeforePage(CommandSourceStack src) {
        if (!REPLACE_PAGE) return;
        ServerPlayer viewer = src.getPlayer();
        if (viewer == null) return;
        // 用空格而不是空字符串，确保客户端渲染出一整行
        for (int i = 0; i < CLEAR_LINES; i++) {
            viewer.sendSystemMessage(Component.literal(" "));
        }
    }

    // 时间格式化（自动 秒/分/时/天），数值零填充并保留一位小数，返回带“前 | ”的前缀
    private static String formatAgoPrefix(long timeMs) {
        long deltaMs = System.currentTimeMillis() - timeMs;
        if (deltaMs < 0) deltaMs = 0;

        double sec = deltaMs / 1000.0;
        if (sec < 60.0) {
            return String.format(Locale.ROOT, "%04.1f秒 前 | ", sec);
        }
        double min = sec / 60.0;
        if (min < 60.0) {
            return String.format(Locale.ROOT, "%04.1f分 前 | ", min);
        }
        double hours = min / 60.0;
        if (hours < 24.0) {
            return String.format(Locale.ROOT, "%04.1f时 前 | ", hours);
        }
        double days = hours / 24.0;
        return String.format(Locale.ROOT, "%04.1f天 前 | ", days);
    }
}