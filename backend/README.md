# Hosted Backup API

This API is the only component that talks to Neon. Minecraft servers never receive Neon credentials.

## Environment variables
- `DATABASE_URL`: your Neon pooled connection string
- `TOKEN_SECRET`: long random secret used to hash server bearer tokens
- `PASSWORD_LOOKUP_SECRET`: separate long random HMAC key used to locate an account for `/backup join <password>`
- `PORT`: optional, defaults to 3000

## Setup
1. Run `sql/schema.sql` against Neon.
2. Set the environment variables on your host.
3. `npm install`
4. `npm start`
5. Compile the plugin after replacing its `ServiceConstants.API_BASE_URL` with this API's HTTPS address.

The owner-facing Minecraft `config.yml` contains no Neon settings.

Retention: the API keeps 2 newest player snapshots per player, caps player-backup data at 100 MiB per server and 950 MiB globally, and keeps 2 newest completed full-server backups per server. These limits are hard-coded and are not exposed in the Minecraft config.
