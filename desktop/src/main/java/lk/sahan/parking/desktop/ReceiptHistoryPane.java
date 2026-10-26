package lk.sahan.parking.desktop;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

final class ReceiptHistoryPane {
 private final ApiClient api;private final ExecutorService io;private final BooleanSupplier active;private final boolean admin;
 private final TableView<JsonNode> table=new TableView<>();private final TextField search=new TextField();
 private final DatePicker from=new DatePicker(),to=new DatePicker();private final ComboBox<JsonNode> branch=new ComboBox<>();
 private final Label feedback=Ui.notice(),paging=Ui.text("","muted");private final Button refresh=Ui.button("Search / Refresh","secondary"),previous=Ui.button("Previous","secondary"),next=Ui.button("Next","secondary");
 private final VBox view;private int page,pages;private long epoch;
 ReceiptHistoryPane(ApiClient api,ExecutorService io,boolean admin,BooleanSupplier active){
  this.api=api;this.io=io;this.admin=admin;this.active=active;
  search.setPromptText("Ticket number or plate");
  table.getColumns().setAll(Ui.column("Receipt","paymentId",80),Ui.column("Ticket","sessionId",80),Ui.column("Plate","plate",125),Ui.column("Branch","branch",170),Ui.column("Amount · LKR","amount",120),Ui.column("Method","method",120),Ui.column("Paid at","exitedAt",180));
  Ui.table(table,"Search to load receipts.",360);Ui.fillColumns(table);
  Button details=Ui.button("Details / Reprint","primary");details.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
  details.setOnAction(e->{JsonNode row=table.getSelectionModel().getSelectedItem();if(row==null)return;details.disableProperty().unbind();details.setDisable(true);
   CompletableFuture.supplyAsync(()->{try{return api.post((admin?"/api/admin/receipts/":"/api/receipts/")+row.path("sessionId").asLong()+"/reprints",java.util.Map.of());}catch(Exception ex){throw new CompletionException(ex);}},io).whenComplete((receipt,error)->Platform.runLater(()->{details.disableProperty().unbind();details.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());if(!api.isActive()||!active.getAsBoolean())return;if(error!=null)Ui.notice(feedback,error.getCause().getMessage(),true);else ThermalDocuments.receipt(receipt,view().getScene().getWindow(),true);}));});
  refresh.setOnAction(e->{page=0;load();});search.setOnAction(e->refresh.fire());previous.setOnAction(e->{page--;load();});next.setOnAction(e->{page++;load();});
  branch.setPromptText("All branches");branch.setCellFactory(c->branchCell());branch.setButtonCell(branchCell());
  var filters=Ui.flow(Ui.field("Ticket / Plate",search),Ui.field("From date",from),Ui.field("To date",to),refresh);
  if(admin)filters.getChildren().add(Ui.field("Branch",branch));
  view=Ui.page(Ui.section("Receipt history","Original stored payment amounts and dates. Duplicate copies are marked REPRINT.",filters,feedback,table,Ui.flow(details,previous,paging,next)));
 }
 private ListCell<JsonNode> branchCell(){return new ListCell<>(){@Override protected void updateItem(JsonNode row,boolean empty){super.updateItem(row,empty);setText(empty||row==null?"All branches":row.path("code").asText()+" · "+row.path("name").asText());}};}
 VBox view(){return view;}
 void activate(){
  if(admin&&branch.getItems().isEmpty())CompletableFuture.supplyAsync(()->{try{return api.get("/api/admin/branches?includeDeleted=true");}catch(Exception e){throw new CompletionException(e);}},io).whenComplete((rows,error)->Platform.runLater(()->{if(!active.getAsBoolean())return;if(error!=null)Ui.notice(feedback,error.getCause().getMessage(),true);else{branch.getItems().add(null);rows.forEach(branch.getItems()::add);}}));
  load();
 }
 void deactivate(){epoch++;}
 private void load(){
  long request=++epoch;String path=(admin?"/api/admin/receipts":"/api/receipts")+"?page="+page+"&search="+URLEncoder.encode(search.getText().trim(),StandardCharsets.UTF_8);
  if(from.getValue()!=null)path+="&from="+from.getValue();if(to.getValue()!=null)path+="&to="+to.getValue();if(admin&&branch.getValue()!=null)path+="&branchId="+branch.getValue().path("id").asLong();
  refresh.setDisable(true);feedback.setText("Loading receipts...");final String url=path;
  CompletableFuture.supplyAsync(()->{try{return api.get(url);}catch(Exception e){throw new CompletionException(e);}},io).whenComplete((result,error)->Platform.runLater(()->{
   if(request!=epoch||!api.isActive()||!active.getAsBoolean())return;refresh.setDisable(false);
   if(error!=null){table.getItems().clear();table.setPlaceholder(Ui.text("Receipts unavailable"));Ui.notice(feedback,error.getCause().getMessage(),true);return;}
   table.getItems().clear();result.path("rows").forEach(table.getItems()::add);pages=result.path("pages").asInt();table.setPlaceholder(Ui.text("No receipts match these filters."));feedback.setText("");
   paging.setText("Page "+(page+1)+" / "+Math.max(1,pages)+" · "+result.path("total").asText()+" receipts");previous.setDisable(page<=0);next.setDisable(page+1>=pages);
  }));
 }
}
