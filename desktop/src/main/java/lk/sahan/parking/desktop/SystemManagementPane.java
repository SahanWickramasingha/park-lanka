package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Admin broadcasts use a stable send key for retries; the backend resolves and snapshots recipients. */
final class SystemManagementPane {
    private final ApiClient api; private final ExecutorService io; private final BooleanSupplier active;
    private final Consumer<String> info, error;
    private final IntegerProperty pending = new SimpleIntegerProperty(), page = new SimpleIntegerProperty(), pages = new SimpleIntegerProperty(1);
    private final TextField title = new TextField(), expiryTime = new TextField("23:59");
    private final TextArea body = new TextArea(); private final DatePicker expiryDate = new DatePicker();
    private final ComboBox<String> priority = new ComboBox<>(FXCollections.observableArrayList("Normal", "Important", "Urgent"));
    private final RadioButton all = new RadioButton("All eligible cashiers"), selected = new RadioButton("Selected branches");
    private final ListView<JsonNode> branchList;
    private final TableView<JsonNode> history = new TableView<>();
    private final Label historyPage = Ui.text("", "muted"); private final VBox root;
    private Map<String, Object> lastPayload; private String sendKey; private long historyVersion; private boolean closed;
    private final ConversationsPane conversations; private final TabPane tabs; private final Tab broadcastsTab,conversationsTab;
    private Runnable messagesChanged=()->{};
    SystemManagementPane(ApiClient api, ExecutorService io, ObservableList<JsonNode> branches, BooleanSupplier active, Consumer<String> info, Consumer<String> error) {
        this.api = api; this.io = io; this.active = active; this.info = info; this.error = error;
        title.setPromptText("Broadcast title · 1–120 characters"); body.setPromptText("Message · 1–4000 characters"); body.setWrapText(true); body.setPrefRowCount(5);
        priority.setValue("Normal"); ToggleGroup audience = new ToggleGroup(); all.setToggleGroup(audience); selected.setToggleGroup(audience); all.setSelected(true);
        branchList = new ListView<>(branches); branchList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE); branchList.setPrefHeight(140);
        branchList.setCellFactory(c -> new ListCell<>() { @Override protected void updateItem(JsonNode branch, boolean empty) { super.updateItem(branch, empty); setText(empty || branch == null ? null : branch.path("code").asText() + " – " + branch.path("name").asText()); } });
        branchList.visibleProperty().bind(selected.selectedProperty()); branchList.managedProperty().bind(branchList.visibleProperty());
        expiryDate.setEditable(false); expiryDate.setPromptText("No expiry"); expiryTime.setPromptText("HH:mm");
        Button clearExpiry = Ui.button("No Expiry", "quiet"); clearExpiry.setOnAction(e -> expiryDate.setValue(null));
        Button preview = Ui.button("Preview & Send", "primary"); preview.disableProperty().bind(pending.greaterThan(0)); preview.setOnAction(e -> preview());
        history.getColumns().setAll(Ui.column("Title", "title", 210), Ui.column("Audience", "audience", 260), Ui.column("Priority", "priority", 110),
                Ui.column("Sent", "sentAt", 185), Ui.column("Expiry", "expiresAt", 185), Ui.column("Status", "status", 110),
                Ui.column("Recipients", "recipientCount", 110), Ui.column("Read", "readCount", 80));
        Ui.table(history, "No broadcasts have been sent.", 280);
        Button refresh = Ui.button("Refresh", "secondary"); refresh.disableProperty().bind(pending.greaterThan(0)); refresh.setOnAction(e -> activate());
        Button details = Ui.button("View Details", "secondary"), revoke = Ui.button("Revoke Message", "danger");
        details.disableProperty().bind(pending.greaterThan(0).or(history.getSelectionModel().selectedItemProperty().isNull()));
        revoke.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> pending.get() > 0 || history.getSelectionModel().getSelectedItem() == null || !history.getSelectionModel().getSelectedItem().path("status").asText().equals("Active"), pending, history.getSelectionModel().selectedItemProperty()));
        details.setOnAction(e -> details()); revoke.setOnAction(e -> revoke());
        Button previous = Ui.button("Previous", "secondary"), next = Ui.button("Next", "secondary");
        previous.disableProperty().bind(pending.greaterThan(0).or(page.lessThanOrEqualTo(0))); next.disableProperty().bind(pending.greaterThan(0).or(page.greaterThanOrEqualTo(pages.subtract(1))));
        previous.setOnAction(e -> { page.set(page.get() - 1); activate(); }); next.setOnAction(e -> { page.set(page.get() + 1); activate(); });
        root = Ui.page(Ui.section("Broadcast messages", "In-app messages only. Enabled cashiers can receive them when online or at their next login.",
                Ui.field("Title", title), Ui.field("Message", body), Ui.flow(Ui.field("Priority", priority), all, selected), branchList,
                Ui.flow(Ui.field("Expiry date · Asia/Colombo", expiryDate), Ui.field("Expiry time · HH:mm", expiryTime), clearExpiry),
                Ui.text("No date means no expiry. Hold Ctrl to select multiple branches. Stopped branches remain eligible while their cashiers have access.", "muted"), preview),
                Ui.card(Ui.row(Ui.text("Broadcast history", "section-title"), Ui.spacer(), refresh), history, Ui.flow(details, revoke, previous, next, historyPage)));
        conversations=new ConversationsPane(api,io,true,this::inboxActive,()->messagesChanged.run());
        broadcastsTab=new Tab("Broadcast Messages",root); conversationsTab=new Tab("Cashier Messages",conversations.view()); broadcastsTab.setClosable(false); conversationsTab.setClosable(false); tabs=new TabPane(broadcastsTab,conversationsTab);
        tabs.getSelectionModel().selectedItemProperty().addListener((o,old,current)->{if(old==conversationsTab)conversations.deactivate();if(current==conversationsTab)conversations.activate();});
    }
    Node view() { return tabs; }
    void onMessagesChanged(Runnable callback){messagesChanged=callback;}
    private boolean inboxActive(){return active.getAsBoolean()&&tabs.getSelectionModel().getSelectedItem()==conversationsTab;}
    void openConversation(long id) { tabs.getSelectionModel().select(conversationsTab); conversations.open(id); }
    void openConversation(long id,Long post) { tabs.getSelectionModel().select(conversationsTab); conversations.open(id,post); }
    void pollMessages(){conversations.poll();}
    void deactivate(){conversations.deactivate();}
    void openBroadcast(long id) { tabs.getSelectionModel().select(broadcastsTab); details(id); }
    void close() { closed = true; conversations.close(); historyVersion++; title.clear(); body.clear(); history.getItems().clear(); branchList.getSelectionModel().clearSelection(); lastPayload = null; sendKey = null; }
    void activate() {
        if(tabs.getSelectionModel().getSelectedItem()==conversationsTab){conversations.activate();return;}
        long version = ++historyVersion; int requested = page.get();
        call(() -> api.get("/api/admin/broadcasts?page=" + requested + "&size=50"), result -> {
            pages.set(Math.max(1, result.path("pages").asInt()));
            if (requested >= pages.get()) { page.set(pages.get() - 1); activate(); return; }
            long selectedId = history.getSelectionModel().getSelectedItem() == null ? -1 : history.getSelectionModel().getSelectedItem().path("id").asLong();
            history.getItems().clear(); result.path("rows").forEach(history.getItems()::add);
            history.getItems().stream().filter(r -> r.path("id").asLong() == selectedId).findFirst().ifPresent(history.getSelectionModel()::select);
            historyPage.setText("Page " + (requested + 1) + " of " + pages.get() + " · " + result.path("total").asText() + " broadcasts");
        }, () -> version == historyVersion);
    }
    private void preview() {
        String heading = title.getText().trim(), message = body.getText().trim();
        if (heading.isEmpty() || heading.length() > 120 || message.isEmpty() || message.length() > 4000) { error.accept("Use a title of 1–120 characters and a message of 1–4000 characters."); return; }
        List<Long> ids = all.isSelected() ? List.of() : branchList.getSelectionModel().getSelectedItems().stream().map(b -> b.path("id").asLong()).sorted().toList();
        if (!all.isSelected() && ids.isEmpty()) { error.accept("Select at least one branch."); return; }
        Instant expires = null;
        if (expiryDate.getValue() != null) {
            try { expires = expiryDate.getValue().atTime(LocalTime.parse(expiryTime.getText().trim())).atZone(ZoneId.of("Asia/Colombo")).toInstant(); }
            catch (Exception ex) { error.accept("Enter an expiry time as HH:mm."); return; }
            if (!expires.isAfter(Instant.now())) { error.accept("Expiry must be in the future."); return; }
        }
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("title", heading); payload.put("body", message); payload.put("priority", priority.getValue().toUpperCase(Locale.ROOT)); payload.put("allBranches", all.isSelected()); payload.put("branchIds", ids); payload.put("expiresAt", expires == null ? null : expires.toString());
        if (!payload.equals(lastPayload)) { lastPayload = new LinkedHashMap<>(payload); sendKey = UUID.randomUUID().toString(); }
        payload.put("requestKey", sendKey);
        String recipients = all.isSelected() ? "All eligible cashiers" : String.join(", ", branchList.getSelectionModel().getSelectedItems().stream().map(b -> b.path("code").asText() + " – " + b.path("name").asText()).toList());
        Dialog<ButtonType> dialog = new Dialog<>(); Ui.dialog(dialog, root.getScene().getWindow()); dialog.setTitle("Preview Broadcast"); dialog.setHeaderText(heading);
        ButtonType send = new ButtonType("Send Broadcast", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(send, ButtonType.CANCEL);
        TextArea preview = new TextArea(message); preview.setWrapText(true); preview.setEditable(false); preview.setPrefRowCount(8);
        dialog.getDialogPane().setContent(Ui.card(Ui.badge(priority.getValue()), Ui.text("Audience: " + recipients), preview,
                Ui.text("Expires: " + (expires == null ? "Never" : Ui.date(expires.toString()) + " · Asia/Colombo"), "muted"),
                Ui.text("Recipients are fixed at send time. Disabled or deleted accounts and deleted branches are excluded.", "muted")));
        dialog.getDialogPane().setPrefWidth(620); Ui.style(dialog.getDialogPane().lookupButton(send), "primary");
        dialog.setResultConverter(button -> button);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != send) return;
        call(() -> api.post("/api/admin/broadcasts", payload), result -> {
            title.clear(); body.clear(); expiryDate.setValue(null); lastPayload = null; sendKey = null;
            info.accept("Broadcast saved for " + result.path("recipientCount").asText() + " recipients."); activate();
        }, () -> true);
    }
    private void details() {
        JsonNode selected = history.getSelectionModel().getSelectedItem(); if (selected == null) return;
        details(selected.path("id").asLong());
    }
    private void details(long id) {
        call(() -> api.get("/api/admin/broadcasts/" + id), result -> {
            JsonNode message = result.path("message"); Dialog<ButtonType> dialog = new Dialog<>(); Ui.dialog(dialog, root.getScene().getWindow()); dialog.setTitle("Broadcast Details"); dialog.setHeaderText(message.path("title").asText()); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            TextArea text = new TextArea(message.path("body").asText()); text.setEditable(false); text.setWrapText(true); text.setPrefRowCount(6);
            TableView<JsonNode> delivery = new TableView<>(); delivery.getColumns().setAll(Ui.column("Username", "username", 180), Ui.column("Branch", "branch", 240), Ui.column("Read at", "readAt", 185)); Ui.table(delivery, "No recipients", 210); result.path("recipients").forEach(delivery.getItems()::add);
            dialog.getDialogPane().setContent(Ui.card(Ui.text(message.path("audience").asText()), Ui.text("Sender: " + message.path("sender").asText() + " · " + message.path("status").asText(), "muted"), text, delivery)); dialog.getDialogPane().setPrefWidth(690); dialog.showAndWait();
        }, () -> true);
    }
    private void revoke() {
        JsonNode message = history.getSelectionModel().getSelectedItem(); if (message == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Remove this broadcast from active cashier notifications. Its history and read state remain saved.", ButtonType.OK, ButtonType.CANCEL);
        Ui.dialog(confirm, root.getScene().getWindow()); confirm.setHeaderText("Revoke · " + message.path("title").asText()); Ui.style(confirm.getDialogPane().lookupButton(ButtonType.OK), "danger-solid");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        call(() -> api.put("/api/admin/broadcasts/" + message.path("id").asLong() + "/revoke", Map.of()), result -> { info.accept("Broadcast revoked. History retained."); activate(); }, () -> true);
    }
    private void call(Callable<JsonNode> request, Consumer<JsonNode> success, BooleanSupplier current) {
        if (closed || !api.isActive() || !active.getAsBoolean()) return; pending.set(pending.get() + 1);
        CompletableFuture.supplyAsync(() -> { try { return request.call(); } catch (Exception ex) { throw new CompletionException(ex); } }, io).whenComplete((result, failure) -> Platform.runLater(() -> {
            try { if (closed || !api.isActive() || !active.getAsBoolean() || !current.getAsBoolean()) return; if (failure == null) success.accept(result); else error.accept(failure.getCause().getMessage()); }
            finally { pending.set(pending.get() - 1); }
        }));
    }
}
