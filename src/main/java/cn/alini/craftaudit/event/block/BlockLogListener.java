package cn.alini.craftaudit.event.block;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;


@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public class BlockLogListener {

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        BlockState placed = event.getPlacedBlock();
        BlockPos pos = event.getPos();
        String targetId = ForgeRegistries.BLOCKS.getKey(placed.getBlock()).toString();

        Database.log(new LogEntry(
                System.currentTimeMillis(),
                player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                player.getName().getString(),
                player.getUUID().toString(),
                "place",
                targetId,
                ""
        ));
    }

    // 使用打火石点燃“状态型”方块（不生成火方块的情况）：营火、蜡烛/蜡烛蛋糕、TNT
    @SubscribeEvent
    public static void onUseFlintAndSteel(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (player == null || player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !(held.getItem() instanceof FlintAndSteelItem)) return;

        Level level = player.level();
        BlockPos pos = event.getPos();
        Block block = level.getBlockState(pos).getBlock();

        String action = "ignite";
        String targetId = null;

        if (block instanceof CampfireBlock) {
            targetId = ForgeRegistries.BLOCKS.getKey(block).toString();
        } else if (block instanceof CandleBlock || block instanceof CandleCakeBlock) {
            targetId = ForgeRegistries.BLOCKS.getKey(block).toString();
        } else if (block instanceof TntBlock) {
            targetId = ForgeRegistries.BLOCKS.getKey(block).toString();
        }

        if (targetId != null) {
            Database.log(new LogEntry(
                    System.currentTimeMillis(),
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    player.getName().getString(),
                    player.getUUID().toString(),
                    action,
                    targetId,
                    ""
            ));
        }
    }

    // 放置火（火或灵魂火）
    @SubscribeEvent
    public static void onFirePlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        BlockState placed = event.getPlacedBlock();
        Block block = placed.getBlock();
        if (!(block instanceof BaseFireBlock)) return;

        BlockPos pos = event.getPos();
        Database.log(new LogEntry(
                System.currentTimeMillis(),
                player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                player.getName().getString(),
                player.getUUID().toString(),
                "ignite",
                ForgeRegistries.BLOCKS.getKey(block).toString(),
                ""
        ));
    }

    // 特殊方块交互（按钮、拉杆、门等）
    @SubscribeEvent
    public static void onSpecialBlockUse(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (player == null || player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;

        var pos = event.getPos();
        var state = player.level().getBlockState(pos);
        var block = state.getBlock();
        String action = null;
        if (block.getDescriptionId().contains("button")) action = "button_press";
        else if (block.getDescriptionId().contains("lever")) action = "lever_pull";
        else if (block.getDescriptionId().contains("door")) action = "door_use";

        if (action != null) {
            Database.log(new LogEntry(
                    System.currentTimeMillis(),
                    player.level().dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    player.getName().getString(),
                    player.getUUID().toString(),
                    action,
                    ForgeRegistries.BLOCKS.getKey(block).toString(),
                    ""
            ));
        }
    }
}