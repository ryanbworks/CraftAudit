package cn.alini.craftaudit.event.entity;

import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public class EntityDeathListener {

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;

        DamageSource source = event.getSource();
        Entity trueSrc = source.getEntity();
        if (trueSrc == null) return; // 只记录有击杀者的死亡（玩家或生物），环境死亡不记录
        Entity directSrc = source.getDirectEntity();

        String dim = victim.level().dimension().location().toString();
        BlockPos pos = victim.blockPosition();

        String actorName;
        String actorUuid = null;
        ServerPlayer killerPlayer = null;
        if (trueSrc instanceof ServerPlayer sp) {
            killerPlayer = sp;
            actorName = sp.getName().getString();
            actorUuid = sp.getUUID().toString();
        } else {
            actorName = ForgeRegistries.ENTITY_TYPES.getKey(trueSrc.getType()).toString();
        }

        String target;
        if (victim instanceof ServerPlayer vp) {
            target = "player:" + vp.getName().getString();
        } else {
            target = ForgeRegistries.ENTITY_TYPES.getKey(victim.getType()).toString();
        }

        String causeId = getCauseId(source);
        String projectileId = (directSrc != null && directSrc != trueSrc)
                ? ForgeRegistries.ENTITY_TYPES.getKey(directSrc.getType()).toString()
                : null;
        String weaponId = null;
        if (killerPlayer != null) {
            var main = killerPlayer.getMainHandItem();
            if (!main.isEmpty() && ForgeRegistries.ITEMS.getKey(main.getItem()) != null) {
                weaponId = ForgeRegistries.ITEMS.getKey(main.getItem()).toString();
            }
        }
        Double distance = (killerPlayer != null) ? (double)Math.round(killerPlayer.distanceTo(victim) * 10.0) / 10.0 : null;

        String dataJson = buildKillDataJson(causeId, projectileId, weaponId, distance);

        Database.log(new LogEntry(
                System.currentTimeMillis(),
                dim,
                pos.getX(), pos.getY(), pos.getZ(),
                actorName,
                actorUuid, // 可能为空（非玩家）
                "kill",
                target,
                dataJson
        ));
    }

    private static String getCauseId(DamageSource src) {
        try {
            return src.getMsgId();
        } catch (Throwable ignore) {
            return "unknown";
        }
    }

    private static String buildKillDataJson(String cause, String projectile, String weapon, Double distance) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        if (cause != null) {
            sb.append("\"cause\":\"").append(escape(cause)).append("\"");
            first = false;
        }
        if (projectile != null) {
            if (!first) sb.append(",");
            sb.append("\"projectile\":\"").append(escape(projectile)).append("\"");
            first = false;
        }
        if (weapon != null) {
            if (!first) sb.append(",");
            sb.append("\"weapon\":\"").append(escape(weapon)).append("\"");
            first = false;
        }
        if (distance != null) {
            if (!first) sb.append(",");
            sb.append("\"distance\":").append(distance);
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}