package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.Window;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Shared native JavaFX components. Business rules remain in the existing API. */
final class Ui {
    private Ui() {}
    static final String STYLESHEET = Objects.requireNonNull(Ui.class.getResource("theme.css")).toExternalForm();

    static <T extends Node> T style(T node, String... classes) { node.getStyleClass().addAll(classes); return node; }
    static Label text(String value, String... classes) {
        Label label = style(new Label(value), classes); label.setWrapText(true); return label;
    }
    static Button button(String title, String kind) { return style(new Button(title), "action", kind); }
    static VBox card(Node... children) { return style(new VBox(16, children), "card"); }
    static VBox section(String title, String description, Node... children) {
        VBox box = card(text(title, "section-title"));
        if (description != null && !description.isBlank()) box.getChildren().add(text(description, "muted"));
        box.getChildren().addAll(children); return box;
    }
    static VBox field(String title, Control input) {
        Label label = text(title, "field-label"); label.setLabelFor(input);
        input.setMaxWidth(Double.MAX_VALUE);
        VBox field = new VBox(7, label, input); field.setMinWidth(155); field.setPrefWidth(205);
        field.setMaxWidth(Double.MAX_VALUE); return field;
    }
    static FlowPane flow(Node... nodes) {
        FlowPane flow = new FlowPane(12, 12, nodes); flow.setRowValignment(VPos.BOTTOM);
        flow.widthProperty().addListener((o, old, width) -> {
            for (Node node : nodes) if (node instanceof VBox field)
                field.setPrefWidth(width.doubleValue() < 400 ? Math.max(155, width.doubleValue()) : 205);
        });
        flow.setAlignment(Pos.BOTTOM_LEFT); return flow;
    }
    static HBox row(Node... nodes) { HBox row = new HBox(12, nodes); row.setAlignment(Pos.CENTER_LEFT); return row; }
    static Region spacer() { Region region = new Region(); HBox.setHgrow(region, Priority.ALWAYS); VBox.setVgrow(region, Priority.ALWAYS); return region; }
    static VBox page(Node... children) { return style(new VBox(20, children), "page"); }
    static ScrollPane scroll(Node content) {
        ScrollPane pane = style(new ScrollPane(content), "page-scroll");
        pane.setFitToWidth(true); pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); return pane;
    }
    static Scene scene(Parent root, double width, double height) {
        style(root, "app-root"); Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(STYLESHEET); return scene;
    }
    static void clearTextInputs(Node root) { clearTextInputs(root, Collections.newSetFromMap(new IdentityHashMap<>())); }
    private static void clearTextInputs(Node node, Set<Node> visited) {
        if (!visited.add(node)) return;
        if (node instanceof TextInputControl input) input.clear();
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) clearTextInputs(child, visited);
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) clearTextInputs(scroll.getContent(), visited);
    }
    static void dialog(Dialog<?> dialog, Window owner) {
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getStylesheets().add(STYLESHEET);
        style(dialog.getDialogPane(), "app-root", "modern-dialog");
        dialog.setResizable(true);
        dialog.setOnShowing(event -> {
            if (dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) BrandAssets.apply(stage);
        });
    }
    static Label badge(String value) {
        Label label = style(new Label(), "badge"); setBadge(label, value); return label;
    }
    static void setBadge(Label label, String value) {
        label.setText(value);
        label.getStyleClass().removeAll("badge-success", "badge-warning", "badge-danger", "badge-neutral");
        label.getStyleClass().add(switch (value) {
            case "Active", "Connected", "Paid", "Free" -> "badge-success";
            case "Stopped", "Syncing", "Connecting", "Occupied", "Important", "Refresh failed" -> "badge-warning";
            case "Deleted", "Removed", "Branch Deleted", "Request failed", "Disconnected", "Logged out", "Urgent", "Revoked", "Expired" -> "badge-danger";
            default -> "badge-neutral";
        });
    }
    static Label notice() {
        Label label = text("", "notice"); label.setMaxWidth(Double.MAX_VALUE);
        label.visibleProperty().bind(label.textProperty().isNotEmpty()); label.managedProperty().bind(label.visibleProperty()); return label;
    }
    static void notice(Label label, String message, boolean error) {
        label.setText(message == null ? "Request failed." : message);
        label.getStyleClass().removeAll("notice-error", "notice-success");
        label.getStyleClass().add(error ? "notice-error" : "notice-success");
        label.setAccessibleText(label.getText());
    }
    static final class Stat extends VBox {
        final Label value = text("—", "stat-value");
        Stat(String title, String note) {
            super(8); style(this, "stat-card"); setMinWidth(0); setMaxWidth(Double.MAX_VALUE);
            getChildren().addAll(text(title, "stat-label"), value, text(note, "stat-note"));
        }
        void set(Object number) { value.setText(String.valueOf(number)); }
    }
    static TilePane stats(Stat... stats) {
        TilePane pane = new TilePane(12, 12, stats); pane.setPrefColumns(stats.length); pane.setPrefTileWidth(185);
        pane.setAlignment(Pos.TOP_LEFT); pane.setTileAlignment(Pos.TOP_LEFT);
        pane.widthProperty().addListener((o, old, width) -> {
            int columns = Math.max(1, Math.min(stats.length, (int) (width.doubleValue() / 170)));
            // Leave room for pixel snapping, otherwise the final tile can wrap by one pixel.
            pane.setPrefColumns(columns); pane.setPrefTileWidth(Math.max(100, Math.floor((width.doubleValue() - (columns - 1) * 12 - 2) / columns)));
        });
        return pane;
    }
    static HBox brand() {
        return row(BrandAssets.sidebarIcon(), text("Park Lanka", "brand-title"));
    }
    static VBox sidebar(String username, String role, Node navigation, Runnable disconnect) {
        Label person = text(username, "sidebar-user"); person.setMaxWidth(170);
        Button logout = button("Log out", "sidebar-disconnect"); logout.setGraphic(icon("disconnect"));
        logout.setTooltip(new Tooltip("Log out and return to the login screen"));
        logout.setMaxWidth(Double.MAX_VALUE); logout.setOnAction(e -> disconnect.run());
        VBox account = style(new VBox(8, text("SIGNED IN", "sidebar-caption"), person,
                text(role, "sidebar-role"), logout), "sidebar-account");
        ScrollPane navScroll = scroll(navigation); navScroll.setMinHeight(90); VBox.setVgrow(navScroll, Priority.ALWAYS);
        VBox sidebar = style(new VBox(20, brand(), text("WORKSPACE", "sidebar-caption"), navScroll, account), "sidebar");
        sidebar.setMinWidth(210); sidebar.setPrefWidth(210); sidebar.setMaxWidth(210); return sidebar;
    }
    static Node icon(String name) {
        String path = switch (name) {
            case "Overview" -> "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z";
            case "Cashiers" -> "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M22 21v-2a4 4 0 0 0-3-3.87 M16 3.13a4 4 0 0 1 0 7.75";
            case "Saved Data" -> "M21 5c0 2-4 3-9 3S3 7 3 5s4-3 9-3 9 1 9 3 M3 5v14c0 2 4 3 9 3s9-1 9-3V5 M3 12c0 2 4 3 9 3s9-1 9-3";
            case "Rates" -> "M12 2v20 M17 5H9a4 4 0 0 0 0 8h6a4 4 0 0 1 0 8H6";
            case "Manage" -> "M4 7h16 M4 17h16 M8 3v8 M16 13v8";
            case "disconnect" -> "M9 3H4v18h5 M13 8l5 4-5 4 M8 12h13";
            case "Bell" -> "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4";
            default -> "M4 5h16v14H4z M8 9h8 M8 14h5";
        };
        SVGPath svg = style(new SVGPath(), "line-icon"); svg.setContent(path);
        StackPane box = style(new StackPane(svg), "icon-box"); box.setMinSize(22, 22); box.setPrefSize(22, 22); return box;
    }
    static <T> void table(TableView<T> table, String empty, double height) {
        style(table, "data-table"); table.setPlaceholder(text(empty, "empty-state"));
        table.setMinHeight(180); table.setPrefHeight(height); table.setMaxHeight(Double.MAX_VALUE);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
    }
    static <T> void fillColumns(TableView<T> table) {
        if (table.getProperties().putIfAbsent("fill-columns", true) != null) return;
        Map<TableColumn<T, ?>, Double> widths = new WeakHashMap<>();
        Runnable resize = () -> {
            double sum = 0;
            for (TableColumn<T, ?> column : table.getColumns()) sum += widths.computeIfAbsent(column, c -> c.getPrefWidth());
            if (sum == 0) return;
            double scale = Math.max(1, (table.getWidth() - 16) / sum);
            for (TableColumn<T, ?> column : table.getColumns()) column.setPrefWidth(Math.floor(widths.get(column) * scale));
        };
        table.widthProperty().addListener((o, old, width) -> resize.run());
        table.getColumns().addListener((ListChangeListener<TableColumn<T, ?>>) change -> resize.run());
        resize.run();
    }
    static TableColumn<JsonNode, String> column(String title, String field, double width) {
        TableColumn<JsonNode, String> column = new TableColumn<>(title);
        column.setPrefWidth(width); column.setMinWidth(Math.min(width, 75));
        column.setCellValueFactory(cell -> new SimpleStringProperty(display(cell.getValue().path(field), field)));
        if (field.equals("status") || field.equals("branchStatus")) column.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty); setText(null); setGraphic(empty || value == null ? null : badge(value));
            }
        });
        return column;
    }
    static String display(JsonNode value, String field) {
        if (value.isMissingNode() || value.isNull()) return "—";
        if (field.equals("bootstrap")) return value.asBoolean() ? "Environment settings" : "Admin managed";
        // Occupied is also a numeric branch count: only boolean values become Free/Occupied.
        if (value.isBoolean()) return field.equals("occupied") ? (value.asBoolean() ? "Occupied" : "Free") : (value.asBoolean() ? "Yes" : "No");
        if (field.endsWith("At")) return date(value.asText());
        if(field.equals("elapsedSeconds"))return duration(value.asLong());
        if (field.equals("vehicleType") || field.equals("type") || field.equals("method")) {
            String lower = value.asText().replace('_', ' ').toLowerCase(Locale.ROOT);
            return lower.isEmpty() ? "—" : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
        if (Set.of("todayRevenue","amount","entryRate","cashTendered","changeDue","refunded","voided","netAmount").contains(field)) return money(value);
        return value.asText();
    }
    static String date(String iso) {
        try { return DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Asia/Colombo")).format(Instant.parse(iso)); }
        catch (Exception ignored) { return iso; }
    }
    static String duration(long seconds){return seconds/3600+"h "+seconds%3600/60+"m "+seconds%60+"s";}
    static String money(JsonNode value) {
        try { return new BigDecimal(value.asText("0")).setScale(2).toPlainString(); }
        catch (NumberFormatException e) { return value.asText(); }
    }
}
