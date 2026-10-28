# Desktop login startup and connection diagnosis

Park Lanka 0.2.2 waits for the API in the background before sending credentials. No terminal warm-up command is required. The login screen shows **“Connecting to server… The first connection may take a few minutes.”** with **Cancel**, and offers **Retry** after a failed or cancelled attempt. Connection settings remain available when no attempt is running.

## Evidence and cause

The original login immediately sent an authenticated `GET /api/me`. `ApiClient` allowed **5 seconds to connect** and **15 seconds per API request**. Network, HTTP timeout and TLS exceptions were all wrapped in `NetworkFailure` and shown as “Could not connect to the server”, which concealed the actual failure category. The login already used background IO; it lacked a startup/readiness phase and cancellation.

On 2026-10-28, the installed launcher configuration and the Windows user's saved connection both selected `https://park-lanka.onrender.com`. Development `desktop/.env` still selected localhost; it was not changed or packaged. Each new attempt now logs its validated effective origin, phase, probe number, elapsed time, status code and failure category. It does not log the username, password, Authorization header, response body, tokens or database connection URLs.

A live credential-free `GET https://park-lanka.onrender.com/api/me` returned **401 with `WWW-Authenticate: Basic realm="Realm"` in 0.606 seconds**, with valid HTTPS certificate checking. This demonstrated the existing protected endpoint's readiness response, not a cold start. [Render documents](https://render.com/docs/free#spinning-down-on-idle) that idle free services spin down after 15 minutes and take about a minute to start again. This is consistent with the reported warm-up workaround, but the initial warm response alone does not establish that the reported incident was a Render cold start.

The local regression test reproduced a **16-second authenticated response failing under the original 15-second limit**, then proved a **16-second startup delay succeeds** with the new readiness flow. The confirmed desktop defect is insufficient startup tolerance and indistinguishable connection errors. A controlled hosted cold-start test needs evidence of the Render service's actual stopped/starting state; no service settings, deployment or database records were changed for this investigation.

A subsequent **real JDK 17 HTTPS probe**, using the packaged 0.2.2 client and the installed user's effective URL, waited **194.298 seconds** before receiving that same valid 401 Basic challenge. This request followed roughly 15 minutes without our traffic to the service. While it was pending, a parallel credential-free curl GET completed TCP/TLS in about **1.01 seconds** but timed out after **30.02 seconds with no HTTP response bytes**. The Java probe maintained an established port-443 connection and eventually succeeded with normal certificate validation. This is direct evidence of a long server-response delay, rather than a wrong effective URL or an invalid-credentials response. It explains why a 15-second login request fails and a long curl warm-up helps. The timing is consistent with Render cold startup, but the exact service lifecycle cause remains unverified without Render logs; no controlled stopped-to-ready transition or live account login was tested. The live probe sent no credentials and used the 240-second request bound; the production readiness retry loop was verified separately against local fixtures.

## Request and retry behavior

- The startup window is at most **240 seconds**, measured with a monotonic clock. Each readiness request is capped at **20 seconds**, with **1, 2, 4, then 8 seconds** between requests, capped by the remaining window. Tests can inject shorter timings without changing the production limit.
- Readiness is an **unauthenticated GET `/api/me`**, with no Authorization header or request body. Only the expected **401 + Basic authentication challenge** means ready. This uses the existing security behavior; no public endpoint, backend deployment, database query or security relaxation was added.
- Timeouts, connection failures, HTTP 408/429/5xx and 2xx loading pages may retry during the window. A loading HTML page is never accepted as readiness. Unexpected status codes/redirects or a missing authentication challenge produce a connection-settings error. TLS validation failures stop immediately; normal JDK certificate/hostname validation remains enabled.
- Once ready, **one** authenticated `GET /api/me` is made. It keeps the normal **15-second** request timeout, capped by the remaining startup budget. Startup-specific deadlines also cover a stalled response body. Authentication is not retried automatically, even on a timeout, invalid credentials or a server error.
- Rejected credentials/account access, connectivity, timeouts, TLS errors, wrong endpoint and server errors receive different messages. Raw server bodies are not exposed on the login form.
- Cancel closes the pending client, cancels the HTTP future and interrupts readiness backoff. Window close also cancels pending login. An attempt identity check discards stale completion callbacks. Duplicate Connect/Retry actions are ignored while an attempt is running. No transaction/write retry policy was added or changed.

## Regression checks

From the repository root in Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
& .\desktop\verify-ui.ps1
# Replace with the exact image directory printed by package-windows.ps1:
& .\desktop\verify-ui.ps1 -AppImage 'C:\path\to\image\Park Lanka'
```

The existing UI suite now invokes `LoginStartupChecks`. It uses temporary loopback servers and fixture accounts, never the operational backend/database. Coverage includes the old timeout reproduction, delayed startup, loading HTML, 502/503/429 retries, unavailable server, bounded exhaustion, slow/stalled response bodies, invalid credentials, forbidden accounts, server errors, TLS exception classification, duplicate clicks, Cancel/Retry, closing the window, stale callbacks and a POST failure submitted only once. TLS classification uses an injected handshake exception; this is not a real invalid-certificate server test. Short deadlines exercise timeout behavior; the full 240-second outage window is not deliberately consumed by each test.

Both development and packaged UI checks passed. Existing checkout, payment/shift recovery, receipt/QR, reports, messages and connection-settings regressions also passed. The native launcher reached the login screen with its bundled JVM, and all 157 embedded MSI files matched the tested app-image. No prohibited private configuration files or matching local secret values were found in the payload.

The follow-up packaged-client check also exercises recovery after repeated readiness timeouts: the local server becomes ready within the same attempt, and credentials are submitted once. The real hosted 194-second measurement did not use fixture credentials or attempt authentication.

Changed files:

- `src/main/java/lk/sahan/parking/desktop/ApiClient.java`: credential-free probe and startup-only request/body deadlines; normal request/write policy preserved.
- `src/main/java/lk/sahan/parking/desktop/LoginStartup.java`: startup budget, bounded backoff, authentication once, typed failures, cancellation and safe diagnostics.
- `src/main/java/lk/sahan/parking/desktop/DesktopApp.java`: background startup UI, Cancel/Retry, duplicate submission guard and stale result protection.
- `src/test/java/lk/sahan/parking/desktop/LoginStartupChecks.java`: local startup/login regression scenarios.
- `src/test/java/lk/sahan/parking/desktop/DesktopUiSmoke.java` and `CheckoutParkingChecks.java`: invoke startup checks and model the existing unauthenticated Basic challenge in fixtures.
- `pom.xml`: desktop version 0.2.2. `package-windows.ps1`: require the startup class in the packaged JAR. `WINDOWS-PACKAGING.md` and this guide: release/evidence documentation.

## Installer

Built with JDK/jpackage 17.0.10 and WiX 3.14.1:

```text
C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\desktop\target\windows\build-20261028-132954-13a83645\installer\Setup.exe
```

Open its folder:

```powershell
Invoke-Item -LiteralPath 'C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\desktop\target\windows\build-20261028-132954-13a83645\installer'
```

SHA-256: `52339D502D15D81C97F3C224FA2FBD518ACE86CE866EEA2B82C81F109F5FC76F`.

The installer remains unsigned. Installation/upgrade/uninstallation and live login were not performed on a clean Windows machine or this development PC. The Spring Boot backend, its security rules and the Neon database remain unchanged.
