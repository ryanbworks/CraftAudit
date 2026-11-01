package cn.alini.craftaudit.util;

import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.config.Config;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 服务端翻译：本模组只装在服务端，客户端没有我们的语言文件，所以消息在服务端翻译后再发送。
 *
 * 1) 内置：jar 内 /assets/craftaudit/lang/<code>.json（zh_cn / en_us / pt_br）
 * 2) 覆盖/新增：<游戏目录>/craftaudit/lang/<code>.json，按 key 覆盖内置翻译，也可以添加新语言
 * 3) 选择：按玩家客户端语言（config language.perPlayer），没有对应翻译时用 config language.default
 * 4) 缺 key 时依次回退：玩家语言 → 默认语言 → en_us → zh_cn → key 本身
 *
 * 文本使用 String.format 占位符（%s、%d，也可以用 %1$s 调整顺序）。
 */
public final class Lang {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> BUNDLED = List.of("zh_cn", "en_us", "pt_br");
    private static final String FALLBACK = "en_us";
    private static final String LAST_RESORT = "zh_cn";

    private static volatile Map<String, Map<String, String>> tables = Map.of();

    private Lang() {}

    public static synchronized void reload() {
        Map<String, Map<String, String>> loaded = new HashMap<>();
        for (String code : BUNDLED) {
            try (InputStream in = Lang.class.getResourceAsStream("/assets/" + Craftaudit.MODID + "/lang/" + code + ".json")) {
                if (in == null) {
                    LOGGER.warn("[craftaudit] 缺少内置语言文件: {}", code);
                    continue;
                }
                loaded.put(code, read(new InputStreamReader(in, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                LOGGER.error("[craftaudit] 读取内置语言文件失败: {}", code, e);
            }
        }

        Path dir = FMLPaths.GAMEDIR.get().resolve(Craftaudit.MODID).resolve("lang");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                    String name = file.getFileName().toString();
                    String code = name.substring(0, name.length() - ".json".length()).toLowerCase(Locale.ROOT);
                    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        loaded.computeIfAbsent(code, k -> new HashMap<>()).putAll(read(r));
                        LOGGER.info("[craftaudit] 已加载自定义语言文件: {}", file);
                    } catch (Exception e) {
                        LOGGER.error("[craftaudit] 读取自定义语言文件失败: {}", file, e);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[craftaudit] 读取语言目录失败: {}", dir, e);
            }
        }
        tables = loaded;
    }

    public static Set<String> available() {
        return new TreeSet<>(tables().keySet());
    }

    private static Map<String, String> read(Reader reader) {
        JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getValue().isJsonPrimitive()) map.put(e.getKey(), e.getValue().getAsString());
        }
        return map;
    }

    private static Map<String, Map<String, String>> tables() {
        if (tables.isEmpty()) reload();
        return tables;
    }

    // ===== 语言选择 =====

    public static String langOf(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        return player != null ? langOf(player) : defaultLang();
    }

    public static String langOf(ServerPlayer player) {
        if (Config.COMMON.languagePerPlayer.get()) {
            String client = player.getLanguage();
            if (client != null) {
                String code = client.toLowerCase(Locale.ROOT);
                if (tables().containsKey(code)) return code;
            }
        }
        return defaultLang();
    }

    public static String defaultLang() {
        return Config.COMMON.language.get().toLowerCase(Locale.ROOT);
    }

    // ===== 翻译 =====

    public static String tr(String lang, String key, Object... args) {
        String pattern = lookup(lang, key);
        if (args.length == 0) return pattern;
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (IllegalFormatException e) {
            LOGGER.warn("[craftaudit] 翻译格式错误 {}/{}: {}", lang, key, e.toString());
            return pattern;
        }
    }

    public static String tr(CommandSourceStack src, String key, Object... args) {
        return tr(langOf(src), key, args);
    }

    public static String tr(ServerPlayer player, String key, Object... args) {
        return tr(langOf(player), key, args);
    }

    public static boolean has(String lang, String key) {
        Map<String, String> t = tables().get(lang);
        return t != null && t.containsKey(key);
    }

    private static String lookup(String lang, String key) {
        Map<String, Map<String, String>> all = tables();
        for (String code : new String[]{lang, defaultLang(), FALLBACK, LAST_RESORT}) {
            Map<String, String> t = all.get(code);
            if (t == null) continue;
            String v = t.get(key);
            if (v != null) return v;
        }
        return key;
    }
}
