# Park Lanka desktop branding and Windows builds

Run these commands from the repository root in Windows PowerShell. Build with a Windows x64 **JDK 17** (including `jpackage` and `jlink`) and **Maven 3.9.x**. The project uses JavaFX **21.0.2** Windows dependencies. An app-image needs no WiX installation; an EXE installer additionally requires **WiX Toolset 3.x**. Maven needs network access on the first build.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop\package-windows.ps1 -Type exe
```

The packaging script resolves an x64 JDK 17 from `-JavaHome`, `JAVA_HOME`, `C:\Program Files\Java\jdk-17`, other `jdk-17*` directories there, or `jpackage.exe` on PATH. It checks the JDK release and required tools. Maven uses the same discovery as `run.ps1`: `mvn.cmd` on PATH, otherwise `%USERPROFILE%\Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd`; `-MavenCommand` overrides it. WiX resolves from `-WixDirectory`, `WIX`, `C:\Program Files (x86)\WiX Toolset v3.14\bin`, other WiX 3 installations, or `candle.exe` on PATH. The script sets child-process `JAVA_HOME` and PATH itself and restores the calling environment afterwards. No temporary terminal PATH setup is required. The execution-policy bypass above applies only to that PowerShell process.

`run.ps1` runs `package javafx:run` without `clean`, so development launch works while a packaged app-image is open. Windows locks the running image's bundled runtime files. An explicit `mvn clean` deletes `desktop/target`, including generated app-images, and fails if an app running there still holds those files open. Close that packaged app before an intentional clean build.

The latest login connection fix is **0.2.2**. See [Login startup and connection diagnosis](LOGIN-CONNECTION.md) for the evidence, bounded 240-second readiness flow, Cancel/Retry behavior, regression results and exact updated `Park Lanka Setup v0.2.2.exe` path. The earlier verification entries below describe their dated builds.

## Supplied artwork

`branding/source/park-lanka-logo.png` and `branding/source/park-lanka-icon.png` are the original attachments retained byte for byte. The login uses the full wordmark and tagline. Both sidebars use a 40px icon beside white **Park Lanka** text against the existing navy background. All image views preserve aspect ratio.

`branding/prepare-assets.py` trims transparent outer margins, centers the icon on a square with a small transparent inset, and resizes the supplied artwork without redrawing it. It produces:

- A trimmed transparent login logo in `src/main/resources/lk/sahan/parking/desktop/branding/`.
- A transparent 1024px PNG master plus 16, 24, 32, 48, 64, 128 and 256px PNGs in the same resource directory.
- `branding/park-lanka.ico` containing all seven requested resolutions and `branding/icon-sizes-preview.png` for inspection.

Regeneration is optional; the prepared assets are enough to build. To regenerate, use Python 3 with Pillow installed:

```powershell
python .\desktop\branding\prepare-assets.py
```

`BrandAssets` uses absolute classpath resource URLs, so loading works from Maven classes and inside a JAR, independently of the working directory. PNG resources are copied without Maven filtering. The primary Stage and application Dialog stages receive all seven icon sizes. Native file/print dialogs are owned by the application; Windows controls their appearance.

## Build the app-image first

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop\package-windows.ps1 -Type app-image -ServerUrl 'http://localhost:8080'
```

The default for both build types is `https://park-lanka.onrender.com`. The command above explicitly overrides it for local development; omit `-ServerUrl` for distribution. The script accepts only an HTTP(S) origin with no credentials, query, fragment or path. This public connection setting is stored as `parking.server.defaultUrl` in the launcher configuration. No environment file or saved developer connection is read or copied during packaging.

Each build creates a new directory under `desktop/target/windows/build-<timestamp>-<id>/`. The script prints the exact executable path, ending in `image\Park Lanka\Park Lanka.exe`. Launch that executable, and distribute the **entire `Park Lanka` directory**, including `app/` and `runtime/`. The executable alone is insufficient. Users do not need a separately installed Java runtime. The backend remains a separately configured service.

Each invocation compiles the current source into that build's separate `build/` directory, packages the desktop JAR and copies dependencies without cleaning previous images or user data. Compilation failures stop the process. Only the desktop JAR and Maven runtime dependency JARs enter the fresh packaging input directory. Packaging checks the ICO's seven square resolutions, logo and PNG resources, CSS, receipt classes and Windows JavaFX DLLs before running jpackage. The script checks for prohibited environment/database/log files and does not copy backend directories, credentials or backups. It also verifies the launcher, bundled JVM and app configuration exist. `package-manifest.json` records tool locations, version, output paths, public default URL and artifact SHA-256 hashes outside the distributed payload.

## Connection settings after installation

Use **Connection settings** on the login screen before signing in. **Save connection** validates the API origin, permits HTTP only for localhost/trusted LAN addresses, saves only the URL in `%APPDATA%\Park Lanka\desktop.env`, and selects it for the current session. No network request or login occurs on save. Writes replace the URL atomically where supported and preserve other lines in an existing settings file. Nothing is saved under Program Files or the application installation directory.

Existing `PARKING_SERVER_URL` and `PARKING_DESKTOP_ENV_FILE` overrides remain supported. A user can also edit this connection-only setting in `%APPDATA%\Park Lanka\desktop.env`:

```dotenv
PARKING_SERVER_URL=https://your-backend-host
```

Resolution order is `PARKING_SERVER_URL`, then an explicit `PARKING_DESKTOP_ENV_FILE`, otherwise the existing development `desktop/.env` or `.env` if present, otherwise the user configuration above, then the packaged default (localhost in development). Keep backend credentials in the backend's own configuration. Neither packaging nor installation creates or rewrites the user's connection file or pending transaction data.

## Build an EXE installer

After installing WiX 3.x (the confirmed v3.14 location is discovered automatically):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop\package-windows.ps1 -Type exe
```

`exe` is the default type. This builds an app-image first, then writes **`installer/Park Lanka Setup v<version>.exe`** (currently `Park Lanka Setup v0.2.2.exe`) under that build's directory and prints its full path and an exact PowerShell command to open the output folder. Distribute **only that installer EXE**: its embedded MSI contains the application, linked JDK 17 runtime, Windows JavaFX native libraries and all runtime dependencies. The filename version comes from `pom.xml`. Renaming the jpackage output does not change the embedded Park Lanka product identity. WiX is checked before building an EXE; use `-Type app-image` if WiX is unavailable. `-OutputDirectory` accepts a fresh directory relative to the caller or an absolute path; existing directories are refused.

The target is Windows x64. It needs internet access to the hosted API, but no installed Java, Maven, WiX or PostgreSQL. The backend API and Neon database remain separate services and are not shipped or changed. The default API is `https://park-lanka.onrender.com`; **Connection settings** and existing URL overrides remain available. Existing saved user settings may override the default. Installed users can uninstall **Park Lanka** from Windows **Settings > Apps**. Start Menu and desktop shortcuts launch the bundled runtime.

The existing per-user installation identity is retained: application/install directory **Park Lanka**, Start menu group **Park Lanka**, upgrade UUID **6ea48f96-8068-4e93-b18b-38971a86e6e8**. Keep that UUID for future upgrades and increase the version in `pom.xml`. The EXE, installer, desktop shortcut and Start menu shortcut use the same supplied ICO. The workflow has no database migration, deletion or user-data cleanup steps. Rehearse install/upgrade on a test PC before distribution. Packages are unsigned unless signing is added separately.

## Window and taskbar icons

Window titles are **Park Lanka | Login**, **Park Lanka | Admin**, and **Park Lanka | Cashier**. The native launcher has the Park Lanka executable name and embedded ICO; installer shortcuts target that launcher. Use it for Windows packaging/icon checks.

`mvn javafx:run` runs under the JDK's Java executable. Stage icons still use Park Lanka PNGs, but Windows can group the development process with Java or show Java's identity for a pinned executable. That development launch does not test the native launcher's executable/shortcut identity. Old pinned shortcuts may retain cached icons; pin the newly installed Park Lanka shortcut. No unsupported JavaFX internal APIs or extra native dependencies are used to force grouping. Final grouping and pinning still require a Windows shell check after installation.

The packaging options follow the [JDK 17 jpackage reference](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html).

## Verification

```powershell
& .\desktop\verify-ui.ps1
# Use the exact app-image directory printed by package-windows.ps1:
& .\desktop\verify-ui.ps1 -AppImage 'C:\path\to\image\Park Lanka'
```

Both checks compile the test harness and run real JavaFX screens against a temporary localhost HTTP fixture. They do not contact the operational backend or database. The second check loads application classes and artwork from the app-image JAR and libraries, explicitly checks JAR resource URLs and transparent icons, and exercises login, Admin, Cashier and a branded dialog. It uses the build JDK to run the test harness; test classes are never shipped in the app-image.

Rendered screens are saved under `desktop/target/ui-preview/` and `desktop/target/ui-preview-packaged/`, including compact login and 1024/1366/1920 desktop layouts. Inspect the icon size sheet at native scale, particularly 16/24/32px. Separately launch `Park Lanka.exe` to verify its bundled runtime and Windows icon, then test the installed desktop/Start menu shortcuts, taskbar pinning and an upgrade on a test PC with WiX available.

## Verification on 2026-10-23

The final verified app-image is `desktop/target/windows/park-lanka-verified-20261023/image/Park Lanka/`. Launch `Park Lanka.exe` inside it. Generated output remains excluded by the existing `**/target/` ignore rule.

- The two source PNGs matched the supplied attachments byte for byte. The seven-resolution ICO and the small-icon preview were inspected.
- Desktop Maven `test-compile package` and both development and app-image JAR UI regression checks passed. Login, Admin and Cashier renderings were inspected; the regression check also verified dialog icons and transparent corners.
- Windows app-image creation passed. Native EXE icon resources matched the seven prepared PNGs byte for byte. The app payload had no `.env`, database or backup files.
- The native launcher reached **Park Lanka | Login**, exposed small and large window icons, and loaded `jvm.dll` from its own `runtime/` directory. No operational login was attempted.
- The classpath-based packaged launch emits JavaFX's existing unnamed-module configuration warning; the native launch and UI regression check passed despite it.
- **No EXE installer was built:** WiX 3.x `candle.exe` and `light.exe` were unavailable. Installed shortcut icons, taskbar pinning/grouping, signing and install/upgrade behavior were not verified. The existing upgrade UUID and packaging options were preserved.

## Verification on 2026-10-26

The Windows setup installer was successfully built from current source:

```text
C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\desktop\target\windows\build-20261026-112407-e5d44114\installer\Park Lanka-0.2.0.exe
```

Rebuild from the repository root (each run creates a new build directory):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop\package-windows.ps1 -Type exe
```

- Tool discovery resolved Oracle x64 JDK/jpackage 17.0.10, the existing Downloads Maven 3.9.16 installation, and WiX 3.14 without terminal PATH preparation. The build ran outside the restricted tool sandbox so Maven could access its dependency cache and Windows build tools.
- Maven compiled all 26 desktop sources and six test sources into a fresh directory; `package`, jpackage app-image and jpackage EXE creation succeeded. The packaged desktop JAR SHA-256 matched the freshly compiled JAR. Build output and SHA-256 hashes are recorded in that build's `package-manifest.json`.
- The native launcher was started hidden, reached **Park Lanka | Login**, exposed both window icons, and loaded `jvm.dll` from its own `runtime` directory. That test process was closed without logging in. All seven embedded EXE icon sizes and packaged PNGs matched the source artwork.
- The final EXE's embedded MSI was extracted for inspection without installing it. WiX decompilation confirmed product/version **Park Lanka 0.2.0**, x64 package/components, per-user LocalAppData installation, Start menu and Desktop shortcuts targeting the launcher, and upgrade code **6ea48f96-8068-4e93-b18b-38971a86e6e8**.
- The existing `verify-ui.ps1 -AppImage` check passed against the final image using JDK 17 and a temporary local HTTP fixture. This covered login/Admin/Cashier screens, connection settings validation and cancellation, AppData URL save/reload and preservation of unrelated settings, environment precedence, reports/messaging, existing checkout/shift recovery regressions, and receipt/QR layouts at 58mm, 80mm and A4. No operational backend/database was used. Previews are in `desktop/target/ui-preview-packaged/`.
- Packaging and native inspection found no environment files, databases or backups in the distributed application. CSS, receipt classes, JavaFX Windows native DLLs and all runtime dependencies were checked. No backend secrets, database connection settings or stored transaction data were copied.

The installer is unsigned. Installation/uninstallation, installed shortcut activation, upgrade behavior on an existing installation, clean-machine compatibility and physical printing have **not** been tested. The classpath JavaFX startup warning and existing unchecked-source/Maven model warnings remain; the native launcher and UI checks passed.

## Setup.exe verification on 2026-10-28

Built **Park Lanka 0.2.1** with x64 JDK/jpackage 17.0.10, Maven 3.9.16 and WiX 3.14.1. The desktop patch version was increased from 0.2.0; the per-user install directory, product branding and upgrade UUID are unchanged.

Single distributable (37,577,216 bytes):

```text
C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\desktop\target\windows\build-20261028-113407-478c7dbb\installer\Setup.exe
```

Open its output folder:

```powershell
Invoke-Item -LiteralPath 'C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\desktop\target\windows\build-20261028-113407-478c7dbb\installer'
```

SHA-256: `AB6B8FB7ADD720994DECBCCCD5136B997444576BEBC2FD260899D1E03837F844`.

- `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop\package-windows.ps1 -Type exe` passed, including Maven compilation/package, runtime dependency copying, JDK runtime linking, JavaFX Windows DLL checks and EXE creation. The restricted sandbox could not reach Maven Central; the successful build used authorized access to the Maven cache/network and installed tools.
- The launcher configuration contains `parking.server.defaultUrl=https://park-lanka.onrender.com`. The HTTPS trust store is bundled. Connection settings and user overrides were preserved; packaging did not read or copy saved developer connections.
- The native app-image launcher reached **Park Lanka | Login**, exposed both window icons and loaded `jvm.dll` from its bundled runtime. Its seven embedded icon resolutions and packaged PNGs matched the source artwork. No live credentials were entered.
- With `JAVA_HOME` set to JDK 17, `verify-ui.ps1 -AppImage` passed against this build's image. It covered fixture login/Admin/Cashier, connection settings, saved URL handling, checkout/shift recovery, messaging/reports and receipt/QR layouts. These tests used a temporary local HTTP fixture, not the hosted backend or Neon.
- Extracted the EXE's embedded MSI as data and decompiled it with WiX `dark.exe`, without installing. All **157 installed files** matched the tested app-image byte for byte. The MSI contains an embedded cabinet, x64 per-user installation, enabled Start Menu and desktop shortcuts targeting `Park Lanka.exe`, Windows uninstall metadata and the preserved upgrade UUID. The app-image's `.jpackage.xml` is build-only metadata and is not installed.
- The app payload and expanded JAR entries contained no prohibited environment/database/backup/private-key files. A read-only comparison against local secret values also found no matches; no secret values were printed or copied into the verification output. Spring Boot code/configuration and Neon were unchanged.

**Installation, uninstallation, installed shortcuts, upgrades and live login were not tested on a clean Windows machine or on this development PC.** The installer is unsigned. Existing JavaFX unnamed-module and Maven/unchecked-source warnings remain; the build, native launch and UI regression checks passed. Distribute only `Setup.exe`, not the build manifest or inspection directories.
