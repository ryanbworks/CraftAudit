package cn.alini.craftaudit.mixin;

import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import cn.alini.craftaudit.util.NbtCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void craftaudit$onBlockBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player = ((ServerPlayerGameModeAccessor)(Object)this).craftaudit$getPlayer();
        Level world = player.level();
        BlockState state = world.getBlockState(pos);
        Database db = Database.get();
        if (db != null) {
            String blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
            String stateStr = NbtCodec.stateToString(state);
            String nbtB64 = NbtCodec.tryDumpBeNbtBase64(world, pos);
            String dataJson = NbtCodec.buildDataJson(stateStr, nbtB64);

            db.insertAsync(new LogEntry(
                    System.currentTimeMillis(),
                    world.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    player.getName().getString(),
                    player.getUUID().toString(),
                    "break",
                    blockId,
                    dataJson
            ));
        }
    }
}