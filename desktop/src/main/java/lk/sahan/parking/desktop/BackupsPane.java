package lk.sahan.parking.desktop;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

final class BackupsPane {
 private final ApiClient api;private final ExecutorService io;private final BooleanSupplier active;private final TableView<JsonNode> table=new TableView<>();private final Label feedback=Ui.notice(),location=Ui.text("","muted"),paging=Ui.text("","muted");private final VBox view;private boolean maintenance,busy;private int page,pages;
 BackupsPane(ApiClient api,ExecutorService io,BooleanSupplier active){
  this.api=api;this.io=io;this.active=active;table.getColumns().setAll(Ui.column("Job","id",60),Ui.column("Kind","kind",110),Ui.column("Status","status",130),Ui.column("Engine","engine",100),Ui.column("Started","createdAt",165),Ui.column("Bytes","sizeBytes",100),Ui.column("Failure","failure",220));Ui.table(table,"Load backup history.",320);Ui.fillColumns(table);
  TextArea details=new TextArea();details.setEditable(false);details.setWrapText(true);details.setPrefRowCount(5);table.getSelectionModel().selectedItemProperty().addListener((o,old,row)->details.setText(row==null?"":"Server archive: "+row.path("fileName").asText("—")+"\nRestore plan: "+row.path("restorePlan").asText("—")+"\n"+row.path("failure").asText("")+"\nRestore never overwrites a live database. Use the approved plan with backend/restore.ps1 after stopping the correct backend; restore to a new destination and validate it first."));
  Button backup=Ui.button("Back up now","primary"),refresh=Ui.button("Refresh status","secondary"),toggle=Ui.button("Enter / Leave maintenance","warning"),restore=Ui.button("Prepare restore plan","danger"),prev=Ui.button("Previous","secondary"),next=Ui.button("Next","secondary");
  backup.setOnAction(e->call(()->api.post("/api/admin/backups",Map.of()),r->{Ui.notice(feedback,"Backup queued. Refresh status to see completion.",false);activate();}));refresh.setOnAction(e->activate());prev.setOnAction(e->{if(page>0){page--;activate();}});next.setOnAction(e->{if(page+1<pages){page++;activate();}});
  toggle.setOnAction(e->{Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,(maintenance?"Leave maintenance and resume parking operations?":"Pause parking operations? All shifts must be closed and no vehicles parked."),ButtonType.OK,ButtonType.CANCEL);Ui.dialog(confirm,view().getScene().getWindow());confirm.showAndWait().filter(b->b==ButtonType.OK).ifPresent(b->call(()->api.post("/api/admin/maintenance",Map.of("enabled",!maintenance,"confirmation","CHANGE MAINTENANCE")),r->activate()));});
  restore.setOnAction(e->{JsonNode row=table.getSelectionModel().getSelectedItem();if(row==null){Ui.notice(feedback,"Select a retained successful backup.",true);return;}long id=row.path("id").asLong();TextInputDialog confirm=new TextInputDialog();Ui.dialog(confirm,view().getScene().getWindow());confirm.setHeaderText("Type PREPARE RESTORE "+id+". Maintenance and a pre-restore backup are required.");confirm.showAndWait().ifPresent(text->call(()->api.post("/api/admin/backups/"+id+"/restore-plan",Map.of("confirmation",text)),r->{Ui.notice(feedback,"Restore preparation queued. Refresh for the approved server plan. No live restore is performed.",false);activate();}));});
  view=Ui.page(Ui.section("Database backup & recovery","Backups are stored on the backend server. They are not downloaded or saved on this desktop. H2 uses consistent database BACKUP; PostgreSQL requires matching pg_dump/pg_restore tools on the server.",location,Ui.flow(backup,refresh,toggle,restore),feedback,table,Ui.flow(prev,paging,next),details));
 }
 VBox view(){return view;}
 interface Work {JsonNode run()throws Exception;}
 private void call(Work work,java.util.function.Consumer<JsonNode> success){if(busy)return;busy=true;CompletableFuture.supplyAsync(()->{try{return work.run();}catch(Exception ex){throw new CompletionException(ex);}},io).whenComplete((result,error)->Platform.runLater(()->{busy=false;if(!api.isActive()||!active.getAsBoolean())return;if(error!=null)Ui.notice(feedback,error.getCause().getMessage(),true);else success.accept(result);}));}
 void activate(){call(()->api.get("/api/admin/backups?page="+page),rows->{table.getItems().clear();rows.path("rows").forEach(table.getItems()::add);maintenance=rows.path("maintenance").asBoolean();pages=rows.path("pages").asInt();location.setText("Backend server: "+rows.path("serverDirectory").asText()+" · "+(maintenance?"MAINTENANCE":"Operations enabled")+(rows.path("busy").asBoolean()?" · Backup task running":""));paging.setText("Page "+(page+1)+" / "+Math.max(1,pages));});}
}
