// Uso: npm run create-user -- <usuario> <admin|viewer>
// A senha é pedida no terminal (não fica no histórico do shell). Se o usuário já existir, a senha e o papel são atualizados.
import readline from 'node:readline';
import bcrypt from 'bcryptjs';
import { ensurePanelSchema, pool, q } from '../src/db.js';

const [username, role = 'viewer'] = process.argv.slice(2);
if (!username || !/^[A-Za-z0-9_.-]{3,32}$/.test(username) || !['admin', 'viewer'].includes(role)) {
  console.error('Uso: npm run create-user -- <usuario> <admin|viewer>');
  process.exit(1);
}

function ask(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  rl._writeToOutput = (s) => { if (s.includes(question)) process.stdout.write(s); }; // não ecoa a senha
  return new Promise((resolve) => rl.question(question, (a) => { rl.close(); process.stdout.write('\n'); resolve(a); }));
}

const password = await ask('Senha: ');
if (!password) {
  console.error('A senha não pode ser vazia.');
  process.exit(1);
}
if (password !== await ask('Confirme a senha: ')) {
  console.error('As senhas não conferem.');
  process.exit(1);
}

await ensurePanelSchema();
await q(`INSERT INTO panel_users(username, password_hash, role, created_ms) VALUES (?,?,?,?)
  ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), role = VALUES(role)`,
  [username, await bcrypt.hash(password, 12), role, Date.now()]);
console.log(`Usuário "${username}" salvo como ${role}.`);
await pool.end();
