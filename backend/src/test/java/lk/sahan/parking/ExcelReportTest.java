package lk.sahan.parking;

import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import lk.sahan.parking.service.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"test.cashier.username=testcashier", "test.cashier.password=testpassword", "PARKING_ADMIN_USER=testadmin", "PARKING_ADMIN_PASSWORD=adminpassword", "DATABASE_URL=jdbc:h2:mem:exceltest;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "PARKING_INITIAL_SLOTS=2"})
@AutoConfigureMockMvc @Transactional
class ExcelReportTest {
    @Autowired MockMvc mvc; @Autowired BranchService branches; @Autowired SlotRepository slots;
    @Autowired SessionRepository sessions; @Autowired PaymentRepository payments; @Autowired CashierRepository cashiers;
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
    private static final Instant DAY = LocalDate.of(2026, 10, 21).atStartOfDay(ZONE).toInstant();

    @Test void allPagesNumericTotalsAndArchivedHistoryAreExported() throws Exception {
        Branch branch = branches.create("Report Branch", 1); ParkingSlot slot = slots.findByBranchId(branch.getId()).get(0);
        for (int i = 0; i < 125; i++) paid(slot, "RPT-" + i, DAY, DAY.plusSeconds(3600), "2.50");
        Branch other = branches.create("Other Report Branch", 1); paid(slots.findByBranchId(other.getId()).get(0), "OTHER-1", DAY, DAY.plusSeconds(3600), "999.00");
        branches.remove(branch.getId());
        try (Workbook workbook = report("payments", branch.getId(), "&from=2026-10-21&to=2026-10-21")) {
            Sheet data = workbook.getSheet("Records"), summary = workbook.getSheet("Report");
            assertEquals(125, data.getLastRowNum()); assertEquals(125, summary.getRow(7).getCell(1).getNumericCellValue());
            assertEquals(125, summary.getRow(8).getCell(1).getNumericCellValue()); assertEquals(312.50, summary.getRow(9).getCell(1).getNumericCellValue());
            assertEquals("Deleted", data.getRow(1).getCell(4).getStringCellValue());
            Cell amount = data.getRow(1).getCell(5); assertEquals(CellType.NUMERIC, amount.getCellType()); assertTrue(amount.getCellStyle().getDataFormatString().contains("LKR"));
            assertEquals(LocalDateTime.of(2026, 10, 21, 1, 0), data.getRow(1).getCell(7).getLocalDateTimeCellValue());
            assertTrue(summary.getRow(3).getCell(1).getStringCellValue().contains("Deleted"));
        }
        assertEquals(126, payments.count());
    }

    @Test void dateBoundsUseColomboAndCorrectTimestampForEachDataset() throws Exception {
        Branch branch = branches.create("Date Branch", 1); ParkingSlot slot = slots.findByBranchId(branch.getId()).get(0);
        paid(slot, "BEFORE", DAY.minusSeconds(1), DAY.plusSeconds(3600), "7.50");
        paid(slot, "START", DAY, DAY.plusSeconds(86400), "8.50");
        paid(slot, "END", DAY.plusSeconds(86400), DAY.plusSeconds(86400), "9.50");
        try (Workbook workbook = report("sessions", branch.getId(), "&from=2026-10-21&to=2026-10-21")) {
            assertEquals(1, workbook.getSheet("Records").getLastRowNum()); assertEquals("START", workbook.getSheet("Records").getRow(1).getCell(1).getStringCellValue());
        }
        try (Workbook workbook = report("payments", branch.getId(), "&from=2026-10-21&to=2026-10-21")) {
            assertEquals(1, workbook.getSheet("Records").getLastRowNum()); assertEquals(7.50, workbook.getSheet("Report").getRow(9).getCell(1).getNumericCellValue());
        }
        try (Workbook workbook = report("sessions", branch.getId(), "&from=2030-01-01")) {
            assertEquals(0, workbook.getSheet("Records").getLastRowNum()); assertEquals(0, workbook.getSheet("Report").getRow(7).getCell(1).getNumericCellValue());
        }
    }

    @Test void userContentIsTextAndCredentialsAreNeverExported() throws Exception {
        Branch branch = branches.create("=1+1", 1);
        CashierAccount account = new CashierAccount("reportcashier", "NEVER-EXPORT-THIS-HASH", branch); account.archive(); cashiers.saveAndFlush(account);
        try (Workbook workbook = report("branches", branch.getId(), "")) {
            Cell name = workbook.getSheet("Records").getRow(1).getCell(2); assertEquals(CellType.STRING, name.getCellType()); assertEquals("=1+1", name.getStringCellValue());
        }
        for (String dataset : new String[]{"branches", "cashiers", "slots", "sessions", "payments"}) try (Workbook workbook = report(dataset, null, "")) {
            for (Sheet sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
                assertNotEquals(CellType.FORMULA, cell.getCellType());
                if (cell.getCellType() == CellType.STRING) { String value = cell.getStringCellValue(); assertFalse(value.contains("NEVER-EXPORT")); assertFalse(value.toLowerCase().contains("password")); assertFalse(value.toLowerCase().contains("authorization")); }
            }
            if (dataset.equals("cashiers")) assertEquals("Removed", workbook.getSheet("Records").getRow(1).getCell(3).getStringCellValue());
        }
    }

    @Test void exportsAreAdminOnlyAndRejectInvalidFilters() throws Exception {
        mvc.perform(get("/api/admin/reports/excel?table=payments").with(httpBasic("testcashier", "testpassword"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reports/excel?table=payments")).andExpect(status().isUnauthorized());
        for (String query : new String[]{"table=passwords", "table=branches&from=2026-01-01", "table=payments&from=2026-02-01&to=2026-01-01", "table=sessions&from=bad"})
            mvc.perform(get("/api/admin/reports/excel?" + query).with(httpBasic("testadmin", "adminpassword"))).andExpect(status().isBadRequest());
    }
    private Workbook report(String table, Long branch, String filter) throws Exception {
        byte[] data = mvc.perform(get("/api/admin/reports/excel?table=" + table + (branch == null ? "" : "&branchId=" + branch) + filter).with(httpBasic("testadmin", "adminpassword")))
                .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().getResponse().getContentAsByteArray();
        return new XSSFWorkbook(new ByteArrayInputStream(data));
    }
    private void paid(ParkingSlot slot, String plate, Instant entered, Instant paid, String amount) {
        ParkingSession session = new ParkingSession(plate, "CAR", slot, entered, new BigDecimal("1.00"), "historicalcashier"); session.close(paid); sessions.saveAndFlush(session);
        payments.saveAndFlush(new Payment(session, new BigDecimal(amount), "CASH", "", paid, "historicalcashier"));
    }
}
