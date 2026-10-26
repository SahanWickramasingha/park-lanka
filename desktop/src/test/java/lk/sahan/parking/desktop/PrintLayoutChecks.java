package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import javafx.geometry.Bounds;
import javafx.scene.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Runs on FX; samples are fixtures only, never business data or printer output. */
final class PrintLayoutChecks {
    static void run(Path output)throws Exception{
        Files.createDirectories(output);ObjectMapper json=new ObjectMapper();
        String token="a4de7057-4a95-4e63-ad41-bba8403d7417";
        JsonNode ticket=json.readTree("""
            {"id":103,"branch":"001 – Colombo City Centre Parking Branch","plate":"WP ABC-1234","vehicleType":"CAR","slot":"GROUND-A-002",
             "enteredAt":"2026-10-23T04:30:00Z","pricingSummary":"LKR 150.00 per started hour after a 15 minute grace period. Daily cap LKR 1500.00. Keep this ticket for checkout."}
            """);
        JsonNode receipt=json.readTree("""
            {"paymentId":501,"sessionId":103,"branch":"001 – Colombo City Centre Parking Branch","plate":"WP ABC-1234","slot":"GROUND-A-002",
             "vehicleType":"CAR","cashier":"cashier.fixture","paidAt":"2026-10-23T05:15:00Z","elapsedSeconds":2700,
             "breakdown":{"explanation":"LKR 350.00 per started hour","billedUnits":1,"rate":350,"unit":"HOUR"},
             "enteredAt":"2026-10-23T04:30:00Z","exitedAt":"2026-10-23T05:15:00Z","billableMinutes":45,"amount":350.00,"method":"CASH","cashTendered":500.00,"changeDue":150.00}
            """);
        for(var format:ParkingPrintDocument.Format.values()){
            String suffix=switch(format){case THERMAL_58->"58mm";case THERMAL_80->"80mm";case A4->"a4";};
            VBox entry=ParkingPrintDocument.ticket(ticket,ThermalDocuments.encodeTicket(token),false).build(format,ParkingPrintDocument.points(format.contentMm));
            BufferedImage entryImage=render(entry,output.resolve("entry-"+suffix+".png"));
            int[] pixels=entryImage.getRGB(0,0,entryImage.getWidth(),entryImage.getHeight(),null,0,entryImage.getWidth());
            String decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(entryImage.getWidth(),entryImage.getHeight(),pixels))),Map.of(DecodeHintType.TRY_HARDER,true)).getText();
            check(token.equals(decoded),"Rendered QR did not decode for "+suffix);
            Canvas qr=descendants(entry,Canvas.class).get(0);Label caption=descendants(entry,Label.class).stream().filter(l->l.getText().equals("Ticket identifier only")).findFirst().orElseThrow();
            check(caption.localToScene(caption.getBoundsInLocal()).getMinY()>=qr.localToScene(qr.getBoundsInLocal()).getMaxY(),"QR caption overlaps code");
            VBox paid=ParkingPrintDocument.receipt(receipt,false).build(format,ParkingPrintDocument.points(format.contentMm));render(paid,output.resolve("receipt-"+suffix+".png"));
            for(String value:List.of("LKR 350.00","LKR 500.00","LKR 150.00","cashier.fixture","LKR 350.00 per started hour"))check(descendants(paid,Label.class).stream().anyMatch(l->l.getText().equals(value)),"Stored receipt value missing: "+value);
            render(ParkingPrintDocument.receipt(receipt,true).build(format,ParkingPrintDocument.points(format.contentMm)),output.resolve("receipt-reprint-"+suffix+".png"));
            check(entry.getHeight()<700,"Excessive ticket whitespace: "+entry.getHeight());
        }
        var legacy=receipt.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove(List.of("cashTendered","changeDue"));
        VBox legacyDoc=ParkingPrintDocument.receipt(legacy,true).build(ParkingPrintDocument.Format.THERMAL_58,142);
        render(legacyDoc,output.resolve("receipt-legacy.png"));
        var legacyFields=ParkingPrintDocument.receipt(legacy,true).fields;
        check(legacyFields.stream().filter(f->Set.of("Cash Received","Change Due").contains(f.label())&&f.value().equals("Not recorded")).count()==2,"Legacy tender/change was fabricated");
        // Force narrower-than-requested printable area to verify driver-specific wrapping.
        render(ParkingPrintDocument.ticket(ticket,ThermalDocuments.encodeTicket(token),true).build(ParkingPrintDocument.Format.THERMAL_80,128),output.resolve("entry-narrow-driver.png"));
        System.out.println("PRINT LAYOUT CHECK PASSED: 58/80/A4 samples, stored cash/change, legacy values, no clipping/overlap, rendered QR decoding.");
    }
    private static BufferedImage render(VBox document,Path output)throws Exception{
        Group group=new Group(document);new Scene(group);document.applyCss();document.layout();
        for(Node child:document.getChildren()){
            Bounds bounds=child.getBoundsInParent();check(bounds.getMaxX()<=document.getWidth()+1,"Document child overflows width");check(bounds.getMaxY()<=document.getHeight()+1,"Document child overflows height");
        }
        double previous=0;
        for(Node child:document.getChildren()){Bounds b=child.getBoundsInParent();check(b.getMinY()>=previous-0.1,"Document rows overlap");previous=b.getMaxY();}
        for(Label label:descendants(document,Label.class))check(label.getHeight()+1>=label.prefHeight(label.getWidth()),"Text is clipped: "+label.getText());
        SnapshotParameters parameters=new SnapshotParameters();parameters.setFill(Color.WHITE);parameters.setTransform(new Scale(3,3));
        WritableImage image=document.snapshot(parameters,null);BufferedImage result=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<result.getHeight();y++)for(int x=0;x<result.getWidth();x++)result.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        ImageIO.write(result,"png",output.toFile());return result;
    }
    private static <T> List<T> descendants(Node root,Class<T> type){List<T> result=new ArrayList<>();if(type.isInstance(root))result.add(type.cast(root));if(root instanceof Parent parent)for(Node child:parent.getChildrenUnmodifiable())result.addAll(descendants(child,type));return result;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
