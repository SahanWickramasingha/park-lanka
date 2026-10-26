package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.beans.binding.Bindings;
import javafx.print.PrinterJob;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.*;
import javafx.stage.Window;
import java.util.Map;
import java.math.BigDecimal;

/** Payment presentation and receipt printing share the app's native JavaFX theme. */
final class ParkingDialogs {
    private ParkingDialogs() {}

    static Dialog<Map<String, String>> payment(JsonNode quote, Window owner) {
        return payment(quote,owner,Map.of(),"");
    }
    static Dialog<Map<String, String>> payment(JsonNode quote, Window owner, Map<String,String> draft, String message) {
        if(!javafx.application.Platform.isFxApplicationThread())throw new IllegalStateException("Payment dialogs require the JavaFX Application Thread");
        if(!quote.path("amount").isNumber())throw new IllegalArgumentException("The server did not return a usable parking quote. Refresh and retry.");
        if(quote.path("quoteId").asText().isBlank())throw new IllegalArgumentException("The backend did not return a server-issued quote ID. Restart the updated backend before checkout.");
        Dialog<Map<String, String>> dialog = new Dialog<>(); Ui.dialog(dialog, owner);
        dialog.setTitle("Collect payment"); dialog.setHeaderText("Vehicle checkout");
        ButtonType pay = new ButtonType("Confirm Payment & Print", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(pay, ButtonType.CANCEL);
        ToggleGroup methods = new ToggleGroup();
        ToggleButton cash = Ui.style(new ToggleButton("Cash"), "payment-choice"), card = Ui.style(new ToggleButton("External Card"), "payment-choice");
        cash.setToggleGroup(methods); card.setToggleGroup(methods); cash.setSelected(true);
        cash.setOnAction(e -> cash.setSelected(true)); card.setOnAction(e -> card.setSelected(true));
        TextField reference = new TextField(); reference.setPromptText("Reference from the external card terminal");
        CheckBox confirmed = new CheckBox("I have confirmed this payment on the external terminal."); confirmed.setWrapText(true);
        VBox cardFields = new VBox(14, Ui.field("Card transaction reference", reference), confirmed);
        cardFields.visibleProperty().bind(card.selectedProperty()); cardFields.managedProperty().bind(card.selectedProperty());
        Label validation = Ui.notice();
        TextField tendered=new TextField();tendered.setPromptText("Cash received");Label change=Ui.text("Enter cash received","muted");
        VBox cashFields=new VBox(8,Ui.field("Cash received (LKR)",tendered),change);cashFields.visibleProperty().bind(cash.selectedProperty());cashFields.managedProperty().bind(cash.selectedProperty());
        Ui.style(change,"amount");change.setStyle("-fx-font-size:20px;");
        BigDecimal due=quote.path("amount").decimalValue();
        tendered.textProperty().addListener((o,old,value)->{try{BigDecimal amount=cashAmount(value);change.setText(amount.compareTo(due)<0?"Still required (LKR): "+due.subtract(amount).setScale(2).toPlainString():"Change due (LKR): "+amount.subtract(due).setScale(2).toPlainString());}catch(Exception ex){change.setText("Enter a nonnegative amount with at most two decimals");}});
        GridPane facts = details("Number plate", quote.path("plate").asText(), "Parking slot", quote.path("slot").asText());
        VBox content = Ui.card(validation,Ui.text("Parking fee", "eyebrow"), Ui.text("LKR " + Ui.money(quote.path("amount")), "amount"),
                details("Ticket", "#"+quote.path("sessionId").asText(), "Vehicle type", Ui.display(quote.path("vehicleType"),"vehicleType")),facts,
                details("Entered",Ui.date(quote.path("enteredAt").asText()),"Billing cutoff",Ui.date(quote.path("billingCutoff").asText(quote.path("calculatedAt").asText()))),
                Ui.text("Quote valid until "+Ui.date(quote.path("expiresAt").asText())+". Expiry requires review of a fresh quote.","muted"),
                Ui.text("Parking duration: "+(quote.path("breakdown").has("elapsedSeconds")?Ui.duration(quote.path("breakdown").path("elapsedSeconds").asLong()):"Not supplied")+" · "+quote.path("billableMinutes").asText()+" billable minute(s)","muted"),
                Ui.text(quote.path("breakdown").path("explanation").asText("Entry pricing applies."),"muted"),
                Ui.text(quote.path("breakdown").has("billedUnits")?"Billed units: "+quote.path("breakdown").path("billedUnits").asText()+" · Rate LKR "+Ui.money(quote.path("breakdown").path("rate")):"","muted"),
                Ui.text("Payment method", "field-label"), Ui.flow(cash, card), cashFields,cardFields,
                Ui.text("Confirm this amount. Any changed quote requires confirmation again. External card payments must be processed and confirmed on your own terminal; this app records the payment only.", "muted"));
        content.setPrefWidth(540);ScrollPane scroll=Ui.scroll(content);scroll.setPrefViewportWidth(570);
        scroll.setPrefViewportHeight(Math.min(560,javafx.stage.Screen.getPrimary().getVisualBounds().getHeight()-210));
        dialog.getDialogPane().setContent(scroll);
        dialog.setOnShown(e->{if(dialog.getDialogPane().getScene().getWindow() instanceof javafx.stage.Stage s){s.toFront();s.requestFocus();}tendered.requestFocus();System.out.println("CHECKOUT dialog shown FX="+javafx.application.Platform.isFxApplicationThread()+" ownerShowing="+(dialog.getOwner()!=null&&dialog.getOwner().isShowing()));});
        Button submit = (Button) dialog.getDialogPane().lookupButton(pay); Ui.style(submit, "primary");
        submit.disableProperty().bind(Bindings.createBooleanBinding(() -> {
            if(card.isSelected())return reference.getText().isBlank()||reference.getText().trim().length()>80||!confirmed.isSelected();
            try{return cashAmount(tendered.getText()).compareTo(due)<0;}catch(Exception ex){return true;}
        },card.selectedProperty(),reference.textProperty(),confirmed.selectedProperty(),tendered.textProperty()));
        dialog.setResultConverter(button -> {
            if(button!=pay)return null;
            Map<String,String> result=new java.util.HashMap<>(Map.of("method",card.isSelected()?"EXTERNAL_CARD":"CASH","reference",card.isSelected()?reference.getText().trim():"","confirmedAmount",due.toPlainString(),"externalConfirmed",String.valueOf(card.isSelected()),"quoteId",quote.path("quoteId").asText()));
            if(!card.isSelected())result.put("cashTendered",tendered.getText().trim());return result;
        });
        tendered.setText(draft.getOrDefault("cashTendered",due.signum()==0?"0.00":""));
        reference.setText(draft.getOrDefault("reference",""));
        if(draft.getOrDefault("method","").equals("EXTERNAL_CARD"))card.setSelected(true);
        // A changed quote or shift always requires a fresh explicit confirmation.
        confirmed.setSelected(false);
        if(!message.isBlank())Ui.notice(validation,message,true);
        return dialog;
    }
    private static BigDecimal cashAmount(String text){
        if(!text.trim().matches("[0-9]+(?:\\.[0-9]{1,2})?"))throw new IllegalArgumentException();
        BigDecimal amount=new BigDecimal(text.trim());
        if(amount.signum()<0||amount.scale()>2||amount.compareTo(new BigDecimal("9999999999.99"))>0)throw new IllegalArgumentException();
        return amount;
    }

    static void receipt(JsonNode receipt, Window owner) {
        ThermalDocuments.receipt(receipt,owner,false);
    }
    static Dialog<ButtonType> receiptDialog(JsonNode receipt, Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>(); Ui.dialog(dialog, owner); dialog.setTitle("Parking receipt"); dialog.setHeaderText("Payment recorded");
        ButtonType print = new ButtonType("Print Receipt", ButtonBar.ButtonData.APPLY); dialog.getDialogPane().getButtonTypes().addAll(print, ButtonType.CLOSE);
        VBox content = Ui.card(Ui.badge("Paid"), Ui.text("LKR " + Ui.money(receipt.path("amount")), "amount"),
                Ui.text("RECEIPT #" + receipt.path("paymentId").asText() + "  /  TICKET #" + receipt.path("sessionId").asText(), "receipt-code"),
                details("Number plate", receipt.path("plate").asText(), "Parking slot", receipt.path("slot").asText()),
                details("Entered", Ui.date(receipt.path("enteredAt").asText()), "Exited", Ui.date(receipt.path("exitedAt").asText())),
                details("Billable duration", receipt.path("billableMinutes").asText() + " started minute(s)", "Payment method", receipt.path("method").asText().equals("CASH") ? "Cash" : "External Card"));
        String reference = receipt.path("reference").asText(""); if (!reference.isBlank()) content.getChildren().add(details("Card reference", reference, "Currency", "LKR"));
        if(receipt.path("method").asText().equals("CASH"))content.getChildren().add(details("Cash Received",recordedMoney(receipt,"cashTendered"),"Change Due",recordedMoney(receipt,"changeDue")));
        content.setPrefWidth(540); dialog.getDialogPane().setContent(content); Ui.style(dialog.getDialogPane().lookupButton(print), "primary");
        dialog.setResultConverter(button -> button); return dialog;
    }
    private static GridPane details(String leftTitle, String leftValue, String rightTitle, String rightValue) {
        GridPane grid = new GridPane(); grid.setHgap(24);
        ColumnConstraints left = new ColumnConstraints(), right = new ColumnConstraints(); left.setPercentWidth(50); right.setPercentWidth(50); grid.getColumnConstraints().addAll(left, right);
        grid.add(new VBox(5, Ui.text(leftTitle, "field-label"), Ui.text(leftValue)), 0, 0); grid.add(new VBox(5, Ui.text(rightTitle, "field-label"), Ui.text(rightValue)), 1, 0); return grid;
    }
    static String receiptBody(JsonNode receipt) {
        return "PARK LANKA · RECEIPT #" + receipt.path("paymentId").asText()
                + "\nTicket #" + receipt.path("sessionId").asText() + "\nPlate: " + receipt.path("plate").asText() + "   Slot: " + receipt.path("slot").asText()
                + "\nEntry: " + Ui.date(receipt.path("enteredAt").asText()) + "\nExit: " + Ui.date(receipt.path("exitedAt").asText())
                + "\nDuration: " + receipt.path("billableMinutes").asText() + " billable minutes\nAmount: LKR " + receipt.path("amount").asText()
                + "\nBranch: "+receipt.path("branch").asText("")
                + "\nMethod: " + receipt.path("method").asText() + (receipt.path("reference").asText("").isBlank() ? "" : "\nReference: " + receipt.path("reference").asText())
                + (receipt.path("method").asText().equals("CASH")?"\nCash Received: "+recordedMoney(receipt,"cashTendered")+"\nChange Due: "+recordedMoney(receipt,"changeDue"):"")
                + correctionBody(receipt);
    }
    private static String recordedMoney(JsonNode receipt,String field){return receipt.path(field).isNumber()?"LKR "+Ui.money(receipt.path(field)):"Not recorded";}
    private static String correctionBody(JsonNode receipt){
        if(!receipt.has("corrections"))return "";StringBuilder text=new StringBuilder("\nOriginal paid: "+Ui.date(receipt.path("paidAt").asText(receipt.path("exitedAt").asText()))
            +"\nRefunds: LKR "+receipt.path("refunded").asText("0.00")+"\nVoids: LKR "+receipt.path("voided").asText("0.00")+"\nNet of corrections: LKR "+receipt.path("netAmount").asText());
        for(JsonNode correction:receipt.path("corrections"))text.append("\n\n").append(correction.path("type").asText()).append(" #").append(correction.path("id").asText()).append(" · LKR ").append(correction.path("amount").asText()).append("\n").append(Ui.date(correction.path("occurredAt").asText())).append("\nBy ").append(correction.path("actor").asText()).append(" · ").append(correction.path("reason").asText()).append(correction.path("externalReference").isTextual()?"\nTerminal reference: "+correction.path("externalReference").asText():"");
        return text.toString();
    }
    private static void print(String body, Window owner) {
        PrinterJob job = PrinterJob.createPrinterJob(); if (job == null || !job.showPrintDialog(owner)) return;
        Text text = new Text(body); text.setFont(Font.font("Monospaced", 11)); if (job.printPage(text)) job.endJob(); else job.cancelJob();
    }
}
