// java
package cn.alini.craftaudit.config;

import cn.alini.craftaudit.Craftaudit;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.commons.lang3.tuple.Pair;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Config {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    static {
        Pair<Common, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        COMMON_SPEC = pair.getRight();
    }

    public enum DbMode { SQLITE, MYSQL }

    public static boolean isSqlite() { return COMMON.dbMode.get() == DbMode.SQLITE; }
    public static boolean isMySql() { return COMMON.dbMode.get() == DbMode.MYSQL; }

    // SQLite: 解析到游戏目录
    public static Path sqliteDbPath() {
        // 获取游戏根目录
        Path baseDir = FMLPaths.GAMEDIR.get();
        // 构造 modid 文件夹路径
        Path modDir = baseDir.resolve(Craftaudit.MODID);
        // 自动创建 modid 文件夹
        try {
            Files.createDirectories(modDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // 数据库文件路径（如 craftaudit.db）
        return modDir.resolve(COMMON.sqlitePath.get());
    }

    // MySQL: 生成 JDBC URL
    public static String mysqlJdbcUrl() {
        String host = COMMON.mysqlHost.get();
        int port = COMMON.mysqlPort.get();
        String db = COMMON.mysqlDatabase.get();
        boolean ssl = COMMON.mysqlUseSSL.get();
        String params = COMMON.mysqlParams.get();

        String base = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=" + ssl
                + "&characterEncoding=utf8"
                + "&serverTimezone=UTC"
                + "&rewriteBatchedStatements=true"
                + "&allowLoadLocalInfile=false"
                + "&autoDeserialize=false";
        String safeParams = filterJdbcParams(params);
        if (!safeParams.isEmpty()) {
            return base + "&" + safeParams;
        }
        return base;
    }

    // 可读取本地文件、反序列化或加载任意类的 JDBC 参数，禁止通过配置开启
    private static final Set<String> BLOCKED_JDBC_PARAMS = Set.of(
            "allowloadlocalinfile", "allowlocalinfile", "allowurlinlocalinfile", "allowloadlocalinfileinpath",
            "autodeserialize", "queryinterceptors", "statementinterceptors", "exceptioninterceptors",
            "connectionlifecycleinterceptors", "socketfactory", "propertiestransform",
            "authenticationplugins", "defaultauthenticationplugin", "clientinfoprovider"
    );

    static String filterJdbcParams(String params) {
        if (params == null || params.isBlank()) return "";
        List<String> kept = new ArrayList<>();
        for (String pair : params.split("&")) {
            if (pair.isBlank()) continue;
            String key = pair.split("=", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (BLOCKED_JDBC_PARAMS.contains(key)) {
                LOGGER.warn("[craftaudit] 已忽略不安全的 JDBC 参数: {}", key);
                continue;
            }
            kept.add(pair.trim());
        }
        return String.join("&", kept);
    }

    public static String mysqlUser() { return COMMON.mysqlUser.get(); }

    // 优先读取环境变量，避免在配置文件中明文保存密码
    public static String mysqlPassword() {
        String env = System.getenv(MYSQL_PASSWORD_ENV);
        if (env != null && !env.isEmpty()) return env;
        return COMMON.mysqlPassword.get();
    }

    public static final String MYSQL_PASSWORD_ENV = "CRAFTAUDIT_MYSQL_PASSWORD";

    public static final class Common {
        public final ForgeConfigSpec.EnumValue<DbMode> dbMode;
        public final ForgeConfigSpec.BooleanValue auditRequirePermission;
        public final ForgeConfigSpec.BooleanValue nearRequirePermission;

        // SQLite
        public final ForgeConfigSpec.ConfigValue<String> sqlitePath;

        // MySQL
        public final ForgeConfigSpec.ConfigValue<String> mysqlHost;
        public final ForgeConfigSpec.IntValue mysqlPort;
        public final ForgeConfigSpec.ConfigValue<String> mysqlDatabase;
        public final ForgeConfigSpec.ConfigValue<String> mysqlUser;
        public final ForgeConfigSpec.ConfigValue<String> mysqlPassword;
        public final ForgeConfigSpec.BooleanValue mysqlUseSSL;
        public final ForgeConfigSpec.ConfigValue<String> mysqlParams;

        private Common(ForgeConfigSpec.Builder builder) {
            builder.push("database");

            dbMode = builder
                    .comment("数据库模式: SQLITE 或 MYSQL")
                    .defineEnum("mode", DbMode.SQLITE);


            builder.push("sqlite");
            sqlitePath = builder
                    .comment("SQLite 文件名（存于游戏根目录下的craftaudit文件夹）")
                    .define("path", "craftaudit.db");
            builder.pop();

            builder.push("mysql");
            mysqlHost = builder.define("host", "127.0.0.1");
            mysqlPort = builder.defineInRange("port", 3306, 1, 65535);
            mysqlDatabase = builder.define("database", "craftaudit");
            mysqlUser = builder.define("user", "root");
            mysqlPassword = builder.comment("注意: 明文存储，请谨慎使用公共环境。设置环境变量 " + MYSQL_PASSWORD_ENV + " 时优先使用环境变量")
                    .define("password", "");
            mysqlUseSSL = builder.define("useSSL", false);
            mysqlParams = builder.comment("附加 JDBC 参数，形如 `allowPublicKeyRetrieval=true`（不安全的参数如 allowLoadLocalInfile、autoDeserialize 会被忽略）")
                    .define("params", "");
            builder.pop();
            builder.push("audit");
            auditRequirePermission = builder
                    .comment("审计模式命令是否需要权限，包含 ca i 审计模式、ca log、ca blocklog")
                    .define("requirePermission", true);
            nearRequirePermission = builder
                    .comment("ca near 范围查询是否需要权限（可暴露他人坐标，建议保持 true）")
                    .define("nearRequirePermission", true);
            builder.pop();

            builder.pop();
        }
    }
}
