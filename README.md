# Park Lanka

**Smart Parking. Simple Control.**

A parking POS system built with JavaFX, Spring Boot and PostgreSQL.

## Download for Windows

[⬇ Download Park Lanka Setup](https://github.com/SahanWickramasingha/park-lanka/releases/latest)

1. Open **Assets** and download the Windows setup `.exe`.
2. Install and open **Park Lanka**.
3. Sign in with your assigned account.

Internet access is required. If needed, set **Connection settings → Server URL** to:

```text
https://park-lanka.onrender.com
```

## Features

- Vehicle entry, parking slots, QR tickets and parking history.
- Parking fee calculation, checkout, cash received and change.
- Receipt printing, reprints, refunds and voids.
- Cashier shifts, branch management and pricing rules.
- Excel reports, audit history and Admin–Cashier messaging.

External card payments are processed on a separate terminal and recorded manually.

## Architecture

The JavaFX desktop app connects to the Spring Boot API hosted on Render.
The backend connects to Neon PostgreSQL. Tickets and receipts print locally.

Database credentials stay on the backend.

## Run Locally

**Development requirements:** JDK 17, Maven and PostgreSQL.

Configure `backend/.env` using `backend/.env.example`.
For existing databases, follow [UPGRADE.md](UPGRADE.md) first.

Start the backend from the project root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\backend\run.ps1
```

Start the desktop in another terminal:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:PARKING_SERVER_URL = 'http://localhost:8080'
.\desktop\run.ps1
```

Sign in with the configured admin account to create cashiers.
Cashiers must open a shift before collecting payments.

## Build for Windows

With JDK 17, Maven and WiX 3.x installed:

```powershell
.\desktop\package-windows.ps1
```

See [Windows packaging](desktop/WINDOWS-PACKAGING.md) for installer output and bundled runtime details.

## Verification

With Java and Maven on PATH:

```powershell
mvn -f backend/pom.xml test
mvn -f desktop/pom.xml package
.\desktop\verify-ui.ps1
```

Physical printing and installation on another PC require separate testing.

## Documentation

- [Architecture and database setup](ARCHITECTURE.md)
- [Render and Neon deployment](backend/RENDER.md)
- [Windows packaging](desktop/WINDOWS-PACKAGING.md)
- [Printing and checkout](desktop/PRINTING-CHECKOUT.md)
- [Upgrades, backups and recovery](UPGRADE.md)

Never commit passwords or private `.env` files.
If printing fails after payment, use Receipt History to reprint.