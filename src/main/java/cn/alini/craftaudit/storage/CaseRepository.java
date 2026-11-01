package cn.alini.craftaudit.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 调查案件：cases（案件）、case_players（涉及玩家）、case_entries（时间线：备注/事件/地点/证据）。
 * 证据为日志记录的完整副本，/ca purge 清理日志后案件中的证据依然保留。
 * 事件以「翻译 key + 参数」保存，不同语言的管理员看到各自语言的时间线。
 * 所有写操作经 Database.writeSync 在写线程串行执行。
 */
public final class CaseRepository {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String OPEN = "open";
    public static final String CLOSED = "closed";

    public static final String NOTE = "note";
    public static final String EVENT = "event";
    public static final String LOCATION = "location";
    public static final String LOG = "log";

    private static final int MAX_TITLE = 100;

    private CaseRepository() {}

    public record CaseInfo(long id, String title, String status, long createdMs, String createdBy,
                           long closedMs, String closedBy, String conclusion,
                           int playerCount, int evidenceCount) {
        public boolean isOpen() { return OPEN.equals(status); }
    }

    public record CasePlayer(String player, String uuid, String role) {}

    public record CaseEntry(long timeMs, String author, String type,
                            String dimension, int x, int y, int z, String text, String data) {}

    static void ensureSchema(Statement st, boolean sqlite) throws SQLException {
        if (sqlite) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS cases(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  title TEXT NOT NULL,
                  status TEXT NOT NULL,
                  created_ms INTEGER NOT NULL,
                  created_by TEXT,
                  created_by_uuid TEXT,
                  closed_ms INTEGER,
                  closed_by TEXT,
                  conclusion TEXT
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS case_players(
                  case_id INTEGER NOT NULL,
                  player TEXT NOT NULL,
                  uuid TEXT,
                  role TEXT NOT NULL,
                  added_ms INTEGER NOT NULL,
                  added_by TEXT,
                  PRIMARY KEY(case_id, player)
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS case_entries(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  case_id INTEGER NOT NULL,
                  time_ms INTEGER NOT NULL,
                  author TEXT,
                  author_uuid TEXT,
                  type TEXT NOT NULL,
                  dimension TEXT,
                  x INTEGER, y INTEGER, z INTEGER,
                  text TEXT,
                  data TEXT
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_case_entries ON case_entries(case_id, type, time_ms)");
        } else {
            st.execute("""
                CREATE TABLE IF NOT EXISTS cases(
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  title VARCHAR(191) NOT NULL,
                  status VARCHAR(16) NOT NULL,
                  created_ms BIGINT NOT NULL,
                  created_by VARCHAR(64),
                  created_by_uuid VARCHAR(36),
                  closed_ms BIGINT NULL,
                  closed_by VARCHAR(64),
                  conclusion TEXT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS case_players(
                  case_id BIGINT NOT NULL,
                  player VARCHAR(64) NOT NULL,
                  uuid VARCHAR(36),
                  role VARCHAR(16) NOT NULL,
                  added_ms BIGINT NOT NULL,
                  added_by VARCHAR(64),
                  PRIMARY KEY(case_id, player)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS case_entries(
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  case_id BIGINT NOT NULL,
                  time_ms BIGINT NOT NULL,
                  author VARCHAR(64),
                  author_uuid VARCHAR(36),
                  type VARCHAR(16) NOT NULL,
                  dimension VARCHAR(128),
                  x INT NULL, y INT NULL, z INT NULL,
                  text TEXT,
                  data TEXT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
            Database.tryExec(st, "CREATE INDEX idx_case_entries ON case_entries(case_id, type, time_ms)");
        }
    }

    // ===== 案件 =====

    public static long create(String title, String author, String authorUuid) {
        String t = title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) : title;
        try {
            return Database.get().writeSync(() -> {
                try (PreparedStatement ps = conn().prepareStatement(
                        "INSERT INTO cases(title, status, created_ms, created_by, created_by_uuid) VALUES (?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, t, OPEN, System.currentTimeMillis(), author, authorUuid);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        return rs.next() ? rs.getLong(1) : -1L;
                    }
                }
            });
        } catch (SQLException e) {
            LOGGER.error("创建案件失败", e);
            return -1;
        }
    }

    private static final String CASE_SELECT =
            "SELECT c.id, c.title, c.status, c.created_ms, c.created_by, c.closed_ms, c.closed_by, c.conclusion, " +
            "(SELECT COUNT(*) FROM case_players p WHERE p.case_id = c.id) AS player_count, " +
            "(SELECT COUNT(*) FROM case_entries e WHERE e.case_id = c.id AND e.type = 'log') AS evidence_count " +
            "FROM cases c ";

    public static CaseInfo find(long id) {
        try (PreparedStatement ps = conn().prepareStatement(CASE_SELECT + "WHERE c.id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readCase(rs) : null;
            }
        } catch (SQLException e) {
            LOGGER.error("查询案件失败", e);
            return null;
        }
    }

    // status 为 null 时列出全部，按编号倒序（最新在前）
    public static List<CaseInfo> list(String statusOrNull, int page, int pageSize) {
        List<CaseInfo> result = new ArrayList<>();
        String sql = CASE_SELECT + (statusOrNull != null ? "WHERE c.status=? " : "") + "ORDER BY c.id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            int idx = 1;
            if (statusOrNull != null) ps.setString(idx++, statusOrNull);
            ps.setInt(idx++, pageSize);
            ps.setInt(idx, (page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readCase(rs));
            }
        } catch (SQLException e) {
            LOGGER.error("列出案件失败", e);
        }
        return result;
    }

    public static int count(String statusOrNull) {
        String sql = "SELECT COUNT(*) FROM cases" + (statusOrNull != null ? " WHERE status=?" : "");
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            if (statusOrNull != null) ps.setString(1, statusOrNull);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOGGER.error("统计案件失败", e);
            return 0;
        }
    }

    public static boolean rename(long id, String title) {
        String t = title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) : title;
        return update("UPDATE cases SET title=? WHERE id=?", t, id);
    }

    public static boolean close(long id, String by, String conclusion) {
        return update("UPDATE cases SET status=?, closed_ms=?, closed_by=?, conclusion=? WHERE id=?",
                CLOSED, System.currentTimeMillis(), by, conclusion, id);
    }

    public static boolean reopen(long id) {
        return update("UPDATE cases SET status=?, closed_ms=NULL, closed_by=NULL, conclusion=NULL WHERE id=?", OPEN, id);
    }

    // ===== 涉及玩家 =====

    // 同一玩家重复添加时更新角色
    public static boolean setPlayer(long caseId, String player, String uuid, String role, String by) {
        return update("REPLACE INTO case_players(case_id, player, uuid, role, added_ms, added_by) VALUES (?,?,?,?,?,?)",
                caseId, player, uuid, role, System.currentTimeMillis(), by);
    }

    // 返回是否确实删除了记录
    public static boolean removePlayer(long caseId, String player) {
        try {
            return Database.get().writeSync(() -> {
                try (PreparedStatement ps = conn().prepareStatement("DELETE FROM case_players WHERE case_id=? AND player=?")) {
                    bind(ps, caseId, player);
                    return ps.executeUpdate() > 0;
                }
            });
        } catch (SQLException e) {
            LOGGER.error("移除案件玩家失败", e);
            return false;
        }
    }

    public static List<CasePlayer> players(long caseId) {
        List<CasePlayer> result = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT player, uuid, role FROM case_players WHERE case_id=? ORDER BY role, player")) {
            ps.setLong(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new CasePlayer(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            LOGGER.error("查询案件玩家失败", e);
        }
        return result;
    }

    // ===== 时间线 =====

    // 事件条目：text 为翻译 key，data 为参数 JSON 数组，显示时按查看者语言翻译
    public static boolean addEntry(long caseId, String author, String authorUuid, String type,
                                   String dimension, Integer x, Integer y, Integer z, String text, String data) {
        return update("INSERT INTO case_entries(case_id, time_ms, author, author_uuid, type, dimension, x, y, z, text, data) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                caseId, System.currentTimeMillis(), author, authorUuid, type, dimension, x, y, z, text, data);
    }

    /**
     * 附加证据：复制日志记录到案件，time_ms 使用日志原始时间；text 存去重键，同一条记录不会重复附加。
     * 返回新增条数，失败返回 -1。
     */
    public static int addLogs(long caseId, List<LogEntry> logs, String author, String authorUuid) {
        try {
            return Database.get().writeSync(() -> {
                Set<String> existing = new HashSet<>();
                try (PreparedStatement ps = conn().prepareStatement("SELECT text FROM case_entries WHERE case_id=? AND type=?")) {
                    bind(ps, caseId, LOG);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) existing.add(rs.getString(1));
                    }
                }
                int added = 0;
                try (PreparedStatement ins = conn().prepareStatement(
                        "INSERT INTO case_entries(case_id, time_ms, author, author_uuid, type, dimension, x, y, z, text, data) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (LogEntry e : logs) {
                        String key = logKey(e);
                        if (!existing.add(key)) continue;
                        bind(ins, caseId, e.timeMillis(), author, authorUuid, LOG,
                                e.dimension(), e.x(), e.y(), e.z(), key, toJson(e));
                        ins.addBatch();
                        added++;
                    }
                    if (added > 0) ins.executeBatch();
                }
                return added;
            });
        } catch (SQLException e) {
            LOGGER.error("附加案件证据失败", e);
            return -1;
        }
    }

    // 按时间正序分页
    public static List<CaseEntry> entries(long caseId, List<String> types, int page, int pageSize) {
        List<CaseEntry> result = new ArrayList<>();
        String sql = "SELECT time_ms, author, type, dimension, x, y, z, text, data FROM case_entries " +
                "WHERE case_id=? AND type IN (" + placeholders(types.size()) + ") ORDER BY time_ms ASC, id ASC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            int idx = 1;
            ps.setLong(idx++, caseId);
            for (String t : types) ps.setString(idx++, t);
            ps.setInt(idx++, pageSize);
            ps.setInt(idx, (page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new CaseEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                            rs.getString(8), rs.getString(9)));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("查询案件时间线失败", e);
        }
        return result;
    }

    public static int countEntries(long caseId, List<String> types) {
        String sql = "SELECT COUNT(*) FROM case_entries WHERE case_id=? AND type IN (" + placeholders(types.size()) + ")";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            int idx = 1;
            ps.setLong(idx++, caseId);
            for (String t : types) ps.setString(idx++, t);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOGGER.error("统计案件时间线失败", e);
            return 0;
        }
    }

    // 证据条目还原为日志记录，用于复用日志渲染
    public static LogEntry toLogEntry(CaseEntry entry) {
        if (entry.data() == null) return null;
        try {
            JsonObject o = JsonParser.parseString(entry.data()).getAsJsonObject();
            return new LogEntry(
                    o.get("time").getAsLong(),
                    str(o, "dimension"),
                    o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                    str(o, "player"),
                    str(o, "uuid"),
                    str(o, "action"),
                    str(o, "target"),
                    str(o, "data"));
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 工具 =====

    private static Connection conn() { return Database.get().connection(); }

    private static CaseInfo readCase(ResultSet rs) throws SQLException {
        return new CaseInfo(rs.getLong("id"), rs.getString("title"), rs.getString("status"),
                rs.getLong("created_ms"), rs.getString("created_by"),
                rs.getLong("closed_ms"), rs.getString("closed_by"), rs.getString("conclusion"),
                rs.getInt("player_count"), rs.getInt("evidence_count"));
    }

    private static boolean update(String sql, Object... params) {
        try {
            return Database.get().writeSync(() -> {
                try (PreparedStatement ps = conn().prepareStatement(sql)) {
                    bind(ps, params);
                    ps.executeUpdate();
                    return true;
                }
            });
        } catch (SQLException e) {
            LOGGER.error("案件写入失败: {}", sql, e);
            return false;
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) ps.setNull(i + 1, Types.VARCHAR);
            else if (p instanceof Long l) ps.setLong(i + 1, l);
            else if (p instanceof Integer n) ps.setInt(i + 1, n);
            else ps.setString(i + 1, p.toString());
        }
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static String logKey(LogEntry e) {
        return e.timeMillis() + "|" + e.dimension() + "|" + e.x() + "|" + e.y() + "|" + e.z() + "|"
                + e.player() + "|" + e.action() + "|" + e.target();
    }

    private static String toJson(LogEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("time", e.timeMillis());
        o.addProperty("dimension", e.dimension());
        o.addProperty("x", e.x());
        o.addProperty("y", e.y());
        o.addProperty("z", e.z());
        o.addProperty("player", e.player());
        o.addProperty("uuid", e.playerUuid());
        o.addProperty("action", e.action());
        o.addProperty("target", e.target());
        o.addProperty("data", e.data());
        return o.toString();
    }

    private static String str(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }
}
