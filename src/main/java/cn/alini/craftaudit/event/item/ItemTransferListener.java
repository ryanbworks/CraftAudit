package cn.alini.craftaudit.event.item;

import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.mixin.ItemEntityAccessor;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import cn.alini.craftaudit.util.ItemData;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * 物品流转：
 * - drop：玩家丢出物品（Q 键、拖出背包、创造模式丢出）
 * - transfer：玩家拾取了「其他玩家」丢出的物品（记在拾取者名下，data 中带 from / from_uuid）
 * 自然掉落、怪物掉落、自己丢自己捡都不记录，避免刷屏。
 */
@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public final class ItemTransferListener {

    private ItemTransferListener() {}

    // LOWEST：其它模组取消丢出时不会收到（默认不接收已取消事件）
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        ItemEntity entity = event.getEntity();
        ItemStack stack = entity.getItem();
        if (stack.isEmpty()) return;

        BlockPos pos = entity.blockPosition();
        Database.log(new LogEntry(
                System.currentTimeMillis(),
                player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                player.getName().getString(),
                player.getUUID().toString(),
                "drop",
                ItemData.id(stack),
                ItemData.toJson(stack, stack.getCount()).toString()
        ));
    }

    @SubscribeEvent
    public static void onPickup(PlayerEvent.ItemPickupEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer picker)) return;
        ItemStack picked = event.getStack(); // 实际拾取的部分（数量已扣除未拾取的）
        if (picked.isEmpty() || picked.getCount() <= 0) return;

        ItemEntity entity = event.getOriginalEntity();
        UUID thrower = ((ItemEntityAccessor) entity).craftaudit$getThrower();
        if (thrower == null || thrower.equals(picker.getUUID())) return;

        JsonObject data = ItemData.toJson(picked, picked.getCount());
        data.addProperty("from", resolveName(picker.getServer(), thrower));
        data.addProperty("from_uuid", thrower.toString());

        BlockPos pos = entity.blockPosition();
        Database.log(new LogEntry(
                System.currentTimeMillis(),
                picker.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                picker.getName().getString(),
                picker.getUUID().toString(),
                "transfer",
                ItemData.id(picked),
                data.toString()
        ));
    }

    private static String resolveName(MinecraftServer server, UUID uuid) {
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) return online.getName().getString();
            var cache = server.getProfileCache();
            if (cache != null) return cache.get(uuid).map(GameProfile::getName).orElse(uuid.toString());
        }
        return uuid.toString();
    }
}
