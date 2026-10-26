# Safe upgrade, restart and recovery

This change is intentionally **not** applied to the saved operational database by development tests. Keep the existing backend running until you schedule an upgrade. Do not stop unrelated Java processes. Close only the desktop windows and backend terminal belonging to this project when restarting them.

Current PostgreSQL cutover uses [ARCHITECTURE.md](ARCHITECTURE.md#h2-preservation-and-postgresql-import). PostgreSQL/admin settings are required and `.env` is loaded explicitly. Legacy H2 adoption below applies only to an explicitly selected H2 rehearsal copy; it does not migrate H2 records to PostgreSQL. V1?V8 remain unchanged; V9/V10 add explicit legacy identity mapping and import receipts.

## Before the first upgraded backend start

1. Finish parking operations and close shifts where supported. Record the exact `DATABASE_URL`, current backend working directory, environment configuration and old application artifact.
2. Gracefully stop that backend using Ctrl+C in its terminal. Confirm that it has stopped and released the H2 database. Never copy an actively written H2 `.mv.db` file as an assumed consistent backup.
3. Copy the **stopped** `backend/parking-pos-data.mv.db` into a durable backup directory outside `target`, with a dated filename. Keep a second offline copy and the old backend artifact. The backup contains sensitive account/database records: restrict filesystem access. Do not store credentials in source control or command logs.
4. Rehearse on a disposable copy with a distinct database path and port. This version adopts only the complete known pre-Flyway schema. It checks tables, columns, types, sizes, nullability, unique keys and foreign keys before recording version 1. An older/different schema is refused and needs a reviewed intermediate upgrade; do not bypass the checks or enable baseline-on-migrate.
5. For the first adopted start only, set `PARKING_ADOPT_LEGACY_SCHEMA=true` and `PARKING_UPGRADE_BACKUP` to the verified stopped backup file. Existence of that file is an operator attestation; the schema check does not prove the backup came from the operational database. Remove both adoption settings after successful upgrade.
6. New databases run all versioned migrations automatically. Hibernate validates the schema; it no longer performs automatic destructive or additive updates. Historical payments remain unassigned to shifts and retain null tender/change values. Legacy pricing uses the existing entry rate or original configured fallback. New entries snapshot the complete effective policy.
7. Compare stable historical IDs/counts, receipts, conversation posts and balances before opening the upgraded system to cashiers. Start a new shift explicitly before new checkout activity.

## Windows backend terminal

Run these from the project root after completing the backup/rehearsal steps:

```powershell
cd 'C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\backend'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;C:\Users\WW\Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin;$env:Path"
# Enter required database/admin settings locally in backend/.env.
notepad .\.env
# H2 rehearsal only: $env:SPRING_PROFILES_ACTIVE = 'h2'
# First adoption only: set these to the verified backup, after rehearsing on a copy.
# $env:PARKING_ADOPT_LEGACY_SCHEMA = 'true'
# $env:PARKING_UPGRADE_BACKUP = 'C:\ParkingBackups\verified-stopped-before-upgrade.mv.db'
mvn package
java -jar .\target\parking-backend-0.2.0.jar
```

Preserve your configured database URL. An explicitly selected H2 profile uses a path relative to this `backend` working directory. PostgreSQL has no H2 fallback. Do not launch from another folder and accidentally create a different empty database. Do not use `clean` to remove prior recovery artifacts stored in `target`; move any needed backups to durable storage first.

## Windows desktop terminal

Close the old app window after saving any drafts. A JavaFX JVM keeps its previously loaded classes even after Maven rebuilds files.

```powershell
cd 'C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\desktop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\run.ps1
```

The title shows the build identifier. Connect to the correct backend URL. An older backend cannot provide the new endpoints and must be upgraded/restarted before using this client.

`backend/run.ps1` also locates the local Maven installation, builds 0.2.0 and starts it from the backend directory. Set the credential/database/adoption environment variables first, then use `.\run.ps1` in the backend terminal. Do not launch an old 0.1.0 artifact for these new endpoints.

For the first adoption, after the correct backend is **stopped** and the upgrade rehearsal has passed, this PowerShell fragment creates the stopped H2 backup and sets the explicit adoption variables. Run it from `backend`; do not run it while the database is open:

```powershell
$taskBackupDir = Join-Path $env:USERPROFILE 'ParkingPOS-Backups'
New-Item -ItemType Directory -Force -Path $taskBackupDir | Out-Null
$taskBackup = Join-Path $taskBackupDir ('before-0.2.0-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.mv.db')
Copy-Item -LiteralPath '.\parking-pos-data.mv.db' -Destination $taskBackup
$env:PARKING_ADOPT_LEGACY_SCHEMA = 'true'
$env:PARKING_UPGRADE_BACKUP = $taskBackup
.\run.ps1
```

Remove the two adoption variables for subsequent starts. Keep the backup. Version 0.2.0 is packaged alongside old artifacts because Windows can hold files used by a running JVM; never attempt to replace a running application's artifact as an upgrade procedure.

## Configuration

- `PARKING_BACKUP_DIRECTORY`: server-owned directory, default `backend/backups` when launched above. Ordinary API requests cannot supply a destination.
- `PARKING_BACKUP_KEEP`: retained successful ordinary backups, default 20, minimum 2. Maintain off-server copies; local retention is not disaster recovery.
- `PARKING_BACKUP_CRON`: optional Spring six-field cron in Asia/Colombo, e.g. `0 0 2 * * *`; `-` disables scheduling.
- `PARKING_PG_DUMP` / `PARKING_PG_RESTORE`: paths to PostgreSQL utilities. Install versions compatible with the server. PostgreSQL backup uses a consistent `pg_dump` custom archive; recovery uses `pg_restore --single-transaction --exit-on-error` against an empty destination. PostgreSQL JDBC TLS options are mapped consistently to pg_dump/pg_restore; use provider-appropriate `sslmode=verify-full` and CA settings in the URL. Put authentication in `DATABASE_USER` / `DATABASE_PASSWORD`. Credentials are supplied to child tools through their environment, never command arguments or backup manifests.
- `PARKING_MAINTENANCE=true`: start with parking writes blocked. The admin Backups screen can enter/leave maintenance after confirming and draining requests. Entering requires no active shifts or parked vehicles. Backup metadata remains writable to prepare recovery.
- Existing `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `PORT`, required configured admin and initial rate/slot settings remain supported; environment bootstrap cashier authentication is removed. Keep the old configured fallback rate available for sessions predating entry snapshots.

## Backup and restore

The Backups screen stores versioned checksummed archives on the **backend server**, not the cashier desktop. H2 uses its supported `BACKUP TO` operation; no hot file copy is performed. In-memory H2 is intentionally unsupported for operational backups. Job failures are visible and never reported as successful backups.

1. In the admin Backups screen choose a retained successful backup. Close all shifts and check out all vehicles, enter maintenance, then type the exact `PREPARE RESTORE <job-id>` confirmation.
2. Wait for `RESTORE_READY`. Preparation validates the source archive and creates a pre-restore backup plus an admin-approved plan in the server backup directory. It does not restore the live database. Keep maintenance enabled until the correct backend is gracefully stopped.
3. On the backend server, after stopping that backend, run:

```powershell
cd 'C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\backend'
.\restore.ps1 -Plan 'C:\ParkingBackups\restore-plan-<job-key>.json' `
  -Destination 'C:\ParkingRecovery\restored-copy.mv.db' -Confirm RESTORE_STOPPED_SERVER
```

The plan expires after 24 hours. Both source and pre-restore archive checksums must match. H2 restore requires a **new** `.mv.db` destination outside the backup directory and refuses existing files. It never overwrites the original database. Validate the separate restored database with maintenance enabled before changing `DATABASE_URL`; use the filename without `.mv.db` in the JDBC URL.

For PostgreSQL, create a separate empty database and configure `RESTORE_DATABASE_URL`, `RESTORE_DATABASE_USER`, `RESTORE_DATABASE_PASSWORD`, and `PARKING_PG_RESTORE`; run `restore.ps1` with the plan and confirmation, without `Destination`. The utility refuses a destination with public tables. Do not point it at the operational database.

Keep the original stopped database unchanged until recovery is verified. Reconcile vehicles, external-terminal transactions, payments and corrections after the backup timestamp: restoration cannot preserve later activity automatically. Never assert that an old backup represents current physical occupancy or real cash/card settlement. Restored backup-job metadata may show the original job as running because the snapshot precedes completion; the archive manifest and approved plan are the recovery evidence.

If an upgrade fails before accepting new business, retain the failed copy, restore the stopped backup to a separate path, and use the matching old application/configuration. Do not run the old application against a newly migrated schema. If business occurred after the backup, reconcile it before switching; restoring an earlier file loses those later transactions.

PostgreSQL verification requires a running isolated server plus client utilities. Real printer media/cutters and external card-terminal settlement require hardware/operator checks; software previews and stored references cannot prove those outcomes.
