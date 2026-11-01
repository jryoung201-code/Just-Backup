# Just-Backup

Universal SMP player-backup system for Paper 1.21.11 / Java 21.

## Backend
- Neon project: `just-backup`
- Neon project ID: `wandering-glade-30809914`
- Database: `justbackup`
- Database credentials are **not stored in this repository**.
- Hosted API reads `DATABASE_URL`, `TOKEN_SECRET`, and `PASSWORD_LOOKUP_SECRET` from environment variables.

## Retention
- 2 newest player backups per player
- 100 MiB maximum player-backup storage per registered server
- 950 MiB maximum player-backup storage across all servers combined

## Commands
- `/backup save player`
- `/backup save all`
- `/backup load player`
- `/backup load all`
- `/backup password create <password> <confirm>`
- `/backup password reset <old> <new> <confirm>`
- `/backup join <password>`
- `/backup wipe <password>`
- `/backup wipe --confirm`
- `/backup unregister <password>`
- `/backup unregister --confirm`

The complete source is committed under the normal project folders.