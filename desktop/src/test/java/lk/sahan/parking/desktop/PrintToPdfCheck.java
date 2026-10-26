package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.*;
import javafx.application.Platform;
import javafx.print.*;
import javafx.scene.Group;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Explicit verification mode: prints fixtures only, exclusively to Microsoft Print to PDF. */
public final class PrintToPdfCheck {
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args.length==0?"target/print-pdf":args[0]).toAbsolutePath();Files.createDirectories(output);
        CountDownLatch ready=new CountDownLatch(1);Platform.startup(ready::countDown);if(!ready.await(10,TimeUnit.SECONDS))throw new AssertionError("JavaFX startup timed out");Platform.setImplicitExit(false);
        try{
            Printer printer=Printer.getAllPrinters().stream().filter(p->p.getName().equals("Microsoft Print to PDF")).findFirst().orElse(null);
            if(printer==null){System.out.println("PDF CHECK SKIPPED: Microsoft Print to PDF is not installed. No physical printer was used.");return;}
            ObjectMapper json=new ObjectMapper();
            JsonNode ticket=json.readTree("""
                {"id":103,"branch":"001 – Colombo City Centre Parking Branch","plate":"WP ABC-1234","vehicleType":"CAR","slot":"GROUND-A-002","enteredAt":"2026-10-23T04:30:00Z",
                 "pricingSummary":"LKR 150.00 per started hour after a 15 minute grace period. Daily cap LKR 1500.00. Keep this ticket for checkout."}
                """);
            JsonNode receipt=json.readTree("""
                {"paymentId":501,"sessionId":103,"branch":"001 – Colombo City Centre Parking Branch","plate":"WP ABC-1234","slot":"GROUND-A-002","enteredAt":"2026-10-23T04:30:00Z",
                 "exitedAt":"2026-10-23T05:15:00Z","billableMinutes":45,"amount":150.00,"method":"CASH","cashTendered":500.00,"changeDue":350.00}
                """);
            var qr=ThermalDocuments.encodeTicket("a4de7057-4a95-4e63-ad41-bba8403d7417");
            for(var format:ParkingPrintDocument.Format.values())for(boolean entry:new boolean[]{true,false}){
                var model=entry?ParkingPrintDocument.ticket(ticket,qr,false):ParkingPrintDocument.receipt(receipt,false);
                String kind=entry?"entry":"receipt",suffix=switch(format){case THERMAL_58->"58mm";case THERMAL_80->"80mm";case A4->"a4";};
                Path file=output.resolve(kind+"-"+suffix+".pdf");PrinterJob job=PrinterJob.createPrinterJob(printer);
                if(job==null)throw new AssertionError("Could not create PDF print job");
                try{
                    String fallback=fx(()->ThermalDocuments.selectPaper(job,format,model));
                    PageLayout page=job.getJobSettings().getPageLayout();Group document=fx(()->ThermalDocuments.preparePage(model,format,page));
                    job.getJobSettings().setJobName("Park Lanka verification "+kind+" "+suffix);job.getJobSettings().setOutputFile(file.toString());
                    if(!job.printPage(page,document)||!job.endJob())throw new AssertionError("PDF printer rejected "+file);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while((!Files.isRegularFile(file)||Files.size(file)<5)&&System.nanoTime()<deadline)Thread.sleep(50);
                    if(!Files.isRegularFile(file)||!new String(Files.readAllBytes(file),0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw new AssertionError("Missing PDF output: "+file);
                    System.out.println("PDF GENERATED: "+file.getFileName()+"; actual paper="+page.getPaper().getName()+"; printable points="+page.getPrintableWidth()+"x"+page.getPrintableHeight()+"; "+fallback);
                }catch(Throwable error){job.cancelJob();throw error;}
            }
            System.out.println("PDF PRINT CHECK PASSED: six fixture documents spooled through Microsoft Print to PDF. Thermal hardware was not used.");
        }finally{Platform.exit();}
    }
    private static <T>T fx(Supplier<T> supplier)throws Exception{FutureTask<T> task=new FutureTask<>(supplier::get);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
}
