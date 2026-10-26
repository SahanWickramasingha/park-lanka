# POS milestone verification

The application is `backend` (Spring Boot 3 / Java 17) and `desktop` (JavaFX 21).
The root AGENTS.md describes an unrelated React game; its game, CSS, and npm instructions do not apply.
Existing UI, credentials, historical records and the `original` console application are preserved.

## Inspection

1. Messaging: current source already has individual Received/Sent projections, owned-thread checks, bootstrap/database identities, exact-post bell links, receipts and session-scoped polling. Earlier failures were an old backend denying GET /api/conversation-messages and an old desktop JVM still displaying its loaded legacy screen. Revalidate existing regression tests.
2. Entry: saved sessions and rate snapshots exist; entry preview, thermal layout and opaque ticket token are missing.
3. Receipts: individual receipt and native print exist; searchable branch-scoped history and REPRINT are missing.
4. Checkout: server BigDecimal fee exists; tender/change, quote confirmation and durable attempt identity are missing.
5. Shifts: missing; legacy payments must remain unassigned.
6. Pricing: global started-minute minimum-one billing and entry rate snapshot exist. Preserve that rule for legacy sessions; add complete policies only to new entries.
7. Audit: notifications are transactional but are not an audit ledger; add a separate admin-only immutable ledger.
8. Backups: no managed backup/restore workflow exists.
9. Corrections: missing; original payment/session must remain immutable.

## Database protection before any upgrade

Do not run the changed backend against the operational database until the upgrade checks below pass.
Development and regression tests use distinct in-memory/disposable file databases. Never delete the saved operational database or copy its files while its backend is writing them.

1. Record the operational database URL, engine/version, backend working directory and current application artifact. Retain the old artifact and external configuration securely.
2. Stop only that backend gracefully. Ensure no process still holds the database. For H2, copy the stopped `.mv.db` (and any necessary companion files) to a durable directory outside `target`; keep a second offline copy. Alternatively use H2's supported consistent BACKUP command from the owning process.
3. Rehearse the upgrade on a disposable copy, with a different file path and port. Verify counts and stable IDs for sessions, payments, conversations and posts, including historical receipts.
4. Schema adoption must be explicit and validate the legacy schema before recording a baseline. An unknown/mismatched schema must fail startup. Do not enable automatic Flyway baseline-on-migrate.
5. Versioned additive migrations run before Hibernate validation. Keep historical pricing/payment columns nullable when their values did not exist historically. Never fabricate legacy shift/cash attribution.
6. On failure stop the upgraded backend. Preserve the failed database separately, then restore the stopped database backup together with the matching old application/configuration. Do not copy an older database over an open database. New transactions after the backup require reconciliation; rollback cannot preserve those automatically.

Milestone outcomes and exact operational commands will be recorded here as each stage is validated.

## Validated stages

- 1: 17 messaging tests passed before feature work; existing Received/Sent and private-thread implementation retained.
- 2: EntryTicketTest passed against Flyway-created isolated H2; desktop package passed. Opaque persisted ticket UUID, QR preview, thermal layout selection, safe print cancellation/failure, and reprint without another entry.
- 3: ReceiptHistoryTest passed (branch enforcement, admin filter, ticket/plate search, Colombo date range, pagination, unchanged originals); desktop package passed. SchemaUpgradeTest passed both a disposable legacy-file adoption preserving ID 99 and refusal of unknown columns.
- 4: CheckoutSafetyTest passed (cash sufficiency/decimal validation, tender/change, external confirmation, changed quote, stable replay, conflicting key payload and concurrent same-attempt checkout). New status endpoint and local credential-free recovery metadata preserve pending attempt identity. Checkout timestamps use database microsecond precision so replayed results agree.
- 5: ShiftTest passed for bootstrap/database cashiers, original amount versus gross tendered, counted/expected difference explanation, ownership, concurrent duplicate open/close, replay after closure and administrator reconciliation of a stopped cashier. Payments require an active shift on the backend; legacy payments remain unassigned. Native views provide current-shift login status, history, printing and text export.
- 6: PricingCalculatorTest, PricingPolicyTest and RateSettingsTest passed. New branch/vehicle policies use started minute/hour, grace and entry-anchored 24h caps. Complete snapshots protect existing tickets; historical per-minute snapshots/configured fallback remain supported. Settings and README explain precedence and boundaries.
- 7: AuditTest plus notification/shift regressions passed (16 tests at that checkpoint). Admin-only filter/pagination, safe before/after projections, rollback, archive persistence and no mutation API verified. Ticket/receipt reprint requests are recorded; paper completion is not assumed.
- 8: BackupRestoreTest passed using a disposable file-backed H2 database. Verified consistent BACKUP, checksummed envelope, admin/confirmation/maintenance gates, no active shifts, pre-restore backup, approved plan, separate-destination restore, unchanged source and corrupt archive rejection. The actual packaged `restore.ps1` command also passed and its restored database booted the packaged backend on port 18089: preserved Sent history returned; writes returned 503 in maintenance. That verification server was stopped, while the operational backend still returned 200 to a read-only check.
- 9: PaymentCorrectionTest and related shift/checkout/Excel tests passed (13 tests at the checkpoint). Refund/void eligibility, immutable gross/session/slot, full/partial/cumulative bounds, terminal evidence/reference duplication, role protection, concurrent attempts, same-key replay, later refund shift versus closed sale shift, correction-date net totals and Excel sheets verified.

## Changed files/modules

Both Maven module versions are now **0.2.0**. The old running JVM is not upgraded by rewriting build files. Windows held an old artifact during initial packaging; 0.2.0 is packaged separately. Do not run the obsolete 0.1.0 artifact for the new APIs. No operational database was used by automated tests; no commit, push, deployment or publication was performed.

- Backend infrastructure: `pom.xml`, `application.properties`, `config/SchemaUpgrade.java`, `config/SecurityConfig.java`, and additive SQL `db/migration/V1__existing_pos_schema.sql` through `V8__immutable_payment_corrections.sql`. Existing schema adoption is explicit and checked, never baseline-on-migrate.
- Backend financial/domain additions: `domain/{CashierShift,PricingPolicy,PricingRule,AuditRecord,BackupJob,PaymentCorrection}.java`; `ParkingSession.java` gains opaque ID and nullable full policy snapshot; `Payment.java` gains nullable cash fields, stable attempt fingerprint and shift.
- Backend repositories: `repo/{ShiftRepository,PricingRuleRepository,AuditRepository,BackupRepository,CorrectionRepository}.java`, with payment/session lookup support retained.
- Backend services: `ParkingService`, `FinancialInputs`, `ShiftService`, `PricingService`, `FeeCalculator`, `AuditService`, `BackupService`, `MaintenanceGuard`, `CorrectionService`, `EventLog`, `RateService`, `CashierService`, `AdminService`, `ExcelReportService`. Financial writes remain server-validated and transactional; branch locks serialize drawer/payment/correction changes.
- Backend API: existing parking/security/error handlers plus `ReceiptHistoryController`, `ShiftController`, `PricingController`, `AuditController`, `BackupController`, `CorrectionController`. CASHIER access remains scoped; admin-only routes remain restricted.
- Offline maintenance: `maintenance/{BackupArchive,PostgresTools,BackupRestore}.java`, `backend/restore.ps1`, `backend/run.ps1`.
- Desktop additions: `ThermalDocuments`, `ReceiptHistoryPane`, `PendingCheckoutStore`, `CheckoutCoordinator`, `ShiftsPane`, `PricingRulesPane`, `AuditPane`, `BackupsPane`, `CorrectionsPane`. Wiring/presentation updated in `DesktopApp`, `AdminDashboard`, `ApiClient`, `ParkingDialogs`. Existing modern JavaFX components/styles reused. Background tasks guard session identity; thermal QR callbacks also guard the owning scene.
- Desktop verification: `verify-ui.ps1` includes the QR dependency; `DesktopUiSmoke` covers new screens, reprint without payment creation, stable confirmed checkout payloads and both thermal ticket widths, alongside previous messaging/polling/logout checks.
- Tests: `EntryTicketTest`, `ReceiptHistoryTest`, `CheckoutSafetyTest`, `ShiftTest`, `PricingCalculatorTest`, `PricingPolicyTest`, `AuditTest`, `BackupRestoreTest`, `config/SchemaUpgradeTest`, `LegacyFinancialUpgradeTest`, `FinancialTestSupport`. Existing checkout fixtures now explicitly open shifts and submit confirmed amounts and keys. Legacy management/code tests use the new audit/migration setup. Generated stale IDE class files were removed only from verified backend build directories.
- Documentation: README, this file, UPGRADE.md and generated backup exclusions in `.gitignore`.

## Verification evidence and practical limits

- Final backend full suite: **63 tests passed, zero failures/errors**, including the additional historical-financial upgrade case. `LegacyFinancialUpgradeTest` verifies preserved session/payment/conversation/post IDs, original gross/reference/time, null legacy shift/tender/change, historical per-minute pricing and configured fallback. Log: `backend/target/final-regression.log`.
- Backend and desktop **0.2.0 package builds passed**, logs in each module's `target/final-package.log`.
- Backend launcher syntax was checked without executing it. Automatic approval review rejected a proposed busy-port execution test because a free port could have started the default database. The safe replacement used read-only port and syntax checks; it confirmed the temporary recovery port was closed. Actual packaged backend startup/restore used only the disposable restored database.
- Native `desktop/verify-ui.ps1` passed after final UI adjustments. Important views and thermal 58/80 mm previews were visually inspected; generated images are in `desktop/target/ui-preview`. It uses a local HTTP fixture, not the operational database. A separate packaged backend recovery startup was tested against the disposable restored file.
- PostgreSQL-specific execution was unavailable: pg_dump/pg_restore/psql were not installed and Docker's engine was not running. PostgreSQL migrations and tool paths are implemented, but no PostgreSQL runtime/restore result is claimed.
- No physical thermal printer, cutter/media, external card settlement, Excel desktop installation or multi-PC recovery was tested. Printer success means the driver accepted the job, not proof of paper or external settlement.
- Restore intentionally creates a separate new database. Live overwrite is not supported. An approved plan, maintenance, pre-restore backup and stopped-server operator workflow are required; changes after the backup require reconciliation.
- Recovery plans and their referenced backups are protected from ordinary retention. Manage old recovery-plan evidence/off-server copies under operator filesystem controls. Backup metadata in restored snapshots can describe an interrupted original job; startup marks interrupted jobs failed.
- Cash refunds require an eligible active shift in the original branch. Archived branches cannot open new drawers; do not fabricate cross-branch cash movement. Administrators can reconcile an already-existing stopped/archived cashier shift with a required reason.

Exact safe backup/adoption and Windows restart commands, environment configuration and recovery steps are in [UPGRADE.md](UPGRADE.md). New `backend/run.ps1` finds the known local Maven installation, checks the selected port before packaging, and starts 0.2.0 without stopping another process; `desktop/run.ps1` rebuilds/launches the matching desktop.
