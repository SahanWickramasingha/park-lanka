package lk.sahan.parking.desktop;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class AuditPane {
 private final ApiClient api;private final ExecutorService io;private final BooleanSupplier active;
 private final TableView<JsonNode> table=new TableView<>();private final TextField actor=new TextField(),action=new TextField();private final DatePicker from=new DatePicker(),to=new DatePicker();private final ComboBox<JsonNode> branch;
 private final Label feedback=Ui.notice(),paging=Ui.text("","muted");private final VBox view;private int page,pages;private long epoch;
 AuditPane(ApiClient api,ExecutorService io,ObservableList<JsonNode> branches,BooleanSupplier active){
  this.api=api;this.io=io;this.active=active;branch=new ComboBox<>(branches);branch.setPromptText("All branches");branch.setCellFactory(c->cell());branch.setButtonCell(cell());
  actor.setPromptText("Exact username");action.setPromptText("Exact action, e.g. SHIFT_CLOSED");
  table.getColumns().setAll(Ui.column("Time","occurredAt",165),Ui.column("Actor","actor",120),Ui.column("Action","action",180),Ui.column("Target","target",150),Ui.column("Branch ID","branchId",80),Ui.column("Reason","reason",200));Ui.table(table,"Load audit history.",320);Ui.fillColumns(table);
  TextArea details=new TextArea();details.setEditable(false);details.setWrapText(true);details.setPrefRowCount(6);table.getSelectionModel().selectedItemProperty().addListener((o,old,row)->details.setText(row==null?"":"Before: "+row.path("beforeDetails").asText("—")+"\nAfter: "+row.path("afterDetails").asText("—")));
  Button load=Ui.button("Search / Refresh","primary"),prev=Ui.button("Previous","secondary"),next=Ui.button("Next","secondary"),all=Ui.button("All branches","secondary");load.setOnAction(e->{page=0;activate();});prev.setOnAction(e->{if(page>0){page--;activate();}});next.setOnAction(e->{if(page+1<pages){page++;activate();}});all.setOnAction(e->branch.setValue(null));
  view=Ui.page(Ui.section("Audit history","Committed actions only. Historical records remain available after branches or cashiers are archived. Reprint requests record intent; printer completion is not assumed.",Ui.flow(Ui.field("Branch",branch),all,Ui.field("Actor",actor),Ui.field("Action",action),Ui.field("From",from),Ui.field("To",to),load),feedback,table,Ui.flow(prev,paging,next),details));
 }
 VBox view(){return view;}
 private ListCell<JsonNode> cell(){return new ListCell<>(){@Override protected void updateItem(JsonNode r,boolean empty){super.updateItem(r,empty);setText(empty||r==null?"All branches":r.path("code").asText()+" · "+r.path("name").asText());}};}
 void activate(){long request=++epoch;String path="/api/admin/audit?page="+page+"&actor="+URLEncoder.encode(actor.getText().trim(),StandardCharsets.UTF_8)+"&action="+URLEncoder.encode(action.getText().trim(),StandardCharsets.UTF_8);if(branch.getValue()!=null)path+="&branchId="+branch.getValue().path("id").asLong();if(from.getValue()!=null)path+="&from="+from.getValue();if(to.getValue()!=null)path+="&to="+to.getValue();String url=path;feedback.setText("Loading audit history...");CompletableFuture.supplyAsync(()->{try{return api.get(url);}catch(Exception ex){throw new CompletionException(ex);}},io).whenComplete((rows,error)->Platform.runLater(()->{if(!active.getAsBoolean()||!api.isActive()||request!=epoch)return;if(error!=null){table.getItems().clear();table.setPlaceholder(Ui.text("Audit history unavailable"));Ui.notice(feedback,error.getCause().getMessage(),true);return;}feedback.setText("");table.getItems().clear();rows.path("rows").forEach(table.getItems()::add);table.setPlaceholder(Ui.text("No audit records match these filters."));pages=rows.path("pages").asInt();paging.setText("Page "+(page+1)+" / "+Math.max(1,pages));}));}
}
