package lk.sahan.parking.desktop;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

final class PricingRulesPane {
 private final ApiClient api;private final ExecutorService io;private final ComboBox<JsonNode> branch;
 private final TableView<JsonNode> table=new TableView<>();private final Label feedback=Ui.notice();private final VBox view;private long epoch;
 PricingRulesPane(ApiClient api,ExecutorService io,ObservableList<JsonNode> branches){
  this.api=api;this.io=io;branch=new ComboBox<>(branches);branch.setPromptText("Choose branch");branch.setCellFactory(c->cell());branch.setButtonCell(cell());
  ComboBox<String> type=new ComboBox<>();type.getItems().setAll("DEFAULT","CAR","VAN","MOTORCYCLE","THREE_WHEELER","OTHER");type.setValue("DEFAULT");ComboBox<String> unit=new ComboBox<>();unit.getItems().setAll("MINUTE","HOUR");unit.setValue("MINUTE");
  TextField rate=new TextField(),grace=new TextField("0"),cap=new TextField();rate.setPromptText("LKR per unit");cap.setPromptText("Blank = no cap");
  table.getColumns().setAll(Ui.column("Vehicle","vehicleType",130),Ui.column("Rate","rate",100),Ui.column("Unit","unit",100),Ui.column("Grace min","graceMinutes",100),Ui.column("24h cap","dailyCap",100));Ui.table(table,"Choose a branch to load overrides.",200);Ui.fillColumns(table);
  Button save=Ui.button("Save policy","primary"),remove=Ui.button("Remove override","secondary"),refresh=Ui.button("Refresh policies","secondary");remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
  save.setOnAction(e->{if(branch.getValue()==null){Ui.notice(feedback,"Choose a branch.",true);return;}try{Map<String,Object> request=new HashMap<>();request.put("vehicleType",type.getValue());request.put("unit",unit.getValue());request.put("rate",new BigDecimal(rate.getText()));request.put("graceMinutes",Integer.parseInt(grace.getText()));request.put("dailyCap",cap.getText().isBlank()?null:new BigDecimal(cap.getText()));long id=branch.getValue().path("id").asLong();call(()->api.put("/api/admin/pricing/"+id,request));}catch(Exception ex){Ui.notice(feedback,"Enter a valid rate, whole grace minutes and optional cap.",true);}});
  remove.setOnAction(e->{JsonNode row=table.getSelectionModel().getSelectedItem();if(row!=null)call(()->api.delete("/api/admin/pricing/"+row.path("branchId").asLong()+"/"+row.path("vehicleType").asText()));});
  table.getSelectionModel().selectedItemProperty().addListener((o,old,row)->{if(row!=null){type.setValue(row.path("vehicleType").asText());unit.setValue(row.path("unit").asText());rate.setText(row.path("rate").asText());grace.setText(row.path("graceMinutes").asText());cap.setText(row.path("dailyCap").isNull()?"":row.path("dailyCap").asText());}});
  branch.setOnAction(e->load());refresh.setOnAction(e->load());
  view=Ui.section("Branch & vehicle pricing","Branch + vehicle override → branch default → global per-minute fallback. Every new entry saves the full effective policy; existing tickets keep their original policy.",Ui.flow(Ui.field("Branch",branch),refresh),table,
   Ui.flow(Ui.field("Vehicle / default",type),Ui.field("Billing unit",unit),Ui.field("Rate · LKR",rate),Ui.field("Grace minutes (0–1440)",grace),Ui.field("Optional 24h cap · LKR",cap)),Ui.flow(save,remove),
   Ui.text("Within grace: free. After grace: round remaining elapsed time up to the chosen unit. Zero grace: minimum one unit. A cap applies separately to each consecutive 24-hour block from entry, including a partial block; midnight does not reset it.","muted"),feedback);
 }
 VBox view(){return view;}
 private ListCell<JsonNode> cell(){return new ListCell<>(){@Override protected void updateItem(JsonNode r,boolean empty){super.updateItem(r,empty);setText(empty||r==null?"Choose branch":r.path("code").asText()+" · "+r.path("name").asText());}};}
 interface Work {JsonNode run()throws Exception;}
 private void call(Work work){feedback.setText("Saving policy...");CompletableFuture.supplyAsync(()->{try{return work.run();}catch(Exception e){throw new CompletionException(e);}},io).whenComplete((r,error)->Platform.runLater(()->{if(!api.isActive())return;if(error!=null)Ui.notice(feedback,error.getCause().getMessage(),true);else{Ui.notice(feedback,"Saved. Applies to new entries only.",false);load();}}));}
 private void load(){if(branch.getValue()==null)return;long request=++epoch,id=branch.getValue().path("id").asLong();CompletableFuture.supplyAsync(()->{try{return api.get("/api/admin/pricing/"+id);}catch(Exception e){throw new CompletionException(e);}},io).whenComplete((rows,error)->Platform.runLater(()->{if(!api.isActive()||request!=epoch)return;if(error!=null){Ui.notice(feedback,error.getCause().getMessage(),true);return;}table.getItems().clear();rows.forEach(table.getItems()::add);}));}
}
