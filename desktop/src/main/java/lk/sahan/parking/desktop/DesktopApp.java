package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.*;
import javafx.beans.property.*;
import javafx.beans.binding.Bindings;
import javafx.collections.*;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class DesktopApp extends Application {
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ObservableList<JsonNode> active = FXCollections.observableArrayList(), availableSlots = FXCollections.observableArrayList();
    private final FilteredList<JsonNode> visibleVehicles = new FilteredList<>(active);
    private final TableView<JsonNode> table = new TableView<>(visibleVehicles);
    private final ComboBox<JsonNode> slot = new ComboBox<>(availableSlots);
    private final BooleanProperty refreshing = new SimpleBooleanProperty(), entryBusy = new SimpleBooleanProperty(), quoteBusy = new SimpleBooleanProperty(), paymentBusy = new SimpleBooleanProperty();
    private final Label notice = Ui.notice(), connection = Ui.badge("Connecting"), lastSync = Ui.text("Waiting for parking data...", "muted");
    private final Label availability = Ui.text("", "muted");
    private final Ui.Stat freeCount = new Ui.Stat("Available slots", "Ready for vehicle entry"), occupiedCount = new Ui.Stat("Occupied slots", "Currently in use");
    private final Ui.Stat paymentCount = new Ui.Stat("Today's payments", "Sri Lanka local time"), revenue = new Ui.Stat("Today's revenue", "LKR · this branch");
    private ApiClient api;
    private CashierMessagesPane messages;
    private NotificationBell notifications; private ConversationsPane conversations;
    private String loginMessage = "";
    private CheckoutCoordinator checkouts;
    private Stage stage;
    private final Supplier<String> serverUrl;
    private final Function<ApiClient, LoginStartup> loginFactory;
    private LoginStartup pendingLogin;
    private String selectedServerUrl;
    private String branchName = "Main Branch";
    private long refreshRequest;
    private final Map<String,JsonNode> slotsByCode=new LinkedHashMap<>();
    private final Set<Long> completedSessions=new HashSet<>();
    private boolean checkoutRefreshPending;
    private boolean showCheckoutCompletion;
    private String completedTicket="";

    public DesktopApp() { this(DesktopConfiguration::serverUrl); }
    DesktopApp(Supplier<String> serverUrl) { this(serverUrl, LoginStartup::new); }
    DesktopApp(Supplier<String> serverUrl, Function<ApiClient, LoginStartup> loginFactory) {
        this.serverUrl = Objects.requireNonNull(serverUrl); this.loginFactory = Objects.requireNonNull(loginFactory);
    }

    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage stage) {
        this.stage = stage; BrandAssets.apply(stage); stage.setTitle(DesktopBuildInfo.title("")); stage.setMinWidth(900); stage.setMinHeight(620);
        System.out.println("Park Lanka desktop | "+DesktopBuildInfo.label()+" | PID "+ProcessHandle.current().pid()+" | source "+DesktopBuildInfo.source()+" | working directory "+System.getProperty("user.dir"));
        show(loginScene(), 940, 790); stage.show();
    }
    @Override public void stop() { if (pendingLogin != null) { pendingLogin.cancel(); pendingLogin = null; } if (api != null) api.logOut(); if (notifications!=null)notifications.close(); if(conversations!=null)conversations.close(); if (messages != null) messages.close(); io.shutdownNow(); }
    private void show(Scene scene, double width, double height) {
        stage.setScene(scene); Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(width, bounds.getWidth() - 40)); stage.setHeight(Math.min(height, bounds.getHeight() - 40)); stage.centerOnScreen();
    }

    private Scene loginScene() {
        TextField username = new TextField(); PasswordField password = new PasswordField();
        username.setPromptText("Your username"); password.setPromptText("Your password");
        Label validation = Ui.notice(); Button connect = Ui.button("Connect to Workspace", "primary"); connect.setDefaultButton(true); connect.setMaxWidth(Double.MAX_VALUE);
        if (!loginMessage.isBlank()) Ui.notice(validation, loginMessage, false);
        BooleanProperty loading = new SimpleBooleanProperty();
        ProgressIndicator progress = new ProgressIndicator(); progress.setMaxSize(18, 18); progress.visibleProperty().bind(loading); progress.managedProperty().bind(loading);
        connect.disableProperty().bind(loading); username.disableProperty().bind(loading); password.disableProperty().bind(loading);
        Button connectionSettings = Ui.button("Connection settings", "secondary");
        connectionSettings.disableProperty().bind(loading);
        connectionSettings.setOnAction(event -> showConnectionSettings(validation));
        Button cancel = Ui.button("Cancel", "secondary"), retry = Ui.button("Retry", "secondary");
        cancel.visibleProperty().bind(loading); cancel.managedProperty().bind(loading);
        retry.setVisible(false); retry.managedProperty().bind(retry.visibleProperty()); retry.disableProperty().bind(loading);
        cancel.setOnAction(event -> {
            LoginStartup attempt = pendingLogin; pendingLogin = null;
            if (attempt != null) attempt.cancel();
            loading.set(false); connect.setText("Connect to Workspace"); retry.setVisible(true);
            Ui.notice(validation, "Connection cancelled. You can retry or change Connection settings.", false);
        });
        Runnable submit = () -> {
            if (loading.get() || pendingLogin != null) return;
            if (username.getText().isBlank() || password.getText().isEmpty()) { Ui.notice(validation, "Enter your username and password.", true); return; }
            try {
                ApiClient next = new ApiClient(selectedServerUrl == null ? serverUrl.get() : selectedServerUrl, username.getText().trim(), password.getText());
                LoginStartup attempt = loginFactory.apply(next); pendingLogin = attempt;
                Scene loginScreen = stage.getScene();
                loading.set(true); connect.setText("Connecting..."); retry.setVisible(false);
                Ui.notice(validation, "Connecting to server… The first connection may take a few minutes.", false);
                io.submit(() -> {
                    JsonNode result = null; String failure = null;
                    try { result = attempt.connect(); }
                    catch (LoginStartup.Failure ex) { failure = ex.getMessage(); }
                    catch (CancellationException | InterruptedException ex) { failure = "Connection cancelled. Please retry."; }
                    catch (Exception ex) {
                        // No raw server bodies/exception messages: they may contain private diagnostics.
                        System.out.println("LOGIN endpoint=" + next.baseUrl() + " phase=failed failure=" + ex.getClass().getSimpleName());
                        failure = "The server returned an unexpected response. Check Connection settings and retry.";
                    }
                    JsonNode authenticated = result; String message = failure;
                    if (message != null) next.logOut();
                    Platform.runLater(() -> {
                        if (pendingLogin != attempt || !stage.isShowing() || stage.getScene() != loginScreen) { next.logOut(); return; }
                        pendingLogin = null; loading.set(false); connect.setText("Connect to Workspace");
                        if (message != null) { retry.setVisible(true); Ui.notice(validation, message, true); return; }
                        api = next; password.clear(); loginMessage = "";
                        boolean admin = authenticated.path("role").asText().equals("ADMIN");
                        if (!admin) branchName = authenticated.path("branchName").asText("Main Branch");
                        stage.setTitle(DesktopBuildInfo.title(admin ? "Admin" : "Cashier"));
                        show(admin ? adminScene() : mainScene(), 1366, 850); if (!admin) { refresh(); messages.start(); notifications.start(); }
                    });
                });
            } catch (Exception ex) { Ui.notice(validation, ex.getMessage(), true); }
        };
        connect.setOnAction(event -> submit.run()); retry.setOnAction(event -> submit.run());
        javafx.scene.image.ImageView brand = BrandAssets.logo();
        VBox fields = new VBox(16, Ui.field("Username", username), Ui.field("Password", password));
        VBox box = Ui.style(Ui.card(brand, Ui.text("Welcome back", "login-title"),
                Ui.text("Sign in to manage your parking workspace.", "muted"), fields, validation, connect, Ui.row(cancel, retry),
                Ui.row(progress, Ui.text("Role-based admin and cashier access", "muted")),
                Ui.text("HTTP is for trusted local networks. Use HTTPS for cloud access.", "login-footer"), connectionSettings), "login-card");
        box.setMaxWidth(470); box.setMaxHeight(Region.USE_PREF_SIZE);
        VBox centered = new VBox(20, box, Ui.text("PARK LANKA  /  SMART PARKING. SIMPLE CONTROL.", "login-footer"),Ui.text(DesktopBuildInfo.label(),"login-footer")); centered.setAlignment(Pos.CENTER);
        centered.setMinHeight(Region.USE_PREF_SIZE);
        ScrollPane scroll = Ui.scroll(centered); scroll.setFitToHeight(true);
        StackPane root = Ui.style(new StackPane(scroll), "login-root");
        return Ui.scene(root, 940, 790);
    }

    private void showConnectionSettings(Label loginValidation) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Park Lanka | Connection settings");
        dialog.setHeaderText("Connect to your parking backend");
        Ui.dialog(dialog, stage);
        TextField address = new TextField();
        address.setPromptText("https://your-backend-host");
        Label validation = Ui.notice();
        try { address.setText(selectedServerUrl == null ? serverUrl.get() : selectedServerUrl); }
        catch (Exception ex) { Ui.notice(validation, ex.getMessage(), true); }
        Label storage = Ui.text("Saved for this Windows user in " + DesktopConfiguration.userSettingsFile(System.getenv())
                + ". Environment and development file overrides still apply on restart.", "muted");
        dialog.getDialogPane().setContent(new VBox(12, Ui.field("Server URL", address), storage, validation));
        dialog.getDialogPane().setPrefWidth(520);
        ButtonType save = new ButtonType("Save connection", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                DesktopConfiguration.saveServerUrl(address.getText());
                selectedServerUrl = address.getText().trim().replaceAll("/+$", "");
                Ui.notice(loginValidation, "Connection saved. Sign in to connect.", false);
            } catch (Exception ex) { Ui.notice(validation, ex.getMessage(), true); event.consume(); }
        });
        dialog.show();
    }

    private Scene adminScene() { return new AdminDashboard(api, io, this::error, this::disconnect).scene(); }
    private void disconnect() {
        if (api != null) api.logOut();
        if(notifications!=null){notifications.close();notifications=null;} if(conversations!=null){conversations.close();conversations=null;}
        if (messages != null) { messages.close(); messages = null; }
        Ui.clearTextInputs(stage.getScene().getRoot());
        api = null; refreshRequest++; active.clear(); availableSlots.clear(); slot.setValue(null);
        slotsByCode.clear();completedSessions.clear();checkoutRefreshPending=false;showCheckoutCompletion=false;completedTicket="";
        branchName = "Main Branch"; visibleVehicles.setPredicate(row -> true);
        for (Ui.Stat stat : List.of(freeCount, occupiedCount, paymentCount, revenue)) stat.set("—");
        lastSync.setText(""); Ui.setBadge(connection, "Logged out");
        loginMessage = "You have been logged out.";
        refreshing.set(false); entryBusy.set(false); quoteBusy.set(false); paymentBusy.set(false); notice.setText("");
        stage.setTitle(DesktopBuildInfo.title("")); show(loginScene(), 940, 790);
    }

    private Scene mainScene() {
        Ui.setBadge(connection, "Connected"); notice.setText("");
        visibleVehicles.setPredicate(row -> true);
        for (Ui.Stat stat : List.of(freeCount, occupiedCount, paymentCount, revenue)) stat.set("—");
        lastSync.setText("Waiting for parking data...");
        TextField plate = new TextField(); plate.setPromptText("e.g. ABC-1234");
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("CAR", "VAN", "MOTORCYCLE", "THREE_WHEELER", "OTHER")); type.setValue("CAR");
        type.setCellFactory(c -> vehicleTypeCell()); type.setButtonCell(vehicleTypeCell());
        slot.setPromptText("Select a free slot"); slot.setCellFactory(c -> slotCell()); slot.setButtonCell(slotCell());
        Button enter = Ui.button("Register Entry", "primary");
        enter.disableProperty().bind(entryBusy.or(refreshing).or(paymentBusy).or(quoteBusy).or(slot.valueProperty().isNull()));
        enter.setTooltip(new Tooltip("Register a vehicle. Press Enter in the plate field.")); plate.setOnAction(e -> enter.fire());
        enter.setOnAction(e -> {
            JsonNode selected = slot.getValue(); if (selected == null) { error("Choose a free parking slot."); return; }
            String normalizedPlate = plate.getText().trim().toUpperCase(Locale.ROOT);
            if (!normalizedPlate.matches("[A-Z0-9 -]{3,24}")) { error("Enter a plate of 3–24 letters, numbers, spaces or hyphens."); plate.requestFocus(); return; }
            Map<String, Object> request = Map.of("plate", normalizedPlate, "vehicleType", type.getValue(), "slotId", selected.path("id").asLong());
            entryBusy.set(true);
            perform(client -> client.post("/api/sessions", request), result -> {
                plate.clear(); info("Entry saved. Ticket #" + result.path("id").asText() + "."); refresh(); plate.requestFocus();
                perform(client->client.get("/api/sessions/"+result.path("id").asLong()+"/ticket"),ticket->ThermalDocuments.ticket(ticket,stage,false),
                    message->error("Entry is saved. Ticket preview unavailable: "+message),null,()->true);
            }, this::requestError, () -> entryBusy.set(false), () -> true);
        });
        availability.textProperty().bind(Bindings.when(Bindings.isEmpty(availableSlots))
                .then("No free slots are available. Refresh after a vehicle checks out.")
                .otherwise("Choose an available slot. Pricing is saved at entry."));
        VBox entry = Ui.section("Vehicle entry", "Register a vehicle and assign an available parking slot.",
                Ui.flow(Ui.field("Number plate", plate), Ui.field("Vehicle type", type), Ui.field("Parking slot", slot), enter), availability);
        table.getColumns().setAll(Ui.column("Ticket", "id", 70), Ui.column("Number plate", "plate", 140), Ui.column("Vehicle type", "vehicleType", 110),
                Ui.column("Slot", "slot", 90), Ui.column("Entry time", "enteredAt", 185));
        Ui.table(table, "No parked vehicles match your search.", 285);
        Ui.fillColumns(table);
        TextField search = new TextField(); search.setPromptText("Search ticket or plate..."); search.setPrefWidth(275);
        search.textProperty().addListener((o, old, value) -> {
            Long selected = selectedTicket(); String query = value.trim().toLowerCase(Locale.ROOT);
            visibleVehicles.setPredicate(row -> row.path("id").asText().contains(query) || row.path("plate").asText().toLowerCase(Locale.ROOT).contains(query)); restoreSelection(selected);
        });
        Button checkout = Ui.button("Quote / Checkout", "primary"), reload = Ui.button("Refresh", "secondary");
        Label checkoutNotice=Ui.notice();
        checkout.disableProperty().bind(quoteBusy.or(paymentBusy).or(entryBusy).or(refreshing));
        reload.disableProperty().bind(refreshing.or(entryBusy).or(quoteBusy).or(paymentBusy)); reload.setOnAction(e -> refresh());
        checkout.setOnAction(e -> {
            checkoutNotice.setText("");
            notice.setText("");showCheckoutCompletion=false;
            JsonNode selected = table.getSelectionModel().getSelectedItem(); if (selected == null) {Ui.notice(checkoutNotice,"Select a parked vehicle to checkout",false);return;}
            if(!selected.path("id").canConvertToLong()||selected.path("id").asLong()<=0){Ui.notice(checkoutNotice,"The selected vehicle has no valid ticket ID. Refresh Parking and select it again.",true);return;}
            System.out.println("CHECKOUT click ticket="+selected.path("id").asLong()+" FX="+Platform.isFxApplicationThread()+" ownerShowing="+stage.isShowing());
            checkouts.start(selected.path("id").asLong());
        });
        table.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER && !checkout.isDisabled()) { checkout.fire(); e.consume(); } });
        Button ticketPrint=Ui.button("Reprint entry ticket","secondary");ticketPrint.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        ticketPrint.setOnAction(e->{long id=selectedTicket();perform(client->client.post("/api/sessions/"+id+"/ticket/reprints",Map.of()),ticket->ThermalDocuments.ticket(ticket,stage,true),this::requestError,null,()->true);});
        HBox tableHeader = Ui.row(Ui.text("Parked vehicles", "section-title"), Ui.spacer(), search);
        VBox vehicles = Ui.card(tableHeader, Ui.flow(checkout, reload,ticketPrint), checkoutNotice,table, lastSync);
        ProgressIndicator checkoutLoading=new ProgressIndicator();checkoutLoading.setMaxSize(18,18);
        HBox checkoutProgress=Ui.row(checkoutLoading,Ui.text("Processing checkout request...","muted"));checkoutProgress.visibleProperty().bind(paymentBusy);checkoutProgress.managedProperty().bind(paymentBusy);vehicles.getChildren().add(1,checkoutProgress);
        ApiClient session = api;
        Button pendingCheckouts=Ui.button("Pending checkouts","secondary");pendingCheckouts.disableProperty().bind(paymentBusy);pendingCheckouts.setOnAction(e->{checkoutNotice.setText("");notice.setText("");showCheckoutCompletion=false;checkouts.pending();});vehicles.getChildren().add(pendingCheckouts);
        ToggleButton systemNav=Ui.style(new ToggleButton("System Messages"),"nav-item");
        Tab announcementsTab=new Tab("Announcements"),conversationsTab=new Tab("Conversations");announcementsTab.setClosable(false);conversationsTab.setClosable(false);TabPane messagingTabs=new TabPane(announcementsTab,conversationsTab);
        notifications=new NotificationBell(session,io,false,()->api==session,(kind,id,post)->{systemNav.fire();if(kind.equals("CONVERSATION")){messagingTabs.getSelectionModel().select(conversationsTab);conversations.open(id,post);}else{messagingTabs.getSelectionModel().select(announcementsTab);messages.open(id);}});
        messages = new CashierMessagesPane(session, io, () -> api == session,()->systemNav.isSelected()&&messagingTabs.getSelectionModel().getSelectedItem()==announcementsTab,notifications::refresh,notifications.poller);
        conversations=new ConversationsPane(session,io,false,()->api==session&&systemNav.isSelected()&&messagingTabs.getSelectionModel().getSelectedItem()==conversationsTab,notifications::refresh);
        notifications.poller.add(()->Platform.runLater(()->{if(api==session)conversations.poll();}));
        announcementsTab.setContent(Ui.scroll(messages.view()));conversationsTab.setContent(Ui.scroll(conversations.view()));
        VBox page = Ui.page(notifications.banner(), Ui.stats(freeCount, occupiedCount, paymentCount, revenue), notice, operations(entry, vehicles));
        VBox heading = new VBox(5, Ui.text("PARKING OPERATIONS", "eyebrow"), Ui.text(branchName, "page-title"),
                Ui.text("Vehicle entry, live quotes and checkout in one workspace.", "page-description")); HBox.setHgrow(heading, Priority.ALWAYS);
        heading.setMinWidth(0);
        ProgressIndicator sync = new ProgressIndicator(); sync.setMaxSize(18, 18); sync.visibleProperty().bind(refreshing); sync.managedProperty().bind(sync.visibleProperty());
        HBox header = Ui.style(Ui.row(heading, sync, connection,notifications.control()), "page-header");
        ToggleButton operation = Ui.style(new ToggleButton("Parking", Ui.icon("Parking")), "nav-item"); operation.setSelected(true); operation.setMaxWidth(Double.MAX_VALUE); operation.setOnAction(e -> operation.setSelected(true));
        Node parkingPage = Ui.scroll(page); StackPane workspace = new StackPane(parkingPage);
        ToggleGroup navigation = new ToggleGroup(); operation.setToggleGroup(navigation);systemNav.setToggleGroup(navigation);systemNav.setMaxWidth(Double.MAX_VALUE);
        ToggleButton receiptsNav=Ui.style(new ToggleButton("Receipt History"),"nav-item");receiptsNav.setToggleGroup(navigation);receiptsNav.setMaxWidth(Double.MAX_VALUE);
        ReceiptHistoryPane history=new ReceiptHistoryPane(session,io,false,()->api==session&&receiptsNav.isSelected());
        ToggleButton parkingHistoryNav=Ui.style(new ToggleButton("Parking History"),"nav-item");parkingHistoryNav.setToggleGroup(navigation);parkingHistoryNav.setMaxWidth(Double.MAX_VALUE);
        ParkingHistoryPane parkingHistory=new ParkingHistoryPane(session,io,()->api==session&&parkingHistoryNav.isSelected());
        parkingHistoryNav.setOnAction(e->{parkingHistoryNav.setSelected(true);conversations.deactivate();workspace.getChildren().setAll(Ui.scroll(parkingHistory.view()));parkingHistory.activate();});
        navigation.selectedToggleProperty().addListener((o,old,next)->{if(next!=parkingHistoryNav)parkingHistory.deactivate();if(next!=receiptsNav)history.deactivate();});
        receiptsNav.setOnAction(e->{receiptsNav.setSelected(true);conversations.deactivate();workspace.getChildren().setAll(Ui.scroll(history.view()));history.activate();});
        ToggleButton shiftsNav=Ui.style(new ToggleButton("Cashier Shifts"),"nav-item");shiftsNav.setToggleGroup(navigation);shiftsNav.setMaxWidth(Double.MAX_VALUE);
        Label shiftStatus=Ui.text("Checking active shift...");
        ShiftsPane shiftPane=new ShiftsPane(session,io,false,()->api==session,shiftStatus::setText);
        Button openShift=Ui.button("Open Shift","primary");
        openShift.visibleProperty().bind(Bindings.createBooleanBinding(()->Boolean.FALSE.equals(shiftPane.activeShiftProperty().get()),shiftPane.activeShiftProperty()));
        openShift.managedProperty().bind(openShift.visibleProperty());openShift.setOnAction(e->shiftsNav.fire());
        HBox shiftBanner=Ui.style(Ui.row(shiftStatus,openShift),"notice");
        shiftStatus.setMinWidth(0);HBox.setHgrow(shiftStatus,Priority.ALWAYS);openShift.setMinWidth(Region.USE_PREF_SIZE);
        // Shift guidance belongs next to the clicked button, including when Parking is scrolled.
        vehicles.getChildren().add(1,shiftBanner);
        reload.setOnAction(e->{refresh();shiftPane.refreshCurrent();});
        checkouts=new CheckoutCoordinator(session,io,stage,paymentBusy,()->api==session,message->{Ui.setBadge(connection,"Request failed");Ui.notice(checkoutNotice,message,true);},ticket->{checkoutCompleted(ticket);shiftPane.refreshCurrent();parkingHistory.refresh();if(receiptsNav.isSelected())history.activate();},
            message->Ui.notice(checkoutNotice,message,message.contains("failed")||message.contains("unavailable")||message.contains("could not")),state->{shiftPane.updateCurrent(state);Ui.setBadge(connection,"Connected");});
        shiftsNav.setOnAction(e->{shiftsNav.setSelected(true);conversations.deactivate();workspace.getChildren().setAll(Ui.scroll(shiftPane.view()));shiftPane.activate();});
        operation.setOnAction(e -> { operation.setSelected(true); conversations.deactivate();workspace.getChildren().setAll(parkingPage);shiftPane.refreshCurrent(); });
        shiftPane.onOpened(operation::fire);shiftPane.refreshCurrent();
        Runnable activateMessages=()->{if(messagingTabs.getSelectionModel().getSelectedItem()==announcementsTab)messages.activate();else conversations.activate();};
        messagingTabs.getSelectionModel().selectedItemProperty().addListener((o,old,selected)->{if(old==conversationsTab)conversations.deactivate();if(systemNav.isSelected())activateMessages.run();});
        systemNav.setOnAction(e->{systemNav.setSelected(true);workspace.getChildren().setAll(messagingTabs);activateMessages.run();});
        VBox nav = Ui.style(new VBox(operation,shiftsNav,parkingHistoryNav,receiptsNav,systemNav), "nav-list");
        VBox body = new VBox(header, workspace); body.setMinWidth(0); VBox.setVgrow(workspace, Priority.ALWAYS);
        BorderPane root = new BorderPane(body); root.setLeft(Ui.sidebar(api.username(), "Cashier · " + branchName, nav, this::disconnect));
        Scene scene = Ui.scene(root, 1366, 850);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN), () -> { if (!reload.isDisabled()) reload.fire(); });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), search::requestFocus);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), plate::requestFocus);
        return scene;
    }

    private ListCell<String> vehicleTypeCell() { return new ListCell<>() {
        @Override protected void updateItem(String item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : switch (item) {
            case "CAR" -> "Car"; case "VAN" -> "Van"; case "MOTORCYCLE" -> "Motorcycle"; case "THREE_WHEELER" -> "Three wheeler"; default -> "Other";
        }); }
    }; }
    private ListCell<JsonNode> slotCell() { return new ListCell<>() {
        @Override protected void updateItem(JsonNode item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? "Select a free slot" : item.path("code").asText()); }
    }; }
    private record Snapshot(JsonNode slots, JsonNode sessions, JsonNode report) {}
    private void checkoutCompleted(long ticket) {
        if(!Platform.isFxApplicationThread())throw new IllegalStateException("Checkout UI updates require the JavaFX Application Thread");
        // Invalidate snapshots taken before payment, before mutating the observable collections.
        refreshRequest++;
        JsonNode parked=active.stream().filter(row->row.path("id").asLong()==ticket).findFirst().orElse(null);
        boolean first=completedSessions.add(ticket);Long selected=selectedTicket();
        active.removeIf(row->row.path("id").asLong()==ticket);restoreSelection(Objects.equals(selected,ticket)?null:selected);
        if(first&&parked!=null){
            String code=parked.path("slot").asText();
            if(active.stream().noneMatch(row->row.path("slot").asText().equals(code))){
                List<JsonNode> rows=new ArrayList<>();
                for(JsonNode row:slotsByCode.values()){
                    if(row.path("code").asText().equals(code)){var freed=row.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)freed).put("occupied",false);rows.add(freed);}
                    else rows.add(row);
                }
                displaySlots(rows);
            }
        }
        checkoutRefreshPending=true;
        showCheckoutCompletion=true;completedTicket="Ticket #"+ticket;
        info("Checkout completed. "+completedTicket+". Updating parking data and totals. Your receipt is available in Receipt History.");
        refresh();
    }
    private void displaySlots(Iterable<JsonNode> rows){
        Long selectedSlot=slot.getValue()==null?null:slot.getValue().path("id").asLong();
        List<JsonNode> free=new ArrayList<>();int occupied=0;slotsByCode.clear();
        for(JsonNode row:rows){slotsByCode.put(row.path("code").asText(),row);if(row.path("occupied").asBoolean())occupied++;else free.add(row);}
        availableSlots.setAll(free);
        slot.setValue(selectedSlot==null?null:free.stream().filter(row->row.path("id").asLong()==selectedSlot).findFirst().orElse(null));
        if(slot.getValue()==null&&!free.isEmpty())slot.setValue(free.get(0));
        freeCount.set(free.size());occupiedCount.set(occupied);
    }
    private void refresh() {
        if (api == null) return; long request = ++refreshRequest; refreshing.set(true); Ui.setBadge(connection, "Syncing");
        perform(client -> new Snapshot(client.get("/api/slots"), client.get("/api/sessions"), client.get("/api/reports/today")), result -> {
            Long selected = selectedTicket();displaySlots(result.slots());
            List<JsonNode> sessions = new ArrayList<>();
            result.sessions().forEach(row->{if(!completedSessions.contains(row.path("id").asLong()))sessions.add(row);});active.setAll(sessions);restoreSelection(selected);
            paymentCount.set(result.report().path("payments").asText()); revenue.set(Ui.money(result.report().path("revenue")));
            Ui.setBadge(connection, "Connected"); lastSync.setText("Last synced " + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Colombo")).format(Instant.now()) + " · Sri Lanka time");
            if(checkoutRefreshPending){
                checkoutRefreshPending=false;
                if(showCheckoutCompletion)
                    info("Checkout completed. "+completedTicket+". Parking data and totals refreshed. Reprint your receipt from Receipt History.");
            }else if(notice.getStyleClass().contains("notice-error")&&!notice.getText().startsWith("Checkout completed;"))notice.setText("");
        }, message->{
            if(checkoutRefreshPending){Ui.setBadge(connection,"Refresh failed");error((showCheckoutCompletion?"Checkout completed; refresh failed. "+completedTicket+" payment is saved. ":"Parking refresh failed. ")+"Use Refresh to reload parking data and totals. "+message);}
            else requestError(message);
        }, () -> refreshing.set(false), () -> request == refreshRequest);
    }
    private Long selectedTicket() { JsonNode row = table.getSelectionModel().getSelectedItem(); return row == null ? null : row.path("id").asLong(); }
    private GridPane operations(VBox entry, VBox vehicles) {
        GridPane grid = new GridPane(); grid.setHgap(20); grid.setVgap(20);
        grid.add(entry, 0, 0); grid.add(vehicles, 0, 1); GridPane.setHgrow(vehicles, Priority.ALWAYS);
        grid.widthProperty().addListener((o, old, width) -> {
            ColumnConstraints form = new ColumnConstraints(), list = new ColumnConstraints();
            if (width.doubleValue() >= 960) {
                form.setMinWidth(300); form.setPrefWidth(310); form.setMaxWidth(310); list.setMinWidth(0); list.setHgrow(Priority.ALWAYS);
                grid.getColumnConstraints().setAll(form, list); GridPane.setColumnIndex(vehicles, 1); GridPane.setRowIndex(vehicles, 0);
            } else {
                form.setMinWidth(0); form.setHgrow(Priority.ALWAYS); grid.getColumnConstraints().setAll(form);
                GridPane.setColumnIndex(vehicles, 0); GridPane.setRowIndex(vehicles, 1);
            }
        });
        return grid;
    }
    private void restoreSelection(Long ticket) {
        table.getSelectionModel().clearSelection();
        if (ticket != null) visibleVehicles.stream().filter(row -> row.path("id").asLong() == ticket).findFirst().ifPresent(row -> table.getSelectionModel().select(row));
    }

    @FunctionalInterface private interface ApiTask<T> { T call(ApiClient client) throws Exception; }
    private <T> void perform(ApiTask<T> task, Consumer<T> success, Consumer<String> failureHandler, Runnable done, BooleanSupplier current) {
        ApiClient client = api; Scene screen = stage.getScene();
        CompletableFuture.supplyAsync(() -> { try { return task.call(client); } catch (Exception e) { throw new CompletionException(e); } }, io)
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    if (!stage.isShowing() || stage.getScene() != screen || api != client || !current.getAsBoolean()) return;
                    try {
                        if (failure != null) failureHandler.accept(failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage());
                        else { if (client != null) Ui.setBadge(connection, "Connected"); success.accept(result); }
                    } finally { if (done != null) done.run(); }
                }));
    }
    private void info(String message) { Ui.notice(notice, message, false); }
    private void error(String message) { Ui.notice(notice, message, true); }
    private void requestError(String message) { Ui.setBadge(connection, "Request failed"); error(message); }
}
