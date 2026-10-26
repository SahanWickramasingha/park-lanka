package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.common.BitMatrix;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import java.util.*;

/** Print coordinates are points (1/72 inch), independent of monitor DPI. */
final class ParkingPrintDocument {
    enum Format {
        THERMAL_58("58 mm",58,50), THERMAL_80("80 mm",80,72), A4("A4 / PDF",210,85);
        final String label; final double paperMm,contentMm;
        Format(String label,double paperMm,double contentMm){this.label=label;this.paperMm=paperMm;this.contentMm=contentMm;}
        static Format from(String label){return Arrays.stream(values()).filter(f->f.label.equals(label)).findFirst().orElse(THERMAL_80);}
    }
    record Field(String label,String value) {}
    final String title,branch; final List<Field> fields; final BitMatrix qr; final boolean reprint;
    private ParkingPrintDocument(String title,String branch,List<Field> fields,BitMatrix qr,boolean reprint){this.title=title;this.branch=branch;this.fields=List.copyOf(fields);this.qr=qr;this.reprint=reprint;}
    static double points(double mm){return mm*72/25.4;}
    static ParkingPrintDocument ticket(JsonNode d,BitMatrix qr,boolean reprint){
        return new ParkingPrintDocument("ENTRY TICKET #"+d.path("id").asText(),d.path("branch").asText(),List.of(
            field("Plate",d.path("plate").asText()),field("Vehicle",d.path("vehicleType").asText()),field("Slot",d.path("slot").asText()),
            field("Entered",Ui.date(d.path("enteredAt").asText())),field("Pricing",d.path("pricingSummary").asText())),qr,reprint);
    }
    static ParkingPrintDocument receipt(JsonNode d,boolean reprint){
        List<Field> rows=new ArrayList<>(List.of(field("Ticket","#"+d.path("sessionId").asText()),field("Plate",d.path("plate").asText()),field("Vehicle",d.path("vehicleType").asText("Not recorded")),field("Slot",d.path("slot").asText()),
            field("Entered",Ui.date(d.path("enteredAt").asText())),field("Exited",Ui.date(d.path("exitedAt").asText())),
            field("Duration",d.path("billableMinutes").asText()+" billable minute(s)"),field("Amount Due",money(d,"amount")),field("Amount Paid",money(d,"amount")),
            field("Method",d.path("method").asText().equals("CASH")?"Cash":"External Card")));
        if(d.path("method").asText().equals("CASH")){rows.add(field("Cash Received",money(d,"cashTendered")));rows.add(field("Change Due",money(d,"changeDue")));}
        if(d.hasNonNull("billingCutoff"))rows.add(field("Billed until",Ui.date(d.path("billingCutoff").asText())));
        rows.add(field("Parking duration",d.has("elapsedSeconds")?Ui.duration(d.path("elapsedSeconds").asLong()):"Not recorded"));
        rows.add(field("Pricing",d.path("breakdown").path("explanation").asText("Historical pricing breakdown not recorded")));
        if(d.path("breakdown").has("billedUnits"))rows.add(field("Billed units",d.path("breakdown").path("billedUnits").asText()+" "+d.path("breakdown").path("unit").asText().toLowerCase(Locale.ROOT)+" unit(s) at LKR "+Ui.money(d.path("breakdown").path("rate"))));
        rows.add(field("Cashier",d.path("cashier").asText("Not recorded")));rows.add(field("Paid at",d.path("paidAt").isTextual()?Ui.date(d.path("paidAt").asText()):"Not recorded"));
        if(!d.path("reference").asText("").isBlank())rows.add(field("Terminal Ref",d.path("reference").asText()));
        if(d.has("corrections")){
            rows.add(field("Original paid",Ui.date(d.path("paidAt").asText(d.path("exitedAt").asText()))));
            rows.add(field("Refunds",money(d,"refunded")));rows.add(field("Voids",money(d,"voided")));rows.add(field("Net",money(d,"netAmount")));
            for(JsonNode c:d.path("corrections")){
                rows.add(field(c.path("type").asText()+" #"+c.path("id").asText(),money(c,"amount")));rows.add(field("When",Ui.date(c.path("occurredAt").asText())));
                rows.add(field("By",c.path("actor").asText()));rows.add(field("Reason",c.path("reason").asText()));
                if(!c.path("externalReference").asText("").isBlank())rows.add(field("Terminal Ref",c.path("externalReference").asText()));
            }
        }
        return new ParkingPrintDocument("PAYMENT RECEIPT #"+d.path("paymentId").asText(),d.path("branch").asText(),rows,null,reprint);
    }
    static ParkingPrintDocument text(String title,String body){return new ParkingPrintDocument(title,"",List.of(field("",body)),null,false);}
    private static Field field(String label,String value){return new Field(label,value);}
    private static String money(JsonNode d,String key){return d.path(key).isNumber()?"LKR "+Ui.money(d.path(key)):"Not recorded";}
    VBox build(Format format,double printableWidth){
        double width=Math.min(points(format.contentMm),printableWidth);
        if(width<points(30))throw new IllegalArgumentException("Printable width is too narrow. Select the correct paper in printer settings.");
        double font=format==Format.THERMAL_58?8:9;
        VBox root=new VBox(6);root.setAlignment(Pos.TOP_CENTER);root.setFillWidth(true);root.setPadding(new Insets(3));root.setStyle("-fx-background-color:white;");
        root.setMinWidth(width);root.setPrefWidth(width);root.setMaxWidth(width);root.setMinHeight(Region.USE_PREF_SIZE);
        var logo=BrandAssets.logo();logo.setFitWidth(Math.min(width-6,160));root.getChildren().add(logo);
        if(!branch.isBlank())root.getChildren().add(label(branch,font,true));root.getChildren().add(label(title,font+1,true));
        if(reprint)root.getChildren().add(label("REPRINT",font+1,true));
        for(Field row:fields){
            if(row.label().isEmpty()){root.getChildren().add(label(row.value(),font,false));continue;}
            GridPane grid=new GridPane();grid.setHgap(5);grid.setMinHeight(Region.USE_PREF_SIZE);
            ColumnConstraints left=new ColumnConstraints(),right=new ColumnConstraints();left.setPercentWidth(39);right.setPercentWidth(61);grid.getColumnConstraints().addAll(left,right);
            Label key=label(row.label(),font,true),value=label(row.value(),font,false);GridPane.setValignment(key,VPos.TOP);GridPane.setValignment(value,VPos.TOP);
            grid.add(key,0,0);grid.add(value,1,0);root.getChildren().add(grid);
        }
        if(qr!=null){
            // Integral modules include the encoder's four-module quiet zone. Never stretch the QR.
            int module=Math.max(1,(int)(Math.min(width-10,88)/qr.getWidth()));
            Canvas canvas=new Canvas(qr.getWidth()*module,qr.getHeight()*module);var g=canvas.getGraphicsContext2D();
            g.setFill(Color.WHITE);g.fillRect(0,0,canvas.getWidth(),canvas.getHeight());g.setFill(Color.BLACK);
            for(int y=0;y<qr.getHeight();y++)for(int x=0;x<qr.getWidth();x++)if(qr.get(x,y))g.fillRect(x*module,y*module,module,module);
            Label caption=label("Ticket identifier only",font,false);caption.setAlignment(Pos.CENTER);
            VBox area=new VBox(4,canvas,caption);area.setAlignment(Pos.CENTER);area.setMinHeight(Region.USE_PREF_SIZE);root.getChildren().add(area);
        }
        root.getChildren().add(label("Times: Asia/Colombo",font-1,false));measure(root,width);return root;
    }
    static void measure(VBox document,double width){Group host=new Group(document);new Scene(host);document.applyCss();document.resize(width,document.prefHeight(width));document.layout();host.getChildren().clear();}
    private static Label label(String text,double size,boolean bold){
        Label label=new Label(text);label.setWrapText(true);label.setMinWidth(0);label.setMaxWidth(Double.MAX_VALUE);label.setMinHeight(Region.USE_PREF_SIZE);label.setAlignment(Pos.TOP_LEFT);
        label.setStyle("-fx-text-fill:black;-fx-font-family:'SansSerif';-fx-font-size:"+size+"px;-fx-font-weight:"+(bold?"bold":"normal")+";");return label;
    }
}
