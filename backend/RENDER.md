# Deploy the Park Lanka API to Render

This deploys the Java 17 Spring Boot API against the existing Neon database.
JavaFX stays on each desktop and connects only to the API. No npm build is involved.

## Render dashboard

After reviewing and pushing the intended files below, choose **New > Web Service**
in the [Render dashboard](https://dashboard.render.com/) and connect GitHub.

| Setting | Value |
| --- | --- |
| Repository | `SahanWickramasingha/park-lanka` |
| Branch | `main` |
| Auto-Deploy | Off for manual deployments after pushes |
| Root Directory | `backend` |
| Runtime | Docker |
| Dockerfile Path | `./Dockerfile` |
| Docker Build Context Directory, if shown | `.` (relative to `backend`) |
| Docker Command | Leave blank; use the image's `java -jar` entrypoint |
| Region | Singapore, if available |
| Instance | Free for initial testing |
| HTTP Health Check Path | Leave blank; use the default TCP check |

These paths are relative to the service's root directory, as described in
[Render's monorepo documentation](https://render.com/docs/monorepo-support).
See [Docker on Render](https://render.com/docs/docker) for image deployment settings.

Enter these values in **Environment**, not in source files or Docker build arguments:

| Variable | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `neon` |
| `DATABASE_URL` | Full pooled Neon PostgreSQL URI, including its credentials and TLS query |
| `DATABASE_URL_UNPOOLED` | Full direct Neon PostgreSQL URI for the same endpoint, database and role |
| `PARKING_ADMIN_USER` | Your configured admin username |
| `PARKING_ADMIN_PASSWORD` | Your configured admin password |
| `PORT` | `10000` |

Use the existing database's connection URIs from your private configuration. Do not
paste literal placeholder text or surrounding shell quotes into Render's value fields.
Separate `DATABASE_USER` / `DATABASE_PASSWORD` are not required in Neon mode.
Do not upload `.env`, `.env.local`, local database files, or backups as Render secret files.

The container defaults to the `neon` profile, binds to `0.0.0.0`, and reads `PORT`
through `server.port`. It runs as UID/GID `10001`, with writable `/app`, `/app/backups`
and the standard temporary directory. Only the exact executable
`parking-backend-0.2.0.jar` is copied into the Java 17 runtime stage.

Choose **Deploy Web Service** manually. Confirm the logs reach Flyway validation
and migration completion (or an up-to-date schema), Hibernate validation, and
`Started ParkingApplication`. The JDBC URLs contain no embedded credentials.
Do not publish raw environment values or diagnostic logs containing secrets.

[Render's default health checks](https://render.com/docs/health-checks) probe TCP.
This project has no public HTTP health endpoint; `/`, `/api/me`, and administrative
API paths must not be configured as unauthenticated HTTP health checks. TCP checks
prove the listener accepts connections; verify a real authenticated operation after
deployment using the desktop. Authentication and branch/account authorization are unchanged.

## Credentials and database behavior

`NeonEnvironment` separates and percent-decodes URI credentials, creates JDBC URLs
with certificate/hostname verification (`sslmode=verify-full`), and translates
`channel_binding` to pgJDBC's `channelBinding`. By default it uses Java's trusted CA
store; a custom `sslrootcert` must point to a separately provisioned PEM file.
Complete injected URIs require no dotenv files and take precedence over raw
`SPRING_DATASOURCE_*` and `SPRING_FLYWAY_*` overrides. Flyway always uses the direct
URL. Leave those Spring override variables unset in Render.

Hibernate remains `validate`; Flyway clean and baseline-on-migrate remain disabled,
and automatic legacy adoption remains off. Normal startup runs existing migrations
and initialization in the configured Neon database. Unknown unversioned schemas
still require the reviewed procedure in [UPGRADE.md](../UPGRADE.md).
Deployment does not import local records, reset tables, or modify local database configuration.

The admin is a **configuration-backed login**, not a database account created or
reset by these environment variables. Startup BCrypt-encodes the supplied password
in memory and grants that username `ADMIN`. Changing the configuration changes
this login after a restart; it does not reset any database cashier's password.
The username must be 3–32 lowercase username characters (`[a-z][a-z0-9_.-]{2,31}`),
and the password must be 10–72 UTF-8 bytes. Startup rejects an admin username that
collides with any existing cashier, including an archived account. Cashier accounts,
password hashes, enabled state and branch assignments continue coming from Neon.

On JavaFX's login screen, open **Connection settings**, save the deployed base URL
such as `https://YOUR-SERVICE.onrender.com` (no `/api` suffix), and sign in. Remove
an old `PARKING_SERVER_URL` environment override if it prevents the saved setting
from taking effect. Never distribute Neon URIs or database credentials to clients.

## Free instance and backup limits

[Render Free services](https://render.com/docs/free) sleep after 15 minutes without
inbound traffic. Waking typically takes about a minute; Java initialization and
Neon activation can add latency. The desktop's first request may time out: wait
for startup and retry. Free is for initial testing; validate capacity and availability
before operating live parking/payment workflows.

The image intentionally does **not** install `pg_dump` or `pg_restore`.
`BackupService` invokes `pg_dump` only in a requested/scheduled backup worker, not
during normal startup. Missing tools therefore do not prevent the API from starting.
Manual PostgreSQL backups and restore-plan preparation are unsupported in this
image; requesting a backup records a failed job rather than stopping the API.
Leave `PARKING_BACKUP_CRON` unset (default `-`, disabled).

`/app/backups` is writable **temporary storage**, not durable backups. Render Free
loses filesystem changes on restarts, redeploys and idle shutdowns and cannot attach
a persistent disk. Backup history can remain in Neon after its archive disappears.
An externally stored, tested PostgreSQL backup/recovery process is still required
for operational use; it is not implemented by this container. Adding client tools
later requires checking their version compatibility with the actual Neon server
and using a direct connection, verified TLS and durable archive storage.

## Local validation without production Neon

Run from the repository root with JDK 17 and Maven on `PATH`:

```powershell
mvn -f backend/pom.xml '-Dparking.build.directory=target/render-verify' package
docker build --tag park-lanka-backend:render ./backend
```

The separate Maven output directory avoids replacing a running local/Neon JAR.
Tests use isolated H2/test databases. Surefire removes injected datasource and
Flyway connection settings, Spring JSON and external configuration locations so
deployment credentials cannot redirect the suite to production. PostgreSQL tests
require an explicit isolated loopback `PARKING_TEST_POSTGRES_URL`; otherwise they skip.
The Docker build skips test execution because some repository tests inspect sibling
desktop sources; run the Maven suite above first. The build context contains only
`pom.xml` and sanitized `src` inputs, with env files excluded at every depth.
Maven also excludes env files, private key stores, generated directories and backups
from application resources. Git ignores these local artifacts and root `original/`;
the explicit staging list below also leaves root npm/Neon CLI files unstaged.

Docker CLI installation alone is insufficient: `docker version` must report a
reachable server before the image build and smoke test can run. Existing JARs and
Surefire reports under `target` can be stale; only count results from the current build.

For an image startup smoke test, use the explicit H2 profile and disposable in-memory
database, never a production env file:

```powershell
docker run --detach --name park-lanka-render-smoke --memory=512m `
  --publish 127.0.0.1:18081:10000 `
  --env SPRING_PROFILES_ACTIVE=h2 `
  --env 'DATABASE_URL=jdbc:h2:mem:render_smoke;DB_CLOSE_DELAY=-1' `
  --env DATABASE_USER=sa `
  --env PARKING_ADMIN_USER=smokeadmin `
  --env PARKING_ADMIN_PASSWORD=disposable-smoke-password `
  park-lanka-backend:render
docker logs park-lanka-render-smoke
```

The password above is only a public disposable test fixture. After startup, use it
for an authenticated `GET http://127.0.0.1:18081/api/admin/branches` and expect HTTP
200 with the initialized branch. Confirm unauthenticated access is rejected. Clean
up only this named disposable container with `docker rm --force park-lanka-render-smoke`.
An H2 smoke test validates image startup, not deployed Render/Neon connectivity.

## Review, stage, commit and push manually

This explicit list includes the Neon support needed by deployment. Run from the
repository root. It excludes `original/`, `neon.ts`, root npm files and secret files.
Review any changes that were already present before committing.

```powershell
$deployFiles = @(
  '.gitignore'
  'README.md'
  '.env.local.example'
  'backend/.env.example'
  'backend/pom.xml'
  'backend/run.ps1'
  'backend/run-neon.ps1'
  'backend/verify-database.ps1'
  'backend/Dockerfile'
  'backend/.dockerignore'
  'backend/RENDER.md'
  'backend/src/main/java/lk/sahan/parking/config/LocalEnvironment.java'
  'backend/src/main/java/lk/sahan/parking/config/NeonEnvironment.java'
  'backend/src/main/java/lk/sahan/parking/maintenance/BackupArchive.java'
  'backend/src/main/java/lk/sahan/parking/maintenance/DatabaseCheck.java'
  'backend/src/main/java/lk/sahan/parking/maintenance/PostgresTools.java'
  'backend/src/main/resources/application-neon.properties'
  'backend/src/test/java/lk/sahan/parking/config/NeonEnvironmentTest.java'
  'backend/src/test/java/lk/sahan/parking/config/RenderEnvironmentTest.java'
)
git add -- $deployFiles
git diff --cached --name-only
git diff --cached --check
git status --short
```

Only continue after the staged filename list contains the intended files. If an
unrelated file was already staged, `git restore --staged -- EXACT_PATH` removes it
from the index without deleting the working copy. Keep real secret values out of
diffs and commit messages.

```powershell
git commit -m "Prepare Neon backend for Render deployment"
git push origin main
```

No commit, push, image publication or Render deployment is performed by these
repository changes. Your local PostgreSQL and local Neon startup scripts remain available.
