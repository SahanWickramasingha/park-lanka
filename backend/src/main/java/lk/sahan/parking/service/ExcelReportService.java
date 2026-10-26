package lk.sahan.parking.service;

import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ExcelReportService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
    private final AdminService records;
    private final BranchService branches;
    private final CorrectionService corrections;
    public ExcelReportService(AdminService records, BranchService branches,CorrectionService corrections) { this.records = records; this.branches = branches;this.corrections=corrections; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] generate(String dataset, Long branchId, LocalDate from, LocalDate to) throws IOException {
        // Read all pages in one consistent transaction, using the same safe field projections as Saved Data.
        var first = records.records(dataset, branchId, 0, 100, from, to);
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Sheet summary = workbook.createSheet("Report");
            CellStyle heading = workbook.createCellStyle(); Font font = workbook.createFont(); font.setBold(true); heading.setFont(font);
            CellStyle currency = workbook.createCellStyle(); currency.setDataFormat(workbook.createDataFormat().getFormat("\"LKR\" #,##0.00"));
            CellStyle timestamp = workbook.createCellStyle(); timestamp.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            String[] fields = fields(dataset.toLowerCase(Locale.ROOT));
            Sheet data = dataSheet(workbook, "Records", fields, heading); int rowIndex = 1, sheetNumber = 1;
            long count = 0; BigDecimal revenue = BigDecimal.ZERO;
            for (int page = 0; page < Math.max(1, first.pages()); page++) {
                var result = page == 0 ? first : records.records(dataset, branchId, page, 100, from, to);
                for (Map<String, Object> record : result.rows()) {
                    if (rowIndex == 1_048_576) { data = dataSheet(workbook, "Records " + (++sheetNumber), fields, heading); rowIndex = 1; }
                    Row row = data.createRow(rowIndex++);
                    for (int column = 0; column < fields.length; column++) {
                        String field = fields[column]; Object value = record.get(field); Cell cell = row.createCell(column);
                        if (value == null) continue;
                        if (value instanceof Instant instant) { cell.setCellValue(LocalDateTime.ofInstant(instant, ZONE)); cell.setCellStyle(timestamp); }
                        else if (value instanceof BigDecimal amount) { cell.setCellValue(amount.doubleValue()); cell.setCellStyle(currency); }
                        else if (value instanceof Number number && !Set.of("id", "ticket").contains(field)) cell.setCellValue(number.doubleValue());
                        // Explicit string cells: user content is NEVER passed to setCellFormula.
                        else cell.setCellValue(value instanceof Boolean bool ? (field.equals("occupied") ? (bool ? "Occupied" : "Free") : (bool ? "Yes" : "No")) : value.toString());
                    }
                    count++; if (record.get("amount") instanceof BigDecimal amount) revenue = revenue.add(amount);
                }
            }
            if(dataset.equalsIgnoreCase("payments")){
                Sheet changes=dataSheet(workbook,"Corrections",new String[]{"Correction ID","Payment ID","Ticket","Type","Amount LKR","Original paid at Colombo","Correction at Colombo","Branch","Method","External reference","Movement shift","Actor","Reason"},heading);
                int correctionRow=1;BigDecimal refunds=BigDecimal.ZERO,voids=BigDecimal.ZERO;var firstChanges=corrections.list(branchId,null,from,to,0,100);
                for(int page=0;page<Math.max(1,firstChanges.pages());page++)for(var correction:(page==0?firstChanges:corrections.list(branchId,null,from,to,page,100)).rows()){
                    if(correctionRow==1_048_576){changes=dataSheet(workbook,"Corrections "+page,new String[]{"Correction ID","Payment ID","Ticket","Type","Amount LKR","Original paid at Colombo","Correction at Colombo","Branch","Method","External reference","Movement shift","Actor","Reason"},heading);correctionRow=1;}
                    Object[] values={correction.id(),correction.paymentId(),correction.sessionId(),correction.type(),correction.amount(),correction.originalPaidAt(),correction.occurredAt(),correction.branch(),correction.method(),correction.externalReference(),correction.movementShiftId(),correction.actor(),correction.reason()};Row row=changes.createRow(correctionRow++);
                    for(int col=0;col<values.length;col++){Object value=values[col];if(value==null)continue;Cell cell=row.createCell(col);if(value instanceof BigDecimal amount){cell.setCellValue(amount.doubleValue());cell.setCellStyle(currency);}else if(value instanceof Instant at){cell.setCellValue(LocalDateTime.ofInstant(at,ZONE));cell.setCellStyle(timestamp);}else cell.setCellValue(value.toString());}
                    if(correction.type().equals("REFUND"))refunds=refunds.add(correction.amount());else voids=voids.add(correction.amount());
                }
                metadata(summary,12,"Refunds by correction date (LKR)",refunds.toPlainString(),null);metadata(summary,13,"Voids by correction date (LKR)",voids.toPlainString(),null);metadata(summary,14,"Net revenue in period (LKR)",revenue.subtract(refunds).subtract(voids).toPlainString(),null);
                metadata(summary,15,"Correction date basis","Original collections use paidAt; corrections use occurredAt, independently of original sale date. Asia/Colombo.",null);
            }
            metadata(summary, 0, "Parking POS · " + title(dataset) + " Report", "", heading);
            metadata(summary, 1, "Generated at (Asia/Colombo)", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now(ZONE)), null);
            metadata(summary, 2, "Dataset", title(dataset), null);
            metadata(summary, 3, "Branch", branchId == null ? "All branches (including archived)" : branches.label(branches.require(branchId)) + " · " + branches.require(branchId).getStatus(), null);
            metadata(summary, 4, "From date (inclusive)", from == null ? "Any" : from.toString(), null);
            metadata(summary, 5, "To date (inclusive)", to == null ? "Any" : to.toString(), null);
            metadata(summary, 6, "Date basis", dataset.equalsIgnoreCase("sessions") ? "Session entry time · Asia/Colombo" : dataset.equalsIgnoreCase("payments") ? "Payment time · Asia/Colombo" : "Not applicable", null);
            metadata(summary, 7, "Record count", count, null);
            if (dataset.equalsIgnoreCase("payments")) { metadata(summary, 8, "Payment count", count, null); metadata(summary, 9, "Total revenue", revenue.doubleValue(), currency); }
            if (count == 0) metadata(summary, 11, "Result", "No matching records", null);
            summary.setColumnWidth(0, 38 * 256); summary.setColumnWidth(1, 80 * 256);
            workbook.write(output); return output.toByteArray();
        }
    }
    private Sheet dataSheet(Workbook workbook, String name, String[] fields, CellStyle heading) {
        Sheet sheet = workbook.createSheet(name); Row row = sheet.createRow(0);
        for (int i = 0; i < fields.length; i++) { Cell cell = row.createCell(i); cell.setCellValue(title(fields[i])); cell.setCellStyle(heading); sheet.setColumnWidth(i, (fields[i].endsWith("At") ? 25 : 28) * 256); }
        sheet.createFreezePane(0, 1); return sheet;
    }
    private void metadata(Sheet sheet, int index, String label, Object value, CellStyle style) {
        Row row = sheet.createRow(index); row.createCell(0).setCellValue(label); Cell cell = row.createCell(1);
        if (value instanceof Number number) cell.setCellValue(number.doubleValue()); else cell.setCellValue(String.valueOf(value));
        if (style != null) { row.getCell(0).setCellStyle(style); cell.setCellStyle(style); }
    }
    private String title(String value) {
        if (value.equals("id")) return "ID";
        if (value.equals("entryRate")) return "Entry rate (LKR)";
        if (value.equals("amount")) return "Amount (LKR)";
        String text = value.replaceAll("([a-z])([A-Z])", "$1 $2"); return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
    private String[] fields(String table) { return switch (table) {
        case "branches" -> new String[]{"id", "code", "name", "status"};
        case "cashiers" -> new String[]{"id", "username", "branch", "status", "branchStatus"};
        case "slots" -> new String[]{"id", "code", "branch", "branchStatus", "occupied"};
        case "sessions" -> new String[]{"id", "plate", "type", "branch", "branchStatus", "slot", "enteredAt", "exitedAt", "entryRate", "enteredBy","policyRate","policyUnit","graceMinutes","dailyCap"};
        case "payments" -> new String[]{"id", "ticket", "plate", "branch", "branchStatus", "amount", "method", "paidAt", "cashier","reference","cashTendered","changeDue","shiftId"};
        default -> throw new IllegalArgumentException("Unsupported report");
    }; }
}
