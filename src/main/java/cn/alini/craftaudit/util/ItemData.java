package cn.alini.craftaudit.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * 物品日志数据：data 字段统一为 JSON
 * {"item":"minecraft:diamond_sword","count":1,"nbt":"<base64 压缩NBT>",
 *  "meta":{"name":"自定义名","ench":[{"id":"minecraft:sharpness","lvl":5}],"dur":[120,1561],"items":[{"id":"minecraft:diamond","n":64}]}}
 * nbt / meta 仅在物品带 NBT 时出现；旧记录只有 item / count，同样可解析。
 * meta 只存结构化数据（ID、数值），显示时再按查看者语言翻译。
 */
public final class ItemData {
    private static final int MAX_COMPRESSED_NBT_BYTES = 16 * 1024; // 与方块 NBT 上限一致
    private static final int MAX_CONTENT_ENTRIES = 64; // 收纳袋可能包含很多种物品

    private ItemData() {}

    public static String id(ItemStack stack) {
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? "unknown" : key.toString();
    }

    // 分组用：同 ID 但 NBT 不同（附魔、命名、潜影盒内容）的物品视为不同物品
    public static String key(ItemStack stack) {
        return stack.hasTag() ? id(stack) + stack.getTag() : id(stack);
    }

    public static JsonObject toJson(ItemStack stack, int count) {
        JsonObject o = new JsonObject();
        o.addProperty("item", id(stack));
        o.addProperty("count", count);
        if (stack.hasTag()) {
            String nbt = nbtBase64(stack.getTag());
            if (nbt != null) o.addProperty("nbt", nbt);
            JsonObject meta = meta(stack);
            if (meta.size() > 0) o.add("meta", meta);
        }
        return o;
    }

    private static String nbtBase64(CompoundTag tag) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            byte[] bytes = baos.toByteArray();
            if (bytes.length > MAX_COMPRESSED_NBT_BYTES) return null; // 过大只保留 meta
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception ignored) {
            return null;
        }
    }

    // 结构化摘要：自定义名称、附魔、耐久、容器物品（潜影盒/收纳袋）内容
    static JsonObject meta(ItemStack stack) {
        JsonObject meta = new JsonObject();
        if (stack.hasCustomHoverName()) {
            meta.addProperty("name", stack.getHoverName().getString());
        }
        Map<Enchantment, Integer> ench = EnchantmentHelper.getEnchantments(stack);
        if (!ench.isEmpty()) {
            JsonArray list = new JsonArray();
            for (Map.Entry<Enchantment, Integer> e : ench.entrySet()) {
                var key = ForgeRegistries.ENCHANTMENTS.getKey(e.getKey());
                if (key == null) continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", key.toString());
                o.addProperty("lvl", e.getValue());
                list.add(o);
            }
            meta.add("ench", list);
        }
        if (stack.isDamageableItem() && stack.isDamaged()) {
            JsonArray dur = new JsonArray();
            dur.add(stack.getMaxDamage() - stack.getDamageValue());
            dur.add(stack.getMaxDamage());
            meta.add("dur", dur);
        }
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            ListTag items = null;
            if (tag.contains("BlockEntityTag", Tag.TAG_COMPOUND)) {
                CompoundTag be = tag.getCompound("BlockEntityTag");
                if (be.contains("Items", Tag.TAG_LIST)) items = be.getList("Items", Tag.TAG_COMPOUND);
            } else if (tag.contains("Items", Tag.TAG_LIST)) {
                items = tag.getList("Items", Tag.TAG_COMPOUND);
            }
            if (items != null && !items.isEmpty()) {
                Map<String, Integer> contents = new LinkedHashMap<>();
                for (int i = 0; i < items.size(); i++) {
                    CompoundTag it = items.getCompound(i);
                    contents.merge(it.getString("id"), it.getInt("Count"), Integer::sum);
                }
                JsonArray list = new JsonArray();
                contents.entrySet().stream().limit(MAX_CONTENT_ENTRIES).forEach(e -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", e.getKey());
                    o.addProperty("n", e.getValue());
                    list.add(o);
                });
                meta.add("items", list);
            }
        }
        return meta;
    }

    /**
     * 按查看者语言生成 meta 的悬浮文本；附魔、物品名用可翻译组件交给客户端显示。
     * 没有 meta 时返回 null。
     */
    public static MutableComponent describe(JsonObject data, String lang) {
        if (data == null || !data.has("meta") || !data.get("meta").isJsonObject()) return null;
        JsonObject meta = data.getAsJsonObject("meta");
        List<MutableComponent> lines = new ArrayList<>();

        String name = getString(meta, "name");
        if (name != null) {
            lines.add(Component.literal(Lang.tr(lang, "craftaudit.meta.name"))
                    .append(Component.literal(name).withStyle(ChatFormatting.ITALIC)));
        }
        if (meta.has("ench") && meta.get("ench").isJsonArray()) {
            MutableComponent line = Component.literal(Lang.tr(lang, "craftaudit.meta.enchants"));
            boolean first = true;
            for (JsonElement el : meta.getAsJsonArray("ench")) {
                JsonObject o = el.getAsJsonObject();
                String id = getString(o, "id");
                int lvl = getInt(o, "lvl");
                ResourceLocation rl = id != null ? ResourceLocation.tryParse(id) : null;
                Enchantment e = rl != null && ForgeRegistries.ENCHANTMENTS.containsKey(rl) ? ForgeRegistries.ENCHANTMENTS.getValue(rl) : null;
                if (!first) line.append(Component.literal(", "));
                line.append(e != null ? e.getFullname(lvl).copy() : Component.literal(id + " " + lvl));
                first = false;
            }
            lines.add(line);
        }
        if (meta.has("dur") && meta.get("dur").isJsonArray() && meta.getAsJsonArray("dur").size() == 2) {
            JsonArray dur = meta.getAsJsonArray("dur");
            lines.add(Component.literal(Lang.tr(lang, "craftaudit.meta.durability", dur.get(0).getAsInt(), dur.get(1).getAsInt())));
        }
        if (meta.has("items") && meta.get("items").isJsonArray()) {
            MutableComponent line = Component.literal(Lang.tr(lang, "craftaudit.meta.contents"));
            boolean first = true;
            for (JsonElement el : meta.getAsJsonArray("items")) {
                JsonObject o = el.getAsJsonObject();
                if (!first) line.append(Component.literal(", "));
                line.append(NameLocalization.item(getString(o, "id")).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" x" + getInt(o, "n")));
                first = false;
            }
            lines.add(line);
        }
        if (lines.isEmpty()) return null;

        MutableComponent result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) result.append(Component.literal("\n"));
            result.append(lines.get(i));
        }
        return result;
    }

    public static JsonObject parse(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(data);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String getString(JsonObject o, String field) {
        if (o == null || !o.has(field) || o.get(field).isJsonNull()) return null;
        try {
            return o.get(field).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static int getInt(JsonObject o, String field) {
        if (o == null || !o.has(field)) return 0;
        try {
            return o.get(field).getAsInt();
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static String itemOf(String data) {
        return getString(parse(data), "item");
    }
}
