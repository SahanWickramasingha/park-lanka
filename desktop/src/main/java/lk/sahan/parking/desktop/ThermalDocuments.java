package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.print.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.transform.Scale;
import javafx.stage.Window;
import java.util.*;
import java.util.concurrent.*;
import static lk.sahan.parking.desktop.ParkingPrintDocument.*;

/** Preview and printing build separate measured nodes from the same immutable document. */
final class ThermalDocuments {
    private static final ExecutorService PRINT=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"parking-print");t.setDaemon(true);return t;});
    static BitMatrix encodeTicket(String token)throws WriterException{
        return new QRCodeWriter().encode(token,BarcodeFormat.QR_CODE,0,0,Map.of(EncodeHintType.MARGIN,4,EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M));
    }
    static void ticket(JsonNode ticket,Window owner,boolean reprint){
        Scene screen=owner==null?null:owner.getScene();
        CompletableFuture.supplyAsync(()->{try{return encodeTicket(ticket.path("opaqueIdentifier").asText());}catch(Exception e){throw new CompletionException(e);}},PRINT)
            .whenComplete((qr,error)->Platform.runLater(()->{
                if(owner==null||!owner.isShowing()||owner.getScene()!=screen)return;
                preview("Entry saved · Ticket preview",ParkingPrintDocument.ticket(ticket,error==null?qr:null,reprint),owner,
                    "Entry is saved. Canceling or failing printing does not undo it."+(error==null?"":" QR unavailable; reprint this ticket to try again."));
            }));
    }
    static void receipt(JsonNode receipt,Window owner,boolean reprint){preview("Parking receipt",ParkingPrintDocument.receipt(receipt,reprint),owner,"Payment is saved. Printing never creates another payment.");}
    static void text(String title,String text,Window owner){preview(title,ParkingPrintDocument.text(title,text),owner,"Select supported paper in printer settings.");}
    private static void preview(String title,ParkingPrintDocument model,Window owner,String saved){
        Dialog<Void> dialog=new Dialog<>();Ui.dialog(dialog,owner);dialog.setTitle(title);dialog.setHeaderText(title);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ComboBox<String> paper=new ComboBox<>();for(Format f:Format.values())paper.getItems().add(f.label);paper.setValue(Format.THERMAL_80.label);
        Label status=Ui.notice();Ui.notice(status,saved,false);
        StackPane preview=new StackPane();preview.setAlignment(Pos.TOP_CENTER);preview.setStyle("-fx-padding:12;-fx-background-color:#e3e8ef;");
        Runnable render=()->{Format f=Format.from(paper.getValue());preview.getChildren().setAll(model.build(f,points(f.contentMm)));};render.run();paper.setOnAction(e->render.run());
        ScrollPane scroll=new ScrollPane(preview);scroll.setFitToWidth(true);scroll.setPrefViewportHeight(430);
        Button print=Ui.button("Print","primary");print.setOnAction(e->{
            print.setDisable(true);status.setText("Finding printers...");
            CompletableFuture.supplyAsync(PrinterJob::createPrinterJob,PRINT).whenComplete((job,error)->Platform.runLater(()->{
                if(!dialog.isShowing()){if(job!=null)job.cancelJob();return;}
                if(error!=null||job==null){Ui.notice(status,saved+" No printer is available. Install/select a printer and try again.",true);print.setDisable(false);return;}
                try{
                    if(!job.showPrintDialog(owner)){job.cancelJob();status.setText(saved+" Printing canceled.");print.setDisable(false);return;}
                    Format format=Format.from(paper.getValue());String fallback=selectPaper(job,format,model);
                    if(!job.showPageSetupDialog(owner)){job.cancelJob();status.setText(saved+" Printing canceled.");print.setDisable(false);return;}
                    PageLayout page=job.getJobSettings().getPageLayout();Group output=preparePage(model,format,page);
                    status.setText(saved+" "+fallback+" Printing...");paper.setDisable(true);
                    CompletableFuture.supplyAsync(()->{try{boolean ok=job.printPage(page,output);if(ok)ok=job.endJob();else job.cancelJob();return ok;}catch(Exception failure){job.cancelJob();throw new CompletionException(failure);}},PRINT)
                        .whenComplete((ok,failure)->Platform.runLater(()->{paper.setDisable(false);print.setDisable(false);Ui.notice(status,saved+" "+fallback+(failure==null&&Boolean.TRUE.equals(ok)?" Print job submitted.":" Printing failed; use reprint when the printer is ready."),failure!=null||!Boolean.TRUE.equals(ok));}));
                }catch(Exception failure){job.cancelJob();print.setDisable(false);Ui.notice(status,saved+" "+failure.getMessage(),true);}
            }));
        });
        Label hint=Ui.text("Layouts use physical points. Thermal forms/lengths must be supported by the driver; A4/PDF keeps the receipt compact. Preview and print use the same document builder.","muted");hint.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(12,Ui.flow(Ui.field("Paper layout",paper),print),hint,scroll,status));dialog.getDialogPane().setPrefWidth(600);dialog.show();
    }
    static Group preparePage(ParkingPrintDocument model,Format format,PageLayout page){
        VBox document=model.build(format,page.getPrintableWidth());
        double factor=Math.min(1,Math.min(page.getPrintableWidth()/document.getWidth(),page.getPrintableHeight()/document.getHeight()));
        if(factor<0.65)throw new IllegalArgumentException("The receipt is too long for readable printing on this form. Select a longer roll form or A4 in printer settings.");
        Group output=new Group(document);new Scene(output);document.applyCss();document.getTransforms().add(new Scale(factor,factor));
        document.setLayoutX(Math.max(0,(page.getPrintableWidth()-document.getWidth()*factor)/2));return output;
    }
    static String selectPaper(PrinterJob job,Format format,ParkingPrintDocument model){
        Printer printer=job.getPrinter();
        if(format==Format.A4){
            if(printer.getPrinterAttributes().getSupportedPapers().contains(Paper.A4)){job.getJobSettings().setPageLayout(printer.createPageLayout(Paper.A4,PageOrientation.PORTRAIT,Printer.MarginType.DEFAULT));return "";}
            return "A4 is unavailable; using the selected driver's paper without enlarging the receipt.";
        }
        // JavaFX has no public custom-Paper constructor. Use only driver-advertised roll forms.
        var candidates=printer.getPrinterAttributes().getSupportedPapers().stream().filter(p->Math.abs(p.getWidth()-points(format.paperMm))<=points(3)).sorted(Comparator.comparingDouble(Paper::getHeight)).toList();
        for(Paper candidate:candidates){
            PageLayout minimum=printer.createPageLayout(candidate,PageOrientation.PORTRAIT,Printer.MarginType.HARDWARE_MINIMUM);double inset=points(2);
            PageLayout layout=printer.createPageLayout(candidate,PageOrientation.PORTRAIT,Math.max(inset,minimum.getLeftMargin()),Math.max(inset,minimum.getRightMargin()),Math.max(inset,minimum.getTopMargin()),Math.max(inset,minimum.getBottomMargin()));
            if(model.build(format,layout.getPrintableWidth()).getHeight()<=layout.getPrintableHeight()){job.getJobSettings().setPageLayout(layout);return "";}
        }
        return "No supported "+format.label+" roll form fits. Using the driver's selected paper with a compact receipt. Configure a suitable roll width/length in Windows printer settings to print on a roll.";
    }
}
