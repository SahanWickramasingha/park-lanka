package lk.sahan.parking.desktop;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

final class ShiftsPane {
 private final javafx.beans.property.ReadOnlyObjectWrapper<Boolean> activeShift=new javafx.beans.property.ReadOnlyObjectWrapper<>();
 javafx.beans.property.ReadOnlyObjectProperty<Boolean> activeShiftProperty(){return activeShift.getReadOnlyProperty();}
 private long currentEpoch;
 void updateCurrent(JsonNode state){currentEpoch++;applyCurrent(state);}
 private boolean reloadPending,currentPending;
 private Runnable opened=()->{};
 void onOpened(Runnable action){opened=action;}
 private final ApiClient api;private final ExecutorService io;private final boolean admin;private final BooleanSupplier active;private final Consumer<String> status;
 private final TableView<JsonNode> table=new TableView<>();private final Label feedback=Ui.notice(),summary=Ui.text("","muted"),paging=Ui.text("","muted");
 private final TextField opening=new TextField(),counted=new TextField(),reason=new TextField(),cashier=new TextField();private final ComboBox<JsonNode> branch=new ComboBox<>();
 private final VBox view;private JsonNode current;private int page,pages;
 private final javafx.beans.property.BooleanProperty busy=new javafx.beans.property.SimpleBooleanProperty();
 private String openKey=UUID.randomUUID().toString(),closeKey=UUID.randomUUID().toString();
 ShiftsPane(ApiClient api,ExecutorService io,boolean admin,BooleanSupplier active,Consumer<String> status){
  this.api=api;this.io=io;this.admin=admin;this.active=active;this.status=status;
  table.getColumns().setAll(Ui.column("Shift","id",70),Ui.column("Cashier","cashier",120),Ui.column("Branch","branch",155),Ui.column("Opened","openedAt",160),Ui.column("Closed","closedAt",160),Ui.column("Expected cash","expectedCash",115),Ui.column("Difference","difference",100));Ui.table(table,"Load shift history.",320);Ui.fillColumns(table);
  opening.setPromptText("0.00");counted.setPromptText("Actual drawer cash");reason.setPromptText("Required when counted and expected differ");cashier.setPromptText("Exact cashier username");
  Button open=Ui.button("Open Shift","primary"),close=Ui.button("Close Shift","primary"),refresh=Ui.button("Refresh","secondary"),previous=Ui.button("Previous","secondary"),next=Ui.button("Next","secondary");
  open.disableProperty().bind(busy.or(activeShift.isEqualTo(Boolean.TRUE)));
  close.disableProperty().bind(busy);refresh.disableProperty().bind(busy);opening.disableProperty().bind(busy);
  open.setOnAction(e->openShift());
  close.setOnAction(e->{if(current==null){Ui.notice(feedback,"Select/refresh the active shift first.",true);return;}try{BigDecimal amount=new BigDecimal(counted.getText());long id=current.path("id").asLong();String explanation=reason.getText().trim();Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,"Close shift #"+id+" with counted cash LKR "+amount+"? Further payments require a new shift.",ButtonType.OK,ButtonType.CANCEL);Ui.dialog(confirm,view().getScene().getWindow());confirm.showAndWait().filter(b->b==ButtonType.OK).ifPresent(b->call(()->api.post(prefix()+"/"+id+"/close",Map.of("requestKey",closeKey,"countedCash",amount,"reason",explanation)),result->{if(!admin){current=null;activeShift.set(false);publishCurrent();}closeKey=UUID.randomUUID().toString();counted.clear();reason.clear();load();ThermalDocuments.text("Shift closing summary",body(result),view().getScene().getWindow());}));}catch(Exception ex){Ui.notice(feedback,"Enter valid counted cash.",true);}});
  refresh.setOnAction(e->{page=0;load();});previous.setOnAction(e->{if(page>0){page--;load();}});next.setOnAction(e->{if(page+1<pages){page++;load();}});
  Button print=Ui.button("View / Print summary","secondary"),export=Ui.button("Export summary","secondary");print.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());export.disableProperty().bind(print.disableProperty());
  print.setOnAction(e->{JsonNode row=table.getSelectionModel().getSelectedItem();call(()->api.get(prefix()+"/"+row.path("id").asLong()),result->ThermalDocuments.text("Shift summary",body(result),view().getScene().getWindow()));});
  export.setOnAction(e->{JsonNode row=table.getSelectionModel().getSelectedItem();FileChooser chooser=new FileChooser();chooser.setInitialFileName("shift-"+row.path("id").asText()+".txt");var file=chooser.showSaveDialog(view().getScene().getWindow());if(file!=null)call(()->{JsonNode result=api.get(prefix()+"/"+row.path("id").asLong());Files.writeString(file.toPath(),body(result),StandardCharsets.UTF_8);return result;},r->Ui.notice(feedback,"Summary exported to "+file.getAbsolutePath(),false));});
  var controls=Ui.flow(refresh,previous,paging,next,print,export);
  if(admin)table.getSelectionModel().selectedItemProperty().addListener((o,old,row)->current=row);
  VBox actions=admin?Ui.section("Reconcile a cashier shift","Select an active shift. Administrators may close a stopped/removed cashier's shift after counting the drawer and providing a reason. Original closed totals remain fixed.",Ui.flow(Ui.field("Counted cash · LKR",counted),Ui.field("Required admin reason",reason),close)):Ui.section("Current cashier shift","Payments require your active shift. Opening cash is separate from collections; cash tendered including change is never counted as revenue.",summary,Ui.flow(Ui.field("Opening cash · LKR",opening),open),Ui.flow(Ui.field("Counted cash · LKR",counted),Ui.field("Difference explanation",reason),close));
  branch.setPromptText("All branches");branch.setCellFactory(c->branchCell());branch.setButtonCell(branchCell());if(admin)controls.getChildren().addAll(Ui.field("Branch",branch),Ui.field("Cashier",cashier));
  view=Ui.page(feedback,actions,Ui.section("Shift history","Closed summaries preserve their closing totals. Legacy payments have no fabricated shift.",controls,table));
 }
 VBox view(){return view;}
 private ListCell<JsonNode> branchCell(){return new ListCell<>(){@Override protected void updateItem(JsonNode r,boolean empty){super.updateItem(r,empty);setText(empty||r==null?"All branches":r.path("code").asText()+" · "+r.path("name").asText());}};}
 private String prefix(){return admin?"/api/admin/shifts":"/api/shifts";}
 interface Work {JsonNode run()throws Exception;}
 private void call(Work work,Consumer<JsonNode> success){call(work,success,error->Ui.notice(feedback,error.getMessage()+" Refresh before retrying; the same action key is retained.",true));}
 private void call(Work work,Consumer<JsonNode> success,Consumer<Throwable> failure){
  if(busy.get())return;busy.set(true);feedback.setText("Working...");
  CompletableFuture.supplyAsync(()->{try{return work.run();}catch(Exception ex){throw new CompletionException(ex);}},io).whenComplete((result,error)->Platform.runLater(()->{
   busy.set(false);if(!active.getAsBoolean()||!api.isActive())return;
   if(error!=null)failure.accept(error.getCause());else{feedback.setText("");success.accept(result);}
   if(!busy.get()){if(currentPending){currentPending=false;refreshCurrent();}else if(reloadPending){reloadPending=false;load();}}
  }));
 }
 private void openShift(){
  if(busy.get())return;
  final BigDecimal amount;
  try{amount=new BigDecimal(opening.getText().trim());if(amount.signum()<0||amount.scale()>2||amount.compareTo(new BigDecimal("9999999999.99"))>0)throw new NumberFormatException();}
  catch(NumberFormatException error){Ui.notice(feedback,"Opening cash must be a nonnegative amount with at most two decimals (maximum LKR 9999999999.99).",true);return;}
  call(()->{
   try{api.post("/api/shifts",Map.of("requestKey",openKey,"openingCash",amount));}
   catch(ApiClient.HttpFailure error){if(error.details()==null||!error.details().path("code").asText().equals("SHIFT_ALREADY_OPEN"))throw error;}
   // A replay may refer to a shift already closed. Only the authenticated current-shift endpoint authorizes the UI.
   return api.get("/api/shifts/current");
  },result->{
   applyCurrent(result);
   if(Boolean.TRUE.equals(activeShift.get())){openKey=UUID.randomUUID().toString();opening.clear();opened.run();}
   else {openKey=UUID.randomUUID().toString();Ui.notice(feedback,"No active shift remains. Review the opening cash and click Open Shift to start a new shift.",true);}
  },error->{activeShift.set(null);status.accept("Active shift needs verification. Refresh Parking before checkout.");Ui.notice(feedback,error.getMessage()+" Opening cash is preserved. Refresh to verify the current shift before retrying; the same action key is retained.",true);});
 }
 private void applyCurrent(JsonNode result){current=result.path("active").asBoolean()?result.path("shift"):null;activeShift.set(current!=null);publishCurrent();}
 private void publishCurrent(){
  String text=Boolean.FALSE.equals(activeShift.get())?"Open a cashier shift before checkout. Review and confirm checkout after opening it.":
   current==null?"Checking active shift...":"Continue active shift #"+current.path("id").asText()+" · "+current.path("branch").asText()+" · Opening cash LKR "+current.path("openingCash").asText()+" · Expected cash LKR "+current.path("expectedCash").asText();
  summary.setText(text);status.accept(text);
 }
 void refreshCurrent(){refreshCurrent(()->{});}
 private void refreshCurrent(Runnable after){
  if(admin)return;
  if(busy.get()){currentPending=true;return;}
  activeShift.set(null);current=null;publishCurrent();
  long request=++currentEpoch;
  call(()->api.get("/api/shifts/current"),result->{if(request==currentEpoch)applyCurrent(result);after.run();},error->{
   if(request!=currentEpoch)return;
   String text="Could not check active shift: "+error.getMessage()+" Refresh Parking before checkout.";summary.setText(text);status.accept(text);Ui.notice(feedback,text,true);
  });
 }
 void activate(){if(admin&&branch.getItems().isEmpty())call(()->api.get("/api/admin/branches?includeDeleted=true"),rows->{branch.getItems().add(null);rows.forEach(branch.getItems()::add);load();});else load();}
 void load(){
  if(busy.get()){reloadPending=true;return;}
  if(!admin){refreshCurrent(this::loadHistory);return;}
  loadHistory();
 }
 private void loadHistory(){
  String path=prefix()+"?page="+page;if(admin&&branch.getValue()!=null)path+="&branchId="+branch.getValue().path("id").asLong();if(admin&&!cashier.getText().isBlank())path+="&cashier="+java.net.URLEncoder.encode(cashier.getText().trim(),StandardCharsets.UTF_8);String url=path;
  call(()->api.get(url),rows->{table.getItems().clear();rows.path("rows").forEach(table.getItems()::add);pages=rows.path("pages").asInt();paging.setText("Page "+(page+1)+" / "+Math.max(1,pages));});
 }
 static String body(JsonNode r){StringBuilder out=new StringBuilder("PARK LANKA · SHIFT #"+r.path("id").asText()+"\n");for(String field:List.of("cashier","branch","openedAt","closedAt","openingCash","cashCollections","cardCollections","cashRefunds","cashVoids","expectedCash","countedCash","difference","reason")){JsonNode value=r.path(field);out.append(field).append(": ").append(value.isNull()?"—":field.endsWith("At")?Ui.date(value.asText()):value.asText()).append('\n');}return out.toString();}
}
