package cn.alini.craftaudit.mixin;

import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 告示牌真正提交编辑时记录（原先在右键时记录，只能拿到编辑前的文本）。
 * HEAD 记下旧文本，TAIL 比较，只有文本确实变化才写日志。
 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {

    @Unique
    private String craftaudit$textBefore;

    @Inject(method = "updateSignText", at = @At("HEAD"))
    private void craftaudit$beforeUpdate(Player player, boolean front, List<FilteredText> lines, CallbackInfo ci) {
        craftaudit$textBefore = craftaudit$describe((SignBlockEntity) (Object) this);
    }

    @Inject(method = "updateSignText", at = @At("TAIL"))
    private void craftaudit$afterUpdate(Player player, boolean front, List<FilteredText> lines, CallbackInfo ci) {
        String before = craftaudit$textBefore;
        craftaudit$textBefore = null;
        if (!(player instanceof ServerPlayer sp)) return;

        SignBlockEntity sign = (SignBlockEntity) (Object) this;
        String after = craftaudit$describe(sign);
        if (after.equals(before)) return;

        Level level = sign.getLevel();
        if (level == null) return;
        BlockPos pos = sign.getBlockPos();
        Database.log(new LogEntry(
                System.currentTimeMillis(),
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                sp.getName().getString(),
                sp.getUUID().toString(),
                "sign_edit",
                ForgeRegistries.BLOCKS.getKey(sign.getBlockState().getBlock()).toString(),
                after
        ));
    }

    @Unique
    private static String craftaudit$describe(SignBlockEntity sign) {
        return "Front: " + craftaudit$join(sign.getFrontText(), sign.isWaxed())
                + "; Back: " + craftaudit$join(sign.getBackText(), sign.isWaxed());
    }

    @Unique
    private static String craftaudit$join(SignText text, boolean filtered) {
        return Arrays.stream(text.getMessages(filtered))
                .map(Component::getString)
                .collect(Collectors.joining(" | "));
    }
}
