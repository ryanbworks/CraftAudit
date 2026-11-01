import crypto from 'node:crypto';
import bcrypt from 'bcryptjs';
import { one } from './db.js';

const MAX_FAILURES = 5;
const LOCK_MS = 15 * 60 * 1000;
const failures = new Map(); // "ip|usuário" -> { count, until }

// Hash fixo para comparar quando o usuário não existe, para o tempo de resposta não revelar quais existem
const DUMMY_HASH = bcrypt.hashSync('craftaudit-dummy', 12);

export async function authenticate(ip, username, password) {
  const key = `${ip}|${username}`;
  const f = failures.get(key);
  if (f && f.until > Date.now()) return { error: 'Muitas tentativas. Tente novamente em alguns minutos.' };

  const user = await one('SELECT id, username, password_hash, role FROM panel_users WHERE username=?', [username]);
  const ok = await bcrypt.compare(password, user ? user.password_hash : DUMMY_HASH);
  if (!user || !ok) {
    const lockExpired = f && f.until && f.until <= Date.now(); // bloqueio vencido zera a contagem
    const count = (f && !lockExpired ? f.count : 0) + 1;
    failures.set(key, { count, until: count >= MAX_FAILURES ? Date.now() + LOCK_MS : 0 });
    return { error: 'Usuário ou senha inválidos.' };
  }
  failures.delete(key);
  return { user: { id: user.id, username: user.username, role: user.role } };
}

export function csrf(req, res, next) {
  if (!req.session.csrf) req.session.csrf = crypto.randomBytes(24).toString('hex');
  res.locals.csrf = req.session.csrf;
  if (req.method === 'POST') {
    const sent = Buffer.from(String(req.body?._csrf || ''));
    const expected = Buffer.from(req.session.csrf);
    if (sent.length !== expected.length || !crypto.timingSafeEqual(sent, expected)) {
      return res.status(403).render('error', { message: 'Token de segurança inválido. Recarregue a página.' });
    }
  }
  next();
}

export function requireLogin(req, res, next) {
  if (!req.session.user) return res.redirect('/login');
  res.locals.user = req.session.user;
  next();
}

// admin: gerencia casos; viewer: só consulta
export function requireAdmin(req, res, next) {
  if (req.session.user?.role !== 'admin') {
    return res.status(403).render('error', { message: 'Apenas administradores podem fazer isso.' });
  }
  next();
}
