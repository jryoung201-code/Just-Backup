# Just-Backup

Just-Backup is a **player-data cloud backup plugin for Paper 1.21.11**.

> Current release: **v1.9.3**  
> Requires **Java 21** and **Paper 1.21.11**

## What it backs up

By default, Just-Backup can save:

- Gamemode
- OP status
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

## What it does not back up

Just-Backup does **not** back up:

- Worlds
- Chunks
- Region files
- Full server folders
- Server configuration files

It is only for player-linked data.

## Installation

1. Download the latest JAR from the GitHub Releases page.
2. Put the JAR in your server's `plugins/` folder.
3. Start the server.
4. Create your backup account:

```
/backup password create <password> <confirm-password>
```

5. The server will restart after setup.
6. Once it starts again, backups are active.

## Link another server

Install Just-Backup on the other server, then run:

```
/backup join <password>
```

That server will connect to the same backup account.

## Commands

Save one online player:

```
/backup save <player>
```

Save every online player:

```
/backup save all
```

Load one player's newest backup:

```
/backup load <player>
```

Load backups for all known players:

```
/backup load all
```

Create a backup account:

```
/backup password create <password> <confirm-password>
```

Change the backup password:

```
/backup password reset <old-password> <new-password> <confirm-new-password>
```

Join an existing backup account:

```
/backup join <password>
```

Delete this server's cloud backups:

```
/backup wipe <password>
/backup wipe --confirm
```

Unregister this server:

```
/backup unregister <password>
/backup unregister --confirm
```

## Permissions

Admin commands require:

```
backup.admin
```

This permission defaults to server operators.

## Default automatic saves

By default, player data is saved:

- When a player quits
- When the server shuts down
- Every 1 hour

## Default automatic loads

The default config is:

```yaml
Load-When:
  - LOCAL_DATA_MISSING
  - SERVER_RESTART
```

Normal player joins do **not** automatically load a cloud backup.

Manual restores still work with:

```
/backup load <player>
/backup load all
```

## Default config

```yaml
Backup:
  Save-When:
    - PLAYER_QUIT
    - SERVER_SHUTDOWN
    - EVERY_60_MINUTES

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

## Important notes

If `HasOp: true`, restoring a backup can also restore the player's OP status.

If you do not want OP status restored, change it to:

```yaml
HasOp: false
```

## Updating

1. Stop the server.
2. Replace the old Just-Backup JAR with the new one.
3. Start the server.

Keep the existing plugin data folder unless the release notes say otherwise.

## Troubleshooting

If backups are not saving or loading:

- Check the server console for `[Backup]` errors.
- Make sure the server is registered.
- Make sure the player is online when using `/backup save <player>`.
- Make sure the player is known to the server when restoring an offline player.
- Make sure the server can make outbound HTTPS connections.
