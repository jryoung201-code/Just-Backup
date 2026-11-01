CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  password_hash TEXT NOT NULL,
  password_lookup TEXT NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS servers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  registered_ip TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_servers_account ON servers(account_id);

CREATE TABLE IF NOT EXISTS player_backups (
  id BIGSERIAL PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  source_server_id UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  player_uuid UUID NOT NULL,
  player_name TEXT,
  created_at_ms BIGINT NOT NULL,
  checksum TEXT NOT NULL,
  compressed_data BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_player_latest ON player_backups(account_id, player_uuid, created_at_ms DESC);

CREATE TABLE IF NOT EXISTS destructive_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  server_id UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  action TEXT NOT NULL CHECK (action IN ('wipe','unregister')),
  code_hash TEXT NOT NULL,
  code_verified BOOLEAN NOT NULL DEFAULT false,
  ip_verified BOOLEAN NOT NULL DEFAULT false,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
