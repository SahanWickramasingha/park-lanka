package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Two views over existing sessions/payments; the server determines cashier branch scope. */
final class ParkingHistoryPane {
 private final ApiClient api;private final ExecutorService io;private final BooleanSupplier active;
 private final TabPane tabs=new TabPane();private final HistoryList entries,checkouts;private final VBox view;
 ParkingHistoryPane(ApiClient api,ExecutorService io,BooleanSupplier active){
  this.api=api;this.io=io;this.active=active;entries=new HistoryList(false);checkouts=new HistoryList(true);
  Tab entryTab=new Tab("Vehicle Entries",entries.view),checkoutTab=new Tab("Completed Checkouts",checkouts.view);entryTab.setClosable(false);checkoutTab.setClosable(false);tabs.getTabs().addAll(entryTab,checkoutTab);
  tabs.getSelectionModel().selectedItemProperty().addListener((o,old,next)->{entries.invalidate();checkouts.invalidate();if(active.getAsBoolean())selected().load();});
  view=Ui.page(Ui.text("Parking History","page-title"),Ui.text("Entry dates and payment dates use Asia/Colombo time. Saved entries and receipts remain available after slot reuse.","muted"),tabs);
 }
 VBox view(){return view;}
 private HistoryList selected(){return tabs.getSelectionModel().getSelectedIndex()==1?checkouts:entries;}
 void activate(){selected().load();}
 void deactivate(){entries.invalidate();checkouts.invalidate();}
 void refresh(){deactivate();if(active.getAsBoolean())selected().load();}
 private final class HistoryList {
  final boolean completed;final TableView<JsonNode> table=new TableView<>();final TextField search=new TextField(),cashier=new TextField();
  final DatePicker from=new DatePicker(LocalDate.now(ZoneId.of("Asia/Colombo"))),to=new DatePicker(LocalDate.now(ZoneId.of("Asia/Colombo")));
  final Label feedback=Ui.notice(),paging=Ui.text("","muted");final BooleanProperty busy=new SimpleBooleanProperty();
  final Button refresh=Ui.button("Refresh History","secondary"),previous=Ui.button("Previous","secondary"),next=Ui.button("Next","secondary");
  final VBox view;int page,pages;long epoch;
  HistoryList(boolean completed){
   this.completed=completed;search.setPromptText("Ticket number or plate");cashier.setPromptText("Optional exact username");
   if(completed)table.getColumns().setAll(Ui.column("Receipt","paymentId",80),Ui.column("Ticket","sessionId",75),Ui.column("Plate","plate",120),Ui.column("Slot","slot",85),Ui.column("Entry","enteredAt",175),Ui.column("Exit","exitedAt",175),Ui.column("Duration","elapsedSeconds",110),Ui.column("Fee · LKR","amount",105),Ui.column("Method","method",115),Ui.column("Cash received","cashTendered",110),Ui.column("Change","changeDue",95),Ui.column("Checkout cashier","cashier",140),Ui.column("Status","status",135),Ui.column("Refunded","refunded",100),Ui.column("Voided","voided",100));
   else table.getColumns().setAll(Ui.column("Ticket","id",75),Ui.column("Plate","plate",125),Ui.column("Vehicle","vehicleType",110),Ui.column("Slot","slot",85),Ui.column("Entry time","enteredAt",175),Ui.column("Entry cashier","enteredBy",140),Ui.column("Status","status",110));
   Ui.table(table,"Loading history...",340);Ui.fillColumns(table);
   Button details=Ui.button(completed?"View Receipt":"View Details","primary"),reprint=Ui.button(completed?"Reprint Receipt":"Reprint Entry Ticket","secondary");
   details.disableProperty().bind(busy.or(table.getSelectionModel().selectedItemProperty().isNull()));reprint.disableProperty().bind(details.disableProperty());refresh.disableProperty().bind(busy);
   details.setOnAction(e->{JsonNode row=table.getSelectionModel().getSelectedItem();if(row==null)return;long id=row.path(completed?"sessionId":"id").asLong();
    action(()->api.get(completed?"/api/receipts/"+id:"/api/parking-history/entries/"+id),result->{
     if(completed)ThermalDocuments.receipt(result,owner(),false);
     else {StringBuilder body=new StringBuilder();for(String key:List.of("id","plate","vehicleType","branch","slot","enteredAt","enteredBy","exitedAt","status","pricingSummary"))body.append(key).append(": ").append(Ui.display(result.path(key),key)).append('\n');ThermalDocuments.text("Entry #"+id,body.toString(),owner());}
    });
   });
   reprint.setOnAction(e->{JsonNode row=table.getSelectionModel().getSelectedItem();if(row==null)return;long id=row.path(completed?"sessionId":"id").asLong();
    action(()->api.post(completed?"/api/receipts/"+id+"/reprints":"/api/sessions/"+id+"/ticket/reprints",Map.of()),result->{if(completed)ThermalDocuments.receipt(result,owner(),true);else ThermalDocuments.ticket(result,owner(),true);});
   });
   refresh.setOnAction(e->{page=0;load();});search.setOnAction(e->refresh.fire());cashier.setOnAction(e->refresh.fire());
   previous.setOnAction(e->{if(page>0){page--;load();}});next.setOnAction(e->{if(page+1<pages){page++;load();}});
   previous.setDisable(true);next.setDisable(true);
   view=Ui.section(completed?"Completed Checkouts":"Vehicle Entries",completed?"Original saved charges and cash/change. Refunds and voids do not overwrite original payments.":"All entry records, including vehicles that have since checked out.",
    Ui.flow(Ui.field("Ticket / Plate",search),Ui.field("Cashier",cashier),Ui.field("From date",from),Ui.field("To date",to),refresh),feedback,table,Ui.flow(details,reprint,previous,paging,next));
  }
  javafx.stage.Window owner(){return view.getScene().getWindow();}
  boolean current(){return api.isActive()&&active.getAsBoolean()&&selected()==this;}
  void invalidate(){epoch++;busy.set(false);}
  void load(){
   long request=++epoch;if(!current())return;
   if(from.getValue()==null||to.getValue()==null||from.getValue().isAfter(to.getValue())){Ui.notice(feedback,"Choose a valid From and To date range.",true);return;}
   busy.set(true);feedback.setText("Loading history...");previous.setDisable(true);next.setDisable(true);
   String url="/api/parking-history/"+(completed?"checkouts":"entries")+"?page="+page+"&size=25&search="+encode(search.getText())+"&cashier="+encode(cashier.getText())+"&from="+from.getValue()+"&to="+to.getValue();
   CompletableFuture.supplyAsync(()->{try{return api.get(url);}catch(Exception e){throw new CompletionException(e);}},io).whenComplete((result,error)->Platform.runLater(()->{
    if(request!=epoch||!current())return;busy.set(false);table.getItems().clear();
    if(error!=null){table.setPlaceholder(Ui.text("History unavailable. Use Refresh History to retry."));Ui.notice(feedback,error.getCause().getMessage(),true);return;}
    result.path("rows").forEach(table.getItems()::add);pages=result.path("pages").asInt();table.setPlaceholder(Ui.text("No records match these filters."));feedback.setText("");
    paging.setText("Page "+(page+1)+" / "+Math.max(1,pages)+" · "+result.path("total").asText()+" records");previous.setDisable(page<=0);next.setDisable(page+1>=pages);
   }));
  }
  void action(Callable<JsonNode> work,Consumer<JsonNode> success){
   if(busy.get())return;busy.set(true);long request=++epoch;feedback.setText("Loading saved record...");
   CompletableFuture.supplyAsync(()->{try{return work.call();}catch(Exception e){throw new CompletionException(e);}},io).whenComplete((result,error)->Platform.runLater(()->{
    if(request!=epoch||!current())return;busy.set(false);
    if(error!=null)Ui.notice(feedback,error.getCause().getMessage(),true);else{feedback.setText("");try{success.accept(result);}catch(RuntimeException e){Ui.notice(feedback,"Saved record is unchanged. Preview unavailable: "+e.getMessage(),true);}}
   }));
  }
 }
 private static String encode(String value){return URLEncoder.encode(value.trim(),StandardCharsets.UTF_8);}
}
