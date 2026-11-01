# Painel CraftAudit

Painel web para consultar os logs do CraftAudit e gerenciar casos de investigação. Lê e escreve no **mesmo MySQL** usado pelo mod (`mode = "MYSQL"` no `craftaudit-common.toml`).

- **Logs**: busca por jogador, ação, item, dimensão, coordenadas + raio e período (de/até).
- **Itens**: rastreio de um item com resumo por jogador (tirou / colocou / pegou / jogou → saldo).
- **Casos**: criar, marcar envolvidos, locais, anotações, anexar logs como evidência, encerrar/reabrir. Tudo aparece também no jogo (`/ca case <id>`), e vice-versa.

## Requisitos
- Node.js 20.6 ou mais novo
- O mod configurado com MySQL e iniciado ao menos uma vez (ele cria as tabelas `logs`, `cases`, `case_players`, `case_entries`)

## Instalação

```bash
cd panel
npm install
cp .env.example .env      # Windows: copy .env.example .env
```

Edite o `.env` (banco, `SESSION_SECRET`, `HOST`).

### Usuário MySQL do painel (recomendado)
Crie um usuário só para o painel, com o mínimo de permissões. Os logs ficam **somente leitura**:

```sql
CREATE USER 'craftaudit_panel'@'localhost' IDENTIFIED BY 'troque-esta-senha';
GRANT SELECT ON craftaudit.logs TO 'craftaudit_panel'@'localhost';
GRANT SELECT, INSERT, UPDATE ON craftaudit.cases TO 'craftaudit_panel'@'localhost';
GRANT SELECT, INSERT, DELETE ON craftaudit.case_players TO 'craftaudit_panel'@'localhost';
GRANT SELECT, INSERT ON craftaudit.case_entries TO 'craftaudit_panel'@'localhost';
GRANT SELECT, INSERT, UPDATE, CREATE ON craftaudit.panel_users TO 'craftaudit_panel'@'localhost';
```

> `case_players` usa `REPLACE`, que precisa de `INSERT` + `DELETE`. Para o `CREATE` da tabela `panel_users`, se preferir, crie-a manualmente (veja `src/db.js`) e remova o `CREATE` do grant.

### Criar usuários do painel

```bash
npm run create-user -- seu_nome admin     # admin: gerencia casos
npm run create-user -- ajudante viewer    # viewer: só consulta
```

A senha é pedida no terminal. Rodar de novo com o mesmo nome troca a senha/papel.

### Iniciar

```bash
npm start
```

## Segurança
- Por padrão escuta só em `127.0.0.1`. Para acessar de outra máquina, use uma VPN (ex.: Tailscale) e coloque o IP da VPN em `HOST`. **Não exponha na internet** sem HTTPS (proxy como Caddy/Nginx).
- Senhas com bcrypt; bloqueio de 15 min após 5 tentativas erradas; sessão expira em 8h.
- Todas as consultas são parametrizadas; formulários têm token CSRF; cabeçalhos de segurança via Helmet.
- Ações feitas pelo painel aparecem nos casos como `web:<usuário>`.
- Casos encerrados ficam somente leitura até serem reabertos; não há como apagar casos.
