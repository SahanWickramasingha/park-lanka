package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.scene.control.*;
import javafx.stage.Window;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** A lost response keeps its original key; a rejection is reconciled before discarding it. */
final class CheckoutCoordinator {
    private final ApiClient api; private final ExecutorService io; private final Window owner; private final BooleanProperty busy;
    private final BooleanSupplier active; private final Consumer<String> error;
    private final LongConsumer saved; private final Consumer<JsonNode> shiftState; private final PendingCheckoutStore store;
    private boolean shiftConfirmed;
    private final Consumer<String> completedNotice; private final BiConsumer<JsonNode,Window> receiptView;
    private final Map<Long,Map<String,String>> drafts=new HashMap<>();
    CheckoutCoordinator(ApiClient api,ExecutorService io,Window owner,BooleanProperty busy,BooleanSupplier active,Consumer<String> error,LongConsumer saved,
                        Consumer<String> completedNotice,Consumer<JsonNode> shiftState){
        this(api,io,owner,busy,active,error,saved,completedNotice,shiftState,ParkingDialogs::receipt);
    }
    CheckoutCoordinator(ApiClient api,ExecutorService io,Window owner,BooleanProperty busy,BooleanSupplier active,Consumer<String> error,LongConsumer saved,
                        Consumer<String> completedNotice,Consumer<JsonNode> shiftState,BiConsumer<JsonNode,Window> receiptView){
        this.api=api;this.io=io;this.owner=owner;this.busy=busy;this.active=active;this.error=error;this.saved=saved;
        this.completedNotice=completedNotice;this.receiptView=receiptView;
        this.shiftState=shiftState;store=new PendingCheckoutStore(api);
    }
    interface Work<T>{T run()throws Exception;}
    private <T> void call(Work<T> work,Consumer<T> success,Consumer<Throwable> failure){
        busy.set(true);
        try{CompletableFuture.supplyAsync(()->{try{return work.run();}catch(Exception e){throw new CompletionException(e);}},io).orTimeout(25,TimeUnit.SECONDS)
            .whenComplete((result,ex)->Platform.runLater(()->{
                if(!active.getAsBoolean()){System.out.println("CHECKOUT callback discarded: signed out/session changed");return;}
                busy.set(false);
                if(!owner.isShowing()){error.accept("Checkout was interrupted because the cashier window is hidden. Return to Parking and check Pending checkouts before another payment.");return;}
                try{if(ex==null)success.accept(result);else{Throwable cause=ex instanceof CompletionException&&ex.getCause()!=null?ex.getCause():ex;System.out.println("CHECKOUT operation failed type="+cause.getClass().getSimpleName());failure.accept(cause);}}
                catch(RuntimeException callbackError){callbackError.printStackTrace();error.accept("The checkout interface could not update. Refresh Parking and check Pending checkouts before another payment. "+callbackError.getMessage());}
            }));
        }catch(RuntimeException schedulingError){busy.set(false);error.accept("Could not start checkout work. No new payment was sent. "+schedulingError.getMessage());}
    }
    void start(long ticket){
        System.out.println("CHECKOUT start ticket="+ticket+" busy="+busy.get());
        if(busy.get()){error.accept("Checkout is already processing. Wait for it to finish before trying again.");return;}
        if(ticket<=0){error.accept("The selected vehicle has no valid ticket. Refresh Parking and select it again.");return;}
        call(()->store.get(ticket),pending->{System.out.println("CHECKOUT pending lookup ticket="+ticket+" found="+(pending!=null));if(pending!=null)check(ticket,pending);else withShift(()->quote(ticket,""));},
            ex->error.accept("Cannot read pending checkout. No payment was sent: "+ex.getMessage()));
    }
    void pending(){
        if(busy.get())return;
        call(store::tickets,ids->{
            if(ids.isEmpty()){error.accept("No pending checkout attempts.");return;}
            ChoiceDialog<Long> pick=new ChoiceDialog<>(ids.get(0),ids);Ui.dialog(pick,owner);pick.setTitle("Pending checkouts");pick.setHeaderText("Check the saved attempt before retrying");pick.showAndWait().ifPresent(this::start);
        },ex->error.accept(ex.getMessage()));
    }
    private void withShift(Runnable next){
        System.out.println("CHECKOUT shift request GET /api/shifts/current");
        call(()->api.get("/api/shifts/current"),state->{
            if(state==null||!state.path("active").isBoolean()){error.accept("Cannot verify your shift: the server returned an invalid shift response. Refresh Parking or restart the matching backend.");return;}
            shiftConfirmed=state.path("active").asBoolean();System.out.println("CHECKOUT shift response active="+shiftConfirmed);
            if(!shiftConfirmed){needShift();return;}shiftState.accept(state);next.run();
        },ex->error.accept("Cannot verify your cashier shift. Retry Quote / Checkout or Refresh Parking. "+ex.getMessage()));
    }
    private void quote(long ticket,String message){System.out.println("CHECKOUT quote request ticket="+ticket);call(()->api.get("/api/sessions/"+ticket+"/quote"),q->{System.out.println("CHECKOUT quote received ticket="+ticket+" numericAmount="+(q!=null&&q.path("amount").isNumber()));collect(ticket,q,message);},ex->{System.out.println("CHECKOUT quote failed ticket="+ticket+" type="+ex.getClass().getSimpleName());error.accept("Cannot load the selected vehicle's quote. Retry Quote / Checkout or Refresh Parking. "+ex.getMessage());});}
    private void collect(long ticket,JsonNode quote,String message){
        System.out.println("CHECKOUT construct dialog ticket="+ticket+" FX="+Platform.isFxApplicationThread()+" shiftConfirmed="+shiftConfirmed);
        if(!shiftConfirmed){needShift();return;}
        Optional<Map<String,String>> answer;
        try{answer=ParkingDialogs.payment(quote,owner,drafts.getOrDefault(ticket,Map.of()),message).showAndWait();}
        catch(RuntimeException ex){error.accept("Could not open checkout window. No payment was sent. "+ex.getMessage());return;}
        answer.ifPresent(values->{
            Map<String,String> request=new HashMap<>(values);request.put("requestKey",UUID.randomUUID().toString());drafts.put(ticket,request);post(ticket,request);
        });
    }
    private void needShift(){
        System.out.println("CHECKOUT stopped: active shift required; showing Open Shift next to checkout");
        // Parking owns a single informational banner/action. This is not a connection failure.
        // Refreshing a shift never resumes this checkout; the cashier must review it again.
        shiftConfirmed=false;shiftState.accept(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("active",false));
    }
    private void check(long ticket,Map<String,String> request){
        call(()->api.get("/api/checkouts/"+request.get("requestKey")),receipt->complete(ticket,receipt),ex->{
            if(ex instanceof ApiClient.HttpFailure http&&http.statusCode()==404){
                withShift(()->{
                Alert retry=new Alert(Alert.AlertType.CONFIRMATION,"No committed payment was found for ticket #"+ticket+". Retry the saved attempt for LKR "+request.get("confirmedAmount")+"? The same key and payment details will be used.",ButtonType.OK,ButtonType.CANCEL);
                Ui.dialog(retry,owner);retry.setTitle("Check uncertain checkout");retry.showAndWait().filter(b->b==ButtonType.OK).ifPresent(b->post(ticket,request));
                });
            }else uncertain(ex);
        });
    }
    private void post(long ticket,Map<String,String> request){
        System.out.println("CHECKOUT confirm ticket="+ticket+" request="+request.get("requestKey")+" quote="+request.get("quoteId")+" confirmedFee="+request.get("confirmedAmount"));
        if(!shiftConfirmed){drafts.put(ticket,request);needShift();return;}
        // A local persistence failure happens before sending, so it is not an uncertain payment.
        call(()->{store.save(ticket,request);return true;},ignored->call(()->api.post("/api/sessions/"+ticket+"/checkout",request),receipt->complete(ticket,receipt),ex->{
            if(ex instanceof ApiClient.HttpFailure http&&http.statusCode()>=400&&http.statusCode()<500&&http.statusCode()!=408&&http.statusCode()!=429)
                reconcileRejection(ticket,request,http);
            else uncertain(ex);
        }),ex->error.accept("No payment was sent: the recovery attempt could not be saved. "+ex.getMessage()));
    }
    private void reconcileRejection(long ticket,Map<String,String> request,ApiClient.HttpFailure rejection){
        // Also covers older servers and reused keys: never assume a rejected request's key is unrecorded.
        call(()->api.get("/api/checkouts/"+request.get("requestKey")),receipt->complete(ticket,receipt),statusError->{
            if(!(statusError instanceof ApiClient.HttpFailure status)||status.statusCode()!=404){
                // Keep a definite business rejection distinct even if the follow-up status query fails.
                // Retain the key until it can be reconciled; opening a shift still cannot submit it.
                JsonNode details=rejection.details();String code=details==null?"":details.path("code").asText();
                if(details!=null&&details.has("attemptRecorded")&&!details.path("attemptRecorded").asBoolean()){
                    drafts.put(ticket,request);
                    if(code.equals("SHIFT_REQUIRED"))needShift();
                    else if(code.equals("QUOTE_CHANGED"))completedNotice.accept("Quote review paused for ticket #"+ticket+". No payment was recorded for this rejected attempt. The status check is unavailable; the original recovery key is retained. Use Pending checkouts before another confirmation. "+rejection.getMessage());
                    else error.accept("Checkout rejected: "+rejection.getMessage()+" The status check is unavailable. The original recovery key is retained; check Pending checkouts before another submission.");
                }else uncertain(statusError);
                return;
            }
            call(()->{store.clear(ticket);return true;},ignored->{
                drafts.put(ticket,request);
                String code=rejection.details()==null?"":rejection.details().path("code").asText();
                switch(code){
                    case "SHIFT_REQUIRED" -> needShift();
                    case "QUOTE_CHANGED" -> {
                        JsonNode fresh=rejection.details().path("quote");
                        System.out.println("CHECKOUT quote replaced ticket="+ticket+" oldQuote="+request.get("quoteId")+" newQuote="+fresh.path("quoteId").asText()+" oldFee="+request.get("confirmedAmount")+" newFee="+fresh.path("amount").asText()+" cutoff="+fresh.path("billingCutoff").asText()+" expires="+fresh.path("expiresAt").asText());
                        collect(ticket,fresh,"Quote changed for ticket #"+ticket+". "+rejection.getMessage()+" Cash is preserved; review the fee and change before confirming again."+("EXTERNAL_CARD".equals(request.get("method"))?" Reconcile the external terminal payment before confirming again.":""));
                    }
                    case "INSUFFICIENT_CASH" -> quote(ticket,"Insufficient cash. Review the amount due and cash received; no payment was recorded for this attempt.");
                    case "ALREADY_CHECKED_OUT" -> {drafts.remove(ticket);saved.accept(ticket);completedNotice.accept("Vehicle is already checked out. View the saved payment in Receipt History. No payment was recorded for this attempt.");}
                    default -> error.accept("Checkout rejected. No payment was recorded for this attempt. Review the details before confirming again. "+rejection.getMessage());
                }
            },ex->error.accept("Checkout was rejected and no committed payment was found, but its local recovery key could not be cleared. Check Pending checkouts before retrying. "+ex.getMessage()));
        });
    }
    private void uncertain(Throwable ex){error.accept("Payment outcome may be uncertain. The original attempt is preserved. Use Pending checkouts to query its status before retrying with the same identity. "+ex.getMessage());}
    private void complete(long ticket,JsonNode receipt){
        // The response/status lookup confirms a committed payment. Update Parking on the FX thread
        // immediately, independently of recovery-key cleanup and receipt rendering/printing.
        drafts.remove(ticket);saved.accept(ticket);
        completedNotice.accept("Checkout completed for ticket #"+ticket+" / receipt #"+receipt.path("paymentId").asText()+". Payment is saved; reprint from Receipt History if needed.");
        call(()->{store.clear(ticket);return receipt;},this::receipt,
            ex->{completedNotice.accept("Checkout completed; local recovery metadata could not be cleared. Checking it again is safe. "+ex.getMessage());receipt(receipt);});
    }
    private void receipt(JsonNode receipt){
        try{receiptView.accept(receipt,owner);}
        catch(RuntimeException ex){completedNotice.accept("Checkout completed; receipt preview unavailable for ticket #"+receipt.path("sessionId").asText()+" / receipt #"+receipt.path("paymentId").asText()+". Reprint from Receipt History. "+ex.getMessage());}
    }
}
