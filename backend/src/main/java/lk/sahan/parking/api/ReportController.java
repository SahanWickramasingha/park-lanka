package lk.sahan.parking.api;

import java.io.IOException;
import java.time.LocalDate;
import lk.sahan.parking.service.ExcelReportService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reports")
public class ReportController {
    private final ExcelReportService reports;
    public ReportController(ExcelReportService reports) { this.reports = reports; }
    @GetMapping("/excel")
    public ResponseEntity<byte[]> excel(@RequestParam String table, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) throws IOException {
        byte[] file = reports.generate(table, branchId, from, to);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=parking-report.xlsx")
                .cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(file);
    }
}
