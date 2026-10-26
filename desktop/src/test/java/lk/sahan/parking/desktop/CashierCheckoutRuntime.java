package lk.sahan.parking.desktop;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.nio.file.*;

/** Real Spring Boot/PostgreSQL with physical mouse input, or explicitly labeled FX dispatch when Windows is locked. */
public final class CashierCheckoutRuntime {
 private static Stage stage;private static DesktopApp app;private static Robot robot;private static boolean physical=true;
 private static String endpoint,user;private static long ticket;private static Path output;
 public static void main(String[] args)throws Exception{
  endpoint=args[0];user=args[1];ticket=Long.parseLong(args[2]);output=Path.of(args[3]);boolean baseline=Boolean.parseBoolean(args[4]);
  if(!endpoint.matches("http://127\\.0\\.0\\.1:[0-9]+")||!user.startsWith("runtime."))throw new IllegalArgumentException("Disposable loopback fixture required");
  physical=args.length<7||!"fx".equals(args[6]);System.out.println("RUNTIME input="+(physical?"PHYSICAL_MOUSE":"JAVAFX_EVENT_DISPATCH"));
  Files.createDirectories(output);CountDownLatch ready=new CountDownLatch(1);Platform.startup(ready::countDown);if(!ready.await(10,TimeUnit.SECONDS))throw new AssertionError("JavaFX startup timed out");Platform.setImplicitExit(false);
  try{
   fx(()->{robot=new Robot();app=new DesktopApp(()->endpoint);stage=new Stage();app.start(stage);stage.hide();stage.show();stage.setAlwaysOnTop(true);stage.toFront();stage.requestFocus();return null;});Thread.sleep(400);
   System.out.println("RUNTIME source="+DesktopBuildInfo.source()+" build="+DesktopBuildInfo.label());
   fx(()->{input("Username").setText(user);input("Password").setText("runtime-password");return null;});click(()->button("Connect to Workspace"));
   await(()->button("Quote / Checkout")!=null&&(text("Open a cashier shift before checkout")||text("Could not check active shift"))&&!button("Refresh").isDisabled());
   if(!baseline){check(fx(()->text("Could not check active shift")&&!button("Quote / Checkout").isDisabled()),"Initial shift outage left checkout silently disabled");System.out.println("RUNTIME initial shift outage: actual button remains enabled for authoritative retry");}
   selectVehicle();click(()->button("Quote / Checkout"));Thread.sleep(700);
   System.out.println("RUNTIME missing-shift click: paymentDialog="+fx(()->dialog("Vehicle checkout")!=null)+" openShiftInViewport="+fx(()->inViewport(button("Open Shift"))));
   if(baseline){
    check(!fx(()->dialog("Vehicle checkout")!=null),"Missing shift unexpectedly authorized payment");
    fx(()->{ScrollPane p=parkingScroll();p.setVvalue(0);return null;});Thread.sleep(200);
   }else check(fx(()->inViewport(button("Open Shift"))),"Checkout stopped before quote and left Open Shift outside the visible button area");
   click(()->button("Open Shift"));await(()->nav("Cashier Shifts").isSelected()&&button("Open Shift")!=null&&!button("Open Shift").isDisabled());
   fx(()->{nodes(TextField.class).stream().filter(t->"0.00".equals(t.getPromptText())).findFirst().orElseThrow().setText("0.00");return null;});click(()->button("Open Shift"));
   await(()->nav("Parking").isSelected()&&text("Continue active shift #")&&!button("Refresh").isDisabled());
   check(fx(()->selectedTicket()==ticket),"Opening shift discarded the selected session");
   check(!fx(()->dialog("Vehicle checkout")!=null),"Opening shift automatically resumed checkout");
   selectVehicle();click(()->button("Quote / Checkout"));await(()->dialog("Vehicle checkout")!=null);
   fx(()->{DialogPane p=dialog("Vehicle checkout");check(((Stage)p.getScene().getWindow()).getOwner()==stage,"Dialog owned by a different cashier window");check(Platform.isFxApplicationThread(),"Payment display outside JavaFX thread");cash(p).setText("500.00");check(labels(p).stream().anyMatch(l->"Change due (LKR): 150.00".equals(l.getText())),"Real backend fee/cash/change mismatch");snapshot(p,"real-payment-dialog");return null;});
   System.out.println("RUNTIME BUTTON HANDLER VERIFIED input="+(physical?"PHYSICAL_MOUSE":"JAVAFX_EVENT_DISPATCH")+": selected ticket="+ticket+" real quote fee=350 cash=500 change=150 owner="+fx(stage::getTitle));
   click(()->(Button)dialog("Vehicle checkout").lookupButton(ButtonType.CANCEL));
   await(()->dialog("Vehicle checkout")==null);
   check(fx(()->table().getItems().stream().anyMatch(r->r.path("id").asLong()==ticket)),"Cancel removed active session");
   selectVehicle();click(()->button("Quote / Checkout"));await(()->dialog("Vehicle checkout")!=null);
   fx(()->{cash(dialog("Vehicle checkout")).setText("349.99");check(confirm().isDisabled(),"Insufficient cash accepted");cash(dialog("Vehicle checkout")).setText("500.00");return null;});
   if(args.length>5&&Boolean.parseBoolean(args[5])){
    System.out.println("RUNTIME ADVANCE QUOTE CLOCK");System.out.flush();Thread.sleep(800);
    click(CashierCheckoutRuntime::confirm);await(()->dialog("Vehicle checkout")!=null&&labels(dialog("Vehicle checkout")).stream().anyMatch(l->l.getText().contains("Quote changed")));
    fx(()->{check(cash(dialog("Vehicle checkout")).getText().equals("500.00"),"Real expired quote lost cash");check(labels(dialog("Vehicle checkout")).stream().anyMatch(l->l.getText().equals("Change due (LKR): 150.00")),"Real refreshed quote lost change");check(inViewport(labels(dialog("Vehicle checkout")).stream().filter(l->l.getText().contains("Quote changed")).findFirst().orElseThrow()),"Expiry review notice is offscreen");check(!text("Request failed")&&!text("Checkout completed"),"Quote expiry showed disconnection or stale completion behind dialog");snapshot(dialog("Vehicle checkout"),"real-expired-quote-review");return null;});
    System.out.println("RUNTIME EXPIRED QUOTE VERIFIED: fresh server quote, preserved 500 cash/150 change, no Request failed or stale completion; explicit reconfirmation required.");
   }
   click(CashierCheckoutRuntime::confirm);await(()->dialog("Parking receipt")!=null&&!button("Refresh").isDisabled());
   fx(()->{check(table().getItems().stream().noneMatch(r->r.path("id").asLong()==ticket),"Completed session remained in actual Parking table");for(String v:List.of("LKR 350.00","LKR 500.00","LKR 150.00"))check(labels(dialog("Parking receipt")).stream().anyMatch(l->v.equals(l.getText())),"Saved receipt missing "+v);snapshot(dialog("Parking receipt"),"real-saved-receipt");return null;});
   click(()->(Button)dialog("Parking receipt").lookupButton(ButtonType.CLOSE));
   await(()->dialog("Parking receipt")==null);
   System.out.println("RUNTIME CHECK PASSED input="+(physical?"PHYSICAL_MOUSE":"JAVAFX_EVENT_DISPATCH")+": real authentication/shift/quote, cancel, expiry review, 350/500/150, explicit payment, saved receipt and active-table refresh.");
  }finally{fx(()->{for(Window w:new ArrayList<>(Window.getWindows()))if(w instanceof Stage s&&(s==stage||s.getOwner()==stage))s.close();if(app!=null)app.stop();return null;});Platform.exit();}
 }
 private static void selectVehicle()throws Exception{
  fx(()->{parkingScroll().setVvalue(1);stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();return null;});Thread.sleep(200);
  click(()->table().lookupAll(".table-row-cell").stream().filter(n->n instanceof TableRow<?> r&&!r.isEmpty()&&((JsonNode)r.getItem()).path("id").asLong()==ticket).findFirst().orElseThrow());
  await(()->selectedTicket()==ticket);
 }
 private static long selectedTicket(){JsonNode row=table().getSelectionModel().getSelectedItem();return row==null?-1:row.path("id").asLong();}
 private static ScrollPane parkingScroll(){for(Node n=button("Quote / Checkout");n!=null;n=n.getParent())if(n instanceof ScrollPane p)return p;throw new AssertionError("Parking scroll missing");}
 private static boolean inViewport(Node n){if(n==null||!n.isVisible())return false;Bounds b=n.localToScreen(n.getBoundsInLocal());if(b==null)return false;for(Node p=n.getParent();p!=null;p=p.getParent())if(p instanceof ScrollPane s){Bounds v=s.lookup(".viewport").localToScreen(s.lookup(".viewport").getBoundsInLocal());if(!v.contains(b.getCenterX(),b.getCenterY()))return false;}return true;}
 private static void click(Supplier<? extends Node> target)throws Exception{
  fx(()->{Node n=target.get();check(n!=null,"Mouse target missing");if(n.getScene().getWindow() instanceof Stage s){s.toFront();s.requestFocus();}return null;});Thread.sleep(120);
  fx(()->{Node n=target.get();check(n!=null,"Input target missing");check(!n.isDisabled(),"Input target disabled: "+n);check(inViewport(n),"Input target clipped/offscreen: "+n);if(!physical){if(n instanceof Button b)b.fire();else if(n instanceof TableRow<?> row)table().getSelectionModel().select((JsonNode)row.getItem());else throw new AssertionError("Unsupported FX input target: "+n);}else{Bounds b=n.localToScreen(n.getBoundsInLocal());robot.mouseMove(b.getCenterX(),b.getCenterY());robot.mouseClick(MouseButton.PRIMARY);}return null;});Thread.sleep(180);
 }
 private static TextField cash(DialogPane p){return (TextField)labels(p).stream().filter(l->"Cash received (LKR)".equals(l.getText())).findFirst().orElseThrow().getLabelFor();}
 private static Button confirm(){return descendants(dialog("Vehicle checkout"),Button.class).stream().filter(b->b.getText().equals("Confirm Payment & Print")).findFirst().orElseThrow();}
 private static Button button(String name){return nodes(Button.class).stream().filter(b->name.equals(b.getText())).findFirst().orElse(null);}
 private static ToggleButton nav(String name){return nodes(ToggleButton.class).stream().filter(b->name.equals(b.getText())).findFirst().orElseThrow();}
 private static TextField input(String name){return (TextField)nodes(Label.class).stream().filter(l->name.equals(l.getText())&&l.getLabelFor()!=null).findFirst().orElseThrow().getLabelFor();}
 @SuppressWarnings("unchecked") private static TableView<JsonNode> table(){return (TableView<JsonNode>)nodes(TableView.class).get(0);}
 private static boolean text(String text){return nodes(Label.class).stream().anyMatch(l->l.getText()!=null&&l.getText().contains(text));}
 private static DialogPane dialog(String header){for(Window w:Window.getWindows())if(w!=stage&&w.isShowing()&&w.getScene()!=null)for(DialogPane p:descendants(w.getScene().getRoot(),DialogPane.class))if(header.equals(p.getHeaderText()))return p;return null;}
 private static List<Label> labels(Node p){return descendants(p,Label.class);}
 private static <T>List<T> nodes(Class<T> type){return descendants(stage.getScene().getRoot(),type);}
 private static <T>List<T> descendants(Node n,Class<T> type){Set<Node> all=new LinkedHashSet<>();walk(n,all);return all.stream().filter(type::isInstance).map(type::cast).toList();}
 private static void walk(Node n,Set<Node> all){if(!n.isVisible()||!all.add(n))return;if(n instanceof Parent p)p.getChildrenUnmodifiable().forEach(c->walk(c,all));if(n instanceof ScrollPane s&&s.getContent()!=null)walk(s.getContent(),all);}
 private static void snapshot(Node n,String name){try{javafx.scene.image.WritableImage img=n.snapshot(null,null);var out=new java.awt.image.BufferedImage((int)img.getWidth(),(int)img.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);for(int y=0;y<out.getHeight();y++)for(int x=0;x<out.getWidth();x++)out.setRGB(x,y,img.getPixelReader().getArgb(x,y));javax.imageio.ImageIO.write(out,"png",output.resolve(name+".png").toFile());}catch(Exception e){throw new RuntimeException(e);}}
 private static <T>T fx(Supplier<T> work)throws Exception{FutureTask<T> f=new FutureTask<>(work::get);Platform.runLater(f);return f.get(15,TimeUnit.SECONDS);}
 private static void await(BooleanSupplier test)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<end){if(fx(test::getAsBoolean))return;Thread.sleep(40);}System.out.println("RUNTIME timeout labels="+fx(()->nodes(Label.class).stream().map(Label::getText).toList()));throw new AssertionError("Native runtime UI timed out");}
 private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
