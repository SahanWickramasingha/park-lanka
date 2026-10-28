package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Stateful HTTP fixture: exercise the actual Parking screen, delayed snapshots and recovery UI. */
final class CheckoutParkingChecks {
 private static final ObjectMapper JSON=new ObjectMapper();
 private final Map<Long,JsonNode> parked=new LinkedHashMap<>(),receipts=new LinkedHashMap<>();
 private final Map<String,JsonNode> attempts=new HashMap<>();
 private final AtomicInteger posts=new AtomicInteger();
 private final AtomicBoolean failSlots=new AtomicBoolean(),holdSessions=new AtomicBoolean();
 private final CountDownLatch captured=new CountDownLatch(1),release=new CountDownLatch(1),oldSent=new CountDownLatch(1);
 private final AtomicReference<Throwable> fixtureError=new AtomicReference<>();
 private final AtomicBoolean failHistory=new AtomicBoolean();
 private final AtomicInteger historyRequests=new AtomicInteger();
 private final String recoveryKey=UUID.randomUUID().toString();
 private DesktopApp app;private Stage stage;private String endpoint;private PendingCheckoutStore store;
 static void run()throws Exception{new CheckoutParkingChecks().execute();}
 private void execute()throws Exception{
  parked.put(101L,vehicle(101,"ABC-1234","A-001"));parked.put(102L,vehicle(102,"DEF-5678","A-002"));
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService http=Executors.newCachedThreadPool();server.setExecutor(http);server.createContext("/",this::serve);server.start();
  endpoint="http://127.0.0.1:"+server.getAddress().getPort();ApiClient recovery=new ApiClient(endpoint,"checkout.parking.fixture","fixture-password");store=new PendingCheckoutStore(recovery);
  try{
   fx(()->{launch();return null;});login();await(()->ready()&&text("Continue active shift #51"));
   fx(()->{search().setText("ABC");table().getSelectionModel().select(0);button("Quote / Checkout").fire();return null;});await(()->dialog("Vehicle checkout")!=null);
   fx(()->{DialogPane p=dialog("Vehicle checkout");Window window=p.getScene().getWindow();check(((Stage)window).getOwner()==stage,"Checkout dialog has wrong owner");check(window.getHeight()<=Screen.getPrimary().getVisualBounds().getHeight(),"Checkout exceeds available screen height");Button confirm=descendants(p,Button.class).stream().filter(b->b.getText().equals("Confirm Payment & Print")).findFirst().orElseThrow();check(confirm.localToScene(confirm.getBoundsInLocal()).getMaxY()<=p.getScene().getHeight(),"Checkout confirmation is offscreen");DesktopUiSmoke.snapshotNode(p,"cashier-cash-checkout");return null;});
   fx(()->{((Button)dialog("Vehicle checkout").lookupButton(ButtonType.CANCEL)).fire();check(table().getItems().size()==1&&posts.get()==0,"Quote/cancel changed parking");return null;});
   fx(()->{button("Quote / Checkout").fire();return null;});await(()->dialog("Vehicle checkout")!=null);pay();
   await(()->dialog("Parking receipt")!=null&&text("Checkout completed; refresh failed")&&ready());
   fx(()->{
    for(String amount:List.of("LKR 350.00","LKR 500.00","LKR 150.00"))check(descendants(dialog("Parking receipt"),Label.class).stream().anyMatch(l->l.getText().equals(amount)),"Saved receipt missing "+amount);
    check(search().getText().equals("ABC")&&table().getItems().isEmpty(),"Completed vehicle remained or filter was discarded after failed refresh");
    check(table().getSelectionModel().getSelectedItem()==null,"Completed selection remained");
    check(slot().getItems().stream().anyMatch(r->r.path("code").asText().equals("A-001")),"Confirmed freed slot missing from entry dropdown");
    check(stats().subList(0,2).equals(List.of("2","1")),"Local free/occupied counts were not updated");
    check(!text("Request failed"),"Refresh failure described the payment as a request failure");close("Parking receipt");button("Refresh").fire();return null;
   });
   await(()->ready()&&stats().equals(List.of("2","1","1","350.00"))&&text("Expected cash LKR 450"));
   fx(()->{check(table().getItems().isEmpty()&&search().getText().equals("ABC"),"Authoritative refresh restored the vehicle/filter");return null;});
   fx(()->{search().setText("DEF");table().getSelectionModel().select(0);button("Quote / Checkout").fire();return null;});await(()->dialog("Vehicle checkout")!=null);
   fx(()->{check(!text("Checkout completed")&&!text("Request failed"),"A different checkout retained a stale success/error banner");((Button)dialog("Vehicle checkout").lookupButton(ButtonType.CANCEL)).fire();search().setText("ABC");return null;});
   // A fresh application instance has no local completed-session set: the server must exclude history.
   fx(()->{app.stop();stage.close();launch();return null;});login();await(()->ready()&&text("Expected cash LKR 450"));
   fx(()->{check(table().getItems().size()==1&&table().getItems().get(0).path("id").asLong()==102,"Completed vehicle returned after restart/login");nav("Receipt History").fire();return null;});
   await(()->table().getItems().size()==1&&button("Details / Reprint")!=null);
   fx(()->{table().getSelectionModel().select(0);button("Details / Reprint").fire();return null;});await(()->dialog("Parking receipt")!=null);
   fx(()->{check(posts.get()==1,"Reprinting submitted another payment");close("Parking receipt");nav("Parking").fire();return null;});await(()->ready()&&text("Continue active shift #51"));
   fx(()->{input("Number plate").setText("NEW-7777");slot().setValue(slot().getItems().stream().filter(r->r.path("code").asText().equals("A-001")).findFirst().orElseThrow());button("Register Entry").fire();return null;});
   await(()->dialog("Entry saved")!=null&&ready());
   fx(()->{close("Entry saved");check(table().getItems().stream().anyMatch(r->r.path("id").asLong()==103),"Freed slot did not accept a new vehicle");return null;});
   checkParkingHistory();
   JsonNode first; synchronized(this){first=receipts.get(101L);}
   store.save(101,Map.of("requestKey",first.path("requestKey").asText()));pending();await(()->dialog("Parking receipt")!=null&&ready());
   fx(()->{close("Parking receipt");check(posts.get()==1&&slot().getItems().stream().noneMatch(r->r.path("code").asText().equals("A-001")),"Confirmed replay charged again or freed a reoccupied slot");return null;});
   // Hold a real pre-payment refresh; recover a committed checkout through Pending checkouts while it waits.
   store.save(102,Map.of("requestKey",recoveryKey));holdSessions.set(true);
   fx(()->{search().setText("DEF");button("Refresh").fire();return null;});check(captured.await(10,TimeUnit.SECONDS),"Stale snapshot was not captured");
   pending();await(()->dialog("Parking receipt")!=null&&ready()&&stats().equals(List.of("2","1","2","700.00")));
   fx(()->{check(table().getItems().isEmpty()&&search().getText().equals("DEF"),"Recovered checkout did not remove the vehicle/preserve search");close("Parking receipt");return null;});
   release.countDown();check(oldSent.await(10,TimeUnit.SECONDS),"Delayed snapshot was not delivered");Thread.sleep(500);
   fx(()->{check(table().getItems().isEmpty()&&stats().equals(List.of("2","1","2","700.00")),"Older refresh restored a completed vehicle or stale counts");search().clear();check(table().getItems().size()==1&&table().getItems().get(0).path("id").asLong()==103,"Refresh lost the still-parked vehicle");check(posts.get()==1,"Recovery retried payment instead of reconciling it");return null;});
   check(fixtureError.get()==null,"HTTP fixture failed: "+fixtureError.get());
   System.out.println("COMPLETED CHECKOUT UI CHECK PASSED: quote/cancel unchanged, immediate removal/free slot on failed refresh, totals/shift refresh, fresh app/history/reprint, slot reuse, safe replay, delayed snapshot rejection and pending recovery.");
  }finally{
   release.countDown();fx(()->{for(Window w:new ArrayList<>(Window.getWindows()))if(w instanceof Stage s&&(s==stage||s.getOwner()==stage))s.close();if(app!=null)app.stop();return null;});
   store.clear(101);store.clear(102);recovery.logOut();server.stop(0);http.shutdownNow();
  }
 }
 private void selectHistoryTab(String title){for(TabPane p:nodes(TabPane.class))for(Tab t:p.getTabs())if(t.getText().equals(title)){p.getSelectionModel().select(t);return;}throw new AssertionError("History tab missing");}
 private void checkParkingHistory()throws Exception{
  fx(()->{nav("Parking History").fire();return null;});await(()->button("Refresh History")!=null&&!button("Refresh History").isDisabled()&&table().getItems().size()==3);
  fx(()->{check(nodes(DatePicker.class).stream().allMatch(d->java.time.LocalDate.now(java.time.ZoneId.of("Asia/Colombo")).equals(d.getValue())),"History did not default to Sri Lankan today");JsonNode old=table().getItems().stream().filter(r->r.path("id").asLong()==101).findFirst().orElseThrow();check(old.path("status").asText().equals("Completed")&&old.path("slot").asText().equals("A-001"),"Slot reuse overwrote old entry history");DesktopUiSmoke.snapshotNode(stage.getScene().getRoot(),"parking-history-entries");table().getSelectionModel().select(old);button("View Details").fire();return null;});
  await(()->dialog("Entry #101")!=null);fx(()->{close("Entry #101");button("Reprint Entry Ticket").fire();return null;});await(()->dialog("Entry saved")!=null);fx(()->{close("Entry saved");return null;});
  failHistory.set(true);fx(()->{button("Refresh History").fire();return null;});await(()->text("Fixture history temporarily unavailable")&&!button("Refresh History").isDisabled());
  fx(()->{check(table().getItems().isEmpty(),"Failed history request retained stale records");button("Refresh History").fire();return null;});await(()->!button("Refresh History").isDisabled()&&table().getItems().size()==3);
  fx(()->{selectHistoryTab("Completed Checkouts");return null;});await(()->!button("Refresh History").isDisabled()&&table().getItems().size()==1);
  fx(()->{JsonNode paid=table().getItems().get(0);check(paid.path("amount").asInt()==350&&paid.path("cashTendered").asInt()==500&&paid.path("changeDue").asInt()==150,"Checkout history changed stored money");DesktopUiSmoke.snapshotNode(stage.getScene().getRoot(),"parking-history-checkouts");table().getSelectionModel().select(0);button("View Receipt").fire();return null;});await(()->dialog("Parking receipt")!=null);
  fx(()->{close("Parking receipt");button("Reprint Receipt").fire();return null;});await(()->dialog("Parking receipt")!=null);
  fx(()->{check(descendants(dialog("Parking receipt"),Label.class).stream().anyMatch(l->l.getText().contains("REPRINT")),"History duplicate copy lacks REPRINT label");check(posts.get()==1&&historyRequests.get()>=4,"History or reprint submitted payment");close("Parking receipt");nav("Parking").fire();return null;});await(()->ready()&&text("Continue active shift #51"));
 }
 private void launch(){app=new DesktopApp(()->endpoint);stage=new Stage();app.start(stage);stage.setX(-10000);stage.setY(-10000);}
 private void login()throws Exception{fx(()->{input("Username").setText("checkout.parking.fixture");input("Password").setText("fixture-password");button("Connect to Workspace").fire();return null;});}
 private void pay()throws Exception{fx(()->{DialogPane p=dialog("Vehicle checkout");((TextField)descendants(p,Label.class).stream().filter(l->l.getText().startsWith("Cash received")).findFirst().orElseThrow().getLabelFor()).setText("500.00");Button pay=descendants(p,Button.class).stream().filter(b->b.getText().equals("Confirm Payment & Print")).findFirst().orElseThrow();pay.fire();pay.fire();return null;});}
 private void pending()throws Exception{fx(()->{button("Pending checkouts").fire();return null;});await(()->dialog("Check the saved attempt before retrying")!=null);fx(()->{((Button)dialog("Check the saved attempt before retrying").lookupButton(ButtonType.OK)).fire();return null;});}
 private boolean ready(){return button("Refresh")!=null&&!button("Refresh").isDisabled();}
 private boolean text(String value){return nodes(Label.class).stream().anyMatch(l->l.getText()!=null&&l.getText().contains(value));}
 private List<String> stats(){return nodes(Ui.Stat.class).stream().map(s->s.value.getText()).toList();}
 private Button button(String value){return nodes(Button.class).stream().filter(b->b.getText().equals(value)).findFirst().orElse(null);}
 private ToggleButton nav(String value){return nodes(ToggleButton.class).stream().filter(b->b.getText().equals(value)).findFirst().orElseThrow();}
 private TextField input(String value){return (TextField)nodes(Label.class).stream().filter(l->l.getText().equals(value)&&l.getLabelFor()!=null).findFirst().orElseThrow().getLabelFor();}
 private TextField search(){return nodes(TextField.class).stream().filter(t->"Search ticket or plate...".equals(t.getPromptText())).findFirst().orElseThrow();}
 @SuppressWarnings("unchecked") private TableView<JsonNode> table(){return (TableView<JsonNode>)nodes(TableView.class).get(0);}
 @SuppressWarnings("unchecked") private ComboBox<JsonNode> slot(){return (ComboBox<JsonNode>)nodes(Label.class).stream().filter(l->l.getText().equals("Parking slot")&&l.getLabelFor()!=null).findFirst().orElseThrow().getLabelFor();}
 private DialogPane dialog(String header){for(Window w:Window.getWindows())if(w instanceof Stage s&&s.getOwner()==stage&&w.isShowing())for(DialogPane p:descendants(w.getScene().getRoot(),DialogPane.class))if(p.getHeaderText()!=null&&p.getHeaderText().startsWith(header)){w.setX(-10000);return p;}return null;}
 private void close(String header){((Button)dialog(header).lookupButton(ButtonType.CLOSE)).fire();}
 private <T>List<T> nodes(Class<T> type){return descendants(stage.getScene().getRoot(),type).stream().filter(value->{if(value instanceof Node node){for(Node p=node;p!=null;p=p.getParent())if(!p.isVisible())return false;}return true;}).toList();}
 private static <T>List<T> descendants(Node node,Class<T> type){Set<Node> all=new LinkedHashSet<>();walk(node,all);return all.stream().filter(type::isInstance).map(type::cast).toList();}
 private static void walk(Node node,Set<Node> all){if(!all.add(node))return;if(node instanceof Parent p)p.getChildrenUnmodifiable().forEach(n->walk(n,all));if(node instanceof ScrollPane s&&s.getContent()!=null)walk(s.getContent(),all);}
 private static <T>T fx(Supplier<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work::get);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
 private void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<end){if(fx(condition::getAsBoolean))return;Thread.sleep(30);}throw new AssertionError("Completed-checkout UI timed out: "+fx(()->nodes(Label.class).stream().map(Label::getText).toList()));}
 private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
 private static JsonNode vehicle(long id,String plate,String slot){return JSON.valueToTree(Map.of("id",id,"plate",plate,"slot",slot,"vehicleType","CAR","enteredAt","2026-10-25T04:00:00Z"));}
 private synchronized JsonNode commit(long id,String key){
  if(attempts.containsKey(key))return attempts.get(key);JsonNode vehicle=parked.remove(id);if(vehicle==null)throw new IllegalStateException("Fixture attempted duplicate payment");
  var receipt=(com.fasterxml.jackson.databind.node.ObjectNode)vehicle.deepCopy();receipt.put("sessionId",id).put("paymentId",500+id).put("amount",350).put("cashTendered",500).put("changeDue",150).put("method","CASH").put("requestKey",key).put("branch","Main Branch").put("exitedAt","2026-10-25T04:10:00Z").put("billableMinutes",10);
  receipt.put("cashier","checkout.parking.fixture").put("paidAt","2026-10-25T04:10:00Z").put("elapsedSeconds",600).put("status","Paid");receipt.set("breakdown",JSON.valueToTree(Map.of("elapsedSeconds",600,"billedUnits",1,"rate",350,"unit","HOUR","explanation","LKR 350.00 per started hour")));
  receipts.put(id,receipt);attempts.put(key,receipt);return receipt;
 }
 private synchronized String sessions(){return JSON.valueToTree(parked.values()).toString();}
 private synchronized String slots(){var rows=JSON.createArrayNode();for(int i=1;i<=3;i++){String code="A-00"+i;rows.addObject().put("id",i).put("code",code).put("occupied",parked.values().stream().anyMatch(r->r.path("slot").asText().equals(code)));}return rows.toString();}
 private void serve(HttpExchange e)throws java.io.IOException{
  try{
   String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
   if(path.startsWith("/api/parking-history/")){
    if(path.equals("/api/parking-history/entries")||path.equals("/api/parking-history/checkouts")){
     historyRequests.incrementAndGet();String query=e.getRequestURI().getQuery();check(query.contains("from=")&&query.contains("to=")&&query.contains("size=25"),"History did not request server filters/pagination");
     if(failHistory.getAndSet(false)){send(e,503,"{\"error\":\"Fixture history temporarily unavailable\"}");return;}
     synchronized(this){var rows=JSON.createArrayNode();if(path.endsWith("checkouts"))receipts.values().forEach(rows::add);else {var all=new TreeMap<Long,JsonNode>();all.putAll(receipts);all.putAll(parked);for(var item:all.entrySet()){var row=(com.fasterxml.jackson.databind.node.ObjectNode)item.getValue().deepCopy();row.put("id",item.getKey()).put("enteredBy","checkout.parking.fixture").put("status",receipts.containsKey(item.getKey())?"Completed":"Parked");rows.add(row);}}send(e,200,"{\"rows\":"+rows+",\"pages\":1,\"total\":"+rows.size()+"}");}return;
    }
    long id=Long.parseLong(path.substring(path.lastIndexOf('/')+1));synchronized(this){var row=(com.fasterxml.jackson.databind.node.ObjectNode)(receipts.containsKey(id)?receipts.get(id):parked.get(id)).deepCopy();row.put("id",id).put("enteredBy","checkout.parking.fixture").put("status",receipts.containsKey(id)?"Completed":"Parked");send(e,200,row.toString());}return;
   }
   if(path.endsWith("/ticket/reprints")){long id=Long.parseLong(path.split("/")[3]);JsonNode row;synchronized(this){row=receipts.get(id);}var ticket=(com.fasterxml.jackson.databind.node.ObjectNode)row.deepCopy();ticket.put("id",id).put("opaqueIdentifier",UUID.randomUUID().toString()).put("pricingSummary","Original entry pricing");send(e,200,ticket.toString());return;}
   if(path.equals("/api/me")){
    if(e.getRequestHeaders().getFirst("Authorization")==null){e.getResponseHeaders().set("WWW-Authenticate","Basic realm=\"Realm\"");send(e,401,"");return;}
    send(e,200,"{\"role\":\"CASHIER\",\"branchId\":1,\"branchName\":\"Main Branch\"}");return;}
   if(path.equals("/api/notifications")){send(e,200,"{\"rows\":[],\"important\":[],\"unread\":0,\"highestPriority\":\"Neutral\",\"pages\":0,\"total\":0}");return;}
   if(path.equals("/api/shifts/current")){int count;synchronized(this){count=receipts.size();}send(e,200,"{\"active\":true,\"shift\":{\"id\":51,\"branch\":\"Main Branch\",\"openingCash\":100,\"expectedCash\":"+(100+count*350)+"}}");return;}
   if(path.equals("/api/slots")){if(failSlots.getAndSet(false))send(e,503,"{\"error\":\"Fixture parking refresh unavailable\"}");else send(e,200,slots());return;}
   if(path.equals("/api/sessions")&&method.equals("GET")){String snapshot=sessions();boolean hold=holdSessions.getAndSet(false);if(hold){captured.countDown();check(release.await(30,TimeUnit.SECONDS),"Stale snapshot fixture timed out");}send(e,200,snapshot);if(hold)oldSent.countDown();return;}
   if(path.equals("/api/reports/today")){int count;synchronized(this){count=receipts.size();}send(e,200,"{\"payments\":"+count+",\"revenue\":"+(350*count)+"}");return;}
   if(path.equals("/api/sessions")&&method.equals("POST")){JsonNode request=JSON.readTree(e.getRequestBody());check(request.path("slotId").asLong()==1,"Re-entry did not use the freed slot");JsonNode added=vehicle(103,request.path("plate").asText(),"A-001");synchronized(this){parked.put(103L,added);}send(e,201,added.toString());return;}
   if(path.endsWith("/quote")){long id=Long.parseLong(path.split("/")[3]);JsonNode vehicle;synchronized(this){vehicle=parked.get(id);}var quote=(com.fasterxml.jackson.databind.node.ObjectNode)vehicle.deepCopy();quote.put("sessionId",id).put("quoteId","fixture-"+id).put("amount",350).put("billableMinutes",10).put("calculatedAt","2026-10-25T04:10:00Z");quote.set("breakdown",JSON.valueToTree(Map.of("elapsedSeconds",600,"billedUnits",1,"rate",350,"explanation","LKR 350.00 per started hour")));send(e,200,quote.toString());return;}
   if(path.endsWith("/checkout")){posts.incrementAndGet();JsonNode request=JSON.readTree(e.getRequestBody());long id=Long.parseLong(path.split("/")[3]);JsonNode result=commit(id,request.path("requestKey").asText());failSlots.set(true);send(e,200,result.toString());return;}
   if(path.startsWith("/api/checkouts/")){String key=path.substring(path.lastIndexOf('/')+1);JsonNode result;synchronized(this){result=attempts.get(key);}if(result==null&&key.equals(recoveryKey))result=commit(102,key);send(e,result==null?404:200,result==null?"{}":result.toString());return;}
   if(path.equals("/api/receipts")){synchronized(this){send(e,200,"{\"rows\":"+JSON.valueToTree(receipts.values())+",\"pages\":1,\"total\":"+receipts.size()+"}");}return;}
   if(path.startsWith("/api/receipts/")&&path.endsWith("/reprints")){long id=Long.parseLong(path.split("/")[3]);synchronized(this){send(e,200,receipts.get(id).toString());}return;}
   if(path.startsWith("/api/receipts/")){long id=Long.parseLong(path.split("/")[3]);synchronized(this){send(e,200,receipts.get(id).toString());}return;}
   if(path.equals("/api/sessions/103/ticket")){var ticket=(com.fasterxml.jackson.databind.node.ObjectNode)vehicle(103,"NEW-7777","A-001");ticket.put("opaqueIdentifier",UUID.randomUUID().toString()).put("branch","Main Branch").put("pricingSummary","LKR 150.00");send(e,200,ticket.toString());return;}
   send(e,404,"{}");
  }catch(Throwable error){fixtureError.compareAndSet(null,error);send(e,500,"{\"error\":\"Fixture failed\"}");}
 }
 private static void send(HttpExchange e,int status,String body)throws java.io.IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();}
}
