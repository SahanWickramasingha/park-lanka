package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Paginated announcements reuse the notification API and original broadcast read receipts. */
final class CashierMessagesPane implements AutoCloseable {
    private final ApiClient api; private final ExecutorService io; private final BooleanSupplier active,visible;
    private final Runnable changed; private final SessionPoller poller;
    private final AtomicLong revision=new AtomicLong(); private volatile boolean closed;
    private final ObservableList<JsonNode> messages=FXCollections.observableArrayList();
    private final ListView<JsonNode> list=new ListView<>(messages);
    private final Label status=Ui.text("Loading announcements…","muted"),pageLabel=Ui.text("","muted"),feedback=Ui.notice();
    private final Label title=Ui.text("Select an announcement","section-title"),details=Ui.text("","muted");
    private final TextArea body=new TextArea();private final BooleanProperty reading=new SimpleBooleanProperty();
    private final ObjectProperty<JsonNode> selection=new SimpleObjectProperty<>();
    private final IntegerProperty page=new SimpleIntegerProperty(),pages=new SimpleIntegerProperty(1);
    private final VBox root; private final Timeline expiry;private JsonNode displayed;private long attempted=-1;private boolean updating;
    private volatile long externalId=-1;

    CashierMessagesPane(ApiClient api,ExecutorService io,BooleanSupplier active,BooleanSupplier visible,Runnable changed,SessionPoller poller) {
        this.api=api;this.io=io;this.active=active;this.visible=visible;this.changed=changed;this.poller=poller;
        list.setPrefHeight(240);list.setMinHeight(160);list.setPlaceholder(Ui.text("No active announcements.","muted"));
        list.setCellFactory(c->new ListCell<>(){@Override protected void updateItem(JsonNode n,boolean empty){super.updateItem(n,empty);setText(null);if(empty||n==null){setGraphic(null);return;}String preview=n.path("description").asText();if(preview.length()>140)preview=preview.substring(0,140)+"…";setGraphic(new VBox(6,Ui.row(Ui.badge(n.path("priority").asText()),Ui.text(n.hasNonNull("readAt")?"Read":"Unread","muted")),Ui.text(n.path("title").asText(),"field-label"),Ui.text(preview,"muted"),Ui.text(Ui.date(n.path("createdAt").asText()),"muted")));}});
        list.getSelectionModel().selectedItemProperty().addListener((o,old,n)->{if(!updating){externalId=-1;attempted=-1;display(n);}});
        body.setEditable(false);body.setWrapText(true);body.setPrefRowCount(7);
        Button mark=Ui.button("Mark as Read","primary");mark.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(()->reading.get()||selection.get()==null||selection.get().hasNonNull("readAt"),reading,selection));mark.setOnAction(e->{attempted=-1;markRead();});
        Button refresh=Ui.button("Refresh Announcements","secondary");refresh.setOnAction(e->refresh());
        Button previous=Ui.button("Previous Announcements","secondary"),next=Ui.button("Next Announcements","secondary");
        previous.disableProperty().bind(reading.or(page.lessThanOrEqualTo(0)));next.disableProperty().bind(reading.or(page.greaterThanOrEqualTo(pages.subtract(1))));
        previous.setOnAction(e->{page.set(page.get()-1);clearSelection();refresh();});next.setOnAction(e->{page.set(page.get()+1);clearSelection();refresh();});
        root=Ui.page(Ui.section("Announcements","Broadcasts and operational notices from Admin. Selecting an announcement marks only that announcement as read.",Ui.row(refresh),status,list,Ui.flow(previous,next,pageLabel)),Ui.card(title,details,body,mark,feedback));
        poller.add(this::poll);expiry=new Timeline(new KeyFrame(Duration.seconds(1),e->prune()));expiry.setCycleCount(Animation.INDEFINITE);
    }
    Node view(){return root;}void start(){expiry.play();}private boolean usable(){return !closed&&api.isActive()&&active.getAsBoolean();}
    void activate(){refresh();if(displayed!=null)markRead();}
    void refresh(){if(!usable())return;revision.incrementAndGet();status.setText("Refreshing announcements…");poller.refresh();}
    void open(long id){
        if(!usable())return;long version=revision.incrementAndGet();
        CompletableFuture.supplyAsync(()->{try{return api.get("/api/notifications/"+id).path("notification");}catch(Exception ex){throw new CompletionException(ex);}},io).whenComplete((n,f)->Platform.runLater(()->{
            if(!usable()||!visible.getAsBoolean()||revision.get()!=version)return;
            if(f!=null){Ui.notice(feedback,"Could not open announcement: "+f.getCause().getMessage(),true);return;}
            if(n.path("relatedType").asText().equals("CONVERSATION"))return;
            updating=true;list.getSelectionModel().clearSelection();updating=false;externalId=id;attempted=-1;display(n);
        }));
    }
    private void poll()throws Exception{try{fetch();}catch(Exception ex){Platform.runLater(()->{if(usable())status.setText("Could not load announcements. Retrying automatically; use Refresh Announcements to retry now.");});throw ex;}}
    private void fetch()throws Exception{
        if(!usable())return;long version=revision.get();int requested=page.get();long external=externalId;
        JsonNode result=api.get("/api/notifications?section=announcements&page="+requested+"&size=20");JsonNode outside=null;
        if(external>0){try{outside=api.get("/api/notifications/"+external).path("notification");}catch(java.io.IOException ex){if(!ex.getMessage().startsWith("HTTP 404:"))throw ex;}}
        JsonNode externalResult=outside;
        Platform.runLater(()->{
            if(!usable()||revision.get()!=version)return;
            long selected=displayed==null?-1:displayed.path("id").asLong();updating=true;messages.clear();result.path("rows").forEach(messages::add);
            JsonNode restored=messages.stream().filter(n->n.path("id").asLong()==selected).findFirst().orElse(externalResult);
            if(restored!=null&&messages.contains(restored))list.getSelectionModel().select(restored);updating=false;
            pages.set(Math.max(1,result.path("pages").asInt()));pageLabel.setText("Page "+(requested+1)+" of "+pages.get()+" · "+result.path("total").asLong()+" announcements · "+result.path("unread").asLong()+" unread");
            if(requested>=pages.get()){page.set(pages.get()-1);refresh();return;}
            status.setText("Announcements are up to date. Times: Asia/Colombo.");display(restored);prune();
        });
    }
    private void clearSelection(){updating=true;list.getSelectionModel().clearSelection();updating=false;externalId=-1;attempted=-1;display(null);}
    private void display(JsonNode n){displayed=n;selection.set(n);title.setText(n==null?"Select an announcement":n.path("title").asText());body.setText(n==null?"":n.path("description").asText());details.setText(n==null?"":"From "+n.path("sender").asText()+" · "+n.path("priority").asText()+" · "+n.path("category").asText()+" · "+Ui.date(n.path("createdAt").asText())+" · "+(n.hasNonNull("readAt")?"Read":"Unread"));if(n!=null&&visible.getAsBoolean())markRead();}
    private void markRead(){
        JsonNode n=displayed;if(!usable()||n==null||n.hasNonNull("readAt")||reading.get()||attempted==n.path("id").asLong())return;
        long id=n.path("id").asLong();attempted=id;reading.set(true);revision.incrementAndGet();
        CompletableFuture.supplyAsync(()->{try{return api.put("/api/notifications/"+id+"/read",Map.of());}catch(Exception ex){throw new CompletionException(ex);}},io).whenComplete((result,failure)->Platform.runLater(()->{
            if(!usable())return;reading.set(false);revision.incrementAndGet();
            if(failure!=null)Ui.notice(feedback,"Could not mark announcement read. Retry with Mark as Read. "+failure.getCause().getMessage(),true);
            else{updating=true;for(int i=0;i<messages.size();i++)if(messages.get(i).path("id").asLong()==id)messages.set(i,result);updating=false;if(displayed!=null&&displayed.path("id").asLong()==id)display(result);Ui.notice(feedback,"Announcement read.",false);changed.run();}
            if(displayed!=null&&displayed.path("id").asLong()!=id)markRead();
        }));
    }
    private void prune(){if(!usable())return;messages.removeIf(n->n.hasNonNull("expiresAt")&&!Instant.parse(n.path("expiresAt").asText()).isAfter(Instant.now()));if(displayed!=null&&displayed.hasNonNull("expiresAt")&&!Instant.parse(displayed.path("expiresAt").asText()).isAfter(Instant.now()))clearSelection();}
    @Override public void close(){closed=true;revision.incrementAndGet();expiry.stop();externalId=-1;displayed=null;selection.set(null);messages.clear();body.clear();title.setText("");details.setText("");feedback.setText("");}
}
