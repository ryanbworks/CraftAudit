import fs from 'node:fs';

// Reaproveita o arquivo de idioma do mod para que ações e eventos dos casos
// apareçam com os mesmos textos do jogo.
let table = {};

export function loadLang(file) {
  try {
    table = JSON.parse(fs.readFileSync(file, 'utf8'));
    console.log(`[painel] idioma carregado: ${file} (${Object.keys(table).length} chaves)`);
  } catch (e) {
    console.warn(`[painel] não foi possível ler ${file}: ${e.message} — exibindo chaves cruas`);
    table = {};
  }
}

export const stripColors = (s) => String(s).replace(/§[0-9a-fk-or]/gi, '');

// Mesmo formato do String.format usado no mod: %s, %d e %1$s
function format(pattern, args) {
  let i = 0;
  return pattern.replace(/%(?:(\d+)\$)?([sd%])/g, (_, pos, t) => {
    if (t === '%') return '%';
    const v = pos ? args[pos - 1] : args[i++];
    return v === undefined || v === null ? '' : String(v);
  });
}

export function tr(key, ...args) {
  return stripColors(format(table[key] ?? key, args));
}

const ENV_ACTORS = { '(爆炸)': 'craftaudit.actor.explosion', '(环境)': 'craftaudit.actor.environment' };

export function actorName(player) {
  return ENV_ACTORS[player] ? tr(ENV_ACTORS[player]) : player ?? '';
}

export function fmtTime(ms) {
  return new Date(Number(ms)).toLocaleString('pt-BR');
}

function parseJson(s) {
  try { return JSON.parse(s); } catch { return null; }
}

// Prepara uma linha de log para a view: texto da ação, alvo e detalhes do item
export function describeLog(log) {
  const data = parseJson(log.data);
  const out = {
    time: fmtTime(log.time_ms),
    actor: actorName(log.player),
    uuid: log.uuid,
    action: log.action,
    actionText: table[`craftaudit.action.${log.action}`] ? tr(`craftaudit.action.${log.action}`) : log.action,
    target: log.target,
    where: `${log.dimension} ${log.x}/${log.y}/${log.z}`,
    item: null,
    details: [],
  };
  if (['put', 'take', 'drop', 'transfer'].includes(log.action) && data) {
    out.item = { id: data.item, count: data.count };
    const meta = data.meta || {};
    if (meta.name) out.details.push(`${tr('craftaudit.meta.name')}${meta.name}`);
    if (meta.ench) out.details.push(tr('craftaudit.meta.enchants') + meta.ench.map((e) => `${e.id} ${e.lvl}`).join(', '));
    if (meta.dur) out.details.push(tr('craftaudit.meta.durability', meta.dur[0], meta.dur[1]));
    if (meta.items) out.details.push(tr('craftaudit.meta.contents') + meta.items.map((e) => `${e.id} x${e.n}`).join(', '));
    if (log.action === 'transfer' && data.from) out.details.push(`${tr('craftaudit.action.transfer_from').trim()} ${data.from}`);
  } else if (log.action === 'sign_edit') {
    out.details.push(log.data);
  } else if (log.action === 'kill' && log.target?.startsWith('player:')) {
    out.target = log.target.slice('player:'.length);
  }
  return out;
}

// Eventos dos casos: text = chave, data = argumentos (os que começam com @ também são chaves)
export function renderEvent(entry) {
  const args = (parseJson(entry.data) || []).map((a) => (String(a).startsWith('@') ? tr(String(a).slice(1)) : a));
  return tr(entry.text, ...args);
}

export function roleName(role) {
  return tr(`craftaudit.case.role.${role}`);
}
