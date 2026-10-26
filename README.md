# Park Lanka — branches, cashiers and admin dashboard

This project rebuilds the supplied Java console parking project as a Spring Boot REST API plus a JavaFX cashier application. The old project is preserved in `original/` for reference. No code or data is automatically imported from it: the original kept vehicles in memory only.

PostgreSQL is the required main database. Admin credentials and database secrets live only in `backend/.env` or deployed environment variables. Cashier accounts are managed through Admin and stored as BCrypt hashes in PostgreSQL. See [ARCHITECTURE.md](ARCHITECTURE.md) for local setup, schema/authentication changes, H2 migration and pgAdmin verification. Existing H2 records are **not** automatically transferred; complete the backup/dry-run procedure before first startup on an import target.

## Financial operations and safe upgrade

See [UPGRADE.md](UPGRADE.md) for exact Windows startup commands, database adoption checks, backup configuration and recovery steps. Do not bypass schema validation or restart against the operational database before a verified stopped backup and disposable rehearsal. Hibernate now validates versioned Flyway migrations; existing unmigrated databases require explicit, verified adoption.

Cashiers open a shift with opening cash before recording new payments. Login shows an existing active shift. Cash collections use the amount due, excluding tender/change. Closing records counted cash and requires an explanation for a difference. Admins can inspect and reconcile stopped cashiers' shifts with a reason. Historical payments retain null shift/tender/change values.

Pricing precedence is **branch + vehicle type → branch DEFAULT → global started-minute rate**. Within an optional grace period parking is free; after grace only the elapsed time beyond grace is billed, rounded up to the selected minute/hour. Zero grace preserves the minimum-one-unit rule. A daily cap applies to each consecutive 24-hour block from entry, including any partial block; midnight does not reset it. The complete effective policy is saved at entry. Legacy sessions retain their original per-minute snapshot or configured legacy fallback.

Checkout requires a UUID `requestKey`, backend-quoted `confirmedAmount`, method and (for CASH) `cashTendered`. EXTERNAL_CARD additionally requires `reference` and `externalConfirmed=true`. A changed quote returns HTTP 409 with `code=QUOTE_CHANGED` and the new quote; nothing is committed until it is explicitly confirmed. Do not charge a terminal again merely because this application's recording request failed: reconcile the external payment first. GET `/api/checkouts/{requestKey}` checks a committed attempt. Desktop pending attempts are retained locally without credentials and use the same key/payload after timeouts. One parking session can have only one original payment.

Cashier Receipt History enforces the assigned branch; admin history allows branch filtering. Searches support ticket/plate and inclusive Colombo date ranges. Reprints use saved amounts/references/times, say REPRINT, and never create new entries or payments. QR ticket UUIDs identify a ticket and provide no authorization. Printer discovery/spooling and QR preparation run off the UI thread; paper support depends on the printer driver.

Admin Payment Corrections creates immutable linked records with reason and confirmation. **REFUND** returns previously collected money (full/partial); cash refunds require an eligible active drawer in the original branch. **VOID** is a full reversal only when collection/settlement did not occur and no refund exists. Reversals cannot exceed gross, refunds cannot follow a void, and voids cannot follow refunds. External corrections require terminal evidence and never transfer money in this app. A cash void in the original still-open shift reduces that drawer's expected collection; a void after closure has no new physical cash movement and does not rewrite the closed summary. Later refunds use their actual refund shift, separately from the original sale shift. Pending correction attempts also retain their identity across restarts.

Receipts retain original gross and payment date and append corrections with occurrence dates, actor, reason and net. Daily revenue and Excel report summaries subtract refunds/voids occurring in the selected period, independently of the original sale date. The Excel Corrections sheet includes both dates; original payment rows remain gross. Audit/reprint records describe requested printing, not proof that paper emerged from a printer.

Admin Audit History provides branch/actor/action/date filters and pagination. It has no update/delete API. Safe projections exclude passwords, credential hashes and tokens; rolled-back actions do not leave successful audit records.

## Architecture

```text
JavaFX Admin/Cashier clients -> Spring Boot REST API -> PostgreSQL
                     |-> local ticket / receipt printer
```

Only the backend connects to PostgreSQL. Desktop clients authenticate to the API and never contain database credentials or a configured admin secret. A cloud deployment uses injected secrets and verified database TLS. H2 is retained only for explicitly selected development/tests and legacy migration.

## Included in this milestone

- Database cashier login, using BCrypt hashes and assigned-branch authorization.
- Required configured admin login with Overview, Cashiers, Saved Data, Rates, Manage and System Management pages in a navy navigation sidebar.
- Branches with separate numbered slots and a daily activity summary; MAIN contains all data from earlier versions.
- DB-backed cashier accounts assigned to one branch, with admin password reset and enable/disable controls.
- Explicit audited legacy identity linking preserves old private messages/read state/shifts after verifying the same cashier in Main Branch.
- 20 initial slots in MAIN (configurable before the first launch), occupied/free status.
- Vehicle entry with plate normalization, vehicle type, duplicate-active-plate protection, and a chosen slot.
- Confirmed backend fee quotes, cash tender/change and transactional checkout with durable retry identities. Each new entry snapshots the complete effective pricing policy.
- Cash or **manually confirmed** external card payment with required card reference. The app does not process card charges.
- Stored receipts and JavaFX print dialog; daily payment count/revenue in `Asia/Colombo`.
- Admin browser for saved branches, cashier names, slots, vehicle sessions and payments, filtered by branch. Passwords and password hashes are excluded.
- Excel reports for every Saved Data dataset, with branch/date filters, archived statuses and payment totals.
- Persistent in-app broadcasts with fixed recipient lists, priorities, expiry, revocation and per-cashier read state.
- Persistent notification bells for both roles, plus private two-way Admin–Cashier conversations and an administrator inbox.
- PostgreSQL persistence, versioned Flyway migrations, and isolated H2/PostgreSQL integration tests.

The application now includes entry-ticket QR/thermal previews, receipt history and reprints, confirmed-quote checkout with tender/change and durable retry keys, cashier shifts, branch/vehicle pricing snapshots, an admin audit ledger, managed consistent backups and approved offline restore plans, and immutable refunds/voids. Branch ownership and roles remain enforced. External card payments/corrections are manually confirmed terminal records: this application never transfers money. Real printer hardware, PostgreSQL operations, multi-PC recovery and production readiness still need target-environment verification. No installer or deployment is included. Read [UPGRADE.md](UPGRADE.md) before starting the changed backend against existing data.

## Run on Windows (PowerShell)

See [ARCHITECTURE.md](ARCHITECTURE.md#exact-windows-commands) for complete commands and data-preservation steps. Install JDK 17/Maven and PostgreSQL. The local database is `parking_pos` at `localhost:5432` with role `parking_app`; the role/password/permissions must be verified locally. Edit `backend/.env` to enter `DATABASE_PASSWORD` and `PARKING_ADMIN_PASSWORD`; no passwords are supplied. An existing `.env` is preserved.

```powershell
cd 'C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\backend'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;C:\Users\WW\Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin;$env:Path"
notepad .\.env
mvn package
.\verify-database.ps1
# For existing H2 data, perform the approved backup/import before starting here.
.\run.ps1
```

In a second terminal:

```powershell
cd 'C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\desktop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\run.ps1
```

The desktop login asks only for username and password. Set its API address in `desktop/.env` (copy `desktop/.env.example` on a new workstation):

```dotenv
PARKING_SERVER_URL=http://localhost:8080
```

For a remote backend, use that server's address. An OS environment variable `PARKING_SERVER_URL` overrides the file. From the project root or desktop module, the client discovers `desktop/.env` or `.env` respectively; from another directory, set `PARKING_DESKTOP_ENV_FILE` to its absolute path. If no URL is configured, it defaults to `http://localhost:8080`. Settings are read on each sign-in attempt. Database and configured admin secrets stay in `backend/.env`; desktop clients do not load backend settings.

Sign in as the configured admin, then create database cashiers with their branch assignments. `PARKING_USER`/`PARKING_PASSWORD` are no longer required or used for authentication. To retain an old bootstrap cashier's private history, follow the explicit same-username Main Branch link procedure in ARCHITECTURE.md. Use HTTPS before exposing the API to a public network; the desktop refuses public HTTP URLs.

### Desktop workspace

The desktop client uses a shared native JavaFX light theme: navy navigation, white cards, blue primary actions and labeled green/amber/red statuses. The sidebar identifies the signed-in username and role; **Log out** returns to login, clears credentials and user-specific tables/forms, stops message polling and cancels pending HTTP requests. Callbacks from the old workspace cannot update a logged-out or replacement workspace. An operation already committed by the backend is not undone by logging out. Overview shows API-backed branch, slot, payment and revenue cards. Archived branches remain available through Saved Data filters, and wide record tables scroll horizontally. Forms, feedback, keyboard focus and confirmation dialogs use the same theme. At smaller window sizes, forms wrap and pages scroll; cashier entry and parked vehicles sit side by side on larger windows and stack at smaller widths.

The cashier workspace includes free/occupied slot counts, today's payments and revenue, local ticket/plate search, and selection-aware checkout. `Ctrl+N` focuses number plate entry, `Ctrl+F` focuses search, `Ctrl+R` refreshes, and Enter in the plate field registers a valid entry. Enter on a selected table row requests its quote. Connection labels reflect request outcomes, and the last successful data sync is displayed. A failed refresh retains the last successful data and shows an error rather than inventing current values.

Checkout presents the confirmed backend amount and pricing breakdown. CASH requires sufficient tender and records change. EXTERNAL_CARD requires the terminal reference and explicit confirmation. A changed quote must be confirmed again before recording; reconcile prior terminal activity before collecting again. Pending uncertain attempts retain the same key/details and can be checked before retrying. The receipt preview uses native printer/paper selection; completed entry/payment records survive canceled or failed printing.

### Manage branches and cashiers

The admin **Manage** page lists operational branches with code, name, Active/Stopped status, slot count and parked vehicle count. Select a row to Stop, Start or Delete it. Stopping blocks new entries while leaving checkout, fee quotes, payment and receipt printing available to enabled cashiers. Starting restores entry without changing any cashier's individual enabled setting. Main Branch can be stopped and started but cannot be deleted.

Deleting a branch is an archive operation. The backend rejects it while either an active parking session or an occupied slot exists. Once deleted, the branch disappears from Overview, Manage and cashier creation selectors; its cashiers cannot log in or make further API requests. Deleted branches cannot be restarted or assigned new cashiers. Codes, names, slots, sessions, payments, receipts and attribution remain stored. In **Saved data**, the branch filter includes archived branches, with `Deleted` labels and branch status columns on related records. Overview totals cover operational branches; use Saved data for archived history.

Manage also lists cashier username, assigned branch and Active/Stopped/Branch Deleted status. Stop revokes login and subsequent requests from open apps; Start restores a stopped account only when its branch has not been deleted. Remove permanently archives the account and reserves its username, hides it from operational cashier lists, and preserves attribution in historical records. Removed accounts appear as `Removed` in Saved data. All database cashiers use these management controls. Legacy ownership linking is a separate explicit Admin action. Branch stopping does not disable cashier accounts, so existing vehicles can still leave.

Stopping, branch deletion and cashier removal require confirmation naming the selected record and explaining the effect. Requests run off the JavaFX thread; controls are disabled while loading, and successful changes refresh tables, selectors and summaries. Refresh displays changes made by another admin. HTTP Basic credentials are checked afresh on each request; no cached login session allows a stopped/removed account to keep using the API. A request already completed before revocation is not undone. Entry, checkout and management mutations lock the branch row within a transaction, serializing them safely.

### Safe upgrade for management support

1. Log out desktop clients and stop **all** backend instances. Never run the old and new backend against the same database simultaneously.
2. Back up the database before upgrading. For H2, copy `backend/parking-pos-data.mv.db` only after the server has stopped. For PostgreSQL, take a database backup using your normal backup procedure.
3. Keep the existing database, JDBC configuration and environment credentials. Replace application code/builds, then start the updated backend from the same `backend/` working directory. Do not delete the H2 file, change the JDBC URL to a new database, or set `ddl-auto` to `create`/`create-drop`.
4. Management columns are part of the validated legacy baseline and versioned schema. Follow UPGRADE.md for explicit adoption; do not enable Hibernate schema update. Existing branch/cashier states are retained, and initialization neither reactivates nor recreates archived records. Management operations preserve historical records.
5. Start the updated desktop, log in as admin, and verify branch counts and historical records in Saved data before resuming entries. If schema update reports an error, stop and investigate using the backup; do not reset the database. Restore a backup into a separate database when testing recovery.

Backend integration tests use isolated in-memory H2 databases, including an upgrade test that creates the old branch/cashier schema before Hibernate updates it. PostgreSQL requires verification against your deployment database; the test suite does not start a PostgreSQL server.

### Saved Data Excel reports

In **Saved Data**, select Branches, Cashiers, Slots, Sessions or Payments, choose all branches or one branch (including archived branches), and select **Export Excel**. For Sessions and Payments, optional From/To dates also filter the visible saved records. Dates are inclusive calendar days in **Asia/Colombo**: Sessions uses entry time and Payments uses payment time. Either date may be omitted. Other datasets do not accept date filters. Choose the destination in the native Save dialog; canceling leaves files unchanged.

The admin-only backend generates a real `.xlsx` workbook with Apache POI. It reads all matching pages in a consistent database snapshot, including archived business records. The Report sheet contains the report title, generated timestamp, selected filters, date basis and record count; Records contains readable column headers and Colombo timestamps. Payment reports also include payment count and total revenue. Amounts are numeric cells formatted in LKR. Empty results produce a valid workbook with headers and zero records. Exported fields are explicitly selected: credentials, passwords, hashes and tokens are never included, and user-entered strings are written as text cells rather than formulas.

Downloading and saving run on a worker thread with progress and success/error feedback. A temporary file in the chosen directory is replaced into the destination only after a successful download/write, using an atomic move where supported. Logging out cancels pending downloads and prevents old completion messages from reaching the login screen. Large exports use a streaming workbook on the server, but the completed XLSX is buffered for HTTP delivery and download; export size is therefore bounded by available memory, temporary disk space and the client's 120-second request timeout.

### System Management: Broadcast Messages

The admin **System Management** page is separate from **Manage**, which retains branch/cashier controls. Compose a title (up to 120 characters) and message (up to 4,000), select Normal, Important or Urgent, choose all cashiers or selected branches, and optionally choose a future expiry date/time in Asia/Colombo. **Preview & Send** shows the message and audience before confirmation. The form retains its request identifier when retrying an unchanged submission, so an ambiguous network failure cannot send the same message twice. Changing the draft creates a new submission. Controls are disabled while their request is running.

The backend resolves and saves eligible recipients at send time. Enabled, non-removed cashiers in non-deleted branches are eligible, including explicitly linked legacy database cashiers. Stopped branches remain eligible because their cashiers can still complete checkout. Individually stopped/removed accounts and deleted branches are excluded. Creating or reassigning an account later does not add it to an older audience; delivery also requires the account to remain eligible and assigned to the saved recipient branch. An eligible offline cashier receives any still-active messages when next logging in. No email or SMS is sent.

Broadcast history shows audience, priority, sent/expiry times, Active/Expired/Revoked status, recipient count and read count. **View Details** shows the saved audience and read timestamps. **Revoke** requires confirmation, removes the message from active inboxes and preserves the broadcast, recipient snapshot and existing read state for history. Expiry and revocation are enforced by the backend. Only admins can send, revoke or view administrative history; cashiers can retrieve and mark read only their own addressed messages. Unique database constraints and transactional checks prevent duplicate sends/recipient/read records.

The cashier sidebar has one **System Messages** item with Announcements and Conversations tabs. The header bell shows the shared unread count. Important/Urgent unread messages appear in a non-modal banner; they do not open dialogs or interrupt checkout/payment. Read state persists across logins and is idempotent. Messages refresh approximately every 10 seconds, without overlapping requests or duplicate entries, and back off after a connection error. Revocations appear on the next successful poll; local expiry is checked every second. Logout and application shutdown stop the poller and discard pending callbacks.

### Notification bells and private System Messages

The cashier sidebar has one **System Messages** item with **Announcements** and **Conversations** tabs. Announcements contains paginated admin broadcasts and branch notices. Conversations contains **Received Messages** and **Sent Messages**, with one row per direct message or reply. Notification links select the matching tab and open the specific item, including announcements outside the current page. Existing messages, accounts, broadcasts and history are retained.

Both existing vector bells stay neutral and hide their badge at zero unread. Unread Normal notifications use blue, Important use amber and Urgent use red, with the highest unread priority across all pages taking precedence. The badge caps visually at `99+`; its tooltip and accessible label retain the exact count. A new unread notification triggers one brief scale pulse. Repeated polls, paging and read-state changes do not replay it, and the initial login snapshot does not animate old history.

Opening the bell list leaves its rows unread. Opening an individual announcement or notification marks only that displayed item read. Opening a conversation page sends its displayed post IDs so only the corresponding notifications are marked; other pages and later replies remain unread. Read changes refresh the shared bell immediately and revert its styling to neutral when the count reaches zero. Broadcasts continue using their original per-recipient receipt, so they are counted once rather than added again from a second inbox counter. Mark All as Read remains scoped to the authenticated user.

Conversation unread markers and filters use those same per-notification receipts. Reading the latest post does not hide an older unread post on another page. Historical administrator read positions and Sent/Read/Resolved status remain preserved; Mark All as Read does not claim that an administrator opened every conversation.

For safe upgrades, Hibernate adds a nullable `notices.related_post_id`. An idempotent startup step links legacy conversation events to their existing posts and carries forward historical read positions. It preserves IDs, bodies, attribution and timestamps, creates no duplicate notifications, and does not delete any records. Follow the backup and shutdown instructions below before upgrading.

Both headers have a vector **notification bell** beside the connection indicator. The badge shows the signed-in user's unread count, capped visually at `99+`. Open the bell for a paginated notification panel showing title/description, category, priority, sender, branch/cashier, Colombo timestamp and read timestamp. **View Details** shows the full text and related safe record fields; **Open Related** opens a conversation or broadcast. **Mark as Read**, **Mark All as Read**, **View All**, pagination and Retry/Refresh are available. Opening or fetching the panel does not mark notifications read. Read All affects only the current user and notifications visible at the start of the operation; later arrivals remain unread.

Administrators see successful entries, checkout/payments, branch/cashier creation and status/archive changes, cashier conversations/replies, and broadcast publication/revocation across all branches. Details include applicable branch, cashier, ticket/payment IDs and LKR amounts. Reading and delivery never generate new notifications. Broadcast deliveries/read receipts remain aggregate history counts rather than one administrator event per recipient. Routine parking activity stays in the center without opening popups. Cashiers receive their addressed broadcasts, private administrator messages/replies and operational stop/start notices for their own branch. Important/Urgent unread messages use a non-modal banner and do not interrupt payment, confirmation or print dialogs.

Cashiers use **System Messages** to compose a subject, General/Parking Issue/Payment Issue/Technical Issue/Other category, priority, body, and optional related ticket or payment ID. Sender and branch come from authentication; the backend rejects records outside that branch. Received/Sent lists contain individual messages and replies. Incoming messages show Read/Unread, outgoing messages show Sent/Read by recipient, and conversations show Open/Resolved. Lists and thread fetches do not mark messages read. Drafts and stable request keys survive failed sends/replies, preventing duplicates on retry. A successful send clears the draft and selects Sent Messages.

In **System Management**, **Broadcast Messages** remains available alongside the new **Cashier Messages** inbox tab. Administrators can filter conversations by branch, cashier, priority, read state, resolution and subject text, open/reply to a thread, resolve/reopen it, or compose a direct message to an eligible cashier. Resolution changes are timestamped thread entries. Resolved conversations reject replies until reopened. Disabled/removed cashiers and deleted branches cannot receive new direct messages or replies; archived conversations remain visible to administrators. A stopped branch still permits eligible cashiers to message administrators and complete checkout. The configured administrator and database cashiers, including explicitly linked legacy identities, are supported; cashiers can access only their own current-branch conversations.

The existing broadcast poller has been consolidated into **one scheduler per login**, shared by broadcasts and notifications. Requests never overlap on that scheduler. Normal polling is approximately every 10 seconds; failures back off to 20/40/60 seconds, and manual refresh retries immediately. Polling fetches durable missed events after reconnection or next login. Broadcast notifications use the original saved recipient list and read timestamp: marking one read in the bell also updates the existing Messages inbox, and vice versa. Expired/revoked broadcasts are excluded server-side; expiry banners also clear locally. Logout/shutdown cancels requests, closes message/notification dialogs, stops polling, clears workspace/draft state and rejects stale callbacks.

Business operations append a small durable notification event in their existing database transaction. Rolled-back operations therefore leave no success event. Delivery is a later paginated read of that event log, not a synchronous network send or fan-out: a failed poll cannot undo a successful payment, and the event remains available for retry. Per-user read rows and conversation request keys have uniqueness constraints, with locks for concurrent retries. New tables have indexes for audience/branch, user/read state, conversation ownership and thread pagination. No passwords, hashes or tokens enter event payloads. Message content is rendered as plain text. These features are in-app only.

### Safe upgrade for reports and broadcasts

Schema changes now use the versioned migrations described in [UPGRADE.md](UPGRADE.md). Preserve the same database and credentials; validate a stopped backup and rehearse explicit adoption before first upgraded startup. Never use create/create-drop or Hibernate update on the operational database.

The notification/conversation update additionally creates `notices`, `notice_reads`, `conversations`, `conversation_posts` and `conversation_reads`. Existing business tables/rows and broadcast read receipts are preserved. Startup idempotently adds notification-center references for existing broadcasts; it does not recreate old parking activity as new events or reactivate archived accounts. Back up and stop all backend instances before schema upgrades, keep the existing JDBC settings, and verify on a copy of the database first. Existing records require no manual reset or conversion.

### Received and sent conversation messages

Cashiers use **System Messages → Conversations → Received Messages / Sent Messages**. Administrators use the same folders in **System Management → Cashier Messages**. Each row represents one existing message or reply, newest first, with a preview, sender/recipient, priority, time and message read state. Search matches subjects, message bodies and senders; administrators retain branch/cashier filters. Announcements continue to contain broadcasts.

Opening a row or a conversation notification opens the original thread and focuses the selected message. Incoming and outgoing cards appear chronologically. **Load Earlier Messages / Load Later Messages** preserve access to history across pages. Only incoming cards visible in the thread viewport are marked read; fetching lists/pages does not mark them read. Conversation status is **Open / Resolved**; incoming state is **Read / Unread**, while outgoing state is **Sent / Read by recipient**. For cashier messages, a recipient receipt means an administrator read that message; each administrator keeps an independent incoming read state.

Bell reads (including Mark All) and message reads share exact per-message receipts. Message history and receipts remain available independently of notification history. Failed sends retain drafts and reuse their request keys on retry. Navigation/logout closes thread dialogs and rejects stale callbacks. Refresh and the existing session poller update folders and open threads without blocking JavaFX.

This update adds only the `conversation_post_reads` receipt table and post-list indexes. On startup, the existing conversation-notice migration carries forward saved notification read timestamps without replacing conversations, messages, IDs or timestamps. Follow the existing backup/shutdown upgrade procedure before restarting against an existing database. No credentials need to change. PostgreSQL upgrades still require verification on a backed-up copy.

New API: `GET /api/conversation-messages?folder=received` (or `sent`) supports `branchId`, `cashier`, `priority`, `status`, incoming `unread`, `search`, `page`, and `size`. `GET /api/conversations/{id}?postId=...` locates the selected message's chronological page, enforcing conversation ownership before returning anything. Existing conversation and notification endpoints remain available; notification responses now include `relatedPostId` and conversation status uses Open/Resolved.

If Received/Sent returns HTTP 403 after updating the desktop, restart the backend as well. A running JVM keeps its old security filter chain and controller mappings even when Maven or the IDE recompiles files on disk. An older backend rejects the new `/api/conversation-messages` route through its default deny rule, including for Admin; restarting only the desktop cannot fix that mismatch. Stop the old backend, back up its database, then start the current backend from `backend/` with the same database and credential environment settings. Restart the desktop to load its updated error handling. Do not start a second backend against the same H2 file.

With matching versions, database-backed cashiers, including explicitly linked legacy accounts can GET their own Received/Sent folders, open/reply to owned threads, and PUT their own read state. Another owner's thread remains unavailable (404), and `/api/admin/**` remains Admin-only (403 for cashiers). Permission errors do not recommend repeated retries, connection failures offer reconnection guidance, and draft-preservation text appears only when an unsent draft exists. A failed folder request is displayed as unavailable rather than an empty inbox.

### Launch the current Windows desktop

An already-open desktop JVM keeps its loaded screens after a rebuild. Seeing **Sent messages & conversations** identifies the old screen; the current implementation has no fallback to it. Logging out alone cannot replace already-loaded Java classes. Save any unsent draft and close that old application window, then launch a new process. Do not terminate unrelated Java processes.

From any PowerShell directory, this launcher selects the `desktop` module by its own location, rebuilds it, and starts a fresh JVM:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
& 'C:\Users\WW\Desktop\Parking-POS-SpringBoot-JavaFX-MVP\parking-pos\desktop\run.ps1'
```

The window title is **Park Lanka | Login**, **Park Lanka | Admin**, or **Park Lanka | Cashier**. The login footer shows the version and build stamp; the terminal prints the PID, code location and working directory. The launcher does not close existing windows or alter the database. It uses Maven from PATH, the known local Downloads installation, or an explicit `-MavenCommand C:\path\to\mvn.cmd`. For direct Maven use, run `mvn package javafx:run` from `desktop/` with the JDK and Maven on PATH. Normal launch builds updated classes/resources without running `clean`: an open packaged app under `desktop/target/windows` locks its bundled runtime files, which causes `mvn clean` to fail. If a clean build is needed, first close any packaged app running from that directory; `clean` also deletes generated app-images. Use the newly opened window, not a prior session that still has the old title.

The matching backend runs from `backend/`, with the existing database and credential environment variables: `mvn package -DskipTests`, then `java -jar .\target\parking-backend-0.2.0.jar`. The 0.2.0 client also needs the shift, receipt-history, pricing, audit, backup and correction APIs. Upgrade/restart the backend using UPGRADE.md; messaging alone does not prove version compatibility. Before a backend restart, stop only that backend from its own terminal; never delete the saved H2 file or start a second server against it. See **Run on Windows** above for credential setup.

## Configuration

Set these environment variables **before** starting the backend:

| Variable | Default | Meaning |
| --- | --- | --- |
| `PARKING_ADMIN_USER`, `PARKING_ADMIN_PASSWORD` | required | Single configured admin, in backend `.env` or injected secrets; no default password |
| `PARKING_RATE_PER_MINUTE` | `1.00` | Initial rate on a new database, plus fallback rate for entries created before this update |
| `PARKING_INITIAL_SLOTS` | `20` | MAIN slots `A-001` onward; only seeded when DB has no slots |
| `PORT` | `8080` | API listening port |
| `DATABASE_URL` | required | PostgreSQL JDBC URL, locally `jdbc:postgresql://localhost:5432/parking_pos` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | required | Backend-only database role/password, locally role `parking_app` |

For PostgreSQL, create an empty isolated database and set `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD`. Flyway applies versioned migrations and Hibernate validates them. Follow [UPGRADE.md](UPGRADE.md) for an existing database, compatible pg_dump/pg_restore utilities, backups and separate-destination recovery.

The preserved legacy H2 file is `backend/parking-pos-data.mv.db`. It is not migrated by a URL change. Use the separate dry-run/import procedure in ARCHITECTURE.md; do not copy an actively written file.

## API (HTTP Basic authentication)

| Method and path | Meaning |
| --- | --- |
| `GET /api/slots` | Available/occupied slots for the logged-in cashier's branch |
| `GET /api/sessions` | Active parked vehicles in that branch |
| `POST /api/sessions` | Entry: `{ "plate": "ABC-1234", "vehicleType": "CAR", "slotId": 1 }` |
| `GET /api/sessions/{id}/quote` | Estimated bill |
| `POST /api/sessions/{id}/checkout` | Active shift required; `{ "requestKey": "stable-uuid", "method": "CASH", "confirmedAmount": 45.00, "cashTendered": 50.00 }`; EXTERNAL_CARD also requires `reference` and `externalConfirmed: true`. |
| `GET /api/receipts/{sessionId}` | Reopen saved receipt |
| `GET /api/reports/today` | That branch's payments/revenue in Sri Lanka time |
| `GET /api/me` | Signed-in role; cashiers also get their branch |
| `GET /api/rates` | Admin only: saved LKR per started minute |
| `PUT /api/rates` | Admin only: global `{ "perMinute": 2.50 }`, range `0.01` to `100000.00` |
| `GET/POST /api/admin/branches` | Admin: operational branch summary / create `{ "name":"Kandy Branch", "slots":20 }`; numeric code is generated |
| `GET /api/admin/branches?includeDeleted=true` | Admin: include archived branches for Saved data selectors |
| `PUT /api/admin/branches/{id}/enabled` | Admin: `{ "enabled":false }` stops entries, `true` starts; deleted branches cannot restart |
| `DELETE /api/admin/branches/{id}` | Admin: soft delete an empty non-Main branch; preserve history |
| `GET/POST /api/admin/cashiers` | Admin: list / create `{ "username":"cashier2", "password":"a-unique-password", "branchId":2 }` |
| `PUT /api/admin/cashiers/{id}/enabled` | Admin: `{ "enabled": false }` (or true) |
| `DELETE /api/admin/cashiers/{id}` | Admin: soft remove cashier and reserve username; synthetic cashier ID `0` is no longer present |
| `PUT /api/admin/cashiers/{id}/legacy-identity` | Admin: explicit same-person Main Branch legacy ownership link; exact username/confirmation required |
| `PUT /api/admin/cashiers/{id}/password` | Admin: `{ "password": "a-new-password" }` |
| `GET /api/admin/records?table=sessions&branchId=2&page=0&size=50` | Admin: paginated saved records; table choices branches, cashiers, slots, sessions, payments |
| `GET /api/admin/reports/excel?table=payments&branchId=2&from=2026-10-01&to=2026-10-31` | Admin: all matching records as XLSX; branch/from/to optional; dates valid only for sessions/payments. The same date parameters apply to `/api/admin/records` |
| `POST /api/admin/broadcasts` | Admin: `{ "requestKey":"UUID", "title":"Notice", "body":"Message", "priority":"IMPORTANT", "allBranches":true, "branchIds":[], "expiresAt":null }`; selected audience uses `allBranches:false` and branch IDs; expiry is an optional ISO instant |
| `GET /api/admin/broadcasts?page=0&size=50` | Admin: paginated broadcast history with status and recipient/read counts |
| `GET /api/admin/broadcasts/{id}` | Admin: message and saved recipient/read details |
| `PUT /api/admin/broadcasts/{id}/revoke` | Admin: revoke while preserving history; repeat requests are safe |
| `GET /api/messages` | Cashier: own active, unexpired, unrevoked messages |
| `PUT /api/messages/{id}/read` | Cashier: persist own read timestamp; repeats keep the first timestamp |
| `GET /api/notifications?page=0&size=20` | Both roles: own visible paginated notifications, global highest unread priority/latest IDs, total/unread counts and prominent unread notices; optional `section=announcements` filters out conversations |
| `GET /api/notifications/{id}` | Own notification and permitted safe record details |
| `PUT /api/notifications/{id}/read` | Mark own notification read; broadcast reads reuse the existing recipient receipt. `?opened=true` also records an explicitly displayed conversation post as opened |
| `PUT /api/notifications/read-all` | Mark all currently visible notifications read for this user |
| `GET /api/conversations?page=0&size=30` | Cashier: own current-branch threads; admin: all branches. Optional `branchId`, `cashier`, `priority`, `status=OPEN/RESOLVED`, `unread=true/false`, `search` filters |
| `POST /api/conversations` | `{ "requestKey":"UUID", "subject":"Question", "category":"PARKING_ISSUE", "priority":"IMPORTANT", "body":"Plain text", "ticketId":1 }`; optional paymentId; admins must supply an eligible `cashier` username; cashier identity is always derived server-side |
| `GET /api/conversations/{id}?page=0&size=30` | Authorized thread and paginated posts; fetching does not mark read |
| `PUT /api/conversations/{id}/read` | `{ "throughPostId":123, "displayedPostIds":[121,122,123] }`: record displayed posts and only their matching notifications as read; older requests without the list mark the through-post notification |
| `POST /api/conversations/{id}/replies` | `{ "requestKey":"UUID", "body":"Reply" }`; owner/admin only |
| `PUT /api/admin/conversations/{id}/resolution` | Admin: `{ "requestKey":"UUID", "resolved":true }` (or false); persist a timestamped status entry |

Vehicle types: `CAR`, `VAN`, `MOTORCYCLE`, `THREE_WHEELER`, `OTHER`. Checkout is final for that parking session; use a new entry to park the same vehicle again.

## Build / verification

```powershell
cd .\backend
mvn test
mvn package
java -jar .\target\parking-backend-0.2.0.jar
```

Run `java -jar` with the same credential environment variables. Run `mvn javafx:run` in `desktop/` to start the UI. The desktop also has a Windows jpackage workflow that creates **Park Lanka.exe** with a bundled runtime, and supports an EXE installer when WiX 3.x is installed. See [Windows packaging and branding](desktop/WINDOWS-PACKAGING.md) for tools, exact commands, backend URL configuration, icon behavior and verification.

## Native desktop UI verification (Windows)

After building the desktop module, run `desktop/verify-ui.ps1` in PowerShell with Java and Maven on PATH. If your local Maven cache is elsewhere, pass `-MavenRepository C:\path\to\repository`. This executable check compiles `DesktopUiSmoke`, starts a temporary localhost HTTP stub, launches the actual JavaFX screens offscreen, exercises navigation/forms/search/payment/receipt behavior and writes rendered previews to `desktop/target/ui-preview/`. It does not connect to the live backend or any business database. Sample records exist only in this test fixture, never in the application. The check uses the project's existing JavaFX/Jackson dependencies and standard JDK APIs.

Backend regression tests use `mvn test` in `backend/`; package the desktop with `mvn package` in `desktop/`. Keep recovery artifacts outside generated build directories. Report tests open the generated workbooks to verify rows beyond pagination, date boundaries, totals, numeric/text cells, archives and authorization. Broadcast tests cover audience snapshots, database/linked-legacy eligibility, offline delivery, expiry, revocation, read ownership/idempotence, account restrictions and concurrent duplicate sends. The native UI check also exercises broadcast preview/send, message reading, stale responses, credential cleanup, stopping polling on logout, and report file saving/cancellation. Preview sizes are 1366×768, 1920×1080 and 1024×700. The UI check uses a stub rather than a live backend; the native Save dialog, opening reports in Microsoft Excel, PostgreSQL deployment, full keyboard/accessibility traversal, external card hardware and a physical receipt printer still need target-PC verification. On newer JDKs, JavaFX 21 may emit JVM native/Unsafe deprecation warnings; the check uses the Java process exit code to determine success.

Notification/messaging integration tests also cover successful commits versus rollbacks, private ownership, linked-record validation, administrator visibility, replies/read state, independent administrator read positions, pagination and user-scoped Read All, database/linked-legacy eligibility, account restrictions and concurrent duplicate retries. The native JavaFX check exercises the vector bell, `99+` count, opening without marking read, Read All, System Messages compose/reply, duplicate-click protection and polling cleanup alongside the existing parking/payment/export checks. Tests use isolated H2 and a localhost desktop fixture; PostgreSQL, real network outages, multi-PC operation, OS accessibility tools and physical printing need deployment verification.

## Target-environment checks remaining

### Cashier checkout and Parking History

Select a row in **Parked vehicles**, then choose **Quote / Checkout**. With no selection the screen gives guidance. With no active shift use **Open Shift** first; the selected vehicle is preserved and checkout must be reviewed manually after returning to Parking. The checkout window shows the server quote, entry/proposed exit times, duration and saved entry pricing. Its content scrolls so confirmation remains accessible on smaller screens.

For cash, enter **Cash received (LKR)**. The window shows either the amount still required or **Change due (LKR)** as you type; for a fee of 350 and cash of 500 the change is 150. **Confirm Payment & Print** records the payment once, updates Parking, then opens the saved receipt preview. The receipt's **Print** action does not charge again. Cancelling a quote does not change parking; cancelling printing does not undo a saved payment. If an HTTP response is lost, use **Pending checkouts** to reconcile the original attempt before confirming any retry.

**Parking History** has **Vehicle Entries** and **Completed Checkouts** tabs. Both default to today in Asia/Colombo and support ticket/plate search, an optional exact cashier username, date ranges and server pagination. Entry dates filter entries; payment dates filter checkouts. Original receipt values remain unchanged after rate changes or slot reuse, and refund/void totals are shown separately. The existing **Receipt History** remains available.

The cashier-only endpoints are `GET /api/parking-history/entries`, `GET /api/parking-history/entries/{id}` and `GET /api/parking-history/checkouts`. List parameters are `from`, `to`, `search`, `cashier`, `page` and `size` (maximum 100); the server derives branch scope from authentication. No new tables or migrations are required. Restart the matching backend and desktop to load new endpoints/classes; an existing JVM keeps its earlier screens.

To verify the built desktop JAR, after `mvn package` in `desktop/`, run:

```powershell
.\verify-ui.ps1 -DesktopJar .\target\parking-desktop-0.2.0.jar
```

This uses the JAR for application classes/resources and a temporary HTTP fixture, writes previews under `desktop/target/ui-preview-jar/`, and never submits operational payments. Physical printer drivers, paper margins and native print-dialog cancellation still require a target-PC check.

Verify printer drivers/media, external terminal procedures, isolated PostgreSQL backup/restore, multi-PC operation, off-server recovery and physical drawer reconciliation. Installer creation and remaining Windows checks are documented in [Windows packaging and branding](desktop/WINDOWS-PACKAGING.md).
