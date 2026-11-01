package cn.alini.craftaudit.mixin;

import cn.alini.craftaudit.event.container.ContainerSessionTracker;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import cn.alini.craftaudit.util.ItemData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(AbstractContainerMenu.class)
public class AbstractContainerMenuMixin {

    @Inject(method = "removed", at = @At("HEAD"))
    private void onRemoved(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        AbstractContainerMenu menu = (AbstractContainerMenu)(Object)this;

        ContainerSessionTracker.Session session = ContainerSessionTracker.takeSession(menu);
        if (session == null) return;

        List<ItemStack> after = ContainerSessionTracker.snapshotContainerSlots(menu);
        List<ContainerSessionTracker.ItemDelta> diff = ContainerSessionTracker.compare(session.openSnapshot, after);
        if (diff.isEmpty()) return;

        BlockPos bp = session.pos;
        String dim = session.dimension;
        String target = session.target;

        for (ContainerSessionTracker.ItemDelta d : diff) {
            int delta = d.delta();
            String action = delta > 0 ? "put" : "take";
            int absCount = Math.abs(delta);

            Database.log(new LogEntry(
                    System.currentTimeMillis(),
                    dim,
                    bp.getX(), bp.getY(), bp.getZ(),
                    serverPlayer.getName().getString(),
                    serverPlayer.getUUID().toString(),
                    action,
                    target,
                    ItemData.toJson(d.sample(), absCount).toString()
            ));
        }
    }
}