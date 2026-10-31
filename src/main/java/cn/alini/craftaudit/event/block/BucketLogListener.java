package cn.alini.craftaudit.event.block;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Bucketable;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public class BucketLogListener {

    @SubscribeEvent
    public static void onBucketFill(PlayerInteractEvent.RightClickBlock event) {
        var p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack held = event.getItemStack();
        if (held.isEmpty() || held.getItem() != Items.BUCKET) return;

        Level level = player.level();
        BlockPos pos = event.getPos();
        BlockState st = level.getBlockState(pos);

        String fluidId = null;
        if (st.getBlock() instanceof LiquidBlock lb) {
            try {
                if (st.getValue(LiquidBlock.LEVEL) == 0) {
                    fluidId = ForgeRegistries.FLUIDS.getKey(lb.getFluid()).toString();
                }
            } catch (Exception ignored) {}
        } else if (st.getBlock() instanceof PowderSnowBlock) {
            fluidId = ForgeRegistries.BLOCKS.getKey(st.getBlock()).toString();
        }

        if (fluidId != null) {
            Database.log(new LogEntry(
                    System.currentTimeMillis(),
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    player.getName().getString(),
                    player.getUUID().toString(),
                    "bucket_fill",
                    fluidId,
                    ""
            ));
        }
    }

    @SubscribeEvent
    public static void onBucketEmpty(PlayerInteractEvent.RightClickBlock event) {
        var p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !(held.getItem() instanceof BucketItem)) return;
        if (held.getItem() == Items.BUCKET) return;

        String fluidId = ForgeRegistries.ITEMS.getKey(held.getItem()).toString();
        BlockPos placePos = event.getPos().relative(event.getFace());

        Database.log(new LogEntry(
                System.currentTimeMillis(),
                player.level().dimension().location().toString(),
                placePos.getX(), placePos.getY(), placePos.getZ(),
                player.getName().getString(),
                player.getUUID().toString(),
                "bucket_empty",
                fluidId,
                ""
        ));
    }

    @SubscribeEvent
    public static void onBucketCatch(PlayerInteractEvent.EntityInteractSpecific event) {
        var p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || held.getItem() != Items.BUCKET) return;

        Entity target = event.getTarget();
        if (!(target instanceof Bucketable)) return;

        BlockPos pos = target.blockPosition();
        String entId = ForgeRegistries.ENTITY_TYPES.getKey(target.getType()).toString();
        Database.log(new LogEntry(
                System.currentTimeMillis(),
                player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                player.getName().getString(),
                player.getUUID().toString(),
                "bucket_catch",
                entId,
                ""
        ));
    }

    @SubscribeEvent
    public static void onBucketMilk(PlayerInteractEvent.EntityInteractSpecific event) {
        var p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || held.getItem() != Items.BUCKET) return;

        Entity target = event.getTarget();
        if (!(target instanceof Cow || target instanceof Goat)) return;

        BlockPos pos = target.blockPosition();
        String entId = ForgeRegistries.ENTITY_TYPES.getKey(target.getType()).toString();
        Database.log(new LogEntry(
                System.currentTimeMillis(),
                player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                player.getName().getString(),
                player.getUUID().toString(),
                "bucket_milk",
                entId,
                ""
        ));
    }
}