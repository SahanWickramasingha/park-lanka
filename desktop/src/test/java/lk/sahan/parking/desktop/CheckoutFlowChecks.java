package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Real dialogs/HTTP failures against a disposable fixture, including a truncated payment response. */
final class CheckoutFlowChecks {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final AtomicReference<String> mode=new AtomicReference<>("SUCCESS"),message=new AtomicReference<>("");
    private final AtomicInteger posts=new AtomicInteger(),quotes=new AtomicInteger(),opened=new AtomicInteger(),payments=new AtomicInteger();
    private final AtomicInteger requestErrors=new AtomicInteger();
    private final List<String> keys=new CopyOnWriteArrayList<>();private final Map<String,String> committed=new ConcurrentHashMap<>();
    private final AtomicBoolean statusUnavailable=new AtomicBoolean();
    private final AtomicBoolean failReceipt=new AtomicBoolean();
    private final Set<Long> completed=ConcurrentHashMap.newKeySet();
    private final BooleanProperty shift=new SimpleBooleanProperty(false),busy=new SimpleBooleanProperty();
    private Stage owner;private CheckoutCoordinator coordinator;private PendingCheckoutStore store;
    static void run()throws Exception{new CheckoutFlowChecks().execute();}
    private void execute()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService http=Executors.newCachedThreadPool(),io=Executors.newFixedThreadPool(2);
        server.setExecutor(http);server.createContext("/",this::serve);server.start();
        ApiClient api=new ApiClient("http://127.0.0.1:"+server.getAddress().getPort(),"checkout.fixture","fixture-password");store=new PendingCheckoutStore(api);
        try{
            fx(()->{owner=new Stage();owner.setScene(Ui.scene(new StackPane(),400,300));owner.setX(-10000);owner.show();
                coordinator=new CheckoutCoordinator(api,io,owner,busy,api::isActive,value->{requestErrors.incrementAndGet();message.set(value);},ticket->{check(Platform.isFxApplicationThread(),"Completion ran outside JavaFX");completed.add(ticket);},message::set,state->{shift.set(state.path("active").asBoolean());if(!shift.get())opened.incrementAndGet();},
                    (receipt,window)->{if(failReceipt.getAndSet(false))throw new IllegalStateException("Fixture printer/preview unavailable");ParkingDialogs.receipt(receipt,window);});return null;});
            start(101);await(()->!busy.get()&&opened.get()==1);
            check(posts.get()==0&&quotes.get()==0,"Known missing shift sent a checkout/quote");
            fx(()->{check(pane(null)==null&&message.get().isEmpty(),"Missing shift opened a dialog or reported a request error");return null;});

            fx(()->{shift.set(true);return null;});mode.set("SHIFT_REQUIRED");start(101);await(()->pane("Vehicle checkout")!=null);pay("500.00");
            await(()->!busy.get()&&opened.get()==2);
            check(!message.get().contains("uncertain"),"Explicit SHIFT_REQUIRED was classified as uncertain");check(store.get(101)==null,"Rejected shift attempt remained pending");
            check(posts.get()==1&&message.get().isEmpty(),"Shift action resubmitted payment or reported a request error");
            fx(()->{shift.set(true);return null;});mode.set("QUOTE_CHANGED");start(101);await(()->pane("Vehicle checkout")!=null);
            fx(()->{check(cash(pane("Vehicle checkout")).getText().equals("500.00"),"Shift rejection discarded cash");return null;});pay("500.00");
            await(()->hasText(pane("Vehicle checkout"),"LKR 200.00"));check(posts.get()==2,"Changed quote was silently finalized");
            fx(()->{check(cash(pane("Vehicle checkout")).getText().equals("500.00"),"Quote change discarded cash");check(hasText(pane("Vehicle checkout"),"Change due (LKR): 300.00"),"Updated quote did not revalidate cash");return null;});
            pay("500.00");await(()->pane("Parking receipt")!=null);closeReceipt();check(payments.get()==1,"Reviewing changed quote duplicated payment");

            mode.set("LOST_AFTER_COMMIT");start(102);await(()->pane("Vehicle checkout")!=null);pay("500.00");
            await(()->!busy.get()&&message.get().contains("uncertain"));String original=store.get(102).get("requestKey");check(committed.containsKey(original),"Lost-response fixture did not commit");
            check(!completed.contains(102L),"Uncertain payment removed a parked vehicle before reconciliation");
            int posted=posts.get();start(102);await(()->pane("Parking receipt")!=null);closeReceipt();
            check(posts.get()==posted&&store.get(102)==null&&payments.get()==2,"Lost response caused another payment rather than status recovery");
            check(completed.contains(102L),"Confirmed recovery did not update Parking");

            mode.set("UNAVAILABLE_BEFORE_COMMIT");message.set("");start(103);await(()->pane("Vehicle checkout")!=null);pay("500.00");
            await(()->!busy.get()&&message.get().contains("uncertain"));String retryKey=store.get(103).get("requestKey");
            start(103);await(()->pane(null)!=null&&pane(null).getScene().getWindow().isShowing()&&hasText(pane(null),"Retry the saved attempt"));
            fx(()->{((Button)pane(null).lookupButton(ButtonType.OK)).fire();return null;});await(()->pane("Parking receipt")!=null);closeReceipt();
            check(keys.get(keys.size()-1).equals(retryKey)&&keys.get(keys.size()-2).equals(retryKey),"Retry changed idempotency identity");check(payments.get()==3,"Retry duplicated a committed payment");

            mode.set("BRANCH_STOPPED");message.set("");start(104);await(()->pane("Vehicle checkout")!=null);pay("500.00");
            await(()->!busy.get()&&message.get().contains("Checkout rejected"));check(!message.get().contains("uncertain")&&store.get(104)==null,"Definite unrecorded 409 was treated as uncertain");
            mode.set("ALREADY_CHECKED_OUT");message.set("");start(105);await(()->pane("Vehicle checkout")!=null);pay("500.00");
            await(()->!busy.get()&&message.get().contains("already checked out"));check(store.get(105)==null&&payments.get()==3,"Already checked-out attempt caused another payment");
            mode.set("SHIFT_STATUS_UNAVAILABLE");message.set("");start(106);await(()->pane("Vehicle checkout")!=null);pay("500.00");
            await(()->!busy.get()&&opened.get()==3);
            check(!message.get().contains("uncertain")&&store.get(106)!=null,"Definite shift rejection with unavailable status lost its classification/key");
            fx(()->{check(pane(null)==null,"Shift rejection opened a repeated dialog");return null;});
            fx(()->{shift.set(true);return null;});mode.set("SUCCESS");message.set("");failReceipt.set(true);
            start(107);await(()->pane("Vehicle checkout")!=null);pay("500.00");
            await(()->!busy.get()&&message.get().contains("Checkout completed; receipt preview unavailable"));
            check(completed.contains(107L)&&store.get(107)==null&&payments.get()==4,"Receipt failure reversed completion or retained an unconfirmed attempt");
            check(!message.get().contains("uncertain"),"Receipt failure was treated as an uncertain payment");
            int beforeMalformed=posts.get();message.set("");mode.set("BAD_QUOTE");start(108);
            await(()->!busy.get()&&message.get().contains("Could not open checkout window"));
            fx(()->{check(pane(null)==null&&posts.get()==beforeMalformed&&!completed.contains(108L),"Malformed quote silently opened a dialog or submitted payment");return null;});
            int beforeCancel=posts.get();start(108);await(()->pane("Vehicle checkout")!=null);
            fx(()->{((Button)pane("Vehicle checkout").lookupButton(ButtonType.CANCEL)).fire();return null;});check(posts.get()==beforeCancel,"Retrying/cancelling quote sent payment");
            int errorsBefore=requestErrors.get();message.set("");mode.set("QUOTE_STATUS_UNAVAILABLE");start(109);await(()->pane("Vehicle checkout")!=null);pay("500.00");
            await(()->!busy.get()&&message.get().contains("Quote review paused"));
            check(requestErrors.get()==errorsBefore&&store.get(109)!=null&&payments.get()==4,"Quote business rejection became a connection failure or lost its reconciliation key");
            System.out.println("CHECKOUT FLOW CHECK PASSED: shift action/gating, cash preservation, changed-quote confirmation, explicit 409 rejection, lost response recovery, same-key retry.");
        }finally{
            fx(()->{for(Window w:new ArrayList<>(Window.getWindows()))if(w instanceof Stage s&&(s==owner||s.getOwner()==owner))s.close();return null;});
            for(long id=101;id<=109;id++)store.clear(id);api.logOut();io.shutdownNow();server.stop(0);http.shutdownNow();
        }
    }
    private void start(long ticket)throws Exception{fx(()->{coordinator.start(ticket);return null;});}
    private void pay(String value)throws Exception{fx(()->{DialogPane pane=pane("Vehicle checkout");cash(pane).setText(value);button(pane,"Confirm Payment & Print").fire();return null;});}
    private void click(String header,String text)throws Exception{fx(()->{button(pane(header),text).fire();return null;});}
    private void closeReceipt()throws Exception{fx(()->{((Button)pane("Parking receipt").lookupButton(ButtonType.CLOSE)).fire();return null;});}
    private static Button button(DialogPane pane,String text){return descendants(pane,Button.class).stream().filter(b->b.getText().equals(text)).findFirst().orElseThrow();}
    private static TextField cash(DialogPane pane){return (TextField)descendants(pane,Label.class).stream().filter(l->l.getText().equals("Cash received (LKR)")).findFirst().orElseThrow().getLabelFor();}
    private DialogPane pane(String header){for(Window w:Window.getWindows())if(w instanceof Stage s&&s.getOwner()==owner&&w.isShowing())for(DialogPane p:descendants(w.getScene().getRoot(),DialogPane.class))if(header==null||header.equals(p.getHeaderText())){w.setX(-10000);return p;}return null;}
    private static boolean hasText(DialogPane pane,String text){return pane!=null&&descendants(pane,Label.class).stream().anyMatch(l->l.getText().contains(text));}
    private static <T>List<T> descendants(Node n,Class<T> type){List<T> result=new ArrayList<>();if(type.isInstance(n))result.add(type.cast(n));if(n instanceof Parent p)for(Node c:p.getChildrenUnmodifiable())result.addAll(descendants(c,type));if(n instanceof ScrollPane s&&s.getContent()!=null)result.addAll(descendants(s.getContent(),type));return result;}
    private static <T>T fx(Supplier<T> supplier)throws Exception{FutureTask<T> task=new FutureTask<>(supplier::get);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    private void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(System.nanoTime()<end){if(fx(condition::getAsBoolean))return;Thread.sleep(30);}throw new AssertionError("Checkout UI timed out; mode="+mode.get()+", message="+message.get());}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void serve(HttpExchange exchange)throws java.io.IOException{
        String path=exchange.getRequestURI().getPath();
        if(path.equals("/api/shifts/current")){send(exchange,200,"{\"active\":"+shift.get()+"}");return;}
        if(path.startsWith("/api/checkouts/")){if(statusUnavailable.getAndSet(false)){send(exchange,503,"{}");return;}String found=committed.get(path.substring(path.lastIndexOf('/')+1));send(exchange,found==null?404:200,found==null?"{}":found);return;}
        if(path.endsWith("/quote")){quotes.incrementAndGet();if(mode.compareAndSet("BAD_QUOTE","SUCCESS")){send(exchange,200,"{}");return;}send(exchange,200,"{\"sessionId\":101,\"plate\":\"FIXTURE\",\"slot\":\"A-001\",\"quoteId\":\"original-quote\",\"amount\":150.00}");return;}
        if(!path.endsWith("/checkout")){send(exchange,404,"{}");return;}
        posts.incrementAndGet();JsonNode request=JSON.readTree(exchange.getRequestBody());String key=request.path("requestKey").asText();keys.add(key);
        String action=mode.getAndSet("SUCCESS");
        if(action.equals("QUOTE_STATUS_UNAVAILABLE")){statusUnavailable.set(true);send(exchange,409,"{\"code\":\"QUOTE_CHANGED\",\"attemptRecorded\":false,\"error\":\"Quote expired\"}");return;}
        if(posts.get()==3)check(request.path("quoteId").asText().equals("refreshed-quote"),"Reconfirmation sent the old quote ID");
        if(action.equals("SHIFT_STATUS_UNAVAILABLE")){statusUnavailable.set(true);send(exchange,409,"{\"code\":\"SHIFT_REQUIRED\",\"attemptRecorded\":false,\"error\":\"Open a shift\"}");return;}
        if(action.equals("QUOTE_CHANGED")){send(exchange,409,"{\"code\":\"QUOTE_CHANGED\",\"error\":\"Confirm updated quote\",\"quote\":{\"quoteId\":\"refreshed-quote\",\"amount\":200.00}}");return;}
        if(Set.of("SHIFT_REQUIRED","BRANCH_STOPPED","ALREADY_CHECKED_OUT").contains(action)){send(exchange,409,"{\"code\":\""+action+"\",\"error\":\"Fixture rejection\"}");return;}
        if(action.equals("UNAVAILABLE_BEFORE_COMMIT")){send(exchange,503,"{}");return;}
        var amount=new java.math.BigDecimal(request.path("confirmedAmount").asText());var cash=new java.math.BigDecimal(request.path("cashTendered").asText());
        var receipt=JSON.createObjectNode();receipt.put("paymentId",500+payments.incrementAndGet());receipt.put("sessionId",Long.parseLong(path.split("/")[3]));receipt.put("plate","FIXTURE");receipt.put("slot","A-001");receipt.put("method","CASH");receipt.put("amount",amount);receipt.put("cashTendered",cash);receipt.put("changeDue",cash.subtract(amount));receipt.put("requestKey",key);
        String body=receipt.toString();committed.put(key,body);
        if(action.equals("LOST_AFTER_COMMIT")){exchange.sendResponseHeaders(200,1000);exchange.getResponseBody().write('{');exchange.close();return;}
        send(exchange,200,body);
    }
    private static void send(HttpExchange exchange,int status,String body)throws java.io.IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
}
