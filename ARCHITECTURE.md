# PostgreSQL architecture and local operation

JavaFX Admin/Cashier clients ? Spring Boot REST API ? PostgreSQL. Only the backend has database credentials or the configured administrator secret. Desktop users enter their own credentials to authenticate to the API; the client contains no preconfigured admin password. Existing parking, shifts, pricing snapshots, idempotent checkout, corrections, receipts/reprints, reports, broadcasts, private messaging and audit APIs retain their authorization boundaries.

## Local configuration and discovery

`backend/.env.example` contains connection details and **blank password placeholders**. A new local `backend/.env` has been prepared only when none existed. Enter `DATABASE_PASSWORD` and `PARKING_ADMIN_PASSWORD` there using a local editor. Existing `.env` files are never overwritten. `DATABASE_URL` is `jdbc:postgresql://localhost:5432/parking_pos`, `DATABASE_USER` is `parking_app`, and `PARKING_ADMIN_USER` is `admin`. No default passwords are supplied. Real OS environment variables and command-line Spring settings take priority over this file. Remove obsolete inherited values if you intend to use `.env`.

The backend's registered `LocalEnvironment` postprocessor explicitly loads UTF-8 `.env` settings before beans/data connections are created. From the project root it selects `backend/.env`; from `backend/` it selects `.env`. From another working directory, set `PARKING_ENV_FILE` to the absolute file path. A supplied path must exist. Maven `spring-boot:run` and packaged-JAR startup use this same discovery. `backend/run.ps1`, `verify-database.ps1`, `migrate-h2.ps1` and `restore.ps1` select their own backend directory. The standalone maintenance tools use the same parser/OS precedence. The frontend loads none of these settings.

Use `KEY=value`, optional `export`, and optional matching single/double quotes. Values are literal, with no shell expansion/backslash decoding; an unquoted whitespace-plus-`#` starts a trailing comment. Keep multiline secrets out of this format. Duplicate/invalid lines fail by line number without showing their values. Real `.env` files are ignored by Git and excluded from resources/JARs. The optional file is unnecessary in deployment: inject settings through the platform's environment/secret store.

PostgreSQL settings and the configured admin are mandatory. Invalid/missing settings fail startup; Hikari/Flyway report connectivity or schema failures. There is no automatic H2 fallback. Only an explicitly selected `h2` or `test` Spring profile permits an H2 URL. The `h2` development profile defaults to `target/h2-development`, separate from the preserved operational file; explicitly select a backup-copy URL when rehearsing a legacy adoption. Never point test profiles or Hibernate `create`/`create-drop` at `parking_pos`.

## Exact Windows commands

Use JDK 17 and Maven. Commands below are PowerShell. Do not start the backend against a target intended for H2 import before importing: initial Main Branch/slots/rate seeds make that target nonempty.

```powershell
cd 'C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\backend'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;C:\Users\WW\Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin;$env:Path"
notepad .\.env                 # enter secrets locally; do not paste them into chat
mvn package                    # tests use isolated H2; never the configured live DB
.\verify-database.ps1           # read-only; no migrations/seeds/grants
# New empty installation: start after verification.
# Existing H2 installation: complete the backup/import process below first.
.\run.ps1
```

For direct launch from this `backend` directory: `mvn spring-boot:run` or `java -jar .\target\parking-backend-0.2.0.jar`. Keep the same working directory for relative backup paths. From another directory, configure `PARKING_ENV_FILE` explicitly. The read-only verifier checks the requested database/role, CONNECT and public-schema USAGE/CREATE privileges and visible table count; it never changes ownership or grants. Existing schema objects may also require owner privileges for ALTER; Flyway stops if insufficient. It explicitly refuses the unrelated `axona_db`.

Open a second terminal:

```powershell
cd 'C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\desktop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\run.ps1
```

Log in at `http://localhost:8080` as the configured admin. Create database cashiers and branch assignments through Cashiers. Newly created installations contain no cashier accounts. Retain HTTPS for nonlocal/public API access; PostgreSQL credentials never belong in the desktop.

## Schema and identities

Existing Flyway V1?V8 are unchanged. They already cover the actual schema, including messaging/read receipts, shifts, pricing, audits, backup jobs and payment corrections. V9 adds a nullable unique cashier `legacy_owner_key`; V10 adds an atomic import receipt. Hibernate uses `validate`. Unknown nonempty databases are refused; no blind baseline. The older complete known pre-Flyway schema still has the explicit, backed-up adoption checks documented in UPGRADE.md. An incompatible/unknown source needs a reviewed intermediate upgrade on a disposable copy.

Primary keys, foreign keys, unique indexes, numeric precision, timestamps and soft-delete fields remain intact. Main Branch, slots and the global rate are initialized idempotently, without demo users. Business APIs keep the existing locking and idempotency rules.

The configured admin is required, BCrypt-encoded in memory and never persisted in plaintext. It cannot shadow any active or archived cashier username. All cashiers authenticate from database BCrypt hashes; branch deletion, disablement, soft deletion, reset and role isolation remain enforced. Username conflicts with the configured admin are rejected. No hashes are returned in API views/Excel exports.

`PARKING_USER` and `PARKING_PASSWORD` no longer create/authenticate a cashier and are unnecessary. There is no artificial cashier ID 0, implicit Main Branch access, synthetic account count or synthetic broadcast recipient. Historical attribution strings remain unchanged.

### Deliberate legacy cashier transition

After import, use the Admin Cashiers screen to create the **same exact old username**, assign Main Branch and enter a new password locally. No legacy password is required. Creating that name alone grants no legacy private-message access. Before signing in as that cashier or opening new shifts/messages, select that verified same-person account and choose **Link Legacy History**, typing `LINK LEGACY <exact username>`.

The admin-only `PUT /api/admin/cashiers/{id}/legacy-identity` action requires the same exact username, Main Branch, real legacy history, no conflicting legacy assignment and no existing private history under the new database identity. It rejects inconsistent historical owner/branch attribution. A successful link is audited and binds the immutable old `bootstrap:<username>` identity without rewriting messages, notices, read receipts or shift ownership. Future messages/shifts use that identity. Another user's messages cannot be claimed by choosing a different name. Archived historical database cashiers retain their IDs and hashes; do not recreate them. If the legacy username cannot pass the current username rules, conflicts with an existing account/admin, or has inconsistent history, stop for reviewed reconciliation; no automatic reassignment is performed.

## H2 preservation and PostgreSQL import

The existing operational H2 file is `backend/parking-pos-data.mv.db`. Changing `DATABASE_URL` does not transfer it. Do not delete it, copy it while actively written, or run this importer on the live file. Keep the original artifact/configuration as a rollback pair. Do not open the operational file merely to inspect it.

1. Schedule downtime and finish parking/cash work where possible. Stop only this project's backend in its own terminal. Verify that it released the H2 file.
2. Create a dated copy of the **stopped** `.mv.db` in durable storage outside `target/`, plus an off-PC copy. Alternatively use the existing H2 Admin Backups consistent `BACKUP` archive and the approved offline restore process to produce a separate backup file. Record counts/balances and keep access restricted. Never assume a raw copy of an active file is consistent.
3. Configure the PostgreSQL destination locally. Use a separate rehearsal target first. Do not start the backend there: the migration command creates the schema without application runners/seeds. The destination must have no business rows, including seeds or a prior import receipt. An already seeded target is refused; choose another reviewed empty destination rather than deleting records.
4. From `backend/`, build the JAR and run the default dry-run against the stopped backup:

```powershell
mvn package
.\migrate-h2.ps1 -SourceBackup 'C:\ParkingBackups\verified-stopped-before-postgres.mv.db'
```

The command refuses the known operational H2 file path. The dry-run reads a separate H2 backup with `IFEXISTS=TRUE;ACCESS_MODE_DATA=r;AUTO_SERVER=FALSE`, checks its Flyway history or supported legacy schema, copies it into a disposable in-memory validation schema, and inspects the PostgreSQL target read-only. It runs no target DDL or writes. It checks every business table/relationship, current constraints, occupancy/active-plate state and paid-session/correction state, and reports per-table counts, active sessions and financial totals without passwords/hashes. Unsupported source schemas are refused, never guessed. Optional `H2_SOURCE_USER`/`H2_SOURCE_PASSWORD` may be entered in backend `.env` if the backup used a nondefault database login.

5. Review/rehearse results and obtain explicit approval before applying to operational data. **No live apply is performed by setup/tests.** After approval, with the correct backend stopped:

```powershell
.\migrate-h2.ps1 -SourceBackup 'C:\ParkingBackups\verified-stopped-before-postgres.mv.db' -Apply -Confirm IMPORT_STOPPED_BACKUP
```

Apply uses the same Flyway migrations and JDBC type conversion; H2 SQL dumps are never sent to PostgreSQL. Source records/IDs/hashes/read states/timestamps/archives/monetary values are preserved. All business rows, sequence restarts and an import receipt commit atomically under an advisory lock and table locks. Counts and a deterministic digest of all migrated column values must match; FKs and uniqueness are enforced. Identity `RESTART` values exceed imported IDs and roll back on failure. A failed import leaves **no committed business rows**; successfully created empty Flyway schema may remain. A committed receipt and nonempty-target checks reject retries/merges, including an empty-source import. Keep the backend stopped for the entire dry-run/apply; concurrent startup/seeding is unsupported and any committed seeds cause refusal.

6. Compare totals, IDs, active vehicles, slots, receipts, read states and audit history in pgAdmin before starting cash operations. Start the backend, then create/link the legacy cashier deliberately. On restart, check that Main Branch/slots/rates did not duplicate.

Rollback before reopening operations: retain PostgreSQL for investigation, restore the saved **old artifact/configuration and stopped H2 backup into a separate path**, then switch the backend explicitly back to the matching H2 configuration/profile. Never merge later PostgreSQL transactions back into the old H2 copy automatically. If business activity occurred after cutover, a reviewed reconciliation is required. Never reset Flyway history, drop live tables or delete the original H2 file to make an upgrade pass.

## Backups and cloud TLS

PostgreSQL Admin Backups uses server-side `pg_dump --format=custom --no-owner --no-acl`; no H2 file-copy behavior applies. Install compatible PostgreSQL client utilities on the backend server. Set `PARKING_PG_DUMP`/`PARKING_PG_RESTORE` to full utility paths if not on PATH. Existing versioned ZIP manifests/checksums, retention, admin maintenance and restore-plan approval remain. Schema versions through V10 and older supported archives are recognized.

A restore is explicit, offline and only into a separate empty destination, with an approved plan and `RESTORE_STOPPED_SERVER` confirmation. Set `RESTORE_DATABASE_URL`, `RESTORE_DATABASE_USER`, `RESTORE_DATABASE_PASSWORD` locally or via injected secrets. It refuses the configured operational destination and `axona_db`. Test restores on disposable databases; do not restore over live data.

For cloud PostgreSQL use provider-specific CA files/hostname and `sslmode=verify-full`, optionally `sslrootcert`, `sslcert`, `sslkey`; URL-encode query values/paths. Supported JDBC TLS parameters are mapped to libpq environment settings for backups/restores, so certificate verification stays consistent. No credentials appear in child-process arguments, command examples, manifests or retained utility output. Unsupported URL options are refused rather than silently dropped. Do not replace certificate verification with a nonvalidating SSL factory to bypass setup errors. See [pgJDBC SSL](https://jdbc.postgresql.org/documentation/ssl/) and [PostgreSQL pg_dump](https://www.postgresql.org/docs/current/app-pgdump.html).

Local backups require off-PC copies and restore rehearsals. A future cloud deployment additionally needs network/firewall restrictions, TLS to the API/database, persistent secrets, a database role authorized for reviewed migrations and application writes, and provider backup/PITR/retention policies. Provider snapshots do not replace testing that application data can be restored. No deployment is performed here.

## pgAdmin verification

Connect pgAdmin to **localhost:5432 ? parking_pos as parking_app**; enter its password locally. Do not select or modify `axona_db`. Expand Databases ? parking_pos ? Schemas ? public ? Tables; refresh after migration. Inspect `flyway_schema_history`, `branches`, `cashier_accounts`, `parking_slots`, `parking_sessions`, `payments`, `cashier_shifts`, `payment_corrections`, `conversations`, `conversation_posts`, `notice_reads` and `audit_records`. Never export/share password hashes. Safe Query Tool checks:

```sql
select current_database(), current_user;
select installed_rank, version, description, success from flyway_schema_history order by installed_rank;
select id, code, name, deleted, stopped from branches order by id;
select id, username, branch_id, enabled, deleted from cashier_accounts order by id;
select count(*) as active_vehicles from parking_sessions where exited_at is null;
select count(*) as occupied_slots from parking_slots where occupied = true;
select count(*) as payment_count, coalesce(sum(amount),0) as gross from payments;
select type, count(*), sum(amount) from payment_corrections group by type;
```

## Verification commands and boundaries

`mvn test` in `backend/` runs isolated H2 regression/configuration tests. Test fixtures create database cashiers only in test databases; none ship with the application. Local `.env` loading is disabled in tests and inherited database/admin secrets are excluded from the forked test process. PostgreSQL tests are enabled only by `PARKING_TEST_POSTGRES_*` settings and reject nonloopback/non-`parking_pos_test_<uuid>` URLs.

`backend/verify-postgres.ps1` initializes a fresh temporary SCRAM-authenticated PostgreSQL cluster under `backend/target/pg-verification-<uuid>`, using a random password and free loopback port. It creates only disposable `parking_pos_test_<uuid>` databases, runs Maven `verify` (tests plus backend packaging), and stops only that cluster in `finally`. It uses installed PostgreSQL binaries, not Docker, and never accesses the normal localhost:5432 cluster. It verifies fresh Flyway migration, no-cashier startup, lifecycle/branch isolation, concurrent checkout, cash/card receipts, exports/corrections/audits, messaging/read state, deliberate legacy linking, context restart without duplicate seeds, H2 transfer of every business table, transactional failure/retry, identity sequences and pg_dump/restore on a separate disposable target. Test artifacts/logs remain under `target` for review.

```powershell
# From backend/, with JAVA_HOME/Maven set as above:
.\verify-postgres.ps1
# From desktop/:
mvn package
.\verify-ui.ps1
```

The native UI script uses a localhost fixture, not the business database. Physical printer/driver/paper, card terminal reconciliation, real network failures, multi-PC operation and the actual user's database credentials/permissions remain target-environment checks. A reachable TCP port alone does not verify authentication or database permissions.

Spring configuration precedence follows [Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html).
