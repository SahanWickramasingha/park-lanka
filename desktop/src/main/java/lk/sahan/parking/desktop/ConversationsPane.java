package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.geometry.*;
import javafx.util.Duration;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static lk.sahan.parking.desktop.MessageErrors.Action.*;

/** The same conversation UI serves a cashier's own history and the administrator's cross-branch inbox. */
final class ConversationsPane implements AutoCloseable {
    private final ApiClient api; private final ExecutorService io; private final boolean admin; private final BooleanSupplier active; private final Runnable changed;
    private final VBox root; private final Label feedback=Ui.notice(),pageLabel=Ui.text("","muted"); private final BooleanProperty busy=new SimpleBooleanProperty();
    private final TableView<JsonNode> history=new TableView<>(); private final TextField subject=new TextField(),relatedId=new TextField(),search=new TextField(); private final TextArea body=new TextArea();
    private final Tab received=new Tab("Received Messages"),sent=new Tab("Sent Messages"); private final TabPane folders=new TabPane(received,sent);
    private record Selection(long conversation,Long post) {}
    private long epoch; private boolean reloadPending,permissionDenied; private Selection pendingSelection;
    private final ComboBox<String> category=new ComboBox<>(FXCollections.observableArrayList("General","Parking Issue","Payment Issue","Technical Issue","Other")),priority=new ComboBox<>(FXCollections.observableArrayList("Normal","Important","Urgent")),related=new ComboBox<>(FXCollections.observableArrayList("None","Ticket","Payment"));
    private final ComboBox<JsonNode> target=new ComboBox<>(),branchFilter=new ComboBox<>(),cashierFilter=new ComboBox<>();
    private final ComboBox<String> priorityFilter=new ComboBox<>(FXCollections.observableArrayList("All","Normal","Important","Urgent")),stateFilter=new ComboBox<>(FXCollections.observableArrayList("All","Open","Resolved")),readFilter=new ComboBox<>(FXCollections.observableArrayList("All","Unread","Read"));
    private int page,pages=1; private long version; private boolean closed; private String draftSignature,sendKey; private final Set<Dialog<?>> dialogs=new HashSet<>(); private final Map<Dialog<?>,Label> dialogFeedback=new HashMap<>();
    ConversationsPane(ApiClient api,ExecutorService io,boolean admin,BooleanSupplier active,Runnable changed) {
        this.api=api;this.io=io;this.admin=admin;this.active=active;this.changed=changed;
        subject.setPromptText("1–120 characters");body.setWrapText(true);body.setPrefRowCount(4);body.setPromptText("1–4000 characters");category.setValue("General");priority.setValue("Normal");related.setValue("None");relatedId.setPromptText("Optional record ID");relatedId.disableProperty().bind(related.valueProperty().isEqualTo("None"));
        Button send=button(admin?"Send Direct Message":"Send Message","primary");send.setOnAction(e->send());
        VBox compose=Ui.section(admin?"Direct message to a cashier":"New message to Admin","Start a conversation or reply to an existing message above.",Ui.field("Subject",subject),Ui.field("Message body",body),Ui.flow(Ui.field("Category",category),Ui.field("Priority",priority),Ui.field("Related record",related),Ui.field("Ticket / Payment ID",relatedId)),send);
        if(admin) { picker(target,"Choose an eligible cashier");compose.getChildren().add(1,Ui.field("Recipient cashier",target)); }
        received.setClosable(false);sent.setClosable(false);folders.getSelectionModel().selectedItemProperty().addListener((o,old,value)->{page=0;readFilter.setDisable(value==sent);version++;if(busy.get())reloadPending=true;else load();});
        TableColumn<JsonNode,String> message=Ui.column("Message","subject",330);message.setMinWidth(200);
        message.setCellFactory(c->new TableCell<>(){
            final VBox card=new VBox(5);
            {card.setMinWidth(0);card.prefWidthProperty().bind(message.widthProperty().subtract(26));}
            @Override protected void updateItem(String text,boolean empty){
                super.updateItem(text,empty);setText(null);setGraphic(null);card.getChildren().clear();
                if(empty||getTableRow().getItem()==null)return;
                JsonNode n=getTableRow().getItem();String person=n.path("incoming").asBoolean()?"From "+n.path("sender").asText():"To "+n.path("recipient").asText();
                card.getChildren().setAll(Ui.text(text,"field-label"),Ui.text(n.path("preview").asText()),Ui.text(person+(admin?" | "+n.path("branch").asText():""),"muted"));setGraphic(card);
            }
            // VirtualFlow can measure a cell before its own width is assigned. Use the column's
            // actual width so wrapped labels are not measured one character per line and cached.
            @Override protected double computePrefHeight(double width){return isEmpty()?46:Math.max(46,card.prefHeight(Math.max(message.getMinWidth(),message.getWidth())-26)+20);}
        });
        TableColumn<JsonNode,String> urgency=Ui.column("Priority","priority",88),readState=Ui.column("Read state","readState",125),time=Ui.column("Time · Colombo","sentAt",165);
        for(var column:List.of(urgency,readState,time))wrapCells(column);
        history.getColumns().setAll(message,urgency,readState,time);Ui.table(history,"No messages match these filters.",300);history.setFixedCellSize(-1);history.getStyleClass().add("message-table");history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        history.setRowFactory(t->{TableRow<JsonNode> row=new TableRow<>();row.setOnMouseClicked(e->{if(e.getClickCount()==2&&!row.isEmpty())openMessage(row.getItem());});return row;});
        history.setOnKeyPressed(e->{if(e.getCode()==javafx.scene.input.KeyCode.ENTER&&history.getSelectionModel().getSelectedItem()!=null)openMessage(history.getSelectionModel().getSelectedItem());});
        Button refresh=button("Refresh Inbox","secondary"),view=button("Open Conversation","primary"),previous=button("Previous","secondary"),next=button("Next","secondary");view.disableProperty().unbind();view.disableProperty().bind(busy.or(history.getSelectionModel().selectedItemProperty().isNull()));
        refresh.setOnAction(e->activate());view.setOnAction(e->openMessage(history.getSelectionModel().getSelectedItem()));previous.setOnAction(e->{if(page>0){page--;load();}});next.setOnAction(e->{if(page+1<pages){page++;load();}});
        priorityFilter.setValue("All");stateFilter.setValue("All");readFilter.setValue("All");search.setPromptText("Search subject");Button apply=button("Apply Filters","secondary");apply.setOnAction(e->{page=0;load();});
        VBox inbox=Ui.section(admin?"Cashier Messages":"Conversations","Open a message to read or reply. Times: Asia/Colombo.",folders);
        if(admin) { picker(branchFilter,"All branches");picker(cashierFilter,"All cashiers");Button clear=button("Clear Filters","quiet");clear.setOnAction(e->{branchFilter.setValue(null);cashierFilter.setValue(null);priorityFilter.setValue("All");stateFilter.setValue("All");readFilter.setValue("All");search.clear();page=0;load();});inbox.getChildren().addAll(Ui.flow(Ui.field("Branch",branchFilter),Ui.field("Cashier",cashierFilter),Ui.field("Priority",priorityFilter)),Ui.flow(Ui.field("Resolution",stateFilter),Ui.field("Read state",readFilter),Ui.field("Search subject",search),apply,clear)); }
        else inbox.getChildren().addAll(Ui.flow(Ui.field("Priority",priorityFilter),Ui.field("Conversation",stateFilter),Ui.field("Incoming read state",readFilter)),Ui.flow(Ui.field("Search",search),apply));
        search.setPromptText("Subject, message or sender");search.setOnAction(e->{page=0;load();});
        ProgressIndicator progress=new ProgressIndicator();progress.setMaxSize(18,18);progress.visibleProperty().bind(busy);progress.managedProperty().bind(busy);inbox.getChildren().add(progress);
        inbox.getChildren().addAll(Ui.flow(refresh,view),history,Ui.flow(previous,next,pageLabel));
        root=Ui.page(feedback,inbox,compose); compose.disableProperty().bind(busy);
    }
    Node view(){return root;} private boolean usable(){return !closed&&api.isActive()&&active.getAsBoolean();}
    void deactivate(){epoch++;pendingSelection=null;reloadPending=false;new ArrayList<>(dialogs).forEach(Dialog::close);}
    void poll(){if(usable()&&!busy.get()&&!permissionDenied){if(!threadViews.isEmpty())threadViews.values().iterator().next().refresh();else load();}}
    void activate(){if(!usable())return;permissionDenied=false;if(busy.get()){reloadPending=true;return;} if(admin) run(()->{
        var result=api.get("/api/admin/cashiers");var branches=api.get("/api/admin/branches?includeDeleted=true");return Map.entry(result,branches);
    },result->{JsonNode recipient=target.getValue(),cashier=cashierFilter.getValue(),branch=branchFilter.getValue();target.getItems().clear();cashierFilter.getItems().clear();result.getKey().forEach(c->{cashierFilter.getItems().add(c);if(c.path("enabled").asBoolean()&&!c.path("branchDeleted").asBoolean())target.getItems().add(c);});restore(target,recipient);restore(cashierFilter,cashier);branchFilter.getItems().clear();result.getValue().forEach(branchFilter.getItems()::add);restore(branchFilter,branch);load();});else load();}
    private void restore(ComboBox<JsonNode> box,JsonNode selected){if(selected!=null)box.getItems().stream().filter(c->c.path("id").equals(selected.path("id"))).findFirst().ifPresent(box::setValue);}
    private void wrapCells(TableColumn<JsonNode,String> column){column.setCellFactory(c->new TableCell<>(){
        final Label label=Ui.text("");
        {label.prefWidthProperty().bind(column.widthProperty().subtract(25));label.setMinHeight(Region.USE_PREF_SIZE);}
        @Override protected void updateItem(String value,boolean empty){super.updateItem(value,empty);setText(null);label.setText(value);setGraphic(empty?null:label);}
        @Override protected double computePrefHeight(double width){return isEmpty()?46:Math.max(46,label.prefHeight(Math.max(column.getMinWidth(),column.getWidth())-25)+20);}
    });}
    private void picker(ComboBox<JsonNode> box,String prompt){box.setPromptText(prompt);box.setCellFactory(v->new ListCell<>(){@Override protected void updateItem(JsonNode n,boolean empty){super.updateItem(n,empty);setText(empty||n==null?null:n.has("username")?n.path("username").asText()+" · "+n.path("branch").asText():n.path("code").asText()+" – "+n.path("name").asText());}});box.setButtonCell(box.getCellFactory().call(null));}
    private void load(){
        if(!usable())return;long requested=++version;String path="/api/conversation-messages?folder="+(folders.getSelectionModel().getSelectedItem()==sent?"sent":"received")+"&page="+page+"&size=30";
        if(admin){if(branchFilter.getValue()!=null)path+="&branchId="+branchFilter.getValue().path("id").asLong();if(cashierFilter.getValue()!=null)path+="&cashier="+encode(cashierFilter.getValue().path("username").asText());}
        if(!priorityFilter.getValue().equals("All"))path+="&priority="+code(priorityFilter.getValue());if(!stateFilter.getValue().equals("All"))path+="&status="+code(stateFilter.getValue());if(folders.getSelectionModel().getSelectedItem()==received&&!readFilter.getValue().equals("All"))path+="&unread="+readFilter.getValue().equals("Unread");if(!search.getText().isBlank())path+="&search="+encode(search.getText().trim());
        String query=path;run(LIST,()->api.get(query),result->{if(requested!=version)return;permissionDenied=false;history.setPlaceholder(Ui.text("No messages match these filters.","empty-state"));long selected=history.getSelectionModel().getSelectedItem()==null?-1:history.getSelectionModel().getSelectedItem().path("id").asLong();history.getItems().clear();result.path("rows").forEach(history.getItems()::add);history.getItems().stream().filter(c->c.path("id").asLong()==selected).findFirst().ifPresent(history.getSelectionModel()::select);pages=Math.max(1,result.path("pages").asInt());pageLabel.setText("Page "+(page+1)+" of "+pages+" · "+result.path("total").asLong()+" messages");if(page>=pages){page=pages-1;load();}});
    }
    private void send(){
        String title=subject.getText().trim(),message=body.getText().trim();if(title.isBlank()||title.length()>120||message.isBlank()||message.length()>4000){notice("Use a subject of 1–120 and a body of 1–4000 characters.",true);return;}
        if(admin&&target.getValue()==null){notice("Choose an eligible cashier.",true);return;}
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("subject",title);payload.put("body",message);payload.put("category",code(category.getValue()));payload.put("priority",code(priority.getValue()));if(admin)payload.put("cashier",target.getValue().path("username").asText());
        if(!related.getValue().equals("None")){try{long id=Long.parseLong(relatedId.getText().trim());if(id<1)throw new NumberFormatException();payload.put(related.getValue().equals("Ticket")?"ticketId":"paymentId",id);}catch(Exception ex){notice("Enter a valid positive ticket/payment ID.",true);return;}}
        String signature=payload.toString();if(!signature.equals(draftSignature)){draftSignature=signature;sendKey=UUID.randomUUID().toString();}payload.put("requestKey",sendKey);
        run(SEND,()->api.post("/api/conversations",payload),result->{subject.clear();body.clear();relatedId.clear();draftSignature=null;sendKey=null;notice("Message sent.",false);changed.run();page=0;if(folders.getSelectionModel().getSelectedItem()==sent)load();else folders.getSelectionModel().select(sent);});
    }
    private final Map<Long,ThreadView> threadViews=new HashMap<>();
    private void openMessage(JsonNode message){if(message!=null)open(message.path("conversationId").asLong(),message.path("id").asLong());}
    void open(long id){open(id,null);}
    void open(long id,Long post){if(!usable())return;if(busy.get()){pendingSelection=new Selection(id,post);return;}ThreadView view=threadViews.get(id);loadThread(id,0,post,view);}
    private void loadThread(long id,int page,Long selected,ThreadView existing){
        String path="/api/conversations/"+id+"?page="+page+"&size=30"+(selected==null?"":"&postId="+selected);
        run(()->api.get(path),result->{
            if(existing!=null&&existing.disposed)return;ThreadView view=existing==null?new ThreadView(id):existing;
            int actual=result.path("messages").path("page").asInt(page);view.pages=Math.max(1,result.path("messages").path("pages").asInt());
            if(existing==null||actual<view.first-1||actual>view.last+1){view.messages.clear();view.first=actual;view.last=actual;}else{view.first=Math.min(view.first,actual);view.last=Math.max(view.last,actual);}
            result.path("messages").path("rows").forEach(p->view.messages.put(p.path("id").asLong(),p));view.metadata(result.path("conversation"));view.render(selected);
            if(existing==null){dialogs.add(view.dialog);threadViews.put(id,view);view.dialog.show();}
            Platform.runLater(()->{if(!view.disposed){if(selected!=null)view.focus(selected);view.reading.playFromStart();}});
        });
    }
    private final class ThreadView {
        final long id;final Dialog<ButtonType> dialog=new Dialog<>();final VBox transcript=new VBox(12);final ScrollPane scroll=Ui.scroll(transcript);final TextArea reply=new TextArea();
        final Label status=Ui.text("","muted"),pageLabel=Ui.text("","muted"),local=Ui.notice();final Button resolve=button("Resolve Conversation","secondary"),previous=button("Load Earlier Messages","secondary"),next=button("Load Later Messages","secondary");
        final BooleanProperty resolved=new SimpleBooleanProperty();final TreeMap<Long,JsonNode> messages=new TreeMap<>();final Map<Long,Node> cards=new LinkedHashMap<>();final Map<Long,Label> states=new HashMap<>();final Set<Long> acknowledged=new HashSet<>();
        final PauseTransition reading=new PauseTransition(Duration.millis(250));int first,last,pages;boolean disposed,readDenied;String signature,key;Long focusPost;
        ThreadView(long id){
            this.id=id;Ui.dialog(dialog,root.getScene().getWindow());dialog.setTitle("Conversation");dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);status.setMaxWidth(Double.MAX_VALUE);status.setMinHeight(Region.USE_PREF_SIZE);
            scroll.setPrefHeight(320);scroll.setMinHeight(150);reply.setWrapText(true);reply.setPrefRowCount(3);reply.setPromptText("Write a reply");dialogFeedback.put(dialog,local);
            Button send=button("Send Reply","primary"),refresh=button("Refresh Conversation","secondary");send.disableProperty().unbind();send.disableProperty().bind(busy.or(resolved));reply.disableProperty().bind(resolved);
            previous.setOnAction(e->{if(first>0)loadThread(id,first-1,null,this);});next.setOnAction(e->{if(last+1<pages)loadThread(id,last+1,null,this);});refresh.setOnAction(e->{readDenied=false;refresh();});
            send.setOnAction(e->{String text=reply.getText().trim();if(text.isEmpty()||text.length()>4000){Ui.notice(local,"Reply needs 1–4000 characters.",true);return;}if(!text.equals(signature)){signature=text;key=UUID.randomUUID().toString();}
                run(REPLY,()->api.post("/api/conversations/"+id+"/replies",Map.of("requestKey",key,"body",text)),r->{if(disposed)return;if(reply.getText().trim().equals(text))reply.clear();signature=null;key=null;Ui.notice(local,"Reply sent.",false);changed.run();
                    loadThread(id,last,r.hasNonNull("lastPostId")?r.path("lastPostId").asLong():null,this);});});
            resolve.setVisible(admin);resolve.setManaged(admin);resolve.setOnAction(e->{String requestKey=UUID.randomUUID().toString();boolean value=!resolved.get();run(RESOLVE,()->api.put("/api/admin/conversations/"+id+"/resolution",Map.of("requestKey",requestKey,"resolved",value)),r->{if(!disposed){changed.run();refresh();}});});
            VBox content=Ui.card(status,Ui.flow(previous,next,refresh),pageLabel,scroll,Ui.field("Reply",reply),Ui.flow(send,resolve),local);VBox.setVgrow(scroll,Priority.ALWAYS);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefWidth(760);
            dialog.setOnHidden(e->{disposed=true;reading.stop();dialogs.remove(dialog);dialogFeedback.remove(dialog);threadViews.remove(id);reply.clear();if(usable()){if(busy.get())reloadPending=true;else load();}});
            reading.setOnFinished(e->markVisible());scroll.vvalueProperty().addListener((o,old,value)->reading.playFromStart());scroll.viewportBoundsProperty().addListener((o,old,value)->reading.playFromStart());
        }
        void metadata(JsonNode thread){dialog.setHeaderText(thread.path("subject").asText());resolved.set(thread.path("status").asText().equals("Resolved"));resolve.setText(resolved.get()?"Reopen Conversation":"Resolve Conversation");status.setText("Conversation: "+thread.path("status").asText()+" | "+thread.path("cashier").asText()+" | "+thread.path("branch").asText()+" | "+thread.path("priority").asText()+"\n"+thread.path("category").asText()+" | Ticket: "+thread.path("ticketId").asText("—")+" | Payment: "+thread.path("paymentId").asText("—"));if(resolved.get())Ui.notice(local,"Resolved. An administrator can reopen this conversation.",false);}
        void render(Long selected){
            if(selected!=null)focusPost=selected;double position=scroll.getVvalue();transcript.getChildren().clear();cards.clear();states.clear();
            for(JsonNode p:messages.values()){
                long post=p.path("id").asLong();boolean incoming=p.path("incoming").asBoolean();
                Label sender=Ui.text(p.path("sender").asText()+(p.path("admin").asBoolean()?" (Admin)":" (Cashier)")+" | "+Ui.date(p.path("sentAt").asText()),"field-label");
                Label state=Ui.text(acknowledged.contains(post)?"Read":p.path("readState").asText(),"muted");VBox card=Ui.style(new VBox(7,sender,Ui.text(p.path("body").asText()),state),"message-bubble",incoming?"message-incoming":"message-outgoing");
                if(p.path("kind").asText().equals("STATUS"))card.getStyleClass().add("message-system");if(focusPost!=null&&focusPost==post)card.getStyleClass().add("message-selected");
                card.setMinWidth(0);card.maxWidthProperty().bind(scroll.widthProperty().subtract(35).multiply(.9));card.setMaxHeight(Region.USE_PREF_SIZE);HBox row=new HBox(card);row.setAlignment(incoming?Pos.TOP_LEFT:Pos.TOP_RIGHT);transcript.getChildren().add(row);cards.put(post,card);states.put(post,state);
            }
            pageLabel.setText("Chronological order | Pages "+(first+1)+"–"+(last+1)+" of "+pages+(first>0?" | Earlier messages available":""));previous.setVisible(first>0);previous.setManaged(first>0);next.setVisible(last+1<pages);next.setManaged(last+1<pages);scroll.setVvalue(position);
        }
        void focus(long post){Node card=cards.get(post);if(card==null)return;dialog.getDialogPane().applyCss();dialog.getDialogPane().layout();scroll.applyCss();scroll.layout();transcript.layout();double height=transcript.getHeight()-scroll.getViewportBounds().getHeight();if(height>0)scroll.setVvalue(Math.max(0,Math.min(1,card.getParent().getBoundsInParent().getMinY()/height)));card.setFocusTraversable(true);card.requestFocus();}
        void refresh(){if(disposed||busy.get())return;int from=first,to=last;run(()->{List<JsonNode> results=new ArrayList<>();for(int p=from;p<=to;p++)results.add(api.get("/api/conversations/"+id+"?page="+p+"&size=30"));return results;},results->{if(disposed)return;for(JsonNode result:results){result.path("messages").path("rows").forEach(p->messages.put(p.path("id").asLong(),p));pages=Math.max(1,result.path("messages").path("pages").asInt());metadata(result.path("conversation"));}render(null);reading.playFromStart();});}
        void markVisible(){
            if(disposed||readDenied||!dialog.isShowing()||!usable())return;if(busy.get()){reading.playFromStart();return;}Node viewport=scroll.lookup(".viewport");if(viewport==null)return;
            Bounds bounds=viewport.localToScene(viewport.getBoundsInLocal());List<Long> displayed=new ArrayList<>();for(var entry:cards.entrySet()){JsonNode p=messages.get(entry.getKey());if(p.path("incoming").asBoolean()&&!p.hasNonNull("readAt")&&!acknowledged.contains(entry.getKey())&&bounds.intersects(entry.getValue().localToScene(entry.getValue().getBoundsInLocal())))displayed.add(entry.getKey());}
            if(displayed.isEmpty())return;run(READ,()->api.put("/api/conversations/"+id+"/read",Map.of("throughPostId",Collections.max(displayed),"displayedPostIds",displayed)),r->{if(disposed)return;acknowledged.addAll(displayed);displayed.forEach(post->{if(states.containsKey(post))states.get(post).setText("Read");});changed.run();});
        }
    }
    private Button button(String text,String kind){Button b=Ui.button(text,kind);b.disableProperty().bind(busy);return b;}
    private String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}private String code(String s){return s.toUpperCase(Locale.ROOT).replace(' ','_');}
    private void notice(String text,boolean error){Ui.notice(feedback,text,error);}
    private boolean hasDraft(){return !subject.getText().isBlank()||!body.getText().isBlank()||!relatedId.getText().isBlank()||threadViews.values().stream().anyMatch(t->!t.reply.getText().isBlank());}
    private <T> void run(Callable<T> work,Consumer<T> success){run(VIEW,work,success);}
    private <T> void run(MessageErrors.Action action,Callable<T> work,Consumer<T> success){
        if(!usable()||busy.get())return;long requestedEpoch=epoch;busy.set(true);
        CompletableFuture.supplyAsync(()->{try{return work.call();}catch(Exception ex){throw new CompletionException(ex);}},io).whenComplete((r,f)->Platform.runLater(()->{
            if(closed||!api.isActive())return;busy.set(false);
            if(active.getAsBoolean()&&requestedEpoch==epoch){if(f!=null){
                String reason=MessageErrors.describe(f,action,hasDraft());notice(reason,true);
                if(action==LIST){permissionDenied=MessageErrors.forbidden(f);history.getItems().clear();history.setPlaceholder(Ui.text(MessageErrors.describe(f,action,false),"empty-state"));pageLabel.setText("Messages unavailable");}
                threadViews.values().forEach(t->{if(t.dialog.isShowing()){Ui.notice(t.local,MessageErrors.describe(f,action,!t.reply.getText().isBlank()),true);if(action==READ&&MessageErrors.forbidden(f))t.readDenied=true;}});
            }else{if(feedback.getStyleClass().contains("notice-error"))feedback.setText("");success.accept(r);}}
            if(!usable()){pendingSelection=null;reloadPending=false;return;}
            if(!busy.get()&&pendingSelection!=null){Selection selected=pendingSelection;pendingSelection=null;open(selected.conversation(),selected.post());}
            else if(!busy.get()&&reloadPending){reloadPending=false;load();}
        }));
    }
    @Override public void close(){closed=true;version++;deactivate();dialogs.clear();threadViews.clear();subject.clear();body.clear();relatedId.clear();history.getItems().clear();target.getItems().clear();branchFilter.getItems().clear();cashierFilter.getItems().clear();sendKey=null;draftSignature=null;}
}
