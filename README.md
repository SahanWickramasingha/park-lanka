# Park Lanka

**Smart Parking. Simple Control.**

A parking POS system with JavaFX Admin and Cashier applications, a Spring Boot REST API, and PostgreSQL persistence.

## Features

- **Parking:** vehicle entry, numbered slots, QR entry tickets, fee quotes, checkout and parking history.
- **Payments:** cashier shifts, cash received/change calculation, receipts and reprints, and audited refunds/voids.
- **Administration:** branch and cashier management, pricing rules, saved records, Excel reports and audit history.
- **Communication:** announcements, notifications and private Admin–Cashier conversations.
- **Data:** branch-based access, BCrypt cashier passwords and versioned Flyway migrations.

External card transactions are processed on a separate terminal; Park Lanka records the manually confirmed payment only.

## Architecture

JavaFX clients communicate with the Spring Boot API. Only the backend connects to PostgreSQL; desktop clients print tickets and receipts locally.

## Run locally on Windows

Requirements: **JDK 17, Maven and a running PostgreSQL database**.

1. Follow [ARCHITECTURE.md](ARCHITECTURE.md) to configure the database and backend credentials. For existing data, follow [UPGRADE.md](UPGRADE.md) before starting the updated backend.
2. Create `backend/.env` from its example if it does not already exist. Set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `PARKING_ADMIN_USER` and `PARKING_ADMIN_PASSWORD`. Keep existing configuration and never commit real secrets.
3. From the project root, start the backend:

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
   $env:Path = "$env:JAVA_HOME\bin;$env:Path"
   .\backend\run.ps1
   ```

4. Once the backend has started, open another terminal at the project root:

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
   $env:PARKING_SERVER_URL = 'http://localhost:8080'
   .\desktop\run.ps1
   ```

Sign in with the configured admin account and create cashiers with branch assignments. Cashiers must open a shift before collecting payments.

## Optional Neon PostgreSQL backend

Local startup with `backend/run.ps1` still uses `backend/.env` and its existing JDBC settings.
Cloud startup is explicit and does not copy local records or change either env file.

Keep the Neon CLI's root `.env.local` private (see `.env.local.example` for a sanitized shape).
It must contain `DATABASE_URL` and `DATABASE_URL_UNPOOLED` for the same Neon endpoint, database
and role. The application uses the former; Flyway uses the direct, unpooled latter.
The backend extracts and percent-decodes credentials separately from the JDBC URLs,
requires `sslmode=verify-full`, and uses the JDK trusted CA store unless `sslrootcert`
specifies a PEM file. `channel_binding` is translated to pgJDBC's `channelBinding`.
An explicit `PARKING_NEON_ENV_FILE` can select a different private cloud file; injected
`DATABASE_URL` / `DATABASE_URL_UNPOOLED` override its URIs. No local JDBC fallback is used in cloud mode.

Retain `PARKING_ADMIN_USER` and `PARKING_ADMIN_PASSWORD` in backend-only `backend/.env`
or injected environment variables. The local database credentials in that file are ignored
only during explicit Neon startup. Never give database credentials to the JavaFX client.

From the project root in PowerShell (JDK 17 and Maven required):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:PORT = '8081' # Use a free port if the local backend already uses 8080.
.\backend\run-neon.ps1
```

To build/test and inspect Neon **before** startup (the inspection performs no migrations or writes):

```powershell
mvn -f backend/pom.xml '-Dparking.build.directory=target/neon' package
.\backend\verify-database.ps1 -Neon
```

The cloud script builds under `backend/target/neon` so it does not replace a running local JAR.
Startup applies the existing Flyway migrations to Neon, validates Hibernate's schema,
and runs the existing application initialization. An unknown existing schema is still
refused; do not enable legacy adoption without following `UPGRADE.md`. No records from
the local database are imported. Inspection reports whether the public schema is empty,
Flyway history, and application row counts, without printing credentials.
PostgreSQL backup/restore tools using the system CA store require libpq 17+; a PEM
`sslrootcert` can instead be supplied in both cloud URIs.

In a separate terminal, the desktop connects only to the API:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:PARKING_SERVER_URL = 'http://localhost:8081'
.\desktop\run.ps1
```

Use a reachable HTTPS API URL when the backend is hosted remotely. Neon hosts the database;
the Spring Boot API must still be running on your PC or a separate backend host.

For hosted API deployment, follow [Render deployment](backend/RENDER.md).
The Docker image contains only the backend; JavaFX connects to its HTTPS API URL.

## Windows packaging

With JDK 17, Maven and WiX 3.x available, run from the project root:

```powershell
.\desktop\package-windows.ps1
```

See [Windows packaging](desktop/WINDOWS-PACKAGING.md) for app-image/installer output, bundled runtime details and connection settings. The desktop package requires a reachable backend; it does not start the backend automatically. Use HTTPS for a public backend.

## Verification

With Java and Maven on PATH, run from the project root:

```powershell
mvn -f backend/pom.xml test
mvn -f desktop/pom.xml package
.\desktop\verify-ui.ps1
```

The desktop UI check uses a local test fixture. Physical printing, PostgreSQL recovery and multi-PC operation require separate target-environment checks.

## Documentation

- [Architecture and database setup](ARCHITECTURE.md)
- [Upgrades, backups and recovery](UPGRADE.md)
- [Printing and checkout](desktop/PRINTING-CHECKOUT.md)
- [Windows packaging and branding](desktop/WINDOWS-PACKAGING.md)

Completed payments remain saved if printing fails. Use receipt history to reprint and pending checkouts to reconcile uncertain payment attempts before retrying.
