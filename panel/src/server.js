import path from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';
import session from 'express-session';
import helmet from 'helmet';
import { ensurePanelSchema } from './db.js';
import { authenticate, csrf, requireAdmin, requireLogin } from './auth.js';
import { describeLog, fmtTime, loadLang, renderEvent, roleName, tr } from './lang.js';
import * as repo from './repo.js';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const HOST = process.env.HOST || '127.0.0.1';
const PORT = Number(process.env.PORT || 8080);
const PAGE_SIZE = 50;
const ROLES = ['suspect', 'victim', 'witness', 'other'];

if (!process.env.SESSION_SECRET || process.env.SESSION_SECRET.length < 32) {
  console.error('[painel] defina SESSION_SECRET no .env (mínimo 32 caracteres)');
  process.exit(1);
}
loadLang(path.resolve(root, process.env.LANG_FILE || '../src/main/resources/assets/craftaudit/lang/pt_br.json'));
await ensurePanelSchema();

const app = express();
app.set('view engine', 'ejs');
app.set('views', path.join(root, 'views'));
app.disable('x-powered-by');
app.use(helmet({ hsts: false, contentSecurityPolicy: { directives: { upgradeInsecureRequests: null } } })); // rede local sem HTTPS
app.use(express.static(path.join(root, 'public')));
app.use(express.urlencoded({ extended: false, limit: '20kb' }));
app.use(session({
  name: 'craftaudit.sid',
  secret: process.env.SESSION_SECRET,
  resave: false,
  saveUninitialized: false,
  cookie: { httpOnly: true, sameSite: 'strict', maxAge: 8 * 3600 * 1000 },
}));
app.use(csrf);
app.use((req, res, next) => {
  Object.assign(res.locals, { user: req.session.user, tr, fmtTime, describeLog, renderEvent, roleName, caseNo: repo.caseNo, query: req.query });
  next();
});

// Erros de rotas assíncronas caem no handler de erro
const h = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
const author = (req) => `web:${req.session.user.username}`;
const pageOf = (req) => Math.max(parseInt(req.query.page, 10) || 1, 1);
// Monta links de paginação preservando os filtros atuais
const pageLink = (req) => (p) => `?${new URLSearchParams({ ...req.query, page: p })}`;

// ===== Login =====
app.get('/login', (req, res) => res.render('login', { error: null }));
app.post('/login', h(async (req, res, next) => {
  const username = String(req.body.username || '').trim();
  const result = await authenticate(req.ip, username, String(req.body.password || ''));
  if (result.error) return res.status(401).render('login', { error: result.error });
  // nova sessão no login (evita fixação de sessão)
  req.session.regenerate((err) => {
    if (err) return next(err);
    req.session.user = result.user;
    res.redirect('/');
  });
}));
app.post('/logout', (req, res) => req.session.destroy(() => res.redirect('/login')));

app.use(requireLogin);

// ===== Início =====
app.get('/', h(async (req, res) => {
  const [s, cases] = await Promise.all([repo.stats(), repo.listCases('open')]);
  res.render('dashboard', { stats: s, cases: cases.slice(0, 10) });
}));

// ===== Logs =====
app.get('/logs', h(async (req, res) => {
  const page = pageOf(req);
  const [{ total, rows }, dims, openCases] = await Promise.all([
    repo.searchLogs(req.query, page, PAGE_SIZE), repo.dimensions(), repo.listCases('open'),
  ]);
  res.render('logs', {
    rows, total, page, pages: Math.max(Math.ceil(total / PAGE_SIZE), 1), link: pageLink(req),
    dims, actions: repo.ACTIONS, openCases, attachLimit: repo.ATTACH_LIMIT,
  });
}));

// ===== Rastreio de itens =====
app.get('/items', h(async (req, res) => {
  const item = String(req.query.item || '').trim();
  const result = item ? await repo.traceItem(item, req.query) : null;
  const page = pageOf(req);
  res.render('items', {
    item, result, page, link: pageLink(req), itemLimit: repo.ITEM_LIMIT,
    rows: result ? result.rows.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE) : [],
    pages: result ? Math.max(Math.ceil(result.rows.length / PAGE_SIZE), 1) : 1,
  });
}));

// ===== Casos =====
app.get('/cases', h(async (req, res) => {
  const status = ['open', 'closed', 'all'].includes(req.query.status) ? req.query.status : 'open';
  res.render('cases', { cases: await repo.listCases(status), status });
}));

app.post('/cases', requireAdmin, h(async (req, res) => {
  const title = String(req.body.title || '').trim();
  if (!title) return res.status(400).render('error', { message: 'Informe um título.' });
  const id = await repo.createCase(title, author(req));
  await repo.addEvent(id, author(req), 'craftaudit.case.event.created');
  res.redirect(`/cases/${id}`);
}));

// Carrega o caso; rotas de edição exigem caso aberto (encerrado = somente leitura, igual ao mod)
const loadCase = (requireOpen) => h(async (req, res, next) => {
  const c = await repo.getCase(parseInt(req.params.id ?? req.body?.case, 10) || 0);
  if (!c) return res.status(404).render('error', { message: 'Caso não encontrado.' });
  if (requireOpen && c.status !== 'open') {
    return res.status(409).render('error', { message: `O caso ${repo.caseNo(c.id)} está encerrado. Reabra para editar.` });
  }
  req.caseData = c;
  next();
});

app.get('/cases/:id', loadCase(false), (req, res) => res.render('case', { c: req.caseData, roles: ROLES }));

app.post('/cases/:id/note', requireAdmin, loadCase(true), h(async (req, res) => {
  const text = String(req.body.text || '').trim();
  if (text) await repo.addEntry(req.caseData.id, author(req), 'note', { text });
  res.redirect(`/cases/${req.caseData.id}`);
}));

app.post('/cases/:id/player', requireAdmin, loadCase(true), h(async (req, res) => {
  const name = String(req.body.player || '').trim();
  const role = String(req.body.role || '');
  if (!name || !ROLES.includes(role)) return res.status(400).render('error', { message: 'Jogador ou papel inválido.' });
  await repo.setPlayer(req.caseData.id, name, role, author(req));
  await repo.addEvent(req.caseData.id, author(req), 'craftaudit.case.event.player_set', [name, `@craftaudit.case.role.${role}`]);
  res.redirect(`/cases/${req.caseData.id}`);
}));

app.post('/cases/:id/player/remove', requireAdmin, loadCase(true), h(async (req, res) => {
  const name = String(req.body.player || '');
  if (await repo.removePlayer(req.caseData.id, name)) {
    await repo.addEvent(req.caseData.id, author(req), 'craftaudit.case.event.player_removed', [name]);
  }
  res.redirect(`/cases/${req.caseData.id}`);
}));

app.post('/cases/:id/location', requireAdmin, loadCase(true), h(async (req, res) => {
  const [x, y, z] = ['x', 'y', 'z'].map((k) => parseInt(req.body[k], 10));
  const dimension = String(req.body.dimension || '').trim();
  if (!dimension || ![x, y, z].every(Number.isFinite)) {
    return res.status(400).render('error', { message: 'Informe dimensão e coordenadas válidas.' });
  }
  const text = String(req.body.label || '').trim() || tr('craftaudit.case.loc_default');
  await repo.addEntry(req.caseData.id, author(req), 'location', { text, dimension, x, y, z });
  res.redirect(`/cases/${req.caseData.id}`);
}));

app.post('/cases/:id/rename', requireAdmin, loadCase(true), h(async (req, res) => {
  const title = String(req.body.title || '').trim().slice(0, 100);
  if (title && title !== req.caseData.title) {
    await repo.updateCase(req.caseData.id, { title });
    await repo.addEvent(req.caseData.id, author(req), 'craftaudit.case.event.renamed', [req.caseData.title, title]);
  }
  res.redirect(`/cases/${req.caseData.id}`);
}));

app.post('/cases/:id/close', requireAdmin, loadCase(true), h(async (req, res) => {
  const conclusion = String(req.body.conclusion || '').trim();
  if (!conclusion) return res.status(400).render('error', { message: 'Escreva a conclusão antes de encerrar.' });
  await repo.updateCase(req.caseData.id, { status: 'closed', closed_ms: Date.now(), closed_by: author(req), conclusion });
  await repo.addEvent(req.caseData.id, author(req), 'craftaudit.case.event.closed', [conclusion]);
  res.redirect(`/cases/${req.caseData.id}`);
}));

app.post('/cases/:id/reopen', requireAdmin, loadCase(false), h(async (req, res) => {
  const c = req.caseData;
  if (c.status === 'open') return res.redirect(`/cases/${c.id}`);
  await repo.updateCase(c.id, { status: 'open', closed_ms: null, closed_by: null, conclusion: null });
  // a conclusão anterior fica registrada na linha do tempo
  await repo.addEvent(c.id, author(req), 'craftaudit.case.event.reopened', [c.conclusion, c.closed_by]);
  res.redirect(`/cases/${c.id}`);
}));

// Anexa os logs da busca atual (até 200) como evidência; o caso vem do formulário da página de logs
app.post('/attach', requireAdmin, loadCase(true), h(async (req, res) => {
  const filters = ['player', 'action', 'item', 'dim', 'x', 'y', 'z', 'r', 'from', 'to'];
  const f = Object.fromEntries(filters.map((k) => [k, String(req.body[k] ?? '')]));
  const r = await repo.attachLogs(req.caseData.id, f, author(req));
  if (r.added > 0) {
    const desc = filters.filter((k) => f[k]).map((k) => `${k}=${f[k]}`).join(', ') || '24h';
    await repo.addEvent(req.caseData.id, author(req), 'craftaudit.case.event.attach_web',
      [r.added, desc, r.truncated ? '@craftaudit.case.attach.truncated_note' : '']);
  }
  res.redirect(`/cases/${req.caseData.id}#evidence`);
}));

app.use((req, res) => res.status(404).render('error', { message: 'Página não encontrada.' }));
app.use((err, req, res, _next) => {
  console.error('[painel]', err);
  res.status(500).render('error', { message: 'Erro interno. Veja o console do painel.' });
});

app.listen(PORT, HOST, () => console.log(`[painel] http://${HOST}:${PORT}`)).on('error', (e) => {
  if (e.code === 'EADDRNOTAVAIL') console.error(`[painel] HOST=${HOST} não é um IP desta máquina. Use o IP dela (ou 127.0.0.1) — o IP do banco vai em DB_HOST.`);
  else if (e.code === 'EADDRINUSE') console.error(`[painel] a porta ${PORT} já está em uso. Troque PORT no .env.`);
  else console.error(`[painel] não foi possível iniciar: ${e.message}`);
  process.exit(1);
});
