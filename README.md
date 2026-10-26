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
