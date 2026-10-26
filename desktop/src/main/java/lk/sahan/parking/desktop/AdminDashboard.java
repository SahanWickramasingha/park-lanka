package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.time.*;
import java.io.File;
import javafx.stage.FileChooser;

/** Native JavaFX admin workspace; authorization and management rules stay in the backend. */
final class AdminDashboard {
    private final ApiClient api;
    private final ExecutorService io;
    private final Runnable disconnect;
    private final ObservableList<JsonNode> branches = FXCollections.observableArrayList(), archiveBranches = FXCollections.observableArrayList();
    private final TableView<JsonNode> overview = new TableView<>(), staff = new TableView<>(), records = new TableView<>();
    private final TableView<JsonNode> managedBranches = new TableView<>(), managedCashiers = new TableView<>();
    private final Label notice = Ui.notice(), managementHint = Ui.text("Select a row to see its available actions.", "muted");
    private final Label connection = Ui.badge("Connected"), title = Ui.text("", "page-title"), description = Ui.text("", "page-description");
    private final IntegerProperty pending = new SimpleIntegerProperty(), currentPage = new SimpleIntegerProperty(), totalPages = new SimpleIntegerProperty(1);
    private final Ui.Stat branchCount = new Ui.Stat("Branches", "Operational locations"), slotCount = new Ui.Stat("Total slots", "Across operational branches");
    private final Ui.Stat occupiedCount = new Ui.Stat("Occupied slots", "Currently in use"), paymentCount = new Ui.Stat("Today's payments", "Sri Lanka local time");
    private final Ui.Stat revenue = new Ui.Stat("Today's revenue", "LKR · operational branches");
    private final StackPane workspace = new StackPane();
    private final Map<String, Node> views = new LinkedHashMap<>();
    private final Map<String, String> descriptions = Map.of("Overview", "A clear view of your branches and today's parking activity.",
            "Cashiers", "Create accounts, assign branches and manage cashier access.", "Saved Data", "Browse stored records, including archived branches and accounts.",
            "Rates", "Set the parking rate for new vehicle entries.", "Manage", "Control branch operations and cashier access safely.");
    private final Label dataPage = Ui.text("Loading records...", "muted");
    private ComboBox<JsonNode> staffBranch, dataBranch;
    private ComboBox<String> dataset;
    private Scene scene;
    private DatePicker fromDate, toDate;
    private SystemManagementPane systemManagement;
    private NotificationBell notifications;
    private ReceiptHistoryPane receiptHistory;
    private ShiftsPane shifts;
    private AuditPane audit;
    private BackupsPane backups;
    private CorrectionsPane corrections;
    private boolean connected = true, updatingPickers;
    private long branchRequest, cashierRequest, dataRequest;

    AdminDashboard(ApiClient api, ExecutorService io, Consumer<String> unusedError, Runnable disconnect) {
        this.api = api; this.io = io; this.disconnect = disconnect;
    }

    Scene scene() {
        views.put("Overview", Ui.scroll(overviewPane())); views.put("Cashiers", Ui.scroll(cashierPane()));
        views.put("Saved Data", Ui.scroll(dataPane())); views.put("Rates", Ui.scroll(ratePane())); views.put("Manage", Ui.scroll(managePane()));
        receiptHistory=new ReceiptHistoryPane(api,io,true,()->connected&&title.getText().equals("Receipt History"));views.put("Receipt History",Ui.scroll(receiptHistory.view()));
        shifts=new ShiftsPane(api,io,true,()->connected&&title.getText().equals("Shifts"),s->{});views.put("Shifts",Ui.scroll(shifts.view()));
        audit=new AuditPane(api,io,archiveBranches,()->connected&&title.getText().equals("Audit History"));views.put("Audit History",Ui.scroll(audit.view()));
        backups=new BackupsPane(api,io,()->connected&&title.getText().equals("Backups"));views.put("Backups",Ui.scroll(backups.view()));
        corrections=new CorrectionsPane(api,io,archiveBranches,()->connected&&title.getText().equals("Payment Corrections"));views.put("Payment Corrections",Ui.scroll(corrections.view()));
        ToggleGroup group = new ToggleGroup(); VBox nav = Ui.style(new VBox(), "nav-list");
        systemManagement = new SystemManagementPane(api, io, branches,
                () -> connected && title.getText().equals("System Management"), this::info, this::fail);
        views.put("System Management", Ui.scroll(systemManagement.view()));
        for (String name : views.keySet()) {
            ToggleButton button = Ui.style(new ToggleButton(name, Ui.icon(name)), "nav-item"); button.setToggleGroup(group); button.setMaxWidth(Double.MAX_VALUE);
            button.setWrapText(true);
            button.setOnAction(e -> { button.setSelected(true); navigate(name); }); nav.getChildren().add(button);
            if (name.equals("Overview")) { button.setSelected(true); navigate(name); }
        }
        VBox sidebar = Ui.sidebar(api.username(), "Administrator", nav, () -> { dispose(); disconnect.run(); });
        ProgressIndicator progress = new ProgressIndicator(); progress.setMaxSize(18, 18);
        progress.visibleProperty().bind(pending.greaterThan(0)); progress.managedProperty().bind(progress.visibleProperty());
        VBox headings = new VBox(6, Ui.text("WORKSPACE / ADMINISTRATION", "eyebrow"), title, description); HBox.setHgrow(headings, Priority.ALWAYS);
        headings.setMinWidth(0);
        notifications=new NotificationBell(api,io,true,()->connected,(type,id,post)->{ navigate("System Management"); if(type.equals("CONVERSATION"))systemManagement.openConversation(id,post);else systemManagement.openBroadcast(id); });
        notifications.poller.add(()->Platform.runLater(()->{if(connected)systemManagement.pollMessages();}));
        systemManagement.onMessagesChanged(notifications::refresh);
        HBox header = Ui.style(Ui.row(headings, progress, connection,notifications.control()), "page-header");
        VBox feedback = Ui.style(new VBox(notice), "feedback-area");
        VBox content = new VBox(header, feedback, workspace); VBox.setVgrow(workspace, Priority.ALWAYS); content.setMinWidth(0);
        BorderPane root = new BorderPane(content); root.setLeft(sidebar); scene = Ui.scene(root, 1366, 850);
        scene.windowProperty().addListener((o, old, window) -> { if (old != null && window == null) dispose(); });
        loadBranches(); loadCashiers(); loadData(); notifications.start(); return scene;
    }
    private void navigate(String name) {
        if(receiptHistory!=null&&!name.equals("Receipt History"))receiptHistory.deactivate();
        if(!name.equals("System Management")&&systemManagement!=null)systemManagement.deactivate();
        workspace.getChildren().setAll(views.get(name)); title.setText(name);
        if(name.equals("Receipt History"))receiptHistory.activate();
        if(name.equals("Shifts"))shifts.activate();
        if(name.equals("Audit History"))audit.activate();
        if(name.equals("Backups"))backups.activate();
        if(name.equals("Payment Corrections"))corrections.activate();
        description.setText(switch(name){case "Receipt History"->"Search original receipts across branches and request marked reprints.";case "Shifts"->"Inspect cashier drawers and reconcile shift closing cash.";case "Audit History"->"Review committed actions and historical accountability.";case "Backups"->"Manage consistent server backups and approved offline recovery.";case "Payment Corrections"->"Record authorized refunds and voids with immutable history.";default->descriptions.getOrDefault(name,"Send in-app broadcasts and review delivery and read status.");});
        if (name.equals("System Management")) systemManagement.activate();
    }
    private void dispose() {
        connected = false;
        if(notifications!=null)notifications.close();
        if (systemManagement != null) systemManagement.close();
        views.values().forEach(Ui::clearTextInputs);
        for (Ui.Stat stat : List.of(branchCount, slotCount, occupiedCount, paymentCount, revenue)) stat.set("—");
        branches.clear(); archiveBranches.clear();
        for (TableView<JsonNode> table : List.of(overview, staff, records, managedBranches, managedCashiers)) table.getItems().clear();
    }

    private VBox overviewPane() {
        overview.getColumns().setAll(col("Code", "code", 70), col("Branch", "name", 170), col("Status", "status", 110),
                col("Slots", "totalSlots", 65), col("Occupied", "occupied", 85), col("Cashiers", "cashiers", 85), col("Parked", "activeVehicles", 75),
                col("Payments today", "todayPayments", 120), col("Revenue · LKR", "todayRevenue", 120), col("All payments", "totalPayments", 110));
        Ui.table(overview, "No branches available.", 280);
        Ui.fillColumns(overview);
        VBox branchCard = Ui.card(Ui.row(Ui.text("Branches & activity", "section-title"), Ui.spacer(), refreshButton(this::refreshAll)), overview,
                Ui.text("Today follows Asia/Colombo time. Archived branches remain available in Saved Data.", "muted"));
        TextField name = new TextField(); name.setPromptText("e.g. Kandy Branch"); TextField count = new TextField(); count.setPromptText("1–1000");
        Button create = busyButton("Add Branch", "primary");
        create.setOnAction(e -> {
            String branchName = name.getText().trim(); int slots;
            try { slots = Integer.parseInt(count.getText().trim()); } catch (NumberFormatException ex) { fail("Enter a whole number of slots between 1 and 1000."); return; }
            if (branchName.length() < 2 || branchName.length() > 60 || slots < 1 || slots > 1000) { fail("Use a branch name of 2–60 characters and 1–1000 slots."); return; }
            call(() -> api.post("/api/admin/branches", Map.of("name", branchName, "slots", slots)), result -> {
                name.clear(); count.clear(); refreshAll(); info("Branch " + result.path("name").asText() + " added.");
            });
        });
        return Ui.page(Ui.stats(branchCount, slotCount, occupiedCount, paymentCount, revenue), branchCard,
                Ui.section("Add a branch", "Give your location a name. Its numeric branch code is assigned automatically.",
                        Ui.flow(Ui.field("Branch name", name), Ui.field("Number of slots", count), create)));
    }

    private VBox cashierPane() {
        staff.getColumns().setAll(col("Username", "username", 170), col("Assigned branch", "branch", 245), col("Status", "status", 145), col("Account management", "bootstrap", 185));
        Ui.table(staff, "No cashier accounts available.", 280);
        Ui.fillColumns(staff);
        Button toggle = Ui.button("Stop / Start Cashier", "secondary"), reset = Ui.button("Reset Password", "secondary"), legacy = Ui.button("Link Legacy History", "secondary");
        Predicate<JsonNode> editable = row -> !row.path("bootstrap").asBoolean() && !row.path("branchDeleted").asBoolean();
        bindAction(toggle, staff, editable); bindAction(reset, staff, editable); bindAction(legacy, staff, editable);
        legacy.setOnAction(e -> linkLegacy(staff.getSelectionModel().getSelectedItem()));
        toggle.setOnAction(e -> { JsonNode row = staff.getSelectionModel().getSelectedItem(); setCashier(row, !row.path("enabled").asBoolean()); });
        Label hint = Ui.text("Select a cashier to change access or reset a password.", "muted");
        staff.getSelectionModel().selectedItemProperty().addListener((o, old, row) -> {
            toggle.setText(row == null || row.path("enabled").asBoolean() ? "Stop Cashier" : "Start Cashier"); hint.setText(cashierHint(row));
        });
        reset.setOnAction(e -> resetPassword(staff.getSelectionModel().getSelectedItem()));
        VBox accounts = Ui.card(Ui.row(Ui.text("Cashier accounts", "section-title"), Ui.spacer(), refreshButton(this::refreshAll)), staff, Ui.flow(toggle, reset, legacy), hint);
        TextField username = new TextField(); username.setPromptText("e.g. kandy.cashier");
        PasswordField password = new PasswordField(); password.setPromptText("10–72 characters"); staffBranch = branchPicker("Choose a branch", branches);
        Button create = busyButton("Add Cashier", "primary");
        create.setOnAction(e -> {
            JsonNode branch = staffBranch.getValue(); String user = username.getText().trim(), pass = password.getText();
            if (!user.toLowerCase(Locale.ROOT).matches("[a-z][a-z0-9_.-]{2,31}")) { fail("Use a username of 3–32 letters, numbers, dots, underscores or hyphens, starting with a letter."); return; }
            if (pass.length() < 10 || pass.length() > 72) { fail("Password must contain 10–72 characters."); return; }
            if (branch == null) { fail("Choose a branch for this cashier."); return; }
            Map<String, Object> request = Map.of("username", user, "password", pass, "branchId", branch.path("id").asLong());
            call(() -> api.post("/api/admin/cashiers", request), result -> { username.clear(); password.clear(); refreshAll(); info("Cashier " + result.path("username").asText() + " added."); });
        });
        return Ui.page(accounts, Ui.section("Add a cashier", "Each cashier works within their assigned branch.",
                Ui.flow(Ui.field("Username", username), Ui.field("Password", password), Ui.field("Assigned branch", staffBranch), create)),
                Ui.text("All cashiers are managed here. Link legacy history only after verifying the same person and username in Main Branch.", "muted"));
    }

    private void linkLegacy(JsonNode row) {
        if (row == null) return;
        String username = row.path("username").asText();
        TextInputDialog dialog = new TextInputDialog(); Ui.dialog(dialog, scene.getWindow());
        dialog.setTitle("Link legacy cashier history"); dialog.setHeaderText("Verify the same person: " + username);
        dialog.setContentText("Main Branch only. This grants access to this exact legacy username's messages and shifts. Type LINK LEGACY " + username);
        dialog.showAndWait().filter(value -> value.equals("LINK LEGACY " + username)).ifPresent(value ->
                manage(() -> api.put("/api/admin/cashiers/" + row.path("id").asLong() + "/legacy-identity",
                        Map.of("legacyUsername", username, "confirmation", value)), "Legacy history linked for " + username + "."));
    }

    private void resetPassword(JsonNode row) {
        if (row == null || row.path("bootstrap").asBoolean()) return;
        Dialog<String> dialog = new Dialog<>(); dialog.setTitle("Reset cashier password"); dialog.setHeaderText("Reset password · " + row.path("username").asText()); Ui.dialog(dialog, scene.getWindow());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        PasswordField password = new PasswordField(); password.setPromptText("10–72 characters"); Label validation = Ui.notice();
        dialog.getDialogPane().setContent(Ui.section("New password", "The cashier must use this password for subsequent requests.", Ui.field("Password", password), validation));
        Button save = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK); Ui.style(save, "primary"); save.setText("Reset Password");
        save.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (password.getText().length() < 10 || password.getText().length() > 72) { e.consume(); Ui.notice(validation, "Password must contain 10–72 characters.", true); }
        });
        dialog.setResultConverter(b -> b == ButtonType.OK ? password.getText() : null);
        dialog.showAndWait().ifPresent(value -> call(() -> api.put("/api/admin/cashiers/" + row.path("id").asLong() + "/password", Map.of("password", value)), result -> info("Password updated for " + row.path("username").asText() + ".")));
    }

    private VBox dataPane() {
        dataset = new ComboBox<>(FXCollections.observableArrayList("branches", "cashiers", "slots", "sessions", "payments")); dataset.setValue("sessions");
        dataBranch = branchPicker("All branches", archiveBranches);
        fromDate = new DatePicker(); toDate = new DatePicker(); fromDate.setEditable(false); toDate.setEditable(false);
        fromDate.setPromptText("Any date"); toDate.setPromptText("Any date");
        dataset.valueProperty().addListener((o, old, value) -> {
            boolean dates = Set.of("sessions", "payments").contains(value);
            if (!dates) { updatingPickers = true; fromDate.setValue(null); toDate.setValue(null); updatingPickers = false; }
            fromDate.setDisable(!dates); toDate.setDisable(!dates); currentPage.set(0); loadData();
        });
        fromDate.valueProperty().addListener((o, old, value) -> { if (!updatingPickers) { currentPage.set(0); loadData(); } });
        toDate.valueProperty().addListener((o, old, value) -> { if (!updatingPickers) { currentPage.set(0); loadData(); } });
        dataBranch.valueProperty().addListener((o, old, value) -> { if (!updatingPickers) { currentPage.set(0); loadData(); } });
        Button all = Ui.button("All Branches", "quiet"); all.setOnAction(e -> dataBranch.setValue(null));
        Button previous = Ui.button("Previous", "secondary"), next = Ui.button("Next", "secondary");
        previous.disableProperty().bind(pending.greaterThan(0).or(currentPage.lessThanOrEqualTo(0)));
        next.disableProperty().bind(pending.greaterThan(0).or(currentPage.greaterThanOrEqualTo(totalPages.subtract(1))));
        previous.setOnAction(e -> { currentPage.set(currentPage.get() - 1); loadData(); }); next.setOnAction(e -> { currentPage.set(currentPage.get() + 1); loadData(); });
        Ui.table(records, "No records match these filters.", 385);
        Button export = busyButton("Export Excel", "primary"); export.setOnAction(e -> exportReport());
        Button clearDates = Ui.button("Clear Dates", "quiet"); clearDates.setOnAction(e -> {
            updatingPickers = true; fromDate.setValue(null); toDate.setValue(null); updatingPickers = false; currentPage.set(0); loadData();
        });
        return Ui.page(Ui.section("Database records", "Historical records stay available here after a branch or cashier is archived. Passwords are never displayed.",
                Ui.flow(Ui.field("Dataset", dataset), Ui.field("Branch", dataBranch), all, refreshButton(this::loadData)),
                Ui.flow(Ui.field("From date · Asia/Colombo", fromDate), Ui.field("To date · inclusive", toDate), clearDates, export),
                Ui.text("Dates filter session entry time or payment time. Excel includes every matching record, including archives.", "muted")),
                Ui.card(records, Ui.flow(previous, next, dataPage)));
    }

    private VBox ratePane() {
        Label currentRate = Ui.text("Loading...", "amount"); TextField amount = new TextField(); amount.setPromptText("e.g. 2.50"); Button save = busyButton("Save Rate", "primary");
        save.setOnAction(e -> {
            BigDecimal value;
            try { value = new BigDecimal(amount.getText().trim()); if (value.signum() <= 0 || value.scale() > 2 || value.compareTo(new BigDecimal("100000")) > 0) throw new NumberFormatException(); }
            catch (NumberFormatException ex) { fail("Enter a rate from 0.01 to 100000.00 with at most two decimal places."); return; }
            call(() -> api.put("/api/rates", Map.of("perMinute", value)), result -> { currentRate.setText("LKR " + Ui.money(result.path("perMinute"))); amount.clear(); info("Rate saved. New entries at all branches use the updated rate."); });
        });
        call(() -> api.get("/api/rates"), result -> currentRate.setText("LKR " + Ui.money(result.path("perMinute"))));
        VBox rate = Ui.section("Current parking rate", "Global fallback when no branch policy applies.", currentRate, Ui.text("LKR per started minute", "muted"));
        VBox form = Ui.section("Update rate", "Only new entries use the updated rate. Vehicles already parked keep their entry rate.", Ui.flow(Ui.field("New rate · LKR per started minute", amount), save));
        rate.setMaxWidth(780); form.setMaxWidth(780); return Ui.page(rate, form,new PricingRulesPane(api,io,branches).view());
    }

    private VBox managePane() {
        managedBranches.getColumns().setAll(col("Code", "code", 80), col("Branch", "name", 260), col("Status", "status", 130), col("Total slots", "totalSlots", 120), col("Parked vehicles", "activeVehicles", 140));
        managedCashiers.getColumns().setAll(col("Username", "username", 210), col("Assigned branch", "branch", 300), col("Status", "status", 160));
        Ui.table(managedBranches, "No operational branches.", 230); Ui.table(managedCashiers, "No cashier accounts.", 230);
        Ui.fillColumns(managedBranches); Ui.fillColumns(managedCashiers);
        Button stopBranch = Ui.button("Stop Branch", "warning"), startBranch = Ui.button("Start Branch", "success"), deleteBranch = Ui.button("Delete Branch", "danger");
        bindAction(stopBranch, managedBranches, row -> row.path("status").asText().equals("Active")); bindAction(startBranch, managedBranches, row -> row.path("status").asText().equals("Stopped"));
        bindAction(deleteBranch, managedBranches, row -> !row.path("main").asBoolean()); stopBranch.setOnAction(e -> changeBranch(false)); startBranch.setOnAction(e -> changeBranch(true));
        deleteBranch.setOnAction(e -> {
            JsonNode row = managedBranches.getSelectionModel().getSelectedItem();
            if (row == null || !confirm("Delete Branch", branchLabel(row), "Archive this branch and deny its cashiers access. All vehicles must be checked out and slots free. History remains in Saved Data. This branch cannot be restarted.", true)) return;
            manage(() -> api.deleteBranch(row.path("id").asLong()), "Branch " + branchLabel(row) + " deleted. History retained.");
        });
        Label branchHint = Ui.text("Select a branch. Stopping blocks entry while allowing checkout, payment and receipts.", "muted");
        managedBranches.getSelectionModel().selectedItemProperty().addListener((o, old, row) -> branchHint.setText(row == null ? "Select a branch to manage its operations." : row.path("main").asBoolean()
                ? "Main Branch may be stopped or started, but cannot be deleted." : row.path("activeVehicles").asLong() > 0 || row.path("occupied").asLong() > 0
                ? "Check out all parked vehicles and free occupied slots before deleting this branch." : "Stopping preserves checkout. Deleting permanently archives this branch and revokes its cashiers' access."));
        Button stopCashier = Ui.button("Stop Cashier", "warning"), startCashier = Ui.button("Start Cashier", "success"), removeCashier = Ui.button("Remove Cashier", "danger");
        bindAction(stopCashier, managedCashiers, row -> !row.path("bootstrap").asBoolean() && row.path("enabled").asBoolean());
        bindAction(startCashier, managedCashiers, row -> !row.path("bootstrap").asBoolean() && !row.path("enabled").asBoolean() && !row.path("branchDeleted").asBoolean());
        bindAction(removeCashier, managedCashiers, row -> !row.path("bootstrap").asBoolean());
        stopCashier.setOnAction(e -> setCashier(managedCashiers.getSelectionModel().getSelectedItem(), false)); startCashier.setOnAction(e -> setCashier(managedCashiers.getSelectionModel().getSelectedItem(), true));
        removeCashier.setOnAction(e -> {
            JsonNode row = managedCashiers.getSelectionModel().getSelectedItem();
            if (row == null || !confirm("Remove Cashier", row.path("username").asText(), "Deny login and further requests from open apps. Archive this account and reserve its username permanently. Historical attribution remains in Saved Data. This account cannot be restarted.", true)) return;
            manage(() -> api.removeCashier(row.path("id").asLong()), "Cashier " + row.path("username").asText() + " removed.");
        });
        managedCashiers.getSelectionModel().selectedItemProperty().addListener((o, old, row) -> managementHint.setText(cashierHint(row)));
        return Ui.page(Ui.row(Ui.text("Changes apply to the shared server.", "muted"), Ui.spacer(), refreshButton(this::refreshAll)),
                Ui.section("Branch management", "Control vehicle entry and archive locations without losing history.", managedBranches, Ui.flow(stopBranch, startBranch, deleteBranch), branchHint),
                Ui.section("Cashier management", "Stopped and removed accounts lose access on their next request.", managedCashiers, Ui.flow(stopCashier, startCashier, removeCashier), managementHint));
    }

    private String cashierHint(JsonNode row) {
        if (row == null) return "Select a cashier to see available actions.";
        if (row.path("bootstrap").asBoolean()) return "This screen requires the current backend with database cashier accounts.";
        if (row.path("branchDeleted").asBoolean()) return "This cashier's branch has been deleted. Access cannot be restarted. You can archive the account with Remove Cashier.";
        return row.path("enabled").asBoolean() ? "Stopping revokes subsequent requests, including requests from already open apps." : "Start restores access. Remove permanently archives the account and reserves its username.";
    }
    private Button busyButton(String name, String kind) { Button button = Ui.button(name, kind); button.disableProperty().bind(pending.greaterThan(0)); return button; }
    private Button refreshButton(Runnable action) { Button button = busyButton("Refresh", "secondary"); button.setOnAction(e -> action.run()); return button; }
    private void bindAction(Button button, TableView<JsonNode> table, Predicate<JsonNode> valid) {
        button.disableProperty().bind(Bindings.createBooleanBinding(() -> { JsonNode row = table.getSelectionModel().getSelectedItem(); return pending.get() > 0 || row == null || !valid.test(row); }, pending, table.getSelectionModel().selectedItemProperty()));
    }
    private void changeBranch(boolean enabled) {
        JsonNode row = managedBranches.getSelectionModel().getSelectedItem(); if (row == null) return;
        if (!enabled && !confirm("Stop Branch", branchLabel(row), "Prevent new entries. Enabled cashiers can still check out existing vehicles, collect payment and print receipts.", false)) return;
        manage(() -> api.setBranchEnabled(row.path("id").asLong(), enabled), "Branch " + branchLabel(row) + (enabled ? " started." : " stopped. Checkout remains available."));
    }
    private void setCashier(JsonNode row, boolean enabled) {
        if (row == null || row.path("bootstrap").asBoolean()) return; String name = row.path("username").asText();
        if (!enabled && !confirm("Stop Cashier", name, "Deny login and further authenticated requests from already open apps.", false)) return;
        manage(() -> api.setCashierEnabled(row.path("id").asLong(), enabled), "Cashier " + name + (enabled ? " started." : " stopped."));
    }
    private boolean confirm(String action, String selected, String effect, boolean destructive) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, effect, ButtonType.OK, ButtonType.CANCEL); Ui.dialog(dialog, scene.getWindow()); dialog.setTitle(action); dialog.setHeaderText(action + " · " + selected);
        Button accept = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK); accept.setText(action); Ui.style(accept, destructive ? "danger-solid" : "warning");
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
    private void manage(Callable<JsonNode> task, String message) { call(task, result -> { refreshAll(); info(message); }); }
    private void refreshAll() { loadBranches(); loadCashiers(); loadData(); }

    private void loadBranches() {
        long request = ++branchRequest;
        call(() -> api.get("/api/admin/branches?includeDeleted=true"), result -> {
            Long staffId = selectedId(staffBranch.getValue()), dataId = selectedId(dataBranch.getValue()); updatingPickers = true;
            try {
                archiveBranches.clear(); result.forEach(archiveBranches::add); branches.setAll(archiveBranches.filtered(b -> !b.path("status").asText().equals("Deleted")));
                staffBranch.setValue(find(branches, staffId)); if (staffBranch.getValue() == null && !branches.isEmpty()) staffBranch.setValue(branches.get(0)); dataBranch.setValue(find(archiveBranches, dataId));
            } finally { updatingPickers = false; }
            replaceRows(overview, branches); replaceRows(managedBranches, branches);
            long slots = 0, occupied = 0, payments = 0; BigDecimal amount = BigDecimal.ZERO;
            for (JsonNode row : branches) { slots += row.path("totalSlots").asLong(); occupied += row.path("occupied").asLong(); payments += row.path("todayPayments").asLong(); amount = amount.add(new BigDecimal(row.path("todayRevenue").asText("0"))); }
            branchCount.set(branches.size()); slotCount.set(slots); occupiedCount.set(occupied); paymentCount.set(payments); revenue.set(amount.setScale(2).toPlainString()); loadData();
        }, () -> request == branchRequest);
    }
    private void loadCashiers() {
        long request = ++cashierRequest;
        call(() -> api.get("/api/admin/cashiers"), result -> { List<JsonNode> rows = new ArrayList<>(); result.forEach(rows::add); replaceRows(staff, rows); replaceRows(managedCashiers, rows); }, () -> request == cashierRequest);
    }
    private void loadData() {
        if (dataset == null || dataset.getValue() == null) return; long request = ++dataRequest;
        if (!validDates()) return;
        String table = dataset.getValue(); JsonNode branch = dataBranch.getValue(); int requestedPage = currentPage.get();
        String path = "/api/admin/records?table=" + table + "&page=" + requestedPage + "&size=50" + filterQuery();
        call(() -> api.get(path), result -> {
            totalPages.set(Math.max(1, result.path("pages").asInt())); if (requestedPage >= totalPages.get()) { currentPage.set(totalPages.get() - 1); loadData(); return; }
            configureColumns(table); List<JsonNode> rows = new ArrayList<>(); result.path("rows").forEach(rows::add); replaceRows(records, rows);
            dataPage.setText("Page " + (requestedPage + 1) + " of " + totalPages.get() + "  ·  " + result.path("total").asText() + " records");
        }, () -> request == dataRequest);
    }
    private boolean validDates() {
        if (fromDate != null && fromDate.getValue() != null && toDate.getValue() != null && fromDate.getValue().isAfter(toDate.getValue())) {
            fail("From date must be on or before To date."); return false;
        }
        return true;
    }
    private String filterQuery() {
        JsonNode branch = dataBranch.getValue();
        return (branch == null ? "" : "&branchId=" + branch.path("id").asLong())
                + (fromDate.getValue() == null ? "" : "&from=" + fromDate.getValue())
                + (toDate.getValue() == null ? "" : "&to=" + toDate.getValue());
    }
    private void exportReport() {
        if (!validDates()) return;
        String path = "/api/admin/reports/excel?table=" + dataset.getValue() + filterQuery();
        FileChooser chooser = new FileChooser(); chooser.setTitle("Export Excel Report");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel workbook (*.xlsx)", "*.xlsx"));
        chooser.setInitialFileName("parking-" + dataset.getValue() + "-" + LocalDate.now(ZoneId.of("Asia/Colombo")) + ".xlsx");
        File file = chooser.showSaveDialog(scene.getWindow());
        if (file == null) { info("Report export canceled."); return; }
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) { fail("Choose a filename ending in .xlsx."); return; }
        pending.set(pending.get() + 1); info("Generating and saving the complete report...");
        CompletableFuture.runAsync(() -> { try { api.saveReport(path, file.toPath()); } catch (Exception ex) { throw new CompletionException(ex); } }, io)
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    try {
                        if (!connected || !api.isActive()) return;
                        if (failure != null) fail("Report export failed: " + failure.getCause().getMessage());
                        else info("Excel report saved: " + file.getAbsolutePath());
                    } finally { pending.set(pending.get() - 1); }
                }));
    }
    private Long selectedId(JsonNode row) { return row == null ? null : row.path("id").asLong(); }
    private JsonNode find(Collection<JsonNode> rows, Long id) { return id == null ? null : rows.stream().filter(row -> row.path("id").asLong() == id).findFirst().orElse(null); }
    private void replaceRows(TableView<JsonNode> table, Collection<JsonNode> rows) { Long id = selectedId(table.getSelectionModel().getSelectedItem()); table.getItems().setAll(rows); JsonNode restored = find(rows, id); if (restored != null) table.getSelectionModel().select(restored); }
    private void configureColumns(String table) {
        String[] fields = switch (table) {
            case "branches" -> new String[]{"id", "code", "name", "status"}; case "cashiers" -> new String[]{"id", "username", "branch", "status", "branchStatus"};
            case "slots" -> new String[]{"id", "code", "branch", "branchStatus", "occupied"}; case "payments" -> new String[]{"id", "ticket", "plate", "branch", "branchStatus", "amount", "method", "paidAt", "cashier"};
            default -> new String[]{"id", "plate", "type", "branch", "branchStatus", "slot", "enteredAt", "exitedAt", "entryRate", "enteredBy"};
        };
        records.getColumns().clear();
        for (String field : fields) records.getColumns().add(col(switch (field) {
            case "branchStatus" -> "Branch status"; case "enteredBy" -> "Entered by"; case "entryRate" -> "Entry rate"; case "enteredAt" -> "Entered"; case "exitedAt" -> "Exited"; case "paidAt" -> "Paid";
            default -> field.substring(0, 1).toUpperCase(Locale.ROOT) + field.substring(1);
        }, field, switch (field) { case "id", "ticket" -> 75; case "name", "branch" -> 230; case "enteredAt", "exitedAt", "paidAt" -> 185; default -> 150; }));
    }
    private ComboBox<JsonNode> branchPicker(String prompt, ObservableList<JsonNode> rows) {
        ComboBox<JsonNode> box = new ComboBox<>(rows); box.setPromptText(prompt); box.setCellFactory(c -> branchCell(prompt)); box.setButtonCell(branchCell(prompt)); return box;
    }
    private ListCell<JsonNode> branchCell(String prompt) { return new ListCell<>() { @Override protected void updateItem(JsonNode row, boolean empty) { super.updateItem(row, empty); setText(empty || row == null ? prompt : branchLabel(row)); } }; }
    private String branchLabel(JsonNode row) { return row.path("code").asText() + " – " + row.path("name").asText() + (row.path("status").asText().equals("Active") ? "" : " (" + row.path("status").asText() + ")"); }
    private TableColumn<JsonNode, String> col(String title, String field, double width) { return Ui.column(title, field, width); }
    private void info(String message) { Ui.notice(notice, message, false); }
    private void fail(String message) { Ui.notice(notice, message, true); }
    private void call(Callable<JsonNode> task, Consumer<JsonNode> success) { call(task, success, () -> true); }
    private void call(Callable<JsonNode> task, Consumer<JsonNode> success, BooleanSupplier current) {
        if (!connected || !api.isActive()) return; pending.set(pending.get() + 1);
        CompletableFuture.supplyAsync(() -> { try { return task.call(); } catch (Exception e) { throw new CompletionException(e); } }, io)
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    try {
                        if (!connected || !api.isActive() || !current.getAsBoolean()) return;
                        if (failure != null) { Ui.setBadge(connection, "Request failed"); fail(failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage()); }
                        else { Ui.setBadge(connection, "Connected"); success.accept(result); }
                    } finally { pending.set(pending.get() - 1); }
                }));
    }
}
