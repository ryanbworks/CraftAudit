package cn.alini.craftaudit.event.container;

import cn.alini.craftaudit.mixin.HorseInventoryMenuAccessor;
import cn.alini.craftaudit.util.ItemData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跟踪玩家打开容器时的方块坐标，并与“菜单实例”建立一一会话绑定，保证高频开关时不串位。
 *
 * 流程：
 * 1) RightClickBlock: pendingByPlayer[uuid] = (pos, dim, time)
 * 2) PlayerContainerEvent.Open: sessionsByMenu[menu] = Session(pos, dim, openSnapshot, playerUuid)
 *    实体容器（运输矿车、船、驴/骡/羊驼/马）直接从菜单识别实体，不依赖右键方块
 * 3) removed/Close: 通过菜单实例精确取回 Session，比对快照并写日志，然后清理
 */
@Mod.EventBusSubscriber
public final class ContainerSessionTracker {

    // RightClick 到 Open 的最大间隔，超过则认为不是同一次打开
    private static final long PENDING_TIMEOUT_MS = 2000;

    // 记录玩家刚右键的坐标（短时有效）
    private static final Map<UUID, Pending> pendingByPlayer = new ConcurrentHashMap<>();

    // 会话表：用菜单实例作为 key，避免同一玩家高频开关时串位；WeakHashMap 防止泄露
    private static final Map<AbstractContainerMenu, Session> sessionsByMenu =
            Collections.synchronizedMap(new WeakHashMap<>());


    // 右键方块：记录可能的容器方块坐标
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        BlockPos pos = event.getPos();
        String dim = player.level().dimension().location().toString();
        pendingByPlayer.put(player.getUUID(), new Pending(pos, dim, System.currentTimeMillis()));
        // 不取消事件
    }

    // 打开容器：把 pending 绑定到“本次菜单实例”，并做打开快照
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        AbstractContainerMenu menu = event.getContainer();
        UUID uuid = player.getUUID();
        Pending pend = pendingByPlayer.remove(uuid);

        BlockPos pos;
        String dim;
        String target;
        long now = System.currentTimeMillis();
        Entity owner = findEntityContainer(menu);
        if (owner != null) {
            pos = owner.blockPosition();
            dim = owner.level().dimension().location().toString();
            var typeKey = ForgeRegistries.ENTITY_TYPES.getKey(owner.getType());
            target = typeKey != null ? typeKey.toString() : owner.getClass().getName();
        } else if (pend != null && now - pend.atMs <= PENDING_TIMEOUT_MS) {
            pos = pend.pos;
            dim = pend.dimension;
            target = menu.getClass().getName();
        } else {
            // 放弃：虚拟容器（插件箱子等）或玩家直接打开背包
            return;
            // 兜底：用玩家指向方块，否则脚下
//            BlockPos onPos = player.getOnPos();
//            pos = onPos;
//            dim = player.level().dimension().location().toString();
        }

        // 构建打开时快照（只快照目标容器槽）
        List<ItemStack> openSnapshot = snapshotContainerSlots(menu);

        Session session = new Session(uuid, pos, dim, openSnapshot, target);
        sessionsByMenu.put(menu, session);
    }

    /**
     * 识别属于实体的容器：马类背包（菜单持有 horse），或槽位容器本身就是实体（运输矿车、漏斗矿车、运输船）
     */
    private static Entity findEntityContainer(AbstractContainerMenu menu) {
        if (menu instanceof HorseInventoryMenu horseMenu) {
            return ((HorseInventoryMenuAccessor) horseMenu).craftaudit$getHorse();
        }
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Entity entity) return entity;
        }
        return null;
    }

    /**
     * 被 Mixin 在 removed 里调用：
     * 取出并移除该菜单的会话；如果没有，返回 null。
     */
    public static Session takeSession(AbstractContainerMenu menu) {
        return sessionsByMenu.remove(menu);
    }

    /**
     * 供 Mixin/Close 使用：生成当前容器的“after”快照。
     * 注意：这里与 openSnapshot 的过滤策略要保持一致。
     */
    public static List<ItemStack> snapshotContainerSlots(AbstractContainerMenu menu) {
        String menuName = menu.getClass().getName();

        // 精确跳过 Curios
        if (menuName.equals("top.theillusivec4.curios.common.inventory.container.CuriosContainerV2")) {
            return Collections.emptyList();
        }

        List<ItemStack> items = new ArrayList<>();
        for (Slot slot : menu.slots) {
            // 跳过玩家背包
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;

            // 其它虚拟容器
            String name = slot.container.getClass().getName().toLowerCase();
            if (name.contains("curio") || name.contains("trinket") || name.contains("bauble")) continue;
            if (name.contains("enderchest")) continue;
            if (name.contains("lightmanscurrency")) continue;

            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) items.add(stack.copy());
        }
        return items;
    }

    /**
     * 计算 before/after 的差异（按 物品ID+NBT 分组，delta=after-before），
     * 附魔/命名/潜影盒内容不同的物品分别记录
     */
    public static List<ItemDelta> compare(List<ItemStack> before, List<ItemStack> after) {
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        Map<String, Integer> deltas = new HashMap<>();
        accumulate(before, -1, samples, deltas);
        accumulate(after, 1, samples, deltas);

        List<ItemDelta> result = new ArrayList<>();
        for (Map.Entry<String, ItemStack> e : samples.entrySet()) {
            int delta = deltas.getOrDefault(e.getKey(), 0);
            if (delta != 0) result.add(new ItemDelta(e.getValue(), delta));
        }
        return result;
    }

    private static void accumulate(List<ItemStack> stacks, int sign, Map<String, ItemStack> samples, Map<String, Integer> deltas) {
        if (stacks == null) return;
        for (ItemStack stack : stacks) {
            String key = ItemData.key(stack);
            samples.putIfAbsent(key, stack);
            deltas.merge(key, sign * stack.getCount(), Integer::sum);
        }
    }

    // ===== 数据结构 =====
    private record Pending(BlockPos pos, String dimension, long atMs) {}

    public record ItemDelta(ItemStack sample, int delta) {}

    public static final class Session {
        public final UUID playerId;
        public final BlockPos pos;
        public final String dimension;
        public final List<ItemStack> openSnapshot;
        public final String target; // 方块容器为菜单类名，实体容器为实体类型 ID

        Session(UUID playerId, BlockPos pos, String dimension, List<ItemStack> openSnapshot, String target) {
            this.playerId = playerId;
            this.pos = pos;
            this.dimension = dimension;
            this.openSnapshot = openSnapshot != null ? openSnapshot : Collections.emptyList();
            this.target = target;
        }
    }
}