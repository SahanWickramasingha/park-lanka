package lk.sahan.parking;

import java.sql.*;
import lk.sahan.parking.repo.*;
import lk.sahan.parking.service.BranchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly adopt the complete supported legacy schema using its isolated logical backup. */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "parking.upgrade.adopt-legacy=true", "PARKING_INITIAL_SLOTS=2"
})
class ManagementUpgradeTest {
    private static final String URL = "jdbc:h2:mem:managementupgrade;DB_CLOSE_DELAY=-1";

    @DynamicPropertySource static void oldSchema(DynamicPropertyRegistry properties) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "sa", ""); Statement sql = connection.createStatement()) {
            var data=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V1__existing_pos_schema.sql")).execute(data);
            sql.execute("insert into branches(code,name) values ('001','Main Branch'), ('002','Existing Branch')");
            sql.execute("insert into cashier_accounts(username,password_hash,branch_id,enabled) values ('legacyactive','unused-test-hash',2,true), ('legacystopped','unused-test-hash',2,false)");
            sql.execute("insert into parking_slots(code,branch_id,occupied) values ('EXISTING-001',2,false)");
            String backup="target/management-legacy-"+java.util.UUID.randomUUID()+".sql";sql.execute("SCRIPT TO '"+backup+"'");properties.add("parking.upgrade.backup",()->backup);
        }
        properties.add("spring.datasource.url", () -> URL);
        properties.add("spring.datasource.username", () -> "sa");
        properties.add("spring.datasource.password", () -> "");
    }

    @Autowired BranchRepository branches;
    @Autowired CashierRepository cashiers;
    @Autowired SlotRepository slots;
    @Autowired BranchService service;

    @Test void existingRowsKeepTheirStatusAndArchivedRowsStayArchivedAfterInitialization() {
        var branch = branches.findByCode("002").orElseThrow();
        assertEquals("Active", branch.getStatus());
        assertEquals("Active", cashiers.findByUsername("legacyactive").orElseThrow().getStatus());
        assertEquals("Stopped", cashiers.findByUsername("legacystopped").orElseThrow().getStatus());
        long originalSlot = slots.findByBranchId(branch.getId()).get(0).getId();
        service.enable(branch.getId(), false);
        service.initialize();
        assertEquals("Stopped", branches.findById(branch.getId()).orElseThrow().getStatus());
        service.remove(branch.getId());
        service.initialize();
        assertEquals(2, branches.count());
        assertEquals("Deleted", branches.findById(branch.getId()).orElseThrow().getStatus());
        assertEquals(originalSlot, slots.findByBranchId(branch.getId()).get(0).getId());
        assertEquals(2, cashiers.count());
        assertFalse(cashiers.findByUsername("legacystopped").orElseThrow().isEnabled());
    }
}
