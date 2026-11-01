package cn.alini.craftaudit.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 物品 / 方块 / 实体名称：返回可翻译组件，由玩家客户端按自己的语言显示（原版及客户端已安装的模组）。
 * 未注册的 ID（如已卸载模组）原样显示。
 */
public final class NameLocalization {

    private NameLocalization() {}

    public static MutableComponent item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null && ForgeRegistries.ITEMS.containsKey(rl)) {
            return Component.translatable(ForgeRegistries.ITEMS.getValue(rl).getDescriptionId());
        }
        return Component.literal(String.valueOf(id));
    }

    public static MutableComponent block(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null && ForgeRegistries.BLOCKS.containsKey(rl)) {
            return Component.translatable(ForgeRegistries.BLOCKS.getValue(rl).getDescriptionId());
        }
        return Component.literal(String.valueOf(id));
    }

    // 依次尝试 物品 → 方块 → 实体（击杀目标、桶装生物、流体方块等）
    public static MutableComponent any(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) {
            if (ForgeRegistries.ITEMS.containsKey(rl)) return item(id);
            if (ForgeRegistries.BLOCKS.containsKey(rl)) return block(id);
            if (ForgeRegistries.ENTITY_TYPES.containsKey(rl)) {
                return Component.translatable(ForgeRegistries.ENTITY_TYPES.getValue(rl).getDescriptionId());
            }
        }
        return Component.literal(String.valueOf(id));
    }
}
