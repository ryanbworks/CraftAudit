package cn.alini.craftaudit.storage;

import cn.alini.craftaudit.config.Config;
import cn.alini.craftaudit.util.ItemData;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.IOException;
import java.nio.file.Files;

public final class Database {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Database INSTANCE;

    private enum Dialect { SQLITE, MYSQL }

    private final Connection conn;
    private final Dialect dialect;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "craftaudit-sql-writer");
        t.setDaemon(true);
        return t;
    });

    // 这些动作的 data 中带 item 字段，写入时同步到 item 列以便按物品索引查询
    private static final Set<String> ITEM_ACTIONS = Set.of("put", "take", "drop", "transfer");

    private Database(Connection conn, Dialect dialect) throws SQLException {
        this.conn = conn;
        this.dialect = dialect;
        ensureSchema();
        writer.execute(this::backfillItemColumn);
    }

    public static synchronized void init(Path sqliteDefaultPath) throws SQLException {
        if (INSTANCE != null) return;

        if (Config.isSqlite()) {
            Path dbPath = Config.sqliteDbPath() != null ? Config.sqliteDbPath() : sqliteDefaultPath;
            try {
                Files.createDirectories(dbPath.getParent());
            } catch (IOException e) {
                LOGGER.error("创建数据库目录失败", e);
            }
            String url = "jdbc:sqlite:" + dbPath;
            LOGGER.info("[craftaudit] 使用 SQLite: {}", dbPath);
            Connection c = DriverManager.getConnection(url);
            INSTANCE = new Database(c, Dialect.SQLITE);
        } else {
            String url = Config.mysqlJdbcUrl();
            String user = Config.mysqlUser();
            String pass = Config.mysqlPassword();
            LOGGER.info("[craftaudit] 使用 MySQL: {}", url);
            Connection c = DriverManager.getConnection(url, user, pass);
            INSTANCE = new Database(c, Dialect.MYSQL);
        }
    }

    public static Database get() { return INSTANCE; }
    public static boolean isConnected() { return INSTANCE != null; }

    // 数据库未连接时直接丢弃，避免各监听器 NPE
    public static void log(LogEntry e) {
        Database db = INSTANCE;
        if (db != null) db.insertAsync(e);
    }

    public boolean ping() {
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            LOGGER.warn("[craftaudit] 数据库 ping 失败", e);
            return false;
        }
    }

    private void ensureSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (dialect == Dialect.SQLITE) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS logs(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      time_ms INTEGER NOT NULL,
                      dimension TEXT NOT NULL,
                      x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                      player TEXT,
                      uuid TEXT,
                      action TEXT NOT NULL,
                      target TEXT,
                      data TEXT,
                      item TEXT
                    )
                """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_logs_pos ON logs(dimension, x, y, z)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_logs_time ON logs(time_ms)");
                // 兼容老表：尝试补列
                tryExec(st, "ALTER TABLE logs ADD COLUMN uuid TEXT");
                tryExec(st, "ALTER TABLE logs ADD COLUMN item TEXT");
                st.execute("CREATE INDEX IF NOT EXISTS idx_logs_item ON logs(item, time_ms)");
            } else {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS logs(
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      time_ms BIGINT NOT NULL,
                      dimension VARCHAR(128) NOT NULL,
                      x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
                      player VARCHAR(64),
                      uuid VARCHAR(36),
                      action VARCHAR(32) NOT NULL,
                      target VARCHAR(191),
                      data TEXT,
                      item VARCHAR(191)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
                tryExec(st, "CREATE INDEX idx_logs_pos ON logs(dimension, x, y, z)");
                tryExec(st, "CREATE INDEX idx_logs_time ON logs(time_ms)");
                // 兼容老表：尝试补列
                tryExec(st, "ALTER TABLE logs ADD COLUMN uuid VARCHAR(36) NULL");
                tryExec(st, "ALTER TABLE logs ADD COLUMN item VARCHAR(191) NULL");
                tryExec(st, "CREATE INDEX idx_logs_item ON logs(item, time_ms)");
            }
            CaseRepository.ensureSchema(st, dialect == Dialect.SQLITE);
        }
    }

    static void tryExec(Statement st, String sql) {
        try { st.execute(sql); } catch (SQLException ignored) { }
    }

    Connection connection() { return conn; }

    // 案件等低频写操作：放到写线程串行执行并等待结果，避免与异步日志写入、补列事务交叉
    <T> T writeSync(Callable<T> task) throws SQLException {
        try {
            return writer.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("写入被中断", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SQLException se) throw se;
            throw new SQLException(e.getCause());
        }
    }

    public void insertAsync(LogEntry e) {
        writer.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO logs(time_ms, dimension, x, y, z, player, uuid, action, target, data, item) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, e.timeMillis());
                ps.setString(2, e.dimension());
                ps.setInt(3, e.x());
                ps.setInt(4, e.y());
                ps.setInt(5, e.z());
                ps.setString(6, e.player());
                ps.setString(7, e.playerUuid());
                ps.setString(8, e.action());
                ps.setString(9, e.target());
                ps.setString(10, e.data());
                ps.setString(11, ITEM_ACTIONS.contains(e.action()) ? ItemData.itemOf(e.data()) : null);
                ps.executeUpdate();
            } catch (SQLException ex) {
                LOGGER.error("写入日志失败", ex);
            }
        });
    }

    /**
     * 旧版本的 put/take 记录没有 item 列：启动时在写线程中按 id 分批补齐（只会处理一次，之后查询直接走索引）
     */
    private void backfillItemColumn() {
        String select = "SELECT id, data FROM logs WHERE item IS NULL AND action IN ('put','take') AND id > ? ORDER BY id LIMIT 1000";
        long lastId = 0;
        int total = 0;
        try (PreparedStatement sel = conn.prepareStatement(select);
             PreparedStatement upd = conn.prepareStatement("UPDATE logs SET item=? WHERE id=?")) {
            while (true) {
                int seen = 0;
                int batched = 0;
                sel.setLong(1, lastId);
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) {
                        seen++;
                        lastId = rs.getLong(1);
                        String item = ItemData.itemOf(rs.getString(2));
                        if (item == null) continue; // 无法解析的旧数据跳过，id 游标保证不会死循环
                        upd.setString(1, item);
                        upd.setLong(2, lastId);
                        upd.addBatch();
                        batched++;
                    }
                }
                if (seen == 0) break;
                if (batched == 0) continue;

                conn.setAutoCommit(false);
                try {
                    upd.executeBatch();
                    conn.commit();
                    total += batched;
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
            if (total > 0) LOGGER.info("[craftaudit] 已为 {} 条旧容器记录补齐 item 列", total);
        } catch (SQLException e) {
            LOGGER.warn("[craftaudit] 补齐 item 列失败（不影响新记录）", e);
        }
    }

    public List<LogEntry> queryLogsAtPaged(String dimension, int x, int y, int z, String actionPattern, int page, int pageSize) {
        List<LogEntry> result = new ArrayList<>();
        int offset = (page - 1) * pageSize;
        String[] actions = actionPattern.split("\\|");

        StringBuilder actionsPlaceholder = new StringBuilder();
        for (int i = 0; i < actions.length; i++) {
            if (i > 0) actionsPlaceholder.append(",");
            actionsPlaceholder.append("?");
        }
        String sql = "SELECT time_ms, dimension, x, y, z, player, uuid, action, target, data " +
                "FROM logs WHERE dimension=? AND x=? AND y=? AND z=? AND action IN (" + actionsPlaceholder + ") " +
                "ORDER BY time_ms DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            for (int i = 0; i < actions.length; i++) {
                ps.setString(5 + i, actions[i]);
            }
            ps.setInt(5 + actions.length, pageSize);
            ps.setInt(6 + actions.length, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LogEntry(
                            rs.getLong("time_ms"),
                            rs.getString("dimension"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("player"),
                            rs.getString("uuid"),
                            rs.getString("action"),
                            rs.getString("target"),
                            rs.getString("data")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("查询日志失败", e);
        }
        return result;
    }

    public int countLogsAt(String dimension, int x, int y, int z, String actionPattern) {
        String[] actions = actionPattern.split("\\|");
        StringBuilder actionsPlaceholder = new StringBuilder();
        for (int i = 0; i < actions.length; i++) {
            if (i > 0) actionsPlaceholder.append(",");
            actionsPlaceholder.append("?");
        }
        String sql = "SELECT COUNT(*) FROM logs WHERE dimension=? AND x=? AND y=? AND z=? AND action IN (" + actionsPlaceholder + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            for (int i = 0; i < actions.length; i++) {
                ps.setString(5 + i, actions[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.error("统计日志失败", e);
        }
        return 0;
    }

    // 半径 + 时间范围查询
    public List<LogEntry> queryLogsNearPaged(String dimension, int cx, int cy, int cz, int radius, long sinceMs, int page, int pageSize) {
        List<LogEntry> result = new ArrayList<>();
        int offset = (page - 1) * pageSize;

        int minX = cx - radius, maxX = cx + radius;
        int minY = cy - radius, maxY = cy + radius;
        int minZ = cz - radius, maxZ = cz + radius;

        String sql = "SELECT time_ms, dimension, x, y, z, player, uuid, action, target, data " +
                "FROM logs WHERE dimension=? AND time_ms>=? " +
                "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? " +
                "ORDER BY time_ms DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setLong(2, sinceMs);
            ps.setInt(3, minX); ps.setInt(4, maxX);
            ps.setInt(5, minY); ps.setInt(6, maxY);
            ps.setInt(7, minZ); ps.setInt(8, maxZ);
            ps.setInt(9, pageSize);
            ps.setInt(10, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LogEntry(
                            rs.getLong("time_ms"),
                            rs.getString("dimension"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("player"),
                            rs.getString("uuid"),
                            rs.getString("action"),
                            rs.getString("target"),
                            rs.getString("data")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("半径查询失败", e);
        }
        return result;
    }

    public int countLogsNear(String dimension, int cx, int cy, int cz, int radius, long sinceMs) {
        int minX = cx - radius, maxX = cx + radius;
        int minY = cy - radius, maxY = cy + radius;
        int minZ = cz - radius, maxZ = cz + radius;

        String sql = "SELECT COUNT(*) FROM logs WHERE dimension=? AND time_ms>=? " +
                "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setLong(2, sinceMs);
            ps.setInt(3, minX); ps.setInt(4, maxX);
            ps.setInt(5, minY); ps.setInt(6, maxY);
            ps.setInt(7, minZ); ps.setInt(8, maxZ);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.error("统计半径查询失败", e);
        }
        return 0;
    }

    // 区域 + 时间（可按玩家过滤）
    public List<LogEntry> queryLogsRegionSince(String dimension,
                                               int minX, int maxX,
                                               int minY, int maxY,
                                               int minZ, int maxZ,
                                               long sinceMs,
                                               List<String> actions,
                                               String player /* 可为 null */) {
        List<LogEntry> result = new ArrayList<>();
        if (actions == null || actions.isEmpty()) return result;

        StringBuilder in = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) in.append(",");
            in.append("?");
        }
        String sql = "SELECT time_ms, dimension, x, y, z, player, uuid, action, target, data " +
                "FROM logs WHERE dimension=? AND time_ms>=? " +
                "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? " +
                "AND action IN (" + in + ") ";
        if (player != null) sql += "AND player=? ";
        sql += "ORDER BY time_ms DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, dimension);
            ps.setLong(idx++, sinceMs);
            ps.setInt(idx++, minX); ps.setInt(idx++, maxX);
            ps.setInt(idx++, minY); ps.setInt(idx++, maxY);
            ps.setInt(idx++, minZ); ps.setInt(idx++, maxZ);
            for (String a : actions) ps.setString(idx++, a);
            if (player != null) ps.setString(idx++, player);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LogEntry(
                            rs.getLong("time_ms"),
                            rs.getString("dimension"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("player"),
                            rs.getString("uuid"),
                            rs.getString("action"),
                            rs.getString("target"),
                            rs.getString("data")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("区域时间查询失败", e);
        }
        return result;
    }

    // ===== 新增：按玩家（名字或UUID）+ 时间 查询/统计（全维度、全坐标，时间倒序，分页） =====

    public List<LogEntry> queryLogsByActorSince(String playerNameOrNull, String uuidOrNull, long sinceMs, int page, int pageSize) {
        List<LogEntry> result = new ArrayList<>();
        if ((playerNameOrNull == null || playerNameOrNull.isBlank()) && (uuidOrNull == null || uuidOrNull.isBlank())) {
            return result;
        }
        int offset = (page - 1) * pageSize;

        StringBuilder sql = new StringBuilder(
                "SELECT time_ms, dimension, x, y, z, player, uuid, action, target, data FROM logs WHERE time_ms>=?"
        );
        if (playerNameOrNull != null && !playerNameOrNull.isBlank() && uuidOrNull != null && !uuidOrNull.isBlank()) {
            sql.append(" AND (player=? OR uuid=?)");
        } else if (playerNameOrNull != null && !playerNameOrNull.isBlank()) {
            sql.append(" AND player=?");
        } else {
            sql.append(" AND uuid=?");
        }
        sql.append(" ORDER BY time_ms DESC LIMIT ? OFFSET ?");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, sinceMs);
            if (playerNameOrNull != null && !playerNameOrNull.isBlank() && uuidOrNull != null && !uuidOrNull.isBlank()) {
                ps.setString(idx++, playerNameOrNull);
                ps.setString(idx++, uuidOrNull);
            } else if (playerNameOrNull != null && !playerNameOrNull.isBlank()) {
                ps.setString(idx++, playerNameOrNull);
            } else {
                ps.setString(idx++, uuidOrNull);
            }
            ps.setInt(idx++, pageSize);
            ps.setInt(idx, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LogEntry(
                            rs.getLong("time_ms"),
                            rs.getString("dimension"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("player"),
                            rs.getString("uuid"),
                            rs.getString("action"),
                            rs.getString("target"),
                            rs.getString("data")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("按玩家查询日志失败", e);
        }
        return result;
    }

    public int countLogsByActorSince(String playerNameOrNull, String uuidOrNull, long sinceMs) {
        if ((playerNameOrNull == null || playerNameOrNull.isBlank()) && (uuidOrNull == null || uuidOrNull.isBlank())) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM logs WHERE time_ms>=?");
        if (playerNameOrNull != null && !playerNameOrNull.isBlank() && uuidOrNull != null && !uuidOrNull.isBlank()) {
            sql.append(" AND (player=? OR uuid=?)");
        } else if (playerNameOrNull != null && !playerNameOrNull.isBlank()) {
            sql.append(" AND player=?");
        } else {
            sql.append(" AND uuid=?");
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, sinceMs);
            if (playerNameOrNull != null && !playerNameOrNull.isBlank() && uuidOrNull != null && !uuidOrNull.isBlank()) {
                ps.setString(idx++, playerNameOrNull);
                ps.setString(idx, uuidOrNull);
            } else if (playerNameOrNull != null && !playerNameOrNull.isBlank()) {
                ps.setString(idx, playerNameOrNull);
            } else {
                ps.setString(idx, uuidOrNull);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.error("统计按玩家日志失败", e);
        }
        return 0;
    }
    // 按物品查询（put/take/drop/transfer），可按玩家名或 UUID 过滤，时间倒序；limit 防止一次取出过多
    public List<LogEntry> queryItemLogsSince(String itemId, long sinceMs, String playerNameOrNull, String uuidOrNull, int limit) {
        List<LogEntry> result = new ArrayList<>();
        boolean hasName = playerNameOrNull != null && !playerNameOrNull.isBlank();
        boolean hasUuid = uuidOrNull != null && !uuidOrNull.isBlank();

        StringBuilder sql = new StringBuilder(
                "SELECT time_ms, dimension, x, y, z, player, uuid, action, target, data FROM logs WHERE item=? AND time_ms>=?");
        if (hasName && hasUuid) sql.append(" AND (player=? OR uuid=?)");
        else if (hasName) sql.append(" AND player=?");
        else if (hasUuid) sql.append(" AND uuid=?");
        sql.append(" ORDER BY time_ms DESC LIMIT ?");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, itemId);
            ps.setLong(idx++, sinceMs);
            if (hasName) ps.setString(idx++, playerNameOrNull);
            if (hasUuid) ps.setString(idx++, uuidOrNull);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LogEntry(
                            rs.getLong("time_ms"),
                            rs.getString("dimension"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("player"),
                            rs.getString("uuid"),
                            rs.getString("action"),
                            rs.getString("target"),
                            rs.getString("data")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("按物品查询日志失败", e);
        }
        return result;
    }

    // Database.java 中添加（或恢复）：
    public int deleteLogsBefore(long beforeMs) {
        String sql = "DELETE FROM logs WHERE time_ms < ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, beforeMs);
            int affected = ps.executeUpdate();

            // 可选：SQLite 做一次 VACUUM 回收空间（可能耗时）
            if (affected > 0 && dialect == Dialect.SQLITE) {
                try (Statement st = conn.createStatement()) {
                    st.execute("VACUUM");
                } catch (SQLException e) {
                    LOGGER.warn("SQLite VACUUM 失败：{}", e.toString());
                }
            }
            return affected;
        } catch (SQLException e) {
            LOGGER.error("清理日志失败", e);
            return -1;
        }
    }
    // 新增：根据玩家名查询最近一次记录的 UUID（可能为 null）
    public String findLastUuidByPlayerName(String playerName) {
        String sql = "SELECT uuid FROM logs WHERE player=? AND uuid IS NOT NULL ORDER BY time_ms DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            LOGGER.error("按玩家名查询最近UUID失败", e);
        }
        return null;
    }

    public void close() {
        writer.shutdown();
        try {
            conn.close();
            LOGGER.info("数据库连接已关闭");
        } catch (SQLException e) {
            LOGGER.error("关闭数据库连接失败", e);
        }
    }
}