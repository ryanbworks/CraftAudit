import mysql from 'mysql2/promise';

export const pool = mysql.createPool({
  host: process.env.DB_HOST || '127.0.0.1',
  port: Number(process.env.DB_PORT || 3306),
  user: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  database: process.env.DB_NAME || 'craftaudit',
  charset: 'utf8mb4',
  connectionLimit: 5,
  supportBigNumbers: true,
});

// query() escapa os parâmetros no cliente (aceita LIMIT ? normalmente, ao contrário de execute())
export async function q(sql, params = []) {
  const [rows] = await pool.query(sql, params);
  return rows;
}

export async function one(sql, params = []) {
  return (await q(sql, params))[0];
}

// Usuários do painel ficam em tabela própria, separada dos dados do mod
export async function ensurePanelSchema() {
  await q(`CREATE TABLE IF NOT EXISTS panel_users (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_ms BIGINT NOT NULL
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`);
}
