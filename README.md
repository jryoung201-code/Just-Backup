# Just-Backup

Universal multi-server **player-data backup** plugin for Paper 1.21.11 / Java 21.

## Important
Just-Backup does **not** back up worlds, chunks, region files, maps, or full server archives.

Everything stored remotely must be linked to a player and enabled under `Backup.Player-Data` or `Backup.Plugin-Data` in `config.yml`.

## Commands
- `/backup save player` — save your player data
- `/backup save all` — save all currently loaded players
- `/backup load player` — load your latest player backup
- `/backup load all` — queue the latest backup for every known player; offline players restore when they join
- `/backup password create <password> <confirm>`
- `/backup password reset <old> <new> <confirm>`
- `/backup join <password>`
- `/backup wipe <password>`
- `/backup wipe --confirm`
- `/backup unregister <password>`
- `/backup unregister --confirm`

## Storage policy
These limits are hard-coded and are not exposed in the Minecraft config:
- newest **2 backups per player**
- maximum **100 MiB of player-backup data per registered server**
- maximum **950 MiB of player-backup data across all servers**

## Neon
- project: `just-backup`
- project ID: `wandering-glade-30809914`
- database: `justbackup`

Neon credentials are never committed to this repository or shipped in the plugin JAR. The hosted API uses environment variables.

## Repository layout
- `plugin/` — Paper plugin
- `backend/` — hosted backup API
- `backend/sql/schema.sql` — player-data-only schema
