# Just-Backup

Just-Backup is a **player-data cloud backup plugin for Paper 1.21.11**.

It is designed for server owners who want player inventories and other player-linked data backed up remotely without uploading world files or full server folders.

> Current release: **v1.9.3**  
> Requires **Java 21** and **Paper 1.21.11**

## What Just-Backup backs up

By default, Just-Backup can save:

- Gamemode
- OP status (`HasOp`)
- Location and world
- Inventory
- Armor
- Offhand
- Ender Chest
- Health
- Hunger and saturation
- Experience
- Potion effects
- Respawn point
- Advancements
- Statistics
- Discovered recipes

Each option can be enabled or disabled in `config.yml`.

## What it does NOT back up

Just-Backup does **not** back up:

- Worlds
- Chunks
- Region files
- World folders
- Map files
- Full server folders
- Plugins as a whole
- Server configuration files

The backup system is intentionally limited to data linked to individual players.

## Installation

1. Download the latest JAR from the GitHub Releases page.
2. Put the JAR inside your server's `plugins/` folder.
3. Start the server.
4. Create a Just-Backup account from the server console or as an authorized admin:

```
/backup password create <password> <confirm-password>
```

5. The server will restart after registration.
6. Once it comes back online, player backups are active.

Do not share the backup password. It is used to register or link servers to the same backup account.

## Linking another server

To connect another Paper server to the same Just-Backup account, install the plugin on that server and run:

```
/backup join <password>
```

The server will register with the same cloud backup account and restart.

Linked servers can access the latest backups for players in that account.

## Backup commands

Save one online player:

```
/backup save <player>
```

Example:

```
/backup save RealJR_25736
```

Save every currently online player:

```
/backup save all
```

Load one player's newest cloud backup:

```
/backup load <player>
```

If the player is online, the backup is applied immediately. If the player is offline but known to the server, the restore is queued and applied when they join.

Load the newest cloud backup for all known players:

```
/backup load all
```

Online players are restored immediately. Offline players are queued for restore.

## Account commands

Create a new backup account:

```
/backup password create <password> <confirm-password>
```

Change the account password:

```
/backup password reset <old-password> <new-password> <confirm-new-password>
```

Join an existing account:

```
/backup join <password>
```

Destructive account actions are protected by an additional verification flow:

```
/backup wipe <password>
/backup wipe --confirm

/backup unregister <password>
/backup unregister --confirm
```

`wipe` removes this server's remote player backups while keeping the registration.

`unregister` removes this server from the backup account and removes its associated remote backups. It does **not** delete local Minecraft player files.

## Permissions

Administrative backup commands require:

```
backup.admin
```

The permission defaults to server operators.

The current build also contains one hardcoded trusted player UUID that may use only `/backup save` and `/backup load` without `backup.admin`. It does not grant access to password, wipe, join, or unregister commands.

## Default automatic saves

The default configuration saves player data:

- When a player quits
- When the server shuts down
- Every 30 minutes

Shutdown and quit backups are handled so the cloud save completes before Paper finishes disabling the plugin.

## Default automatic loads

The default configuration is:

```yaml
Load-When:
  - LOCAL_DATA_MISSING
  - SERVER_RESTART
```

Normal player joins do **not** automatically load a cloud backup by default.

You can still restore manually with `/backup load <player>` or `/backup load all`.

## Default configuration

```yaml
Backup:
  Save-When:
    - PLAYER_QUIT
    - SERVER_SHUTDOWN
    - EVERY_30_MINUTES

  Load-When:
    - LOCAL_DATA_MISSING
    - SERVER_RESTART

  Player-Data:
    Gamemode: true
    HasOp: true
    Location: true
    World: true
    Inventory: true
    Armor: true
    Offhand: true
    EnderChest: true
    Health: true
    Hunger: true
    Saturation: true
    Experience: true
    Potion-Effects: true
    Spawnpoint: true
    Advancements: true
    Statistics: true
    Recipes: true

  Plugin-Data:
    Enabled: false
    Custom-Folders: []
```

## OP-status restores

When `HasOp: true`, new backups record whether the player was an operator.

When that backup is restored, their saved OP status is restored too.

Older backups that do not contain the `HasOp` field will not change the player's current OP status.

If you do not want cloud restores to change operator status, set:

```yaml
HasOp: false
```

## Advancement restores

Advancement progress can be restored without flooding chat.

Just-Backup temporarily suppresses advancement announcements while applying restored advancement criteria and then restores the world's previous advancement-announcement gamerule.

## Storage limits

The hosted backup service currently uses these limits:

- Maximum **2 backups per player**
- Maximum **100 MiB** of player-backup data per registered server
- Maximum **950 MiB** of player-backup data across the complete backup account

When possible, older backups are removed first while preserving the newest valid backup for each player.

## Cloud service

The plugin connects to the hosted Just-Backup API over HTTPS.

Database credentials and backend secrets are stored on the hosted service and are **not** included in the plugin JAR or committed to this repository.

## Updating

To update Just-Backup:

1. Stop the server.
2. Replace the old Just-Backup JAR with the newer release.
3. Start the server again.
4. Keep your existing plugin data folder unless the release notes specifically say otherwise.

## Troubleshooting

If backups are not saving, check the server console for messages beginning with:

```
[Backup]
```

During shutdown, a successful shutdown pass logs how many online players were processed.

Also verify that:

- The server is registered.
- The server can reach the hosted API over HTTPS.
- Your backup account has not reached its storage limit.
- The player is online when using `/backup save <player>`.
- The player is known to the server when loading an offline player.

## Repository layout

- `plugin/` — Paper plugin source
- `backend/` — hosted backup API
- `backend/sql/schema.sql` — backend database schema
