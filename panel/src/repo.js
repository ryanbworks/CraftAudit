import { pool, q, one } from './db.js';

const LOG_COLS = 'time_ms, dimension, x, y, z, player, uuid, action, target, data';
const UUID_RE = /^[0-9a-fA-F-]{32,36}$/;
export const ATTACH_LIMIT = 200;   // igual ao mod
export const ITEM_LIMIT = 5000;    // igual ao /ca item

export const ACTIONS = [
  'break', 'place', 'natural_break', 'put', 'take', 'drop', 'transfer', 'kill', 'sign_edit', 'ignite',
  'button_press', 'lever_pull', 'door_use', 'bucket_fill', 'bucket_empty', 'bucket_catch', 'bucket_milk',
];

// "2026-10-31T14:30" (input datetime-local) → ms
const toMs = (v) => (v ? new Date(v).getTime() : NaN);

/**
 * Monta o WHERE da busca de logs. Filtros: player (nome ou UUID), action, item, dim,
 * x/y/z + r (raio), from/to (período). Sem período, usa as últimas 24h.
 */
export function logFilter(input) {
  // parâmetros repetidos na URL viram lista; usa sempre o primeiro valor como texto
  const f = Object.fromEntries(Object.entries(input).map(([k, v]) => [k, String(Array.isArray(v) ? v[0] : v ?? '').trim()]));
  const where = [];
  const args = [];
  const from = toMs(f.from);
  const to = toMs(f.to);
  where.push('time_ms >= ?');
  args.push(Number.isFinite(from) ? from : Date.now() - 24 * 3600 * 1000);
  if (Number.isFinite(to)) { where.push('time_ms <= ?'); args.push(to); }
  if (f.player) { where.push('(player = ? OR uuid = ?)'); args.push(f.player, f.player); }
  if (f.action && ACTIONS.includes(f.action)) { where.push('action = ?'); args.push(f.action); }
  if (f.item) { where.push('item = ?'); args.push(f.item); }
  if (f.dim) { where.push('dimension = ?'); args.push(f.dim); }
  if (f.x && f.y && f.z) {
    const r = Math.min(Math.max(parseInt(f.r, 10) || 10, 1), 256);
    for (const axis of ['x', 'y', 'z']) {
      const c = parseInt(f[axis], 10);
      if (Number.isFinite(c)) { where.push(`${axis} BETWEEN ? AND ?`); args.push(c - r, c + r); }
    }
  }
  return { sql: where.join(' AND '), args };
}

export async function searchLogs(f, page, pageSize) {
  const { sql, args } = logFilter(f);
  const total = (await one(`SELECT COUNT(*) AS n FROM logs WHERE ${sql}`, args)).n;
  const rows = await q(`SELECT ${LOG_COLS} FROM logs WHERE ${sql} ORDER BY time_ms DESC LIMIT ? OFFSET ?`,
    [...args, pageSize, (page - 1) * pageSize]);
  return { total, rows };
}

export async function dimensions() {
  return (await q('SELECT DISTINCT dimension FROM logs ORDER BY dimension')).map((r) => r.dimension);
}

// Rastreio de item: mesma regra do /ca item (saldo = tirou + pegou - colocou - jogou)
export async function traceItem(item, f) {
  const { sql, args } = logFilter({ ...f, item, action: '' });
  const rows = await q(`SELECT ${LOG_COLS} FROM logs WHERE ${sql} AND action IN ('put','take','drop','transfer')
    ORDER BY time_ms DESC LIMIT ?`, [...args, ITEM_LIMIT]);
  const stats = new Map();
  for (const r of rows) {
    const key = r.uuid || r.player;
    if (!key) continue;
    const s = stats.get(key) || { player: r.player, take: 0, put: 0, transfer: 0, drop: 0 };
    let count = 0;
    try { count = JSON.parse(r.data).count || 0; } catch { /* registro antigo/inválido */ }
    s[r.action] += count;
    stats.set(key, s);
  }
  const summary = [...stats.values()]
    .map((s) => ({ ...s, net: s.take + s.transfer - s.put - s.drop }))
    .sort((a, b) => Math.abs(b.net) - Math.abs(a.net));
  return { rows, summary, truncated: rows.length >= ITEM_LIMIT };
}

export async function stats() {
  const dayAgo = Date.now() - 24 * 3600 * 1000;
  const [logs24h, openCases, closedCases] = await Promise.all([
    one('SELECT COUNT(*) AS n FROM logs WHERE time_ms >= ?', [dayAgo]),
    one("SELECT COUNT(*) AS n FROM cases WHERE status='open'"),
    one("SELECT COUNT(*) AS n FROM cases WHERE status='closed'"),
  ]);
  return { logs24h: logs24h.n, openCases: openCases.n, closedCases: closedCases.n };
}

// ===== Casos (mesmas tabelas e formatos do CaseRepository.java) =====

const CASE_SELECT = `SELECT c.*,
  (SELECT COUNT(*) FROM case_players p WHERE p.case_id = c.id) AS player_count,
  (SELECT COUNT(*) FROM case_entries e WHERE e.case_id = c.id AND e.type = 'log') AS evidence_count
  FROM cases c`;

export const caseNo = (id) => `#${String(id).padStart(3, '0')}`;

export async function listCases(status) {
  return status === 'all'
    ? q(`${CASE_SELECT} ORDER BY c.id DESC LIMIT 200`)
    : q(`${CASE_SELECT} WHERE c.status = ? ORDER BY c.id DESC LIMIT 200`, [status]);
}

export async function getCase(id) {
  const c = await one(`${CASE_SELECT} WHERE c.id = ?`, [id]);
  if (!c) return null;
  const [players, entries] = await Promise.all([
    q('SELECT player, uuid, role FROM case_players WHERE case_id = ? ORDER BY role, player', [id]),
    q('SELECT time_ms, author, type, dimension, x, y, z, text, data FROM case_entries WHERE case_id = ? ORDER BY time_ms, id', [id]),
  ]);
  return {
    ...c,
    players,
    timeline: entries.filter((e) => e.type !== 'log'),
    locations: entries.filter((e) => e.type === 'location'),
    evidence: entries.filter((e) => e.type === 'log'),
  };
}

// Eventos: text = chave de tradução, data = argumentos em JSON (renderizados no idioma de quem lê)
export async function addEvent(caseId, author, key, args = []) {
  await addEntry(caseId, author, 'event', { text: key, data: JSON.stringify(args.map(String)) });
}

export async function addEntry(caseId, author, type, { text = null, data = null, dimension = null, x = null, y = null, z = null } = {}) {
  await q(`INSERT INTO case_entries(case_id, time_ms, author, author_uuid, type, dimension, x, y, z, text, data)
    VALUES (?,?,?,?,?,?,?,?,?,?,?)`, [caseId, Date.now(), author, null, type, dimension, x, y, z, text, data]);
}

export async function createCase(title, author) {
  const [res] = await pool.query(
    'INSERT INTO cases(title, status, created_ms, created_by, created_by_uuid) VALUES (?,?,?,?,NULL)',
    [title.slice(0, 100), 'open', Date.now(), author]);
  return res.insertId;
}

export async function updateCase(id, fields) {
  const keys = Object.keys(fields);
  await q(`UPDATE cases SET ${keys.map((k) => `${k} = ?`).join(', ')} WHERE id = ?`, [...keys.map((k) => fields[k]), id]);
}

export async function setPlayer(caseId, name, role, author) {
  let uuid = UUID_RE.test(name) ? name : null;
  if (!uuid) {
    const r = await one('SELECT uuid FROM logs WHERE player = ? AND uuid IS NOT NULL ORDER BY time_ms DESC LIMIT 1', [name]);
    uuid = r?.uuid ?? null;
  }
  await q('REPLACE INTO case_players(case_id, player, uuid, role, added_ms, added_by) VALUES (?,?,?,?,?,?)',
    [caseId, name, uuid, role, Date.now(), author]);
  return uuid;
}

export async function removePlayer(caseId, name) {
  const [res] = await pool.query('DELETE FROM case_players WHERE case_id = ? AND player = ?', [caseId, name]);
  return res.affectedRows > 0;
}

// Mesma chave de deduplicação e mesmo JSON do CaseRepository.addLogs (o mod lê essas evidências)
const logKey = (l) => [l.time_ms, l.dimension, l.x, l.y, l.z, l.player, l.action, l.target].map(String).join('|');
const logJson = (l) => JSON.stringify({
  time: Number(l.time_ms), dimension: l.dimension, x: l.x, y: l.y, z: l.z,
  player: l.player, uuid: l.uuid, action: l.action, target: l.target, data: l.data,
});

export async function attachLogs(caseId, f, author) {
  const { sql, args } = logFilter(f);
  const logs = await q(`SELECT ${LOG_COLS} FROM logs WHERE ${sql} ORDER BY time_ms DESC LIMIT ?`, [...args, ATTACH_LIMIT]);
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const [existingRows] = await conn.query("SELECT text FROM case_entries WHERE case_id = ? AND type = 'log'", [caseId]);
    const existing = new Set(existingRows.map((r) => r.text));
    let added = 0;
    for (const l of logs) {
      const key = logKey(l);
      if (existing.has(key)) continue;
      existing.add(key);
      await conn.query(`INSERT INTO case_entries(case_id, time_ms, author, author_uuid, type, dimension, x, y, z, text, data)
        VALUES (?,?,?,NULL,'log',?,?,?,?,?,?)`, [caseId, l.time_ms, author, l.dimension, l.x, l.y, l.z, key, logJson(l)]);
      added++;
    }
    await conn.commit();
    return { found: logs.length, added, truncated: logs.length >= ATTACH_LIMIT };
  } catch (e) {
    await conn.rollback();
    throw e;
  } finally {
    conn.release();
  }
}
