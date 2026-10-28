package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.input.*;
import javafx.stage.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Executable native UI regression check. Only talks to its temporary local HTTP stub, never a business database. */
public final class DesktopUiSmoke {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger logins = new AtomicInteger(), rateWrites = new AtomicInteger(), checkouts = new AtomicInteger();
    private static final AtomicInteger entries = new AtomicInteger();
    private static final AtomicInteger messagePolls = new AtomicInteger(), broadcastWrites = new AtomicInteger();
    private static final AtomicBoolean messageRead = new AtomicBoolean();
    private static final AtomicBoolean noticeRead=new AtomicBoolean(),conversationRead=new AtomicBoolean();
    private static final AtomicInteger notificationPolls=new AtomicInteger(),conversationWrites=new AtomicInteger(),replyWrites=new AtomicInteger();
    private static final AtomicInteger replyAttempts=new AtomicInteger();
    private static final AtomicReference<String> failedReplyKey=new AtomicReference<>();
    private static final AtomicInteger folderStatus=new AtomicInteger();
    private static final AtomicBoolean emptyFolder=new AtomicBoolean();
    private static final AtomicBoolean cashierLoggedIn=new AtomicBoolean();
    private static final AtomicReference<JsonNode> lastEntry = new AtomicReference<>();
    private static final AtomicBoolean failSlots = new AtomicBoolean();
    private static final AtomicBoolean shiftActive=new AtomicBoolean(),failShiftOpen=new AtomicBoolean(),failShiftCurrent=new AtomicBoolean();
    private static final AtomicInteger shiftOpens=new AtomicInteger(),shiftQuotes=new AtomicInteger();
    private static final AtomicReference<Throwable> uiError = new AtomicReference<>();
    private static final byte[] REPORT_BYTES = "report transport fixture".getBytes(StandardCharsets.UTF_8);
    private static final CountDownLatch reportStarted = new CountDownLatch(1), releaseReport = new CountDownLatch(1);
    private static Stage stage;
    private static DesktopApp app;
    private static Path output;
    private static String endpoint;
    private static String rate = "1.00";

    public static void main(String[] args) throws Exception {
        output = Path.of(args.length == 0 ? "target/ui-preview" : args[0]); Files.createDirectories(output);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService http = Executors.newCachedThreadPool(); server.setExecutor(http); server.createContext("/", DesktopUiSmoke::serve); server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            checkDesktopConfiguration();
            checkSavedConnection();
            checkSharedPoller();
            checkReportSaving();
            checkMessageErrors();
            CountDownLatch ready = new CountDownLatch(1); Platform.startup(ready::countDown); check(ready.await(5, TimeUnit.SECONDS), "JavaFX startup timed out");
            Platform.setImplicitExit(false);
            LoginStartupChecks.run();
            fx(()->{try{PrintLayoutChecks.run(output.resolve("print-documents"));return null;}catch(Exception ex){throw new CompletionException(ex);}});
            CheckoutFlowChecks.run();
            CheckoutParkingChecks.run();
            checkBellPresentation();
            fx(() -> { Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> uiError.set(error)); app = new DesktopApp(() -> endpoint); stage = new Stage(); app.start(stage); stage.setX(-10000); stage.setY(-10000); return null; });
            fx(()->{
                check(stage.getTitle().equals("Park Lanka | Login"),"Login title lost Park Lanka branding");
                check(!DesktopBuildInfo.label().contains("${"),"Build metadata was not filtered");
                checkBrandingIcons(stage);
                check(nodes(ImageView.class).stream().anyMatch(v -> v.getAccessibleText().contains("Smart Parking") && v.isPreserveRatio()), "Full login logo missing");
                if (Boolean.getBoolean("park.expectJarResources")) check(BrandAssets.class.getResource("branding/park-lanka-logo.png").getProtocol().equals("jar"), "Branding was not loaded from the packaged JAR");
                return null;
            });
            snapshot("login-1920", 1920, 1080); snapshot("login-1366", 1366, 768);
            snapshot("login-compact", 900, 620); snapshot("login-1024", 1024, 700);
            fx(()->{try{checkCashPaymentPresentation();return null;}catch(Exception ex){throw new CompletionException(ex);}});
            fx(() -> {
                check(nodes(TextInputControl.class).size() == 2 && !textContains("Server URL"), "Server URL remains in the login form");
                button("Connection settings").fire();
                DialogPane connection = dialogPane();
                TextField address = descendants(connection, TextField.class).get(0);
                check(address.getText().equals(endpoint), "Connection settings did not show the configured endpoint");
                address.setText("http://public.example.com");
                connection.lookupButton(connection.getButtonTypes().stream().filter(type -> type.getText().equals("Save connection")).findFirst().orElseThrow()).fireEvent(new javafx.event.ActionEvent());
                check(descendants(connection, Label.class).stream().anyMatch(label -> label.getText().contains("Use HTTPS")), "Unsafe connection was saved");
                connection.lookupButton(ButtonType.CANCEL).fireEvent(new javafx.event.ActionEvent());
                check(logins.get() == 0, "Connection settings attempted a login");
                button("Connect to Workspace").fire(); check(textContains("Enter your username and password"), "Credential validation missing");
                String saved = endpoint;
                try {
                    endpoint = "http://public.example.com";
                    input("Username").setText("admin.preview"); input("Password").setText("fixture-password");
                    button("Connect to Workspace").fire(); check(textContains("Use HTTPS"), "Configured URL validation missing");
                } finally { endpoint = saved; }
                return null;
            });
            login("admin.preview");
            waitFor(() -> nav("Overview") != null && !button("Refresh").isDisabled());
            fx(() -> {
                checkSidebarBranding("Admin");
                check(nodes(Ui.Stat.class).size() == 5, "Overview statistics missing");
                TableView<JsonNode> table = table();
                check(table.getItems().size() == 2, "Archived branch leaked into Overview");
                check(table.getColumns().get(4).getCellData(0).equals("3"), "Occupied count became Free");
                List<String> values = nodes(Ui.Stat.class).stream().map(s -> s.value.getText()).toList();
                check(values.equals(List.of("2", "60", "15", "8", "900.00")), "Statistics do not match API: " + values);
                check(textContains("admin.preview"), "Signed-in identity missing"); return null;
            });
            snapshot("overview-1366", 1366, 768); snapshot("overview-1920", 1920, 1080); snapshot("overview-1024", 1024, 700);
            waitFor(()->textContains("99+"));
            fx(()->{nodes(Button.class).stream().filter(b->b.getAccessibleText()!=null&&b.getAccessibleText().startsWith("Notifications")).findFirst().orElseThrow().fire();return null;});
            waitFor(()->dialogPaneOrNull()!=null);
            fx(()->{check(!noticeRead.get(),"Opening bell marked notices read");snapshotNode(dialogPane(),"admin-notification-bell");descendants(dialogPane(),TableView.class).get(0).getSelectionModel().select(0);descendants(dialogPane(),Button.class).stream().filter(b->b.getText().equals("View Details")).findFirst().orElseThrow().fire();return null;});
            waitFor(()->noticeRead.get()&&detailsPane("Parking question")!=null);fx(()->{check(!messageRead.get(),"Opening one notification marked another read");check(descendants(detailsPane("Parking question"),Node.class).stream().anyMatch(n->n.getStyleClass().contains("message-incoming")),"Incoming bubble missing");((Button)detailsPane("Parking question").lookupButton(ButtonType.CLOSE)).fire();notificationButton().fire();return null;});
            waitFor(()->!descendants(dialogPane(),Button.class).stream().filter(b->b.getText().equals("Mark All as Read")).findFirst().orElseThrow().isDisabled());
            fx(()->{descendants(dialogPane(),Button.class).stream().filter(b->b.getText().equals("Mark All as Read")).findFirst().orElseThrow().fire();return null;});
            waitFor(messageRead::get);fx(()->{((Button)dialogPane().lookupButton(ButtonType.CLOSE)).fire();return null;});
            Thread.sleep(150);fx(()->{nodes(Button.class).stream().filter(b->b.getAccessibleText()!=null&&b.getAccessibleText().startsWith("Notifications")).findFirst().orElseThrow().fire();return null;});
            fx(()->{DialogPane pane=dialogPane();descendants(pane,TableView.class).get(0).getSelectionModel().select(1);descendants(pane,Button.class).stream().filter(b->b.getText().equals("View Details")).findFirst().orElseThrow().fire();((Button)pane.lookupButton(ButtonType.CLOSE)).fire();return null;});
            Thread.sleep(650);fx(()->{check(dialogPaneOrNull()==null,"Closed notification panel opened a stale details dialog");return null;});
            for (String page : List.of("Cashiers", "Saved Data", "Rates", "Receipt History", "Shifts", "Audit History", "Backups", "Payment Corrections", "System Management", "Manage")) {
                fx(() -> { nav(page).fire(); return null; }); snapshot(page.toLowerCase(Locale.ROOT).replace(' ', '-') + "-1024", 1024, 700);
            }
            fx(() -> {
                List<TableView<JsonNode>> tables = tables(); check(tables.size() == 2, "Manage sections missing");
                tables.get(0).getSelectionModel().select(0); check(button("Delete Branch").isDisabled(), "Main deletion enabled");
                tables.get(1).getSelectionModel().select(0); check(button("Stop Cashier").isDisabled() && button("Start Cashier").isDisabled() && button("Remove Cashier").isDisabled(), "Bootstrap actions enabled");
                tables.get(1).getSelectionModel().select(1); check(!button("Start Cashier").isDisabled(), "Stopped cashier start disabled");
                Platform.runLater(() -> { try { DialogPane pane = dialogPane(); check(pane.getHeaderText().contains("Main Branch"), "Confirmation does not identify branch"); pane.lookupButton(ButtonType.CANCEL).fireEvent(new javafx.event.ActionEvent()); } catch (Throwable error) { uiError.set(error); } });
                button("Stop Branch").fire(); return null;
            });
            fx(() -> { nav("System Management").fire(); selectTab("Broadcast Messages");return null; });
            waitFor(() -> !button("Preview & Send").isDisabled());
            fx(() -> {
                input("Title").setText("UI broadcast");
                ((TextArea) nodes(Label.class).stream().filter(l -> l.getText().equals("Message") && l.getLabelFor() != null).findFirst().orElseThrow().getLabelFor()).setText("Please review this notice.");
                Platform.runLater(() -> descendants(dialogPane(), Button.class).stream().filter(b -> b.getText().equals("Send Broadcast")).findFirst().orElseThrow().fire());
                button("Preview & Send").fire(); return null;
            });
            waitFor(() -> broadcastWrites.get() == 1 && !button("Preview & Send").isDisabled());
            fx(() -> { nav("Rates").fire(); input("New rate · LKR per started minute").setText("2.50"); button("Save Rate").fire(); button("Save Rate").fire(); return null; });
            waitFor(() -> rateWrites.get() == 1 && !button("Save Rate").isDisabled());
            fx(() -> { check(textContains("LKR 2.50"), "Rate not refreshed"); nav("Saved Data").fire(); return null; });
            fx(() -> {
                ComboBox<String> data = dataset(); data.setValue("cashiers"); data.setValue("payments"); return null;
            });
            Thread.sleep(650);
            fx(() -> { check(table().getColumns().stream().anyMatch(c -> c.getText().equals("Paid")), "Stale dataset response applied"); return null; });
            fx(() -> { check(button("Export Excel") != null, "Export action missing"); dataset().setValue("cashiers"); button("Log out").fire(); return null; });
            Thread.sleep(500); fx(() -> {
                check(button("Connect to Workspace") != null && textContains("logged out"), "Logout lost login screen");
                check(nodes(TextInputControl.class).size() == 2 && !textContains("Server URL"), "Logout restored the URL field");
                check(input("Username").getText().isEmpty() && input("Password").getText().isEmpty(), "Logout retained credentials");
                return null;
            });
            login("cashier.preview");
            waitFor(() -> nodes(Ui.Stat.class).size() == 4 && !button("Refresh").isDisabled());
            checkCashierShiftFlow();
            fx(() -> {
                checkSidebarBranding("Cashier");
                button("Quote / Checkout").fire();check(textContains("Select a parked vehicle to checkout"),"No-selection guidance missing");
                TextField search = search(); search.setText("ABC"); check(table().getItems().size() == 1, "Plate search failed");
                table().getSelectionModel().select(0); search.setText("NO-MATCH");check(table().getSelectionModel().getSelectedItem()==null,"Filtered selection unsafe");button("Quote / Checkout").fire();check(textContains("Select a parked vehicle to checkout"),"Filtered selection did not show guidance");
                search.setText("101"); check(table().getItems().size() == 1, "Ticket search failed"); search.clear();
                check(stage.getScene().getAccelerators().size() == 3, "Cashier keyboard shortcuts missing"); return null;
            });
            snapshot("cashier-1366", 1366, 768); snapshot("cashier-1920", 1920, 1080); snapshot("cashier-1024", 1024, 700);
            fx(()->{nav("Cashier Shifts").fire();return null;});waitFor(()->textContains("Continue active shift #51"));snapshot("cashier-shifts-1024",1024,700);
            fx(()->{nav("Receipt History").fire();return null;});waitFor(()->table().getItems().size()==1);
            fx(()->{table().getSelectionModel().select(0);button("Details / Reprint").fire();return null;});waitFor(()->dialogPaneOrNull()!=null);
            fx(()->{check(descendants(dialogPane(),Label.class).stream().anyMatch(l->l.getText().contains("REPRINT")),"Receipt duplicate was not labeled REPRINT");check(checkouts.get()==0,"Reprinting created a payment");snapshotNode(dialogPane(),"receipt-history-reprint");((Button)dialogPane().lookupButton(ButtonType.CLOSE)).fire();nav("Parking").fire();return null;});
            waitFor(()->textContains("Continue active shift #51"));
            fx(() -> { table().getSelectionModel().select(0); button("Quote / Checkout").fire(); return null; });
            waitFor(() -> dialogPaneOrNull() != null);
            fx(() -> {
                DialogPane dialog = dialogPane(); ToggleButton card = descendants(dialog, ToggleButton.class).stream().filter(t -> t.getText().equals("External Card")).findFirst().orElseThrow(); card.fire();
                checkBrandingIcons((Stage) dialog.getScene().getWindow());
                Button pay = descendants(dialog, Button.class).stream().filter(b -> b.getText().equals("Confirm Payment & Print")).findFirst().orElseThrow();
                check(pay.isDisabled(), "External card accepted without reference and confirmation");
                TextField reference = (TextField) descendants(dialog, Label.class).stream().filter(l -> l.getText().equals("Card transaction reference")).findFirst().orElseThrow().getLabelFor(); reference.setText("TERMINAL-100");
                check(pay.isDisabled(), "External terminal confirmation missing"); descendants(dialog, CheckBox.class).get(0).fire(); check(!pay.isDisabled(), "Confirmed external payment disabled");
                snapshotNode(dialog, "payment-dialog"); pay.fire(); pay.fire(); return null;
            });
            waitFor(() -> dialogPaneOrNull() != null && dialogPane().getHeaderText().equals("Parking receipt"));
            fx(() -> {
                DialogPane pane = dialogPane(); check(descendants(pane, Button.class).stream().anyMatch(b -> b.getText().equals("Print")), "Print action missing");
                snapshotNode(pane, "receipt-dialog"); ((Button) pane.lookupButton(ButtonType.CLOSE)).fire(); check(checkouts.get() == 1, "Duplicate checkout"); return null;
            });
            waitFor(() -> !button("Refresh").isDisabled());
            failSlots.set(true); fx(() -> { button("Refresh").fire(); return null; });
            waitFor(() -> textContains("Request failed") && !button("Refresh").isDisabled());
            fx(() -> { button("Refresh").fire(); return null; }); waitFor(() -> textContains("Connected") && !button("Refresh").isDisabled());
            fx(() -> {
                stage.getScene().getAccelerators().get(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN)).run();
                check(stage.getScene().getFocusOwner() == search(), "Search shortcut did not move focus");
                stage.getScene().getAccelerators().get(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN)).run();
                check(stage.getScene().getFocusOwner() == input("Number plate"), "Entry shortcut did not move focus");
                input("Number plate").setText("abc-9999"); button("Register Entry").fire(); button("Register Entry").fire(); return null;
            });
            waitFor(() -> entries.get() == 1 && !button("Refresh").isDisabled());
            check(lastEntry.get().path("plate").asText().equals("ABC-9999") && lastEntry.get().path("vehicleType").asText().equals("CAR"), "Entry payload changed");
            waitFor(()->dialogPaneOrNull()!=null&&dialogPane().getHeaderText().contains("Entry saved"));
            fx(()->{DialogPane pane=dialogPane();check(!descendants(pane,javafx.scene.canvas.Canvas.class).isEmpty(),"Opaque ticket QR missing");snapshotNode(pane,"entry-ticket-80mm");ComboBox<String> paper=(ComboBox<String>)descendants(pane,ComboBox.class).get(0);paper.setValue("58 mm");snapshotNode(pane,"entry-ticket-58mm");check(entries.get()==1,"Ticket preview duplicated entry");((Button)pane.lookupButton(ButtonType.CLOSE)).fire();return null;});
            fx(()->{check(nav("Messages")==null,"Duplicate Messages sidebar item remains");check(nodes(ToggleButton.class).stream().filter(b->b.getText().equals("System Messages")).count()==1,"Messaging navigation duplicated");nav("System Messages").fire();selectTab("Conversations");return null;});waitFor(()->button("Refresh Inbox")!=null&&!button("Refresh Inbox").isDisabled());
            fx(()->{checkMessageFolders();check(table().getItems().get(0).path("id").asLong()==702,"Received reply was hidden by a subject-only list");return null;});
            snapshot("received-messages-1024",1024,700);
            fx(()->{for(Node node:table().lookupAll(".table-row-cell"))if(node instanceof TableRow<?> row&&!row.isEmpty())check(row.getHeight()<200,"Short message row was measured too tall; metadata is hidden below the viewport: "+row.getHeight());return null;});
            folderStatus.set(403);fx(()->{button("Refresh Inbox").fire();return null;});
            waitFor(()->textContains("You do not have permission to view these messages")&&!button("Refresh Inbox").isDisabled());
            fx(()->{check(!textContains("draft is preserved")&&!textContains("Retry / Refresh"),"Permission error suggested retry or invented a draft");check(table().getItems().isEmpty()&&((Label)table().getPlaceholder()).getText().contains("permission"),"Permission error looked like an empty inbox");input("Subject").setText("Unsaved subject");button("Refresh Inbox").fire();return null;});
            waitFor(()->textContains("Your draft is preserved")&&!button("Refresh Inbox").isDisabled());
            fx(()->{check(input("Subject").getText().equals("Unsaved subject"),"Permission failure erased compose draft");input("Subject").clear();return null;});
            folderStatus.set(503);fx(()->{button("Refresh Inbox").fire();return null;});waitFor(()->textContains("server could not complete")&&!button("Refresh Inbox").isDisabled());
            folderStatus.set(0);emptyFolder.set(true);fx(()->{button("Refresh Inbox").fire();return null;});waitFor(()->!button("Refresh Inbox").isDisabled()&&table().getItems().isEmpty()&&((Label)table().getPlaceholder()).getText().equals("No messages match these filters."));
            fx(()->{check(!textContains("permission")&&!textContains("server could not complete"),"Successful empty response retained a stale error");return null;});
            emptyFolder.set(false);fx(()->{button("Refresh Inbox").fire();return null;});waitFor(()->!button("Refresh Inbox").isDisabled()&&!table().getItems().isEmpty());
            fx(()->{check(!conversationRead.get(),"Listing marked conversation read");input("Subject").setText("Cashier UI question");((TextArea)nodes(Label.class).stream().filter(l->l.getText().equals("Message body")).findFirst().orElseThrow().getLabelFor()).setText("Please check this ticket.");button("Send Message").fire();button("Send Message").fire();return null;});
            waitFor(()->conversationWrites.get()==1&&!button("Refresh Inbox").isDisabled());snapshot("system-messages-1024",1024,700);
            fx(()->{check(table().getItems().get(0).path("id").asLong()==701,"Initial message missing from Sent");table().getSelectionModel().select(0);button("Open Conversation").fire();return null;});waitFor(()->conversationRead.get()&&dialogPaneOrNull()!=null&&!descendants(dialogPane(),Button.class).stream().filter(b->b.getText().equals("Send Reply")).findFirst().orElseThrow().isDisabled());
            fx(()->{DialogPane pane=dialogPane();((TextArea)descendants(pane,Label.class).stream().filter(l->l.getText().equals("Reply")).findFirst().orElseThrow().getLabelFor()).setText("Thank you.");Button reply=descendants(pane,Button.class).stream().filter(b->b.getText().equals("Send Reply")).findFirst().orElseThrow();reply.fire();reply.fire();return null;});
            waitFor(()->replyAttempts.get()==1&&!descendants(dialogPane(),Button.class).stream().filter(b->b.getText().equals("Send Reply")).findFirst().orElseThrow().isDisabled());
            fx(()->{DialogPane pane=dialogPane();TextArea draft=(TextArea)descendants(pane,Label.class).stream().filter(l->l.getText().equals("Reply")).findFirst().orElseThrow().getLabelFor();check(draft.getText().equals("Thank you."),"Failed reply discarded the draft");check(descendants(pane,Label.class).stream().anyMatch(l->l.getText().contains("draft is preserved")),"Reply failure is not visible");descendants(pane,Button.class).stream().filter(b->b.getText().equals("Send Reply")).findFirst().orElseThrow().fire();return null;});
            waitFor(()->replyWrites.get()==1&&descendants(dialogPane(),Label.class).stream().anyMatch(l->l.getText().equals("Thank you.")));fx(()->{check(replyAttempts.get()==2,"Double click sent duplicate replies");snapshotNode(dialogPane(),"cashier-conversation");((Button)dialogPane().lookupButton(ButtonType.CLOSE)).fire();return null;});
            waitFor(()->!button("Refresh Inbox").isDisabled()&&table().getItems().get(0).path("id").asLong()==703);
            fx(() -> { selectTab("Announcements"); return null; });
            waitFor(() -> !nodes(ListView.class).isEmpty()&&!nodes(ListView.class).get(0).getItems().isEmpty());
            fx(() -> {check(!messageRead.get(),"Merely opening Announcements marked its list read");nodes(ListView.class).get(0).getSelectionModel().select(0);return null;});
            try{waitFor(() -> messageRead.get()&&notificationButton().getStyleClass().contains("notification-neutral"));}catch(AssertionError failure){throw new AssertionError("Announcement read="+messageRead.get()+", conversation notice read="+noticeRead.get()+", bell="+fx(()->notificationButton().getAccessibleText()+" / "+notificationButton().getStyleClass())+", feedback="+fx(()->nodes(Label.class).stream().filter(l->l.getStyleClass().contains("notice")).map(Label::getText).toList()),failure);}
            fx(()->{check(!descendants(stage.getScene().getRoot(),Label.class).stream().filter(l->l.getStyleClass().contains("notification-count")).findFirst().orElseThrow().isVisible(),"Zero unread badge visible");return null;});
            snapshot("announcements-1024", 1024, 700);
            openRelatedFromBell(902,"Admin announcement");waitFor(()->selectedMessagingTab().equals("Announcements"));
            openRelatedFromBell(901,"Cashier question");waitFor(()->selectedMessagingTab().equals("Conversations")&&detailsPane("Parking question")!=null);fx(()->{check(descendants(detailsPane("Parking question"),Node.class).stream().anyMatch(n->n.getStyleClass().contains("message-selected")),"Notification did not focus its message");((Button)detailsPane("Parking question").lookupButton(ButtonType.CLOSE)).fire();return null;});
            waitFor(()->!button("Refresh Inbox").isDisabled());fx(()->{table().getSelectionModel().select(0);button("Open Conversation").fire();nav("Parking").fire();return null;});Thread.sleep(500);fx(()->{check(dialogPaneOrNull()==null,"Navigation opened a stale conversation");return null;});
            fx(() -> { button("Log out").fire(); return null; });
            Thread.sleep(200); int pollsAfterLogout = messagePolls.get(),noticesAfterLogout=notificationPolls.get(); Thread.sleep(10_500); check(messagePolls.get() == pollsAfterLogout&&notificationPolls.get()==noticesAfterLogout, "Message/notification polling continued after logout");
            login("cashier.preview");waitFor(()->nav("System Messages")!=null&&!button("Refresh").isDisabled());
            fx(()->{nav("System Messages").fire();selectTab("Conversations");return null;});waitFor(()->button("Refresh Inbox")!=null&&!button("Refresh Inbox").isDisabled());
            fx(()->{checkMessageFolders();check(table().getItems().get(0).path("readState").asText().equals("Read"),"Read state did not survive a fresh login");selectTab("Sent Messages");return null;});
            waitFor(()->!button("Refresh Inbox").isDisabled()&&table().getItems().size()==2);
            fx(()->{check(table().getItems().get(0).path("id").asLong()==703,"Sent reply did not survive a fresh login");check(table().getItems().get(1).path("id").asLong()==701,"Original message did not survive a fresh login");button("Log out").fire();return null;});
            ApiClient canceled = new ApiClient(endpoint, "test", "fixture-password"); canceled.logOut();
            check(canceled.username().isEmpty() && !canceled.isActive(), "Credentials survived logout");
            try { canceled.get("/api/me"); throw new AssertionError("Logged-out client sent a request"); } catch (java.io.IOException expected) { }
            check(uiError.get() == null, "JavaFX callback failed: " + uiError.get());
            check(logins.get() == 4, "Duplicate sign-in request");
            System.out.println("UI CHECK PASSED: bells/99+ badge/read state, conversations/replies, shared polling/retry, logout cleanup, stale dialogs, broadcasts, report saving/cancellation, cards, Manage protection, rate save, search, payment, receipt, connection recovery, keyboard shortcuts and three window sizes.");
            System.out.println("Preview images: " + output.toAbsolutePath());
        } finally {
            if (app != null) fx(() -> { app.stop(); stage.close(); return null; });
            Platform.exit(); server.stop(0); http.shutdownNow();
        }
    }
    private static void selectTab(String name){for(TabPane tabs:nodes(TabPane.class))for(Tab tab:tabs.getTabs())if(tab.getText().equals(name)){tabs.getSelectionModel().select(tab);return;}throw new AssertionError("Tab missing: "+name);}
    private static void checkMessageFolders(){
        for(String name:List.of("Announcements","Conversations","Received Messages","Sent Messages"))check(nodes(TabPane.class).stream().flatMap(t->t.getTabs().stream()).filter(t->t.getText().equals(name)).count()==1,"Missing or duplicate messaging tab: "+name);
        check(!textContains("Sent messages & conversations"),"Navigation returned the old subject-only inbox");
        check(stage.getTitle().equals("Park Lanka | Cashier"),"Role navigation lost Park Lanka branding");
    }
    private static void checkBrandingIcons(Stage window) {
        check(window.getIcons().size() == BrandAssets.ICON_SIZES.size(), "Window icon resolutions missing");
        for (int i = 0; i < BrandAssets.ICON_SIZES.size(); i++) {
            Image icon = window.getIcons().get(i); int size = BrandAssets.ICON_SIZES.get(i);
            check(!icon.isError() && icon.getWidth() == size && icon.getHeight() == size, "Invalid icon resource: " + size);
            check(icon.getPixelReader().getColor(0, 0).getOpacity() == 0, "Icon lost its transparent corners: " + size);
            if (Boolean.getBoolean("park.expectJarResources"))
                check(URI.create(icon.getUrl()).getScheme().equals("jar"), "Icon was not loaded from the packaged JAR: " + size);
        }
    }
    private static void checkCashPaymentPresentation()throws Exception{
        Dialog<Map<String,String>> dialog=ParkingDialogs.payment(JSON.readTree("{\"quoteId\":\"preview-quote\",\"amount\":350.00}"),stage);
        DialogPane pane=dialog.getDialogPane();
        TextField cash=(TextField)descendants(pane,Label.class).stream().filter(l->l.getText().equals("Cash received (LKR)")).findFirst().orElseThrow().getLabelFor();
        Button pay=(Button)pane.lookupButton(pane.getButtonTypes().stream().filter(b->b.getText().equals("Confirm Payment & Print")).findFirst().orElseThrow());
        for(String invalid:List.of("","-1","349.99","NaN","350.001","10000000000","1e3","+500")){cash.setText(invalid);check(pay.isDisabled(),"Invalid cash accepted: "+invalid);}
        cash.setText("349.99");check(descendants(pane,Label.class).stream().anyMatch(l->l.getText().equals("Still required (LKR): 0.01")),"Outstanding cash not shown");
        cash.setText("350.00");check(!pay.isDisabled()&&descendants(pane,Label.class).stream().anyMatch(l->l.getText().equals("Change due (LKR): 0.00")),"Exact cash rejected");
        cash.setText("500.00");check(!pay.isDisabled()&&descendants(pane,Label.class).stream().anyMatch(l->l.getText().equals("Change due (LKR): 150.00")),"Live change calculation failed");
        ToggleButton card=descendants(pane,ToggleButton.class).stream().filter(t->t.getText().equals("External Card")).findFirst().orElseThrow();card.fire();
        check(!cash.getParent().getParent().isManaged(),"Cash fields remain in external-card layout");
        Dialog<Map<String,String>> zero=ParkingDialogs.payment(JSON.readTree("{\"quoteId\":\"preview-zero\",\"amount\":0.00}"),stage);
        check(!zero.getDialogPane().lookupButton(zero.getDialogPane().getButtonTypes().stream().filter(b->b.getText().equals("Confirm Payment & Print")).findFirst().orElseThrow()).isDisabled(),"Legitimate zero bill was blocked");
    }
    private static void checkSidebarBranding(String role) {
        check(stage.getTitle().equals("Park Lanka | " + role), "Workspace title lost branding");
        check(nodes(Label.class).stream().anyMatch(l -> l.getText().equals("Park Lanka") && l.getStyleClass().contains("brand-title")), "Sidebar brand name missing");
        check(nodes(ImageView.class).stream().anyMatch(v -> v.isPreserveRatio() && v.getFitWidth() == 40), "Sidebar icon missing");
    }
    private static void checkMessageErrors()throws Exception {
        ApiClient client=new ApiClient(endpoint,"cashier.preview","fixture-password");
        try {
            try{client.get("/fixture/forbidden");throw new AssertionError("Expected 403");}
            catch(ApiClient.HttpFailure failure){
                check(failure.statusCode()==403&&failure.method().equals("GET")&&failure.path().equals("/fixture/forbidden"),"HTTP failure lost its method, endpoint or status");
                String denied=MessageErrors.describe(failure,MessageErrors.Action.LIST,false);
                check(denied.equals("You do not have permission to view these messages."),"403 was not presented as a permission error");
                check(!denied.toLowerCase(Locale.ROOT).contains("retry")&&!denied.contains("draft"),"403 invented a draft or suggested repeated retries");
                check(MessageErrors.describe(failure,MessageErrors.Action.REPLY,true).endsWith("Your draft is preserved."),"Existing draft preservation was not explained");
            }
            int port;try(var socket=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
            ApiClient offline=new ApiClient("http://127.0.0.1:"+port,"cashier.preview","fixture-password");
            try{offline.get("/api/conversation-messages");throw new AssertionError("Expected a connection failure");}
            catch(ApiClient.NetworkFailure failure){check(MessageErrors.describe(failure,MessageErrors.Action.LIST,false).contains("Check your connection"),"Network failure appeared as a permission or empty-inbox error");}
            finally{offline.logOut();}
        }finally{client.logOut();}
    }
    private static String selectedMessagingTab(){return nodes(TabPane.class).stream().filter(t->t.getTabs().stream().anyMatch(tab->tab.getText().equals("Announcements"))).findFirst().orElseThrow().getSelectionModel().getSelectedItem().getText();}
    private static DialogPane detailsPane(String header){for(Window window:Window.getWindows())if(window!=stage&&window.isShowing()&&window.getScene()!=null)for(DialogPane pane:descendants(window.getScene().getRoot(),DialogPane.class))if(header.equals(pane.getHeaderText()))return pane;return null;}
    @SuppressWarnings("unchecked") private static void openRelatedFromBell(long id,String title)throws Exception{
        fx(()->{nav("Parking").fire();notificationButton().fire();return null;});waitFor(()->dialogPaneOrNull()!=null);
        fx(()->{DialogPane pane=dialogPane();TableView<JsonNode> table=(TableView<JsonNode>)descendants(pane,TableView.class).get(0);table.getSelectionModel().select(table.getItems().stream().filter(n->n.path("id").asLong()==id).findFirst().orElseThrow());descendants(pane,Button.class).stream().filter(b->b.getText().equals("View Details")).findFirst().orElseThrow().fire();return null;});
        if(id==901){waitFor(()->detailsPane("Parking question")!=null);return;}
        waitFor(()->detailsPane(title)!=null&&!descendants(detailsPane(title),Button.class).stream().filter(b->b.getText().equals("Open Related")).findFirst().orElseThrow().isDisabled());fx(()->{descendants(detailsPane(title),Button.class).stream().filter(b->b.getText().equals("Open Related")).findFirst().orElseThrow().fire();return null;});
    }
    private static Button notificationButton(){return nodes(Button.class).stream().filter(b->b.getStyleClass().contains("notification-bell")).findFirst().orElseThrow();}
    private static void checkBellPresentation() throws Exception {
        for(boolean admin:List.of(false,true)) {
            ApiClient api=new ApiClient(endpoint,"bell.fixture","fixture-password");ExecutorService worker=Executors.newSingleThreadExecutor();
            NotificationBell bell=fx(()->new NotificationBell(api,worker,admin,()->true,(kind,id)->{}));Stage preview=fx(()->{Stage window=new Stage();window.setScene(Ui.scene(new StackPane(bell.control()),160,100));window.setX(-10000);window.show();return window;});
            var apply=NotificationBell.class.getDeclaredMethod("apply",JsonNode.class);apply.setAccessible(true);AtomicInteger pulses=new AtomicInteger();
            try {
                fx(()->{Button button=descendants(bell.control(),Button.class).get(0);button.scaleXProperty().addListener((o,old,n)->{if(old.doubleValue()==1&&n.doubleValue()>1)pulses.incrementAndGet();});return null;});
                applyBell(bell,apply,0,"Neutral",10,0);checkBellStyle(bell,"neutral",false,"#6c83a5");
                applyBell(bell,apply,105,"Normal",11,11);Thread.sleep(400);checkBellStyle(bell,"normal",true,"#2563eb");
                fx(()->{Button b=descendants(bell.control(),Button.class).get(0);check(b.getTooltip().getText().contains("105 unread")&&b.getAccessibleText().contains("105 unread"),"Actual count lost behind badge cap");check(descendants(bell.control(),Label.class).stream().anyMatch(l->l.getText().equals("99+")),"99+ cap missing");return null;});
                int after=pulses.get();check(after==1,"New arrival did not animate once");applyBell(bell,apply,105,"Normal",11,11);Thread.sleep(350);check(pulses.get()==after,"Polling repeated arrival animation");
                applyBell(bell,apply,2,"Important",12,12);Thread.sleep(350);checkBellStyle(bell,"important",true,"#b66a09");applyBell(bell,apply,3,"Urgent",13,13);Thread.sleep(350);checkBellStyle(bell,"urgent",true,"#c03547");
                after=pulses.get();applyBell(bell,apply,2,"Important",13,12);Thread.sleep(350);checkBellStyle(bell,"important",true,"#b66a09");check(pulses.get()==after,"Reading retriggered arrival animation");applyBell(bell,apply,1,"Normal",13,11);checkBellStyle(bell,"normal",true,"#2563eb");applyBell(bell,apply,0,"Neutral",13,0);checkBellStyle(bell,"neutral",false,"#6c83a5");
            } finally {fx(()->{bell.close();preview.close();return null;});api.logOut();worker.shutdownNow();}
        }
    }
    private static void applyBell(NotificationBell bell,java.lang.reflect.Method apply,long count,String priority,long latest,long unread)throws Exception{JsonNode result=JSON.valueToTree(Map.of("rows",List.of(),"important",List.of(),"unread",count,"highestPriority",priority,"latestId",latest,"latestUnreadId",unread,"pages",1,"total",0));fx(()->{try{apply.invoke(bell,result);}catch(Exception ex){throw new RuntimeException(ex);}return null;});}
    private static void checkBellStyle(NotificationBell bell,String severity,boolean unread,String icon)throws Exception{fx(()->{bell.control().applyCss();Button b=descendants(bell.control(),Button.class).get(0);check(b.getStyleClass().contains("notification-"+severity),"Bell severity is wrong: "+severity);Label badge=descendants(bell.control(),Label.class).stream().filter(l->l.getStyleClass().contains("notification-count")).findFirst().orElseThrow();check(badge.isVisible()==unread&&badge.isManaged()==unread,"Badge visibility does not match unread count");var svg=descendants(bell.control(),javafx.scene.shape.SVGPath.class).get(0);check(svg.getStroke().equals(javafx.scene.paint.Color.web(icon)),"Icon color does not match severity: "+svg.getStroke());snapshotNode(bell.control(),"bell-"+severity);return null;});}

    private static void checkSharedPoller() throws Exception {
        AtomicInteger calls=new AtomicInteger(),running=new AtomicInteger(),maximum=new AtomicInteger();CountDownLatch first=new CountDownLatch(1),release=new CountDownLatch(1),retried=new CountDownLatch(1);
        SessionPoller poller=new SessionPoller(()->true);poller.add(()->{int attempt=calls.incrementAndGet();maximum.accumulateAndGet(running.incrementAndGet(),Math::max);try{if(attempt==1){first.countDown();check(release.await(5,TimeUnit.SECONDS),"Poll fixture timed out");throw new java.io.IOException("Fixture disconnect");}retried.countDown();}finally{running.decrementAndGet();}});
        try {poller.start();check(first.await(5,TimeUnit.SECONDS),"Poll did not start");for(int i=0;i<5;i++)poller.refresh();release.countDown();check(retried.await(5,TimeUnit.SECONDS),"Manual retry was lost during an in-flight poll");check(maximum.get()==1,"Polls overlapped");check(calls.get()==2,"Duplicate queued refreshes were not coalesced");}finally{release.countDown();poller.close();}
    }

    private static void checkReportSaving() throws Exception {
        Path directory = Files.createTempDirectory(output, "report-save-");
        ApiClient api = new ApiClient(endpoint, "report.fixture", "fixture-password");
        Path saved = directory.resolve("saved.xlsx");
        Files.writeString(saved, "previous file");
        api.saveReport("/fixture/report", saved);
        check(Arrays.equals(Files.readAllBytes(saved), REPORT_BYTES), "Report bytes were not saved intact");
        Path canceled = directory.resolve("canceled.xlsx");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = worker.submit(() -> {
                try { api.saveReport("/fixture/slow-report", canceled); throw new AssertionError("Canceled report was saved"); }
                catch (java.io.IOException expected) { }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
            });
            check(reportStarted.await(5, TimeUnit.SECONDS), "Report request did not start");
            api.logOut(); releaseReport.countDown(); pending.get(5, TimeUnit.SECONDS);
            check(!Files.exists(canceled), "Report file appeared after logout");
            try (var files = Files.list(directory)) { check(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Report temporary file leaked"); }
        } finally {
            api.logOut(); releaseReport.countDown(); worker.shutdownNow();
            Files.deleteIfExists(saved); Files.deleteIfExists(canceled); Files.deleteIfExists(directory);
        }
    }

    private static void login(String username) throws Exception {
        fx(() -> { input("Username").setText(username); input("Password").setText("fixture-password"); button("Connect to Workspace").fire(); button("Connecting...").fire(); return null; });
    }

    private static void checkDesktopConfiguration() throws Exception {
        Path directory = Files.createTempDirectory("parking-desktop-config-");
        Path module = Files.createDirectory(directory.resolve("desktop")), file = module.resolve(".env");
        try {
            check(DesktopConfiguration.serverUrl(directory, Map.of()).equals("http://localhost:8080"), "Default endpoint changed");
            Files.writeString(file, "\uFEFF# Desktop fixture\nexport PARKING_SERVER_URL='" + endpoint + "' # localhost stub\n", StandardCharsets.UTF_8);
            check(DesktopConfiguration.serverUrl(directory, Map.of()).equals(endpoint), "Project-root .env discovery failed");
            check(DesktopConfiguration.serverUrl(module, Map.of()).equals(endpoint), "Desktop-module .env discovery failed");
            check(DesktopConfiguration.serverUrl(directory, Map.of("PARKING_SERVER_URL", "https://api.example.com")).equals("https://api.example.com"), "Environment override lost precedence");
            check(DesktopConfiguration.serverUrl(directory, Map.of("PARKING_DESKTOP_ENV_FILE", file.toString())).equals(endpoint), "Explicit .env path ignored");
            Files.writeString(file, "PARKING_SERVER_URL=\n");
            expectConfigurationFailure(directory, Map.of());
            Files.writeString(file, "PARKING_SERVER_URL=" + endpoint + "\n");
            expectConfigurationFailure(directory, Map.of("PARKING_SERVER_URL", " "));
            expectConfigurationFailure(directory, Map.of("PARKING_DESKTOP_ENV_FILE", directory.resolve("missing.env").toString()));
            Files.writeString(file, "PARKING_SERVER_URL=\"https://api.example.com\n");
            expectConfigurationFailure(directory, Map.of());
            Files.writeString(file, "PARKING_SERVER_URL=" + endpoint + "\nPARKING_SERVER_URL=https://api.example.com\n");
            expectConfigurationFailure(directory, Map.of());
        } finally {
            Files.deleteIfExists(file); Files.delete(module); Files.delete(directory);
        }
    }

    private static void checkSavedConnection() throws Exception {
        Path directory = Files.createTempDirectory("parking-saved-connection-");
        Map<String,String> environment = Map.of("APPDATA", directory.toString());
        Path file = DesktopConfiguration.userSettingsFile(environment);
        try {
            DesktopConfiguration.saveServerUrl("https://api.example.com/", environment);
            check(file.startsWith(directory) && DesktopConfiguration.serverUrl(directory, environment).equals("https://api.example.com"), "User connection did not survive reload");
            Files.writeString(file, "# preserve user settings\nEXISTING_SETTING=keep\nPARKING_SERVER_URL=https://old.example.com\n", StandardCharsets.UTF_8);
            DesktopConfiguration.saveServerUrl(endpoint, environment);
            String saved = Files.readString(file);
            check(saved.contains("EXISTING_SETTING=keep") && saved.contains("# preserve user settings"), "Saving connection erased other settings");
            check(DesktopConfiguration.serverUrl(directory, environment).equals(endpoint), "Connection replacement failed");
            check(DesktopConfiguration.serverUrl(directory, Map.of("APPDATA", directory.toString(), "PARKING_SERVER_URL", "https://override.example.com")).equals("https://override.example.com"), "Saved connection overrode the environment");
            for (String invalid : List.of("http://public.example.com", "https://user:password@example.com", "https://api.example.com/path", "https://api.example.com?token=secret", "")) {
                try { DesktopConfiguration.saveServerUrl(invalid, environment); throw new AssertionError("Invalid endpoint saved"); }
                catch (IllegalArgumentException expected) { }
                check(Files.readString(file).equals(saved), "Rejected connection modified user settings");
            }
        } finally {
            Files.deleteIfExists(file); Files.deleteIfExists(file.getParent()); Files.delete(directory);
        }
    }

    private static void expectConfigurationFailure(Path directory, Map<String, String> environment) {
        try {
            DesktopConfiguration.serverUrl(directory, environment);
            throw new AssertionError("Invalid desktop configuration silently accepted");
        } catch (IllegalStateException expected) { }
    }
    private static void snapshot(String name, int width, int height) throws Exception {
        fx(() -> { stage.setWidth(width + (stage.getWidth() - stage.getScene().getWidth())); stage.setHeight(height + (stage.getHeight() - stage.getScene().getHeight())); stage.setX(-10000); stage.setY(-10000); return null; });
        Thread.sleep(120);
        fx(() -> { Parent root = stage.getScene().getRoot(); root.resize(width, height); root.applyCss(); root.layout(); snapshotNode(root, name); return null; });
    }
    static void snapshotNode(Node node, String name) {
        try {
            node.applyCss(); if (node instanceof Parent parent) parent.layout();
            WritableImage image = node.snapshot(null, null); int width = (int) image.getWidth(), height = (int) image.getHeight();
            int[] pixels = new int[width * height]; image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); png.setRGB(0, 0, width, height, pixels, 0, width); ImageIO.write(png, "png", output.resolve(name + ".png").toFile());
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private static <T> T fx(Supplier<T> work) throws Exception { FutureTask<T> task = new FutureTask<>(work::get); Platform.runLater(task); return task.get(12, TimeUnit.SECONDS); }
    private static void waitFor(Supplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) { if (uiError.get() != null) throw new AssertionError(uiError.get()); if (fx(condition)) return; Thread.sleep(40); }
        throw new AssertionError("Timed out waiting for UI state.");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void walk(Node node, Set<Node> result) {
        if (!result.add(node)) return;
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(child -> walk(child, result));
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) walk(scroll.getContent(), result);
    }
    private static <T> List<T> descendants(Node root, Class<T> type) { Set<Node> nodes = new LinkedHashSet<>(); walk(root, nodes); return nodes.stream().filter(type::isInstance).map(type::cast).toList(); }
    private static <T> List<T> nodes(Class<T> type) { return descendants(stage.getScene().getRoot(), type).stream().filter(n->!(n instanceof Node node)||visible(node)).toList(); }
    private static boolean visible(Node node){for(Node n=node;n!=null;n=n.getParent())if(!n.isVisible())return false;return true;}
    private static Button button(String text) { return nodes(Button.class).stream().filter(b -> b.getText().equals(text)).findFirst().orElse(null); }
    private static ToggleButton nav(String text) { return nodes(ToggleButton.class).stream().filter(b -> b.getText().equals(text)).findFirst().orElse(null); }
    private static TextField input(String title) { return (TextField) nodes(Label.class).stream().filter(l -> l.getText().equals(title) && l.getLabelFor() != null).findFirst().orElseThrow().getLabelFor(); }
    private static boolean textContains(String text) { return nodes(Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().contains(text)); }
    @SuppressWarnings("unchecked") private static List<TableView<JsonNode>> tables() { return (List) nodes(TableView.class); }
    private static TableView<JsonNode> table() { return tables().get(0); }
    @SuppressWarnings("unchecked") private static ComboBox<String> dataset() { return (ComboBox<String>) nodes(ComboBox.class).stream().filter(c -> c.getItems().contains("sessions")).findFirst().orElseThrow(); }
    private static TextField search() { return nodes(TextField.class).stream().filter(t -> "Search ticket or plate...".equals(t.getPromptText())).findFirst().orElseThrow(); }
    private static DialogPane dialogPaneOrNull() { for (Window window : Window.getWindows()) if (window != stage && window.isShowing() && window.getScene() != null) { List<DialogPane> panes = descendants(window.getScene().getRoot(), DialogPane.class); if (!panes.isEmpty()) { window.setX(-10000); window.setY(-10000); return panes.get(0); } } return null; }
    private static DialogPane dialogPane() { return Objects.requireNonNull(dialogPaneOrNull(), "Dialog not visible"); }

    private static TextField openingCash(){return nodes(TextField.class).stream().filter(t->"0.00".equals(t.getPromptText())).findFirst().orElseThrow();}
    private static void checkCashierShiftFlow()throws Exception{
        waitFor(()->button("Open Shift")!=null&&button("Open Shift").isVisible());
        fx(()->{
            check(textContains("Connected")&&!textContains("Request failed"),"No shift was classified as a connection failure");
            check(nodes(Label.class).stream().filter(l->l.getText().startsWith("Open a cashier shift before checkout")).count()==1,"Duplicate shift banners");
            table().getSelectionModel().select(0);button("Quote / Checkout").fire();return null;
        });
        waitFor(()->!button("Quote / Checkout").isDisabled());
        fx(()->{check(dialogPaneOrNull()==null&&shiftQuotes.get()==0&&checkouts.get()==0,"No shift opened a dialog or sent checkout");button("Open Shift").fire();return null;});
        waitFor(()->openingCash()!=null&&!button("Open Shift").isDisabled());
        fx(()->{openingCash().setText("-1");button("Open Shift").fire();check(textContains("nonnegative"),"Negative opening cash accepted");openingCash().setText("1.001");button("Open Shift").fire();check(shiftOpens.get()==0,"Invalid opening cash reached the backend");nav("Parking").fire();return null;});
        waitFor(()->button("Open Shift")!=null&&button("Open Shift").isVisible());
        fx(()->{check(table().getSelectionModel().getSelectedItem().path("id").asLong()==101,"Cancelling shift navigation discarded selected vehicle");check(checkouts.get()==0,"Cancelling shift form submitted a payment");button("Open Shift").fire();return null;});
        waitFor(()->!button("Open Shift").isDisabled());
        failShiftOpen.set(true);
        fx(()->{openingCash().setText("100.00");button("Open Shift").fire();button("Open Shift").fire();return null;});
        waitFor(()->textContains("Opening drawer is unavailable")&&!button("Open Shift").isDisabled());
        failShiftCurrent.set(true);
        fx(()->{check(openingCash().getText().equals("100.00")&&nav("Cashier Shifts").isSelected(),"Failed opening discarded form or navigated away");check(shiftOpens.get()==1&&checkouts.get()==0&&!textContains("Request failed"),"Failed opening duplicated requests or changed connection badge");button("Open Shift").fire();return null;});
        waitFor(()->textContains("Shift verification temporarily unavailable")&&!button("Open Shift").isDisabled());
        fx(()->{check(nav("Cashier Shifts").isSelected()&&openingCash().getText().equals("100.00"),"Unverified opening assumed success or discarded form");check(checkouts.get()==0&&shiftOpens.get()==2,"Unverified opening automatically retried");button("Open Shift").fire();return null;});
        waitFor(()->nav("Parking").isSelected()&&textContains("Continue active shift #51")&&!button("Quote / Checkout").isDisabled());
        fx(()->{check(!textContains("Open a cashier shift before checkout")&&button("Open Shift")==null,"Successful opening retained stale guidance");check(table().getSelectionModel().getSelectedItem().path("id").asLong()==101,"Opening discarded selection");check(checkouts.get()==0&&shiftQuotes.get()==0&&dialogPaneOrNull()==null,"Opening silently resumed payment");button("Log out").fire();return null;});
        login("cashier.preview");waitFor(()->textContains("Continue active shift #51")&&!button("Refresh").isDisabled());
        fx(()->{check(button("Open Shift")==null,"Fresh login did not restore active shift");nav("Cashier Shifts").fire();return null;});
        waitFor(()->textContains("Continue active shift #51")&&!button("Refresh").isDisabled());
        shiftActive.set(false);fx(()->{nav("Parking").fire();return null;});
        waitFor(()->button("Open Shift")!=null&&button("Open Shift").isVisible());
        fx(()->{table().getSelectionModel().select(0);button("Quote / Checkout").fire();return null;});
        waitFor(()->!button("Quote / Checkout").isDisabled());
        fx(()->{check(checkouts.get()==0&&shiftQuotes.get()==0&&dialogPaneOrNull()==null,"Closed shift authorized checkout");button("Open Shift").fire();return null;});
        waitFor(()->!button("Open Shift").isDisabled());
        // Another client opens this cashier's shift between GET and POST: reload the existing shift.
        shiftActive.set(true);fx(()->{openingCash().setText("100.00");button("Open Shift").fire();return null;});
        waitFor(()->nav("Parking").isSelected()&&textContains("Continue active shift #51")&&!button("Refresh").isDisabled());
        fx(()->{check(checkouts.get()==0&&shiftOpens.get()==4,"Concurrent-open recovery submitted a payment or duplicated opening");table().getSelectionModel().clearSelection();return null;});
        System.out.println("SHIFT UI CHECK PASSED: single guidance, validation, cancelled/failed opening, preserved selection, verified opening, login restore, closed-shift block, existing-shift recovery; no automatic payment.");
    }

    private static void serve(HttpExchange exchange) throws java.io.IOException {
        try {
            String path = exchange.getRequestURI().getPath(), query = exchange.getRequestURI().getQuery(); String body;
            if(path.equals("/fixture/forbidden")){send(exchange,403,"");return;}
            if(path.equals("/api/shifts/current")){if(failShiftCurrent.getAndSet(false)){send(exchange,503,"{\"error\":\"Shift verification temporarily unavailable\"}");return;}send(exchange,200,shiftActive.get()?"{\"active\":true,\"shift\":"+SHIFT+"}":"{\"active\":false,\"shift\":null}");return;}
            if(path.equals("/api/shifts")&&exchange.getRequestMethod().equals("POST")){
                shiftOpens.incrementAndGet();JsonNode request=JSON.readTree(exchange.getRequestBody());check(request.path("openingCash").decimalValue().compareTo(new java.math.BigDecimal("100.00"))==0,"Opening cash was not preserved");
                if(failShiftOpen.getAndSet(false)){send(exchange,400,"{\"error\":\"Opening drawer is unavailable; contact the manager\"}");return;}
                if(shiftActive.getAndSet(true)){send(exchange,409,"{\"code\":\"SHIFT_ALREADY_OPEN\",\"error\":\"An active shift already exists\"}");return;}
                send(exchange,200,SHIFT);return;
            }
            if(path.equals("/api/shifts")||path.equals("/api/admin/shifts")){send(exchange,200,"{\"rows\":["+SHIFT+"],\"total\":1,\"pages\":1,\"page\":0}");return;}
            if(path.startsWith("/api/admin/pricing/")){send(exchange,200,"[]");return;}
            if(path.equals("/api/receipts")||path.equals("/api/admin/receipts")){send(exchange,200,"{\"rows\":["+RECEIPT+"],\"total\":1,\"pages\":1,\"page\":0}");return;}
            if(path.endsWith("/reprints")&&path.contains("/receipts/")){send(exchange,200,RECEIPT);return;}
            if(path.equals("/api/admin/audit")){send(exchange,200,"{\"rows\":[{\"id\":1,\"actor\":\"admin.preview\",\"action\":\"SHIFT_CLOSED\",\"target\":\"SHIFT:50\",\"branchId\":1,\"occurredAt\":\"2026-10-21T05:00:00Z\",\"afterDetails\":\"Closing summary saved\"}],\"pages\":1,\"total\":1}");return;}
            if(path.equals("/api/admin/backups")){send(exchange,200,"{\"rows\":[{\"id\":1,\"kind\":\"MANUAL\",\"status\":\"SUCCEEDED\",\"engine\":\"H2\",\"createdAt\":\"2026-10-21T05:00:00Z\",\"sizeBytes\":12345}],\"pages\":1,\"total\":1,\"serverDirectory\":\"C:/ParkingBackups\",\"maintenance\":false,\"busy\":false}");return;}
            if(path.equals("/api/admin/corrections")){send(exchange,200,"{\"rows\":[],\"pages\":0,\"total\":0}");return;}
            if(path.equals("/api/sessions/103/ticket")){send(exchange,200,"{\"id\":103,\"branch\":\"Main Branch\",\"plate\":\"ABC-9999\",\"vehicleType\":\"CAR\",\"slot\":\"A-002\",\"enteredAt\":\"2026-10-21T04:30:00Z\",\"pricingSummary\":\"LKR 1.00 per started minute; minimum one unit\",\"opaqueIdentifier\":\"ae5470ec-d3a5-4e1e-b918-583242098369\"}");return;}
            if(path.startsWith("/api/notifications")) {
                if(exchange.getRequestMethod().equals("PUT")){if(path.contains("902"))messageRead.set(true);else if(path.endsWith("read-all")){noticeRead.set(true);messageRead.set(true);}else noticeRead.set(true);}else {notificationPolls.incrementAndGet();if(query!=null&&query.contains("section=announcements"))messagePolls.incrementAndGet();}
                JsonNode conversation=JSON.readTree("{\"id\":901,\"title\":\"Cashier question\",\"description\":\"Please check this ticket\",\"category\":\"Conversation\",\"priority\":\"Important\",\"sender\":\"cashier.preview\",\"createdAt\":\"2026-10-21T04:00:00Z\",\"relatedType\":\"CONVERSATION\",\"relatedId\":700}");
                ((com.fasterxml.jackson.databind.node.ObjectNode)conversation).put("relatedPostId",702);
                JsonNode announcement=JSON.readTree("{\"id\":902,\"title\":\"Admin announcement\",\"description\":\"Keep checkout available.\",\"category\":\"Broadcast\",\"priority\":\"Urgent\",\"sender\":\"admin.preview\",\"createdAt\":\"2026-10-21T04:00:00Z\",\"relatedType\":\"BROADCAST\",\"relatedId\":900}");
                if(noticeRead.get())((com.fasterxml.jackson.databind.node.ObjectNode)conversation).put("readAt","2026-10-21T05:00:00Z");if(messageRead.get())((com.fasterxml.jackson.databind.node.ObjectNode)announcement).put("readAt","2026-10-21T05:00:00Z");
                boolean announcements=query!=null&&query.contains("section=announcements");long count=announcements?(messageRead.get()?0:1):(noticeRead.get()?0:123)+(messageRead.get()?0:1);
                body=JSON.writeValueAsString(Map.of("rows",announcements?List.of(announcement):List.of(conversation,announcement),"unread",count,"highestPriority",count==0?"Neutral":messageRead.get()?"Important":"Urgent","latestId",902,"latestUnreadId",messageRead.get()?(noticeRead.get()?0:901):902,"total",announcements?1:2,"pages",1));
                if(path.equals("/api/notifications/901")||path.equals("/api/notifications/902")){Thread.sleep(350);body=JSON.writeValueAsString(Map.of("notification",path.endsWith("901")?conversation:announcement,"record",Map.of()));}
                else if(path.endsWith("/read")&&!path.endsWith("read-all"))body=JSON.writeValueAsString(path.contains("902")?announcement:conversation);
                send(exchange,200,body);return;
            }
            if(path.equals("/api/conversation-messages")) {
                if(folderStatus.get()!=0){send(exchange,folderStatus.get(),"");return;}
                if(emptyFolder.get()){send(exchange,200,"{\"rows\":[],\"page\":0,\"pages\":0,\"total\":0}");return;}
                boolean incoming=query==null||query.contains("folder=received");
                body="{\"rows\":[{\"id\":"+(incoming?702:701)+",\"conversationId\":700,\"subject\":\"Parking question\",\"preview\":\""+(incoming?"Admin response":"Please help with this lane.")+"\",\"incoming\":"+incoming+",\"sender\":\"admin.preview\",\"recipient\":\"Administrators\",\"priority\":\"Important\",\"readState\":\""+(incoming?"Unread":"Sent")+"\",\"sentAt\":\"2026-10-21T04:00:00Z\"}],\"pages\":1,\"total\":1}";
                if(incoming&&conversationRead.get()){var result=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(body);((com.fasterxml.jackson.databind.node.ObjectNode)result.path("rows").get(0)).put("readState","Read");body=JSON.writeValueAsString(result);}
                if(!incoming&&replyWrites.get()>0){var result=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(body);var rows=(com.fasterxml.jackson.databind.node.ArrayNode)result.path("rows");var reply=((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0)).deepCopy();reply.put("id",703).put("preview","Thank you.");rows.insert(0,reply);result.put("total",2);body=JSON.writeValueAsString(result);}
                send(exchange,200,body);return;
            }
            if(path.startsWith("/api/conversations")) {
                if(path.endsWith("/read")){
                    JsonNode displayed=JSON.readTree(exchange.getRequestBody()).path("displayedPostIds");String login=new String(Base64.getDecoder().decode(exchange.getRequestHeaders().getFirst("Authorization").substring(6)),StandardCharsets.UTF_8);long incoming=login.startsWith("admin.preview:")?701:702;
                    if(displayed.size()!=1||displayed.get(0).asLong()!=incoming)throw new IllegalStateException("Reading marked an outgoing or unrelated message");conversationRead.set(true);noticeRead.set(true);
                }
                if(exchange.getRequestMethod().equals("POST")){if(path.endsWith("/replies")){
                    String key=JSON.readTree(exchange.getRequestBody()).path("requestKey").asText();
                    if(replyAttempts.incrementAndGet()==1){failedReplyKey.set(key);send(exchange,503,"{\"error\":\"Temporary reply failure\"}");return;}
                    if(!key.equals(failedReplyKey.get()))throw new IllegalStateException("Retry changed the request key");replyWrites.incrementAndGet();
                }else conversationWrites.incrementAndGet();}
                if(path.equals("/api/conversations")&&exchange.getRequestMethod().equals("GET"))body="{\"rows\":["+CONVERSATION+"],\"pages\":1,\"total\":1}";
                else if(exchange.getRequestMethod().equals("GET")){
                    String login=new String(Base64.getDecoder().decode(exchange.getRequestHeaders().getFirst("Authorization").substring(6)),StandardCharsets.UTF_8);boolean admin=login.startsWith("admin.preview:");
                    body="{\"conversation\":"+CONVERSATION+",\"messages\":{\"rows\":[{\"id\":701,\"kind\":\"MESSAGE\",\"sender\":\"cashier.preview\",\"admin\":false,\"incoming\":"+admin+",\"readState\":\"Sent\",\"body\":\"Please help with this lane.\",\"sentAt\":\"2026-10-21T04:00:00Z\"},{\"id\":702,\"kind\":\"REPLY\",\"sender\":\"admin.preview\",\"admin\":true,\"incoming\":"+!admin+",\"readState\":\"Unread\",\"body\":\"Admin response\",\"sentAt\":\"2026-10-21T05:00:00Z\"}],\"page\":0,\"pages\":1,\"total\":2}}";
                    if(replyWrites.get()>0){var result=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(body);var message=(com.fasterxml.jackson.databind.node.ObjectNode)result.path("messages");((com.fasterxml.jackson.databind.node.ArrayNode)message.path("rows")).add(JSON.readTree("{\"id\":703,\"sender\":\"cashier.preview\",\"kind\":\"REPLY\",\"incoming\":false,\"admin\":false,\"body\":\"Thank you.\",\"readState\":\"Sent\",\"sentAt\":\"2026-10-21T06:00:00Z\"}"));message.put("total",3);body=JSON.writeValueAsString(result);}
                }
                else {var result=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(CONVERSATION);if(replyWrites.get()>0)result.put("lastPostId",703);body=JSON.writeValueAsString(result);}send(exchange,200,body);return;
            }
            if (path.equals("/fixture/report") || path.equals("/fixture/slow-report")) {
                if (path.endsWith("slow-report")) { reportStarted.countDown(); releaseReport.await(10, TimeUnit.SECONDS); }
                exchange.sendResponseHeaders(200, REPORT_BYTES.length); exchange.getResponseBody().write(REPORT_BYTES); exchange.close(); return;
            }
            if (path.equals("/api/me")) {
                if (exchange.getRequestHeaders().getFirst("Authorization") == null) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"Realm\""); send(exchange, 401, ""); return;
                }
                logins.incrementAndGet(); String auth = new String(Base64.getDecoder().decode(exchange.getRequestHeaders().getFirst("Authorization").substring(6)), StandardCharsets.UTF_8);
                if(auth.startsWith("cashier.preview:")&&cashierLoggedIn.compareAndSet(false,true)){noticeRead.set(false);messageRead.set(false);conversationRead.set(false);}
                body = auth.startsWith("admin.preview:") ? "{\"role\":\"ADMIN\"}" : "{\"role\":\"CASHIER\",\"branchName\":\"001 – Main Branch\",\"branchId\":1}";
            } else if (path.equals("/api/admin/branches")) body = BRANCHES;
            else if (path.equals("/api/admin/broadcasts")) {
                if (exchange.getRequestMethod().equals("POST")) { broadcastWrites.incrementAndGet(); body = "{\"id\":900,\"recipientCount\":2}"; }
                else body = "{\"rows\":[],\"pages\":1,\"total\":0}";
            }
            else if (path.equals("/api/messages") || path.equals("/api/messages/900/read")) {
                if (exchange.getRequestMethod().equals("PUT")) messageRead.set(true); else messagePolls.incrementAndGet();
                String message = "{\"id\":900,\"title\":\"Important parking notice\",\"body\":\"Keep checkout available while reading notices.\",\"sender\":\"admin.preview\",\"priority\":\"URGENT\",\"sentAt\":\"2026-10-21T04:00:00Z\",\"readAt\":" + (messageRead.get() ? "\"2026-10-21T05:00:00Z\"" : "null") + "}";
                body = exchange.getRequestMethod().equals("PUT") ? message : "[" + message + "]";
            }
            else if (path.equals("/api/admin/cashiers")) body = CASHIERS;
            else if (path.equals("/api/rates")) {
                if (exchange.getRequestMethod().equals("PUT")) { rateWrites.incrementAndGet(); rate = JSON.readTree(exchange.getRequestBody()).path("perMinute").asText(); }
                body = "{\"perMinute\":" + rate + "}";
            } else if (path.equals("/api/admin/records")) {
                if (query != null && query.contains("table=cashiers")) Thread.sleep(350);
                body = "{\"rows\":[{\"id\":1,\"plate\":\"ABC-1234\",\"branchStatus\":\"Deleted\",\"occupied\":false}],\"pages\":1,\"total\":1}";
            } else if (path.equals("/api/slots")) {
                if (failSlots.getAndSet(false)) { send(exchange, 503, "{\"error\":\"Server temporarily unavailable\"}"); return; }
                body = "[{\"id\":1,\"code\":\"A-001\",\"occupied\":true},{\"id\":2,\"code\":\"A-002\",\"occupied\":false},{\"id\":3,\"code\":\"A-003\",\"occupied\":false}]";
            } else if (path.equals("/api/sessions")) {
                if (exchange.getRequestMethod().equals("POST")) {
                    entries.incrementAndGet(); lastEntry.set(JSON.readTree(exchange.getRequestBody()));
                    body = "{\"id\":103,\"plate\":\"ABC-9999\",\"vehicleType\":\"CAR\",\"slot\":\"A-002\",\"enteredAt\":\"2026-10-21T04:30:00Z\"}";
                } else body = SESSIONS;
            }
            else if (path.equals("/api/reports/today")) body = "{\"payments\":4,\"revenue\":350.00,\"occupied\":1}";
            else if (path.endsWith("/quote")) {shiftQuotes.incrementAndGet();body = "{\"sessionId\":101,\"plate\":\"ABC-1234\",\"slot\":\"A-001\",\"quoteId\":\"smoke-quote\",\"billableMinutes\":45,\"amount\":45.00}";}
            else if (path.endsWith("/checkout")) {JsonNode request=JSON.readTree(exchange.getRequestBody());check(!request.path("requestKey").asText().isBlank(),"Checkout lacks stable attempt key");check(request.path("confirmedAmount").asText().equals("45.0")||request.path("confirmedAmount").asText().equals("45.00"),"Confirmed quote missing");checkouts.incrementAndGet();body=RECEIPT;}
            else { send(exchange, 404, "{\"error\":\"Unknown fixture endpoint\"}"); return; }
            send(exchange, 200, body);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); exchange.close(); }
    }
    private static void send(HttpExchange exchange, int status, String body) throws java.io.IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }
    private static final String SHIFT="{\"id\":51,\"cashier\":\"cashier.preview\",\"branchId\":1,\"branch\":\"Main Branch\",\"openedAt\":\"2026-10-21T04:30:00Z\",\"openingCash\":100.00,\"cashCollections\":20.00,\"cardCollections\":45.00,\"cashRefunds\":0,\"cashVoids\":0,\"expectedCash\":120.00}";
    private static final String BRANCHES = """
        [{"id":1,"code":"001","name":"Main Branch","status":"Active","main":true,"totalSlots":20,"occupied":3,"activeVehicles":3,"cashiers":2,"todayPayments":3,"todayRevenue":350.00,"totalPayments":28},
         {"id":2,"code":"002","name":"Kandy Branch","status":"Stopped","main":false,"totalSlots":40,"occupied":12,"activeVehicles":12,"cashiers":3,"todayPayments":5,"todayRevenue":550.00,"totalPayments":46},
         {"id":3,"code":"003","name":"Archived Branch","status":"Deleted","main":false,"totalSlots":10,"occupied":0,"activeVehicles":0,"todayPayments":0,"todayRevenue":0}]
        """;
    private static final String CASHIERS = """
        [{"id":0,"username":"cashier","branch":"001 – Main Branch","status":"Active","bootstrap":true,"enabled":true,"branchDeleted":false},
         {"id":1,"username":"kandy.cashier","branch":"002 – Kandy Branch","status":"Stopped","bootstrap":false,"enabled":false,"branchDeleted":false},
         {"id":2,"username":"archived.cashier","branch":"003 – Archived Branch","status":"Branch Deleted","bootstrap":false,"enabled":true,"branchDeleted":true}]
        """;
    private static final String SESSIONS = """
        [{"id":101,"plate":"ABC-1234","vehicleType":"CAR","slot":"A-001","enteredAt":"2026-10-21T03:30:00Z"},
         {"id":102,"plate":"WP-5678","vehicleType":"VAN","slot":"A-005","enteredAt":"2026-10-21T03:45:00Z"}]
        """;
    private static final String RECEIPT = """
        {"paymentId":501,"sessionId":101,"plate":"ABC-1234","slot":"A-001","enteredAt":"2026-10-21T03:30:00Z","exitedAt":"2026-10-21T04:15:00Z","billableMinutes":45,"amount":45.00,"currency":"LKR","method":"EXTERNAL_CARD","reference":"TERMINAL-100"}
        """;
    private static final String CONVERSATION="""
        {"id":700,"subject":"Parking question","cashier":"cashier.preview","branch":"001 – Main Branch","branchId":1,"priority":"Important","category":"Parking issue","status":"Open","unread":true,"lastPostId":702,"updatedAt":"2026-10-21T04:00:00Z"}
        """;
}
