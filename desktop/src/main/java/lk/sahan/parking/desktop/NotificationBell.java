package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** One persistent notification store, including existing broadcast recipients/read receipts. */
final class NotificationBell implements AutoCloseable {
    interface OpenRelated { void accept(String type,Long id,Long postId); }
    private final ApiClient api; private final ExecutorService io; private final BooleanSupplier active; private final OpenRelated open;
    final SessionPoller poller;
    private final Label badge=Ui.badge("0"),status=Ui.text("Loading notifications…","muted"),pageLabel=Ui.text("","muted");
    private final Label prominent=Ui.text("","field-label"); private final HBox banner;
    private final Button bell=Ui.button("Notifications","secondary"); private final StackPane control;
    private final TableView<JsonNode> table=new TableView<>(); private final BooleanProperty busy=new SimpleBooleanProperty();
    private final IntegerProperty page=new SimpleIntegerProperty(),pages=new SimpleIntegerProperty(1); private final AtomicLong revision=new AtomicLong();
    private final boolean admin; private volatile boolean closed; private Dialog<ButtonType> panel; private List<JsonNode> priorityNotices=List.of();
    private final Set<Dialog<?>> detailsDialogs=new HashSet<>();
    private long panelVersion;
    private long unreadCount,observedLatestId;private boolean initialized;
    private final javafx.animation.ScaleTransition arrivalPulse=new javafx.animation.ScaleTransition(javafx.util.Duration.millis(140));
    private final javafx.animation.Timeline expiry;
    NotificationBell(ApiClient api,ExecutorService io,boolean admin,BooleanSupplier active,BiConsumer<String,Long> open) {
        this(api,io,admin,active,(type,id,postId)->open.accept(type,id));
    }
    NotificationBell(ApiClient api,ExecutorService io,boolean admin,BooleanSupplier active,OpenRelated open) {
        this.api=api; this.io=io; this.admin=admin; this.active=active; this.open=open; poller=new SessionPoller(this::usable);
        bell.setGraphic(Ui.icon("Bell")); bell.setText(""); bell.setAccessibleText("Notifications"); bell.setTooltip(new Tooltip("Notifications — opening does not mark messages as read")); bell.setOnAction(e->show(false));
        control=new StackPane(bell,badge); control.setMinWidth(62); badge.setMouseTransparent(true); StackPane.setAlignment(badge,javafx.geometry.Pos.TOP_RIGHT);
        bell.getStyleClass().addAll("notification-bell","notification-neutral");badge.getStyleClass().add("notification-count");badge.setVisible(false);badge.setManaged(false);
        arrivalPulse.setNode(bell);arrivalPulse.setFromX(1);arrivalPulse.setFromY(1);arrivalPulse.setToX(1.08);arrivalPulse.setToY(1.08);arrivalPulse.setAutoReverse(true);arrivalPulse.setCycleCount(2);
        table.getColumns().setAll(Ui.column("Title","title",210),Ui.column("Description","description",260),Ui.column("Category","category",120),Ui.column("Priority","priority",100),Ui.column("Sender","sender",140),Ui.column("Branch","branch",190),Ui.column("Cashier","cashier",140),Ui.column("Time · Colombo","createdAt",185),Ui.column("Read at","readAt",185)); Ui.table(table,"No notifications.",300);
        TableColumn<JsonNode,String> state=new TableColumn<>("State"); state.setPrefWidth(90);state.setCellValueFactory(row->new SimpleStringProperty(row.getValue().hasNonNull("readAt")?"Read":"Unread"));table.getColumns().add(1,state);
        table.setRowFactory(t->{ TableRow<JsonNode> row=new TableRow<>(); row.setOnMouseClicked(e->{ if(e.getClickCount()==2&&!row.isEmpty()) details(row.getItem()); }); return row; });
        Button view=Ui.button("View notifications","secondary"); view.setOnAction(e->show(false)); HBox.setHgrow(prominent,Priority.ALWAYS);
        banner=Ui.style(Ui.row(Ui.badge("Important / Urgent"),prominent,view),"notification-banner"); banner.setVisible(false); banner.setManaged(false);
        poller.add(this::poll); expiry=new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),e->updateBanner())); expiry.setCycleCount(javafx.animation.Animation.INDEFINITE);
    }
    Node control() { return control; } Node banner() { return banner; }
    void start() { expiry.play(); poller.start(); } void refresh() { revision.incrementAndGet(); status.setText("Loading notifications…"); poller.refresh(); }
    private boolean usable() { return !closed&&api.isActive()&&active.getAsBoolean(); }
    private void poll() throws Exception {
        long version=revision.get(); int requested=page.get();
        try {
            JsonNode result=api.get("/api/notifications?page="+requested+"&size=20");
            Platform.runLater(()->{ if(!usable()||revision.get()!=version) return; apply(result); });
        } catch(Exception ex) { Platform.runLater(()->{ if(usable()) status.setText("Could not load notifications. Retry or wait for reconnection."); }); throw ex; }
    }
    private void apply(JsonNode result) {
        long selected=selectedId(); table.getItems().clear(); result.path("rows").forEach(table.getItems()::add); table.getItems().stream().filter(n->n.path("id").asLong()==selected).findFirst().ifPresent(table.getSelectionModel()::select);
        updateBell(result);
        pages.set(Math.max(1,result.path("pages").asInt())); pageLabel.setText("Page "+(page.get()+1)+" of "+pages.get()+" · "+result.path("total").asLong()+" notifications");
        status.setText("Opening this panel does not mark notifications as read. Times: Asia/Colombo.");
        if(result.has("important")){List<JsonNode> prominent=new ArrayList<>();result.path("important").forEach(prominent::add);priorityNotices=prominent;}else if(page.get()==0) priorityNotices=table.getItems().stream().filter(n->!n.hasNonNull("readAt")&&!n.path("priority").asText().equals("Normal")).toList(); updateBanner();
        if(page.get()>=pages.get()) { page.set(pages.get()-1); refresh(); }
    }
    private void updateBell(JsonNode result) {
        unreadCount=result.path("unread").asLong();
        String highest=result.path("highestPriority").asText("");
        if(highest.isEmpty()) {highest="Normal";for(JsonNode n:result.path("important"))if(n.path("priority").asText().equals("Urgent")){highest="Urgent";break;}else highest="Important";}
        String severity=unreadCount==0?"neutral":highest.toLowerCase(Locale.ROOT);
        if(!Set.of("neutral","normal","important","urgent").contains(severity))severity="normal";
        for(Node n:List.of(bell,badge)){n.getStyleClass().removeAll("notification-neutral","notification-normal","notification-important","notification-urgent");n.getStyleClass().add("notification-"+severity);}
        badge.setText(unreadCount>99?"99+":Long.toString(unreadCount));badge.setVisible(unreadCount>0);badge.setManaged(unreadCount>0);
        String accessible="Notifications, "+unreadCount+" unread"+(unreadCount>0?" · highest priority "+highest:"");bell.setAccessibleText(accessible);bell.getTooltip().setText(accessible+". Opening this panel does not mark notifications as read.");badge.setAccessibleText(unreadCount+" unread notifications");
        long latest=result.path("latestId").asLong(),latestUnread=result.path("latestUnreadId").asLong();
        if(!result.has("latestId")){for(JsonNode n:result.path("rows")){latest=Math.max(latest,n.path("id").asLong());if(!n.hasNonNull("readAt"))latestUnread=Math.max(latestUnread,n.path("id").asLong());}}
        if(initialized&&unreadCount>0&&latestUnread>observedLatestId){arrivalPulse.stop();bell.setScaleX(1);bell.setScaleY(1);arrivalPulse.playFromStart();}
        observedLatestId=Math.max(observedLatestId,latest);initialized=true;
    }
    private void updateBanner() {
        if(!usable()) return;
        JsonNode top=priorityNotices.stream().filter(n->!n.hasNonNull("expiresAt")||Instant.parse(n.path("expiresAt").asText()).isAfter(Instant.now())).max(Comparator.comparingInt(n->n.path("priority").asText().equals("Urgent")?2:1)).orElse(null);
        banner.setVisible(!admin&&top!=null); banner.setManaged(!admin&&top!=null);
        if(top!=null) prominent.setText(top.path("priority").asText()+" · "+top.path("title").asText());
    }
    private void show(boolean all) {
        if(!usable()) return; if(panel!=null&&panel.isShowing()) { panel.getDialogPane().requestFocus(); return; }
        panelVersion++;page.set(0); refresh(); panel=new Dialog<>(); Ui.dialog(panel,control.getScene().getWindow()); panel.setTitle(all?"Notification Center":"Notifications"); panel.setHeaderText(all?"All notifications":"Recent notifications"); panel.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Button details=Ui.button("View Details","primary"),read=Ui.button("Mark as Read","secondary"),readAll=Ui.button("Mark All as Read","secondary"),viewAll=Ui.button("View All","secondary"),retry=Ui.button("Retry / Refresh","secondary"),previous=Ui.button("Previous","secondary"),next=Ui.button("Next","secondary");
        details.disableProperty().bind(busy.or(table.getSelectionModel().selectedItemProperty().isNull())); read.disableProperty().bind(details.disableProperty()); readAll.disableProperty().bind(busy); retry.disableProperty().bind(busy);
        details.setOnAction(e->details(table.getSelectionModel().getSelectedItem())); read.setOnAction(e->{long id=selectedId();run(()->api.put("/api/notifications/"+id+"/read",Map.of()),result->{ refresh(); });}); readAll.setOnAction(e->run(()->api.put("/api/notifications/read-all",Map.of()),result->{ page.set(0); revision.incrementAndGet(); apply(result); refresh(); }));
        retry.setOnAction(e->refresh()); viewAll.setOnAction(e->{ panel.setTitle("Notification Center"); panel.setHeaderText("All notifications"); panel.getDialogPane().setPrefWidth(1050); });
        previous.disableProperty().bind(busy.or(page.lessThanOrEqualTo(0))); next.disableProperty().bind(busy.or(page.greaterThanOrEqualTo(pages.subtract(1)))); previous.setOnAction(e->{page.set(page.get()-1);refresh();}); next.setOnAction(e->{page.set(page.get()+1);refresh();});
        ProgressIndicator progress=new ProgressIndicator(); progress.setMaxSize(18,18); progress.visibleProperty().bind(busy); progress.managedProperty().bind(busy);
        VBox content=Ui.card(Ui.row(status,progress),table,Ui.flow(details,read,readAll,viewAll,retry),Ui.flow(previous,next,pageLabel)); panel.getDialogPane().setContent(content); panel.getDialogPane().setPrefWidth(all?1050:900);
        panel.setOnHidden(e->{panelVersion++; content.getChildren().remove(table); panel=null; }); panel.show();
    }
    private long selectedId() { JsonNode n=table.getSelectionModel().getSelectedItem(); return n==null?-1:n.path("id").asLong(); }
    private void details(JsonNode selected) {
        if(selected==null) return; long id=selected.path("id").asLong(),version=panelVersion;
        run(()->api.get("/api/notifications/"+id),result->{
            if(panel==null||version!=panelVersion)return;JsonNode n=result.path("notification");
            if(n.path("relatedType").asText().equals("CONVERSATION")){panel.close();open.accept("CONVERSATION",n.path("relatedId").asLong(),n.hasNonNull("relatedPostId")?n.path("relatedPostId").asLong():null);return;}
            Dialog<ButtonType> dialog=new Dialog<>(); Ui.dialog(dialog,control.getScene().getWindow()); dialog.setTitle("Notification Details"); dialog.setHeaderText(n.path("title").asText()); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            StringBuilder text=new StringBuilder(n.path("description").asText()).append("\n\n").append(n.path("category").asText()).append(" · ").append(n.path("priority").asText()).append("\nFrom ").append(n.path("sender").asText()).append("\n").append(n.path("branch").asText("")).append("\n").append(Ui.date(n.path("createdAt").asText())).append(" · Asia/Colombo");
            result.path("record").fields().forEachRemaining(f->{ String value=f.getValue().isNull()?"—":f.getValue().asText(); if(value.matches("\\d{4}-.*T.*Z")) value=Ui.date(value); text.append("\n").append(f.getKey()).append(": ").append(value); });
            TextArea body=new TextArea(text.toString()); body.setWrapText(true); body.setEditable(false); body.setPrefRowCount(13);
            BooleanProperty isRead=new SimpleBooleanProperty(n.hasNonNull("readAt"));Label state=Ui.text(isRead.get()?"Read":"Unread","muted");
            Button mark=Ui.button("Mark as Read","secondary"),related=Ui.button("Open Related","primary"); mark.disableProperty().bind(busy.or(isRead)); related.disableProperty().bind(busy);
            Runnable readDisplayed=()->run(()->api.put("/api/notifications/"+id+"/read?opened=true",Map.of()),v->{if(dialog.isShowing()){isRead.set(true);state.setText("Read");}refresh();});mark.setOnAction(e->readDisplayed.run());
            boolean link=!admin||Set.of("CONVERSATION","BROADCAST").contains(n.path("relatedType").asText()); related.setVisible(link); related.setManaged(link); related.setOnAction(e->{ dialog.close(); if(panel!=null) panel.close();boolean conversation=n.path("relatedType").asText().equals("CONVERSATION");open.accept(!admin&&!conversation?"ANNOUNCEMENT":n.path("relatedType").asText(),!admin&&!conversation?id:n.path("relatedId").asLong(),n.hasNonNull("relatedPostId")?n.path("relatedPostId").asLong():null); });
            dialog.getDialogPane().setContent(Ui.card(body,state,Ui.flow(mark,related))); dialog.getDialogPane().setPrefWidth(620); detailsDialogs.add(dialog);dialog.setOnHidden(e->detailsDialogs.remove(dialog));dialog.show();if(!isRead.get())readDisplayed.run();
        });
    }
    private void run(Callable<JsonNode> request,Consumer<JsonNode> success) {
        if(!usable()||busy.get()) return; busy.set(true);
        CompletableFuture.supplyAsync(()->{try{return request.call();}catch(Exception ex){throw new CompletionException(ex);}},io).whenComplete((r,f)->Platform.runLater(()->{if(!usable())return;busy.set(false);if(f!=null)status.setText("Request failed. Retry: "+f.getCause().getMessage());else success.accept(r);}));
    }
    @Override public void close() { closed=true; revision.incrementAndGet(); poller.close(); expiry.stop();arrivalPulse.stop();bell.setScaleX(1);bell.setScaleY(1);unreadCount=0;initialized=false;observedLatestId=0;badge.setVisible(false);badge.setManaged(false); new ArrayList<>(detailsDialogs).forEach(Dialog::close);detailsDialogs.clear();if(panel!=null)panel.close(); table.getItems().clear();priorityNotices=List.of();badge.setText("0");banner.setVisible(false);banner.setManaged(false); }
}
