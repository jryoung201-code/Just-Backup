import express from 'express';
import pg from 'pg';
import argon2 from 'argon2';
import crypto from 'node:crypto';

const { Pool } = pg;
const app = express();
const pool = new Pool({ connectionString: must('DATABASE_URL'), ssl: process.env.PGSSLMODE === 'disable' ? false : { rejectUnauthorized: false } });
const TOKEN_SECRET = must('TOKEN_SECRET');
const LOOKUP_SECRET = must('PASSWORD_LOOKUP_SECRET');
const PORT = Number(process.env.PORT || 3000);
const MAX_JSON = process.env.MAX_JSON_BODY || '16mb';

// Hard-coded player backup storage policy. These are intentionally not owner-configurable.
const PLAYER_VERSIONS_PER_PLAYER = 2;
const PLAYER_STORAGE_PER_SERVER_BYTES = 100 * 1024 * 1024;
const PLAYER_STORAGE_GLOBAL_BYTES = 950 * 1024 * 1024;

app.set('trust proxy', true);
app.use(express.json({ limit: MAX_JSON }));

function must(name) { const v = process.env[name]; if (!v) throw new Error(`Missing ${name}`); return v; }
function sha(v) { return crypto.createHash('sha256').update(v).digest('hex'); }
function lookup(password) { return crypto.createHmac('sha256', LOOKUP_SECRET).update(password, 'utf8').digest('hex'); }
function newToken() { return crypto.randomBytes(32).toString('base64url'); }
function code6() { return String(crypto.randomInt(0, 1_000_000)).padStart(6, '0'); }
function publicIp(req) { return String(req.ip || req.socket.remoteAddress || '').replace(/^::ffff:/, ''); }

async function issueServer(client, accountId, name, ip) {
  const token = newToken();
  const tokenHash = sha(TOKEN_SECRET + token);
  const { rows } = await client.query(
    'INSERT INTO servers(account_id,name,registered_ip,token_hash) VALUES ($1,$2,$3,$4) RETURNING id,account_id,registered_ip',
    [accountId, name || 'Minecraft Server', ip, tokenHash]
  );
  return { serverId: rows[0].id, accountId: rows[0].account_id, token, registeredIp: rows[0].registered_ip };
}

async function auth(req, res, next) {
  try {
    const raw = req.get('authorization') || '';
    if (!raw.startsWith('Bearer ')) return res.status(401).json({ error: 'missing_token' });
    const tokenHash = sha(TOKEN_SECRET + raw.slice(7));
    const { rows } = await pool.query('SELECT id, account_id, registered_ip FROM servers WHERE token_hash=$1 AND active=true', [tokenHash]);
    if (!rows[0]) return res.status(401).json({ error: 'invalid_token' });
    req.serverAuth = rows[0];
    await pool.query('UPDATE servers SET last_seen_at=now() WHERE id=$1', [rows[0].id]);
    next();
  } catch (e) { next(e); }
}

app.get('/health', (_req,res) => res.json({ ok: true, service: 'Universal SMP Backup API', version: '0.1.0' }));

app.post('/v1/register', async (req,res,next) => {
  const client = await pool.connect();
  try {
    const password = String(req.body?.pass || '');
    if (password.length < 6) return res.status(400).json({ error: 'password_too_short' });
    const passwordLookup = lookup(password);
    const existing = await client.query('SELECT 1 FROM accounts WHERE password_lookup=$1', [passwordLookup]);
    if (existing.rowCount) return res.status(409).json({ error: 'password_already_in_use', message: 'Choose a different backup password.' });
    await client.query('BEGIN');
    const passwordHash = await argon2.hash(password, { type: argon2.argon2id });
    const account = await client.query('INSERT INTO accounts(password_hash,password_lookup) VALUES ($1,$2) RETURNING id', [passwordHash,passwordLookup]);
    const result = await issueServer(client, account.rows[0].id, req.body?.name, publicIp(req));
    await client.query('COMMIT');
    res.status(201).json(result);
  } catch (e) { await client.query('ROLLBACK').catch(()=>{}); next(e); }
  finally { client.release(); }
});

app.post('/v1/join', async (req,res,next) => {
  const client = await pool.connect();
  try {
    const password = String(req.body?.pass || '');
    const account = await client.query('SELECT id,password_hash FROM accounts WHERE password_lookup=$1', [lookup(password)]);
    if (!account.rows[0] || !(await argon2.verify(account.rows[0].password_hash, password))) return res.status(403).json({ error: 'invalid_password' });
    await client.query('BEGIN');
    const result = await issueServer(client, account.rows[0].id, req.body?.name, publicIp(req));
    await client.query('COMMIT');
    res.status(201).json(result);
  } catch (e) { await client.query('ROLLBACK').catch(()=>{}); next(e); }
  finally { client.release(); }
});

app.post('/v1/password/reset', auth, async (req,res,next) => {
  try {
    const oldPass = String(req.body?.oldPass || ''), newPass = String(req.body?.newPass || '');
    if (newPass.length < 6) return res.status(400).json({ error: 'password_too_short' });
    const { rows } = await pool.query('SELECT password_hash FROM accounts WHERE id=$1', [req.serverAuth.account_id]);
    if (!rows[0] || !(await argon2.verify(rows[0].password_hash, oldPass))) return res.status(403).json({ error: 'invalid_password' });
    const newLookup = lookup(newPass);
    const dup = await pool.query('SELECT 1 FROM accounts WHERE password_lookup=$1 AND id<>$2', [newLookup, req.serverAuth.account_id]);
    if (dup.rowCount) return res.status(409).json({ error: 'password_already_in_use' });
    const hash = await argon2.hash(newPass, { type: argon2.argon2id });
    await pool.query('UPDATE accounts SET password_hash=$1,password_lookup=$2,updated_at=now() WHERE id=$3', [hash,newLookup,req.serverAuth.account_id]);
    res.json({ ok: true });
  } catch (e) { next(e); }
});

async function prunePlayerStorage(client, sourceServerId) {
  await client.query(`DELETE FROM player_backups
    WHERE id IN (
      SELECT id FROM (
        SELECT id, row_number() OVER (
          PARTITION BY account_id, player_uuid
          ORDER BY created_at_ms DESC, id DESC
        ) AS rn
        FROM player_backups
      ) ranked
      WHERE rn > $1
    )`, [PLAYER_VERSIONS_PER_PLAYER]);

  while (true) {
    const used = await client.query(
      'SELECT COALESCE(sum(octet_length(compressed_data)),0)::bigint AS bytes FROM player_backups WHERE source_server_id=$1',
      [sourceServerId]
    );
    if (Number(used.rows[0].bytes) <= PLAYER_STORAGE_PER_SERVER_BYTES) break;
    const victim = await client.query(`SELECT pb.id
      FROM player_backups pb
      WHERE pb.source_server_id=$1
        AND EXISTS (
          SELECT 1 FROM player_backups newer
          WHERE newer.account_id=pb.account_id
            AND newer.player_uuid=pb.player_uuid
            AND (newer.created_at_ms > pb.created_at_ms OR (newer.created_at_ms=pb.created_at_ms AND newer.id > pb.id))
        )
      ORDER BY pb.created_at_ms ASC, pb.id ASC
      LIMIT 1`, [sourceServerId]);
    if (!victim.rows[0]) break;
    await client.query('DELETE FROM player_backups WHERE id=$1', [victim.rows[0].id]);
  }

  while (true) {
    const used = await client.query(
      'SELECT COALESCE(sum(octet_length(compressed_data)),0)::bigint AS bytes FROM player_backups'
    );
    if (Number(used.rows[0].bytes) <= PLAYER_STORAGE_GLOBAL_BYTES) break;
    const victim = await client.query(`SELECT pb.id
      FROM player_backups pb
      WHERE EXISTS (
        SELECT 1 FROM player_backups newer
        WHERE newer.account_id=pb.account_id
          AND newer.player_uuid=pb.player_uuid
          AND (newer.created_at_ms > pb.created_at_ms OR (newer.created_at_ms=pb.created_at_ms AND newer.id > pb.id))
      )
      ORDER BY pb.created_at_ms ASC, pb.id ASC
      LIMIT 1`);
    if (!victim.rows[0]) break;
    await client.query('DELETE FROM player_backups WHERE id=$1', [victim.rows[0].id]);
  }
}

app.post('/v1/player/save', auth, async (req,res,next) => {
  try {
    const data = Buffer.from(String(req.body?.data || ''), 'base64');
    if (!data.length) return res.status(400).json({ error: 'empty_backup' });
    if (sha(data) !== String(req.body?.checksum || '').toLowerCase()) return res.status(400).json({ error: 'checksum_mismatch' });
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      if (data.length > PLAYER_STORAGE_PER_SERVER_BYTES) {
        await client.query('ROLLBACK');
        return res.status(413).json({ error: 'player_backup_too_large' });
      }
      await client.query(`INSERT INTO player_backups(account_id,source_server_id,player_uuid,player_name,created_at_ms,checksum,compressed_data)
                        VALUES ($1,$2,$3,$4,$5,$6,$7)`,
        [req.serverAuth.account_id, req.serverAuth.id, req.body.uuid, req.body.name || null, Number(req.body.createdAt || Date.now()), req.body.checksum, data]);

      await prunePlayerStorage(client, req.serverAuth.id);

      const serverUsed = await client.query(
        'SELECT COALESCE(sum(octet_length(compressed_data)),0)::bigint AS bytes FROM player_backups WHERE source_server_id=$1',
        [req.serverAuth.id]
      );
      const globalUsed = await client.query(
        'SELECT COALESCE(sum(octet_length(compressed_data)),0)::bigint AS bytes FROM player_backups'
      );

      if (Number(serverUsed.rows[0].bytes) > PLAYER_STORAGE_PER_SERVER_BYTES || Number(globalUsed.rows[0].bytes) > PLAYER_STORAGE_GLOBAL_BYTES) {
        await client.query('ROLLBACK');
        return res.status(507).json({ error: 'player_backup_storage_full' });
      }

      await client.query('COMMIT');
      res.status(201).json({
        ok: true,
        retainedPerPlayer: PLAYER_VERSIONS_PER_PLAYER,
        serverPlayerBytes: Number(serverUsed.rows[0].bytes),
        globalPlayerBytes: Number(globalUsed.rows[0].bytes)
      });
    } catch (e) {
      await client.query('ROLLBACK').catch(()=>{});
      throw e;
    } finally {
      client.release();
    }
  } catch (e) { next(e); }
});

app.get('/v1/player/latest/:uuid', auth, async (req,res,next) => {
  try {
    const { rows } = await pool.query(`SELECT player_name,created_at_ms,checksum,compressed_data FROM player_backups
      WHERE account_id=$1 AND player_uuid=$2 ORDER BY created_at_ms DESC LIMIT 1`, [req.serverAuth.account_id,req.params.uuid]);
    if (!rows[0]) return res.status(404).json({ error: 'not_found' });
    res.json({ name: rows[0].player_name, createdAt: Number(rows[0].created_at_ms), checksum: rows[0].checksum, data: rows[0].compressed_data.toString('base64') });
  } catch (e) { next(e); }
});

app.post('/v1/server/backup/start', auth, async (req,res,next) => {
  try {
    const { rows } = await pool.query('INSERT INTO server_backups(server_id,checksum,size_bytes) VALUES ($1,$2,$3) RETURNING id',
      [req.serverAuth.id,req.body.sha256,Number(req.body.bytes)]);
    res.status(201).json({ backupId: rows[0].id });
  } catch (e) { next(e); }
});

app.put('/v1/server/backup/:id/chunk/:index', auth, express.raw({ type: 'application/octet-stream', limit: '5mb' }), async (req,res,next) => {
  try {
    const owned = await pool.query('SELECT 1 FROM server_backups WHERE id=$1 AND server_id=$2', [req.params.id,req.serverAuth.id]);
    if (!owned.rowCount) return res.status(404).json({ error: 'backup_not_found' });
    await pool.query(`INSERT INTO backup_chunks(backup_id,chunk_index,data) VALUES ($1,$2,$3)
      ON CONFLICT (backup_id,chunk_index) DO UPDATE SET data=excluded.data`, [req.params.id,Number(req.params.index),req.body]);
    res.json({ ok: true });
  } catch (e) { next(e); }
});

app.post('/v1/server/backup/:id/complete', auth, async (req,res,next) => {
  try {
    const chunks = Number(req.body?.chunks || 0);
    const count = await pool.query('SELECT count(*)::int AS c FROM backup_chunks WHERE backup_id=$1', [req.params.id]);
    if (count.rows[0].c !== chunks) return res.status(409).json({ error: 'chunk_count_mismatch' });
    await pool.query('UPDATE server_backups SET complete=true,chunk_count=$1 WHERE id=$2 AND server_id=$3', [chunks,req.params.id,req.serverAuth.id]);

    await pool.query(`DELETE FROM server_backups
      WHERE id IN (
        SELECT id FROM server_backups
        WHERE server_id=$1 AND complete=true
        ORDER BY created_at DESC, id DESC
        OFFSET 2
      )`, [req.serverAuth.id]);

    await pool.query(`DELETE FROM server_backups
      WHERE server_id=$1 AND complete=false AND created_at < now() - interval '24 hours'`, [req.serverAuth.id]);

    res.json({ ok: true, retained: 2 });
  } catch (e) { next(e); }
});

app.post('/v1/server/backup/latest', auth, async (req,res,next) => {
  try {
    const { rows } = await pool.query('SELECT id,chunk_count,checksum,size_bytes FROM server_backups WHERE server_id=$1 AND complete=true ORDER BY created_at DESC LIMIT 1', [req.serverAuth.id]);
    if (!rows[0]) return res.status(404).json({ error: 'not_found' });
    res.json({ backupId: rows[0].id, chunks: rows[0].chunk_count, checksum: rows[0].checksum, bytes: Number(rows[0].size_bytes) });
  } catch (e) { next(e); }
});

app.get('/v1/server/backup/:id/chunk/:index', auth, async (req,res,next) => {
  try {
    const { rows } = await pool.query(`SELECT c.data FROM backup_chunks c JOIN server_backups b ON b.id=c.backup_id
      WHERE c.backup_id=$1 AND c.chunk_index=$2 AND b.server_id=$3 AND b.complete=true`, [req.params.id,Number(req.params.index),req.serverAuth.id]);
    if (!rows[0]) return res.status(404).end();
    res.type('application/octet-stream').send(rows[0].data);
  } catch (e) { next(e); }
});

app.post('/v1/destructive/start', auth, async (req,res,next) => {
  try {
    const action = String(req.body?.type || '').toLowerCase();
    if (!['wipe','unregister'].includes(action)) return res.status(400).json({ error: 'invalid_action' });
    const password = String(req.body?.pass || '');
    const a = await pool.query('SELECT password_hash FROM accounts WHERE id=$1', [req.serverAuth.account_id]);
    if (!a.rows[0] || !(await argon2.verify(a.rows[0].password_hash,password))) return res.status(403).json({ error: 'invalid_password' });
    const code = code6();
    const { rows } = await pool.query(`INSERT INTO destructive_requests(server_id,action,code_hash,expires_at)
      VALUES ($1,$2,$3,now()+interval '5 minutes') RETURNING id,expires_at`, [req.serverAuth.id,action,sha(code)]);
    res.status(201).json({ requestId: rows[0].id, code, expiresAt: rows[0].expires_at });
  } catch (e) { next(e); }
});

app.post('/v1/destructive/verify', auth, async (req,res,next) => {
  try {
    const r = await pool.query('SELECT * FROM destructive_requests WHERE id=$1 AND server_id=$2 AND expires_at>now()', [req.body.id,req.serverAuth.id]);
    if (!r.rows[0]) return res.status(404).json({ error: 'request_expired_or_missing' });
    if (sha(String(req.body.verificationCode || '')) !== r.rows[0].code_hash) return res.status(403).json({ error: 'invalid_code' });
    if (String(req.body.serverIp || '').trim() !== req.serverAuth.registered_ip) return res.status(403).json({ error: 'invalid_server_ip' });
    await pool.query('UPDATE destructive_requests SET code_verified=true,ip_verified=true WHERE id=$1', [req.body.id]);
    res.json({ ok: true });
  } catch (e) { next(e); }
});

app.post('/v1/destructive/confirm', auth, async (req,res,next) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const r = await client.query('SELECT * FROM destructive_requests WHERE id=$1 AND server_id=$2 AND expires_at>now() FOR UPDATE', [req.body.id,req.serverAuth.id]);
    const d = r.rows[0];
    if (!d || !d.code_verified || !d.ip_verified) { await client.query('ROLLBACK'); return res.status(403).json({ error: 'verification_incomplete' }); }
    if (d.action === 'wipe') {
      await client.query('DELETE FROM player_backups WHERE source_server_id=$1', [req.serverAuth.id]);
      await client.query('DELETE FROM server_backups WHERE server_id=$1', [req.serverAuth.id]);
      await client.query('DELETE FROM destructive_requests WHERE server_id=$1', [req.serverAuth.id]);
    } else {
      const accountId = req.serverAuth.account_id;
      await client.query('DELETE FROM servers WHERE id=$1', [req.serverAuth.id]);
      const remain = await client.query('SELECT count(*)::int AS c FROM servers WHERE account_id=$1', [accountId]);
      if (remain.rows[0].c === 0) await client.query('DELETE FROM accounts WHERE id=$1', [accountId]);
    }
    await client.query('COMMIT');
    res.json({ ok: true, action: d.action });
  } catch (e) { await client.query('ROLLBACK').catch(()=>{}); next(e); }
  finally { client.release(); }
});

app.use((err,_req,res,_next) => {
  console.error(err);
  res.status(500).json({ error: 'server_error' });
});

app.listen(PORT, () => console.log(`Universal SMP Backup API listening on :${PORT}`));
