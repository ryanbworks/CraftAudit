package cn.alini.craftaudit.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    // 丢出该物品的玩家 UUID（自然掉落为 null）
    @Accessor("thrower")
    UUID craftaudit$getThrower();
}
