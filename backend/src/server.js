import express from 'express';
import pg from 'pg';
import argon2 from 'argon2';
import crypto from 'node:crypto';

const { Pool } = pg;
const app = express();
const pool = new Pool({
  connectionString: must('DATABASE_URL'),
  ssl: process.env.PGSSLMODE === 'disable' ? false : { rejectUnauthorized: false }
});

const TOKEN_SECRET = must('TOKEN_SECRET');
const LOOKUP_SECRET = must('PASSWORD_LOOKUP_SECRET');
const PORT = Number(process.env.PORT || 3000);

const PLAYER_VERSIONS_PER_PLAYER = 5;
const PLAYER_STORAGE_PER_SERVER_BYTES = 100 * 1024 * 1024;
const PLAYER_STORAGE_GLOBAL_BYTES = 950 * 1024 * 1024;

app.set('trust proxy', true);
app.use(express.json({ limit: '16mb' }));

function must(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}
function hash(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}
function passwordLookup(password) {
  return crypto.createHmac('sha256', LOOKUP_SECRET).update(password, 'utf8').digest('hex');
}
function newToken() {
  return crypto.randomBytes(32).toString('base64url');
}
function verificationCode() {
  return String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
}
function requestIp(req) {
  return String(req.ip || req.socket.remoteAddress || '').replace(/^::ffff:/, '');
}

async function issueServer(client, accountId, name, ip) {
  const token = newToken();
  const tokenHash = hash(TOKEN_SECRET + token);
  const { rows } = await client.query(
    'INSERT INTO servers(account_id,name,registered_ip,token_hash) VALUES ($1,$2,$3,$4) RETURNING id,account_id,registered_ip',
    [accountId, name || 'Minecraft Server', ip, tokenHash]
  );
  return {
    serverId: rows[0].id,
    accountId: rows[0].account_id,
    token,
    registeredIp: rows[0].registered_ip
  };
}

async function auth(req, res, next) {
  try {
    const header = req.get('authorization') || '';
    if (!header.startsWith('Bearer ')) return res.status(401).json({ error: 'missing_token' });

    const tokenHash = hash(TOKEN_SECRET + header.slice(7));
    const { rows } = await pool.query(
      'SELECT id,account_id,registered_ip FROM servers WHERE token_hash=$1 AND active=true',
      [tokenHash]
    );
    if (!rows[0]) return res.status(401).json({ error: 'invalid_token' });

    req.serverAuth = rows[0];
    await pool.query('UPDATE servers SET last_seen_at=now() WHERE id=$1', [rows[0].id]);
    next();
  } catch (error) {
    next(error);
  }
}

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    service: 'Just-Backup API',
    version: '0.2.0',
    backupType: 'player-data-only'
  });
});

app.post('/v1/register', async (req, res, next) => {
  const client = await pool.connect();
  try {
    const password = String(req.body?.pass || '');
    if (password.length < 6) return res.status(400).json({ error: 'password_too_short' });

    const lookup = passwordLookup(password);
    const duplicate = await client.query('SELECT 1 FROM accounts WHERE password_lookup=$1', [lookup]);
    if (duplicate.rowCount) return res.status(409).json({ error: 'password_already_in_use' });

    await client.query('BEGIN');
    const passwordHash = await argon2.hash(password, { type: argon2.argon2id });
    const account = await client.query(
      'INSERT INTO accounts(password_hash,password_lookup) VALUES ($1,$2) RETURNING id',
      [passwordHash, lookup]
    );
    const registration = await issueServer(
      client,
      account.rows[0].id,
      req.body?.name,
      requestIp(req)
    );
    await client.query('COMMIT');
    res.status(201).json(registration);
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    next(error);
  } finally {
    client.release();
  }
});

app.post('/v1/join', async (req, res, next) => {
  const client = await pool.connect();
  try {
    const password = String(req.body?.pass || '');
    const account = await client.query(
      'SELECT id,password_hash FROM accounts WHERE password_lookup=$1',
      [passwordLookup(password)]
    );

    if (!account.rows[0] || !(await argon2.verify(account.rows[0].password_hash, password))) {
      return res.status(403).json({ error: 'invalid_password' });
    }

    await client.query('BEGIN');
    const registration = await issueServer(
      client,
      account.rows[0].id,
      req.body?.name,
      requestIp(req)
    );
    await client.query('COMMIT');
    res.status(201).json(registration);
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    next(error);
  } finally {
    client.release();
  }
});

app.post('/v1/password/reset', auth, async (req, res, next) => {
  try {
    const oldPass = String(req.body?.oldPass || '');
    const newPass = String(req.body?.newPass || '');
    if (newPass.length < 6) return res.status(400).json({ error: 'password_too_short' });

    const { rows } = await pool.query(
      'SELECT password_hash FROM accounts WHERE id=$1',
      [req.serverAuth.account_id]
    );
    if (!rows[0] || !(await argon2.verify(rows[0].password_hash, oldPass))) {
      return res.status(403).json({ error: 'invalid_password' });
    }

    const lookup = passwordLookup(newPass);
    const duplicate = await pool.query(
      'SELECT 1 FROM accounts WHERE password_lookup=$1 AND id<>$2',
      [lookup, req.serverAuth.account_id]
    );
    if (duplicate.rowCount) return res.status(409).json({ error: 'password_already_in_use' });

    const passwordHash = await argon2.hash(newPass, { type: argon2.argon2id });
    await pool.query(
      'UPDATE accounts SET password_hash=$1,password_lookup=$2,updated_at=now() WHERE id=$3',
      [passwordHash, lookup, req.serverAuth.account_id]
    );
    res.json({ ok: true });
  } catch (error) {
    next(error);
  }
});

async function trimPlayerVersions(client) {
  await client.query(`
    DELETE FROM player_backups
    WHERE id IN (
      SELECT id FROM (
        SELECT id,
               row_number() OVER (
                 PARTITION BY account_id,player_uuid
                 ORDER BY created_at_ms DESC,id DESC
               ) AS rn
        FROM player_backups
      ) ranked
      WHERE rn > $1
    )
  `, [PLAYER_VERSIONS_PER_PLAYER]);
}

async function oldestDeletable(client, whereSql = '', args = []) {
  const { rows } = await client.query(`
    SELECT pb.id
    FROM player_backups pb
    WHERE EXISTS (
      SELECT 1
      FROM player_backups newer
      WHERE newer.account_id=pb.account_id
        AND newer.player_uuid=pb.player_uuid
        AND (
          newer.created_at_ms > pb.created_at_ms OR
          (newer.created_at_ms=pb.created_at_ms AND newer.id > pb.id)
        )
    )
    ${whereSql}
    ORDER BY pb.created_at_ms ASC,pb.id ASC
    LIMIT 1
  `, args);
  return rows[0]?.id || null;
}

async function enforceStorage(client, serverId) {
  await trimPlayerVersions(client);

  while (true) {
    const { rows } = await client.query(
      'SELECT COALESCE(sum(octet_length(compressed_data)),0)::bigint AS bytes FROM player_backups WHERE source_server_id=$1',
      [serverId]
    );
    if (Number(rows[0].bytes) <= PLAYER_STORAGE_PER_SERVER_BYTES) break;

    const victim = await oldestDeletable(client, 'AND pb.source_server_id=$1', [serverId]);
    if (!victim) break;
    await client.query('DELETE FROM player_backups WHERE id=$1', [victim]);
  }

  while (true) {
    const { rows } = await client.query(
      'SELECT COALESCE(sum(octet_length(compressed_data)),0)::bigint AS bytes FROM player_backups'
    );
    if (Number(rows[0].bytes) <= PLAYER_STORAGE_GLOBAL_BYTES) break;

    const victim = await oldestDeletable(client);
    if (!victim) break;
    await client.query('DELETE FROM player_backups WHERE id=$1', [victim]);
  }
}

app.post('/v1/player/save', auth, async (req, res, next) => {
  const client = await pool.connect();
  try {
    const data = Buffer.from(String(req.body?.data || ''), 'base64');
    if (!data.length) return res.status(400).json({ error: 'empty_backup' });
    if (hash(data) !== String(req.body?.checksum || '').toLowerCase()) {
      return res.status(400).json({ error: 'checksum_mismatch' });
    }
    if (data.length > PLAYER_STORAGE_PER_SERVER_BYTES) {
      return res.status(413).json({ error: 'player_backup_too_large' });
    }

    await client.query('BEGIN');
    await client.query(
      `INSERT INTO player_backups(
        account_id,source_server_id,player_uuid,player_name,created_at_ms,checksum,compressed_data
      ) VALUES ($1,$2,$3,$4,$5,$6,$7)`,
      [
        req.serverAuth.account_id,
        req.serverAuth.id,
        req.body.uuid,
        req.body.name || null,
        Number(req.body.createdAt || Date.now()),
        req.body.checksum,
        data
      ]
    );

    await enforceStorage(client, req.serverAuth.id);

    const serverUsage = await client.query(
      'SELECT COALESCE(sum(octet_length(compressed_data)),0)::bigint AS bytes FROM player_backups WHERE source_server_id=$1',
      [req.serverAuth.id]
    );
    const globalUsage = await client.query(
      'SELECT COALESCE(sum(octet_length(compressed_data)),0)::bigint AS bytes FROM player_backups'
    );

    if (
      Number(serverUsage.rows[0].bytes) > PLAYER_STORAGE_PER_SERVER_BYTES ||
      Number(globalUsage.rows[0].bytes) > PLAYER_STORAGE_GLOBAL_BYTES
    ) {
      await client.query('ROLLBACK');
      return res.status(507).json({ error: 'player_backup_storage_full' });
    }

    await client.query('COMMIT');
    res.status(201).json({
      ok: true,
      retainedPerPlayer: PLAYER_VERSIONS_PER_PLAYER,
      serverPlayerBytes: Number(serverUsage.rows[0].bytes),
      globalPlayerBytes: Number(globalUsage.rows[0].bytes)
    });
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    next(error);
  } finally {
    client.release();
  }
});

app.get('/v1/player/latest/:uuid', auth, async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      `SELECT player_name,created_at_ms,checksum,compressed_data
       FROM player_backups
       WHERE account_id=$1 AND player_uuid=$2
       ORDER BY created_at_ms DESC,id DESC
       LIMIT 1`,
      [req.serverAuth.account_id, req.params.uuid]
    );

    if (!rows[0]) return res.status(404).json({ error: 'not_found' });

    res.json({
      name: rows[0].player_name,
      createdAt: Number(rows[0].created_at_ms),
      checksum: rows[0].checksum,
      data: rows[0].compressed_data.toString('base64')
    });
  } catch (error) {
    next(error);
  }
});

app.get('/v1/player/list', auth, async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      `SELECT DISTINCT ON (player_uuid)
         player_uuid,player_name,created_at_ms
       FROM player_backups
       WHERE account_id=$1
       ORDER BY player_uuid,created_at_ms DESC,id DESC`,
      [req.serverAuth.account_id]
    );
    res.json({ players: rows.map(row => ({
      uuid: row.player_uuid,
      name: row.player_name,
      createdAt: Number(row.created_at_ms)
    })) });
  } catch (error) {
    next(error);
  }
});

app.post('/v1/destructive/start', auth, async (req, res, next) => {
  try {
    const action = String(req.body?.type || '').toLowerCase();
    if (!['wipe', 'unregister'].includes(action)) {
      return res.status(400).json({ error: 'invalid_action' });
    }

    const password = String(req.body?.pass || '');
    const { rows: accounts } = await pool.query(
      'SELECT password_hash FROM accounts WHERE id=$1',
      [req.serverAuth.account_id]
    );
    if (!accounts[0] || !(await argon2.verify(accounts[0].password_hash, password))) {
      return res.status(403).json({ error: 'invalid_password' });
    }

    const code = verificationCode();
    const { rows } = await pool.query(
      `INSERT INTO destructive_requests(server_id,action,code_hash,expires_at)
       VALUES ($1,$2,$3,now()+interval '5 minutes')
       RETURNING id,expires_at`,
      [req.serverAuth.id, action, hash(code)]
    );

    res.status(201).json({
      requestId: rows[0].id,
      code,
      expiresAt: rows[0].expires_at
    });
  } catch (error) {
    next(error);
  }
});

app.post('/v1/destructive/verify', auth, async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      'SELECT * FROM destructive_requests WHERE id=$1 AND server_id=$2 AND expires_at>now()',
      [req.body.id, req.serverAuth.id]
    );
    if (!rows[0]) return res.status(404).json({ error: 'request_expired_or_missing' });

    if (hash(String(req.body.verificationCode || '')) !== rows[0].code_hash) {
      return res.status(403).json({ error: 'invalid_code' });
    }
    if (String(req.body.serverIp || '').trim() !== req.serverAuth.registered_ip) {
      return res.status(403).json({ error: 'invalid_server_ip' });
    }

    await pool.query(
      'UPDATE destructive_requests SET code_verified=true,ip_verified=true WHERE id=$1',
      [req.body.id]
    );
    res.json({ ok: true });
  } catch (error) {
    next(error);
  }
});

app.post('/v1/destructive/confirm', auth, async (req, res, next) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows } = await client.query(
      'SELECT * FROM destructive_requests WHERE id=$1 AND server_id=$2 AND expires_at>now() FOR UPDATE',
      [req.body.id, req.serverAuth.id]
    );
    const request = rows[0];

    if (!request || !request.code_verified || !request.ip_verified) {
      await client.query('ROLLBACK');
      return res.status(403).json({ error: 'verification_incomplete' });
    }

    if (request.action === 'wipe') {
      // Player data only. No worlds/chunks exist in this service.
      await client.query('DELETE FROM player_backups WHERE source_server_id=$1', [req.serverAuth.id]);
      await client.query('DELETE FROM destructive_requests WHERE server_id=$1', [req.serverAuth.id]);
    } else {
      const accountId = req.serverAuth.account_id;
      await client.query('DELETE FROM servers WHERE id=$1', [req.serverAuth.id]);
      const remaining = await client.query(
        'SELECT count(*)::int AS count FROM servers WHERE account_id=$1',
        [accountId]
      );
      if (remaining.rows[0].count === 0) {
        await client.query('DELETE FROM accounts WHERE id=$1', [accountId]);
      }
    }

    await client.query('COMMIT');
    res.json({ ok: true, action: request.action });
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    next(error);
  } finally {
    client.release();
  }
});

app.use((error, _req, res, _next) => {
  console.error(error);
  res.status(500).json({ error: 'server_error' });
});

app.listen(PORT, () => {
  console.log(`Just-Backup API listening on :${PORT} (player-data only)`);
});
