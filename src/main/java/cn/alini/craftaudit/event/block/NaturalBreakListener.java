package cn.alini.craftaudit.event.block;

import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.TickEvent;

import java.util.*;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public class NaturalBreakListener {
    private static final Map<Explosion, ExplosionData> explosionCache = new HashMap<>();

    private static String getBlockKey(Level level, BlockPos pos) {
        return level.dimension().location().toString() + "/" + pos.getX() + "/" + pos.getY() + "/" + pos.getZ();
    }

    static class ExplosionData {
        Level level;
        Map<BlockPos, BlockState> beforeStates;

        ExplosionData(Level level, Map<BlockPos, BlockState> beforeStates) {
            this.level = level;
            this.beforeStates = beforeStates;
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) return;

        Map<BlockPos, BlockState> beforeStates = new HashMap<>();
        for (BlockPos pos : event.getAffectedBlocks()) {
            BlockState state = event.getLevel().getBlockState(pos);
            if (!state.isAir()) beforeStates.put(pos.immutable(), state);
        }

        explosionCache.put(event.getExplosion(), new ExplosionData(event.getLevel(), beforeStates));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (explosionCache.isEmpty()) return;
        // 仅在本 tick 内去重（多个爆炸波及同一方块时只记一次）；原先的全局集合永不清理，
        // 导致同一坐标第二次被炸时不再记录，且内存持续增长
        Set<String> loggedBlocks = new HashSet<>();
        Iterator<Map.Entry<Explosion, ExplosionData>> it = explosionCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Explosion, ExplosionData> entry = it.next();
            Explosion explosion = entry.getKey();
            ExplosionData data = entry.getValue();
            Level level = data.level;
            Map<BlockPos, BlockState> beforeStates = data.beforeStates;

            for (Map.Entry<BlockPos, BlockState> e : beforeStates.entrySet()) {
                BlockPos pos = e.getKey();
                BlockState before = e.getValue();
                BlockState after = level.getBlockState(pos);

                if (!after.isAir()) continue;

                String key = getBlockKey(level, pos);
                if (loggedBlocks.contains(key)) continue;
                loggedBlocks.add(key);

                String targetId = ForgeRegistries.BLOCKS.getKey(before.getBlock()) == null
                        ? "unknown"
                        : ForgeRegistries.BLOCKS.getKey(before.getBlock()).toString();

                Database.log(new LogEntry(
                        System.currentTimeMillis(),
                        level.dimension().location().toString(),
                        pos.getX(), pos.getY(), pos.getZ(),
                        "(爆炸)",
                        null, // 环境事件无玩家，UUID 为空
                        "natural_break",
                        targetId,
                        "{\"cause\":\"explosion\"}"
                ));
            }

            it.remove();
        }
    }

    @SubscribeEvent
    public static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        var levelAcc = event.getLevel();
        if (levelAcc.isClientSide()) return;

        BlockState oldSt = event.getOriginalState();
        if (oldSt.isAir()) return;

        if (!(oldSt.getBlock() instanceof LiquidBlock) && !(event.getNewState().getBlock() instanceof BaseFireBlock)) {
            String dim = (levelAcc instanceof Level lvl)
                    ? lvl.dimension().location().toString()
                    : "unknown";
            String targetId = ForgeRegistries.BLOCKS.getKey(oldSt.getBlock()).toString();
            BlockPos pos = event.getPos();
            Database.log(new LogEntry(
                    System.currentTimeMillis(),
                    dim,
                    pos.getX(), pos.getY(), pos.getZ(),
                    "(环境)",
                    null,
                    "natural_break",
                    targetId,
                    "{\"cause\":\"fluid\"}"
            ));
        }
    }

    @SubscribeEvent
    public static void onFallingSpawn(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof FallingBlockEntity falling)) return;
        var level = event.getLevel();
        if (level.isClientSide()) return;

        BlockState st = falling.getBlockState();
        String targetId = ForgeRegistries.BLOCKS.getKey(st.getBlock()).toString();
        BlockPos pos = falling.blockPosition();

        Database.log(new LogEntry(
                System.currentTimeMillis(),
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                "(环境)",
                null,
                "natural_break",
                targetId,
                "{\"cause\":\"gravity\"}"
        ));
    }
}