# Ticket printing and checkout fixes

## Confirmed implementation gaps

The previous print path reused the display-preview VBox, with fixed widths and a 128-unit QR, as the printer document. It had no A4 layout, only scaled for printable width, and did not rebuild wrapping/height for the selected printer. The body was one text block rather than aligned document rows. QR quiet-zone and caption sizing were mixed into that layout.

The checkout coordinator treated every HTTP 409 except `QUOTE_CHANGED` as uncertain. The backend's missing-shift response had no structured code, and the cashier's shift status was informational rather than connected to checkout submission. Cash/change columns and revenue calculations already existed; the fix retains them and adds explicit coverage rather than changing historical money or pricing.

## Resulting behavior

`ParkingPrintDocument` is shared by preview and printing. Entry tickets and receipts have dedicated fields, the supplied Park Lanka logo, branch/heading/number, wrapped values, and rows that cannot shrink into one another. A QR occupies its own centered block with an integral module size and a four-module quiet zone. Its caption is below it. Receipts use stored amount paid, cash received and change. Legacy missing money values say **Not recorded**; external-card receipts retain the terminal reference and omit cash fields. Duplicate copies say **REPRINT**.

The layouts use physical points and the actual driver's returned `PageLayout` printable area. The [JavaFX printer API](https://openjfx.io/javadoc/21/javafx.graphics/javafx/print/Printer.html#createPageLayout(javafx.print.Paper,javafx.print.PageOrientation,double,double,double,double)) may adjust requested margins to its supported values; the returned values are used. The 58 mm layout targets a 50 mm content width, 80 mm targets 72 mm, and A4 keeps a compact 85 mm document. The builder wraps to a narrower actual printable area, and only scales down if needed. It never enlarges a receipt or QR to fill A4.

For thermal printing, the code selects the shortest driver-advertised matching-width roll form that fits the document and valid margins. JavaFX's public API does not supply arbitrary custom `Paper` construction. Configure a suitable width/length in Windows printer preferences if the driver supports custom forms. If no advertised roll form fits, the preview reports that the selected driver paper will contain a compact receipt. A layout that would require excessive shrinking is refused with a longer-paper/A4 instruction. Driver selection and page setup remain available before printing.

Each print uses a fresh, measured node; the live preview is never transformed for printing. Printer discovery and spooling remain off the FX thread. Canceling or failing a print does not call a business write or undo the saved entry/payment. Reprinting still uses the existing saved-record endpoints.

Cash checkout shows **Amount Due**, **Cash Received**, and live **Change Due**. Client input checks now agree with the backend's nonnegative/two-decimal/maximum monetary range. Zero received is valid for a legitimate zero bill. Switching to EXTERNAL_CARD hides cash fields and requires the terminal reference and explicit external confirmation. A revised quote preserves entered values but resets the external-payment confirmation.

The backend remains authoritative and keeps its BigDecimal calculations, transaction locks, original request fingerprint/key replay, stored amounts and revenue/shift totals.

## Server-issued quotes

Checkout requires the immutable `quoteId` returned by the authenticated quote endpoint. V11 adds persistent quotes bound to the session, cashier identity, and branch. `PARKING_CHECKOUT_QUOTE_VALIDITY_SECONDS` defaults to 120 (allowed 1..86400). Within that period the server honors its stored fee and billing cutoff even across a billing boundary, after rechecking the active session, cashier access and active shift. Client prices and timestamps cannot set the charge. Expired/missing/mismatched quotes return `QUOTE_CHANGED` with a persisted fresh quote and `attemptRecorded: false`; the desktop retains cash, recalculates change and requires explicit confirmation with the new ID. No automatic payment occurs.

Saved payments record `billingCutoff` and `quoteId` separately from actual `exitedAt`/`paidAt`. Receipt billable units derive from the cutoff; the actual completion time remains visible. Existing receipts retain their historical exit-based cutoff. Status lookup and completed same-key retries return the original receipt, including legacy attempts without quote IDs. A quote ID cannot authorize a second payment for a completed session.

Build/restart both backend and desktop together for this API contract. Use the existing upgrade/backup procedure in `UPGRADE.md` for the additive V11 migration; verification uses disposable databases and does not migrate the operational database. Old desktop builds without quote IDs receive an actionable review/upgrade response rather than an unvalidated payment.

Targeted clock checks: `mvn -f backend/pom.xml '-Dtest=CheckoutQuoteTest' test` and `backend/verify-postgres.ps1 -Tests PostgresCheckoutQuoteTest -TestsOnly`. For real mouse clicks against a disposable backend, build the desktop JAR/test classes, unlock Windows, set `$env:PARKING_TEST_DESKTOP_UI='true'`, then run `backend/verify-postgres.ps1 -Tests CheckoutDesktopRuntimeTest -TestsOnly`. That test advances the server clock to verify expiry, preserved cash/change, explicit reconfirmation, and one committed payment. On a locked desktop, `$env:PARKING_TEST_DESKTOP_INPUT='fx'` invokes the same JavaFX button handlers against the real backend; logs explicitly label this `JAVAFX_EVENT_DISPATCH`, which does **not** verify physical mouse input. Clear that variable or use `mouse` for physical input. Ordinary backend checks do not launch UI windows.

| Backend response | Cashier behavior |
| --- | --- |
| `SHIFT_REQUIRED` (409) | Open Shift action, refresh state, preserve selection and cash, require a later review/confirmation. Opening a shift sends no payment. |
| `QUOTE_CHANGED` (409) | Show the updated quote, retain cash, recalculate change and require confirmation again. Never silently finalize an increase. |
| `INSUFFICIENT_CASH` (400) | Reload the quote and show the retained cash for correction. |
| `ALREADY_CHECKED_OUT` (409) | Refresh vehicles and direct the cashier to the existing receipt. |
| Other rejected 4xx responses | Reconcile the key first; show a business rejection if no committed payment exists. |
| Timeout, lost response, 5xx | Keep the original persisted attempt and query status before offering an explicit same-key/same-payload retry. |

Structured transactional rejections include `attemptRecorded: false`. The client still checks the attempt status before clearing its recovery key, including for older servers and reused-key responses. If that status query is unavailable, the key stays saved. An explicitly rejected no-shift submission keeps its business classification and Open Shift action even then. A found committed result opens its original receipt without posting another payment.

The parking page offers Open Shift when no active shift is known. Opening/closing a shift, refreshing parking, and a stale-shift rejection update the shared shift state. A refresh requested during another shift operation is queued. The unused direct checkout method was removed so the UI has a single coordinator-backed payment path.

## Verification performed on 2026-10-23

- 11 targeted isolated H2 backend tests passed: checkout safety, shifts, entry tickets, receipt history and legacy cash receipts.
- The five checkout-safety tests also passed on a **separate temporary PostgreSQL cluster**, including stored cash/change, exact/excess/zero cash, invalid input, insufficient cash, revenue/shift deltas, reprints, no-shift rejection without closure, quote boundaries, repeated/concurrent attempts and status recovery. The temporary cluster stopped after the check; the operational `parking_pos` database was not a test target.
- The native JavaFX regression suite passed. Added real-dialog HTTP fixtures check missing-shift gating, Open Shift without submission, retained cash, a changed quote needing confirmation, explicit 409 rejection, unavailable status after a definite shift rejection, a truncated response after commit, and retry with the same attempt key after an uncommitted failure.
- Geometry checks cover wrapping, row bounds, QR/caption separation, stored cash/change and legacy placeholders for 58 mm, 80 mm and A4. Rendered ticket QR codes decode successfully.
- The desktop Maven package build passed.
- Six fixture documents were spooled through **Microsoft Print to PDF**, using the same production page preparation. All six PDFs were rendered at 216 dpi; their content bounds fit the actual page margins and stayed within the intended physical document width. All three entry-ticket QR codes decoded from these PDF renders. The 58 mm ticket and A4 receipt PDF renders were visually inspected.

Generated artifacts (excluded by the existing `target/` ignore rule):

- `desktop/target/ui-preview/print-documents/`: entry/receipt PNGs for all three formats, reprints, a narrow driver-width case and a legacy receipt.
- `desktop/target/print-pdf/`: `entry-58mm.pdf`, `entry-80mm.pdf`, `entry-a4.pdf`, and the corresponding `receipt-*.pdf`, plus rendered PNGs and detail crops.
- `backend/target/checkout-tests.log` and `backend/target/postgres-checkout-tests.log`: test summaries.

The installed PDF driver did not advertise 58/80 mm roll forms. Those four documents used its **Letter** fallback with compact 50/72 mm content. The two A4 documents used **A4**. This verifies actual PDF-driver output and fallback behavior, not roll-paper output. No physical thermal printer, cutter, media, real paper QR scan or driver-specific custom roll length was tested.

PyMuPDF was installed only into `desktop/target/pdf-verification-tools/` to inspect generated PDFs. It is not a runtime or Maven dependency. The generated `render-pdf-check.py` and `PdfQrVerifier.java` inspection helpers remain under `desktop/target/`.

## Reproduce the checks

From the project root with Windows JDK 17 and Maven on PATH:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -f .\backend\pom.xml '-Dtest=CheckoutSafetyTest,ShiftTest,EntryTicketTest,ReceiptHistoryTest,LegacyCashReceiptTest' test
& .\backend\verify-postgres.ps1 -Tests PostgresCheckoutSafetyTest -TestsOnly
mvn -f .\desktop\pom.xml package
& .\desktop\verify-ui.ps1
& .\desktop\verify-ui.ps1 -PrintToPdf
```

The PostgreSQL script uses installed PostgreSQL 18 binaries by default; pass `-PostgresBin` if elsewhere. `-TestsOnly` leaves backend packaging out of the verification lifecycle, avoiding a locked live backend JAR. The default `verify` mode still includes packaging. The PDF verification switch targets **only Microsoft Print to PDF**, and skips clearly if it is absent; it never selects a physical printer.

## Changed files and using the update

Desktop implementation: `ParkingPrintDocument.java`, `ThermalDocuments.java`, `ParkingDialogs.java`, `CheckoutCoordinator.java`, `DesktopApp.java`, `ShiftsPane.java`. Backend implementation: `CheckoutRejected.java`, `ApiErrors.java`, `ParkingService.java`, `ShiftService.java`.

Verification: `CheckoutSafetyTest.java`, `LegacyCashReceiptTest.java`, `PostgresCheckoutSafetyTest.java`, `DesktopUiSmoke.java`, `PrintLayoutChecks.java`, `CheckoutFlowChecks.java`, `PrintToPdfCheck.java`, `verify-ui.ps1`, and `backend/verify-postgres.ps1`.

The running backend/desktop JVMs keep their previously loaded classes. These fixes have not been deployed or activated in an existing session. Restart matching backend and desktop builds when ready, using the existing configuration and database. Stop the old backend from its own launcher before rebuilding its executable JAR; Windows can lock that JAR during repackaging. Use `desktop/run.ps1` for the updated desktop code. Previously generated Windows app-images contain their earlier code until rebuilt with `package-windows.ps1`.
