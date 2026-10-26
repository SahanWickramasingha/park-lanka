package lk.sahan.parking;

import lk.sahan.parking.domain.Branch;
import lk.sahan.parking.domain.ParkingSlot;
import lk.sahan.parking.repo.BranchRepository;
import lk.sahan.parking.repo.SlotRepository;
import lk.sahan.parking.service.BranchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "parking.initial-slots=2")
@Import({BranchService.class, lk.sahan.parking.service.EventLog.class, lk.sahan.parking.service.AuditService.class, org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class, lk.sahan.parking.config.TimeConfig.class})
class BranchCodeMigrationTest {
    @Autowired BranchService service;
    @Autowired BranchRepository branches;
    @Autowired SlotRepository slots;

    @Test void migratesExistingBranchCodesWithoutChangingSlotsOrRelationships() {
        Branch main = branches.findByNameIgnoreCase("Main Branch").orElseThrow();
        main.setCode("MAIN");
        branches.saveAndFlush(main);
        Branch kandy = branches.saveAndFlush(new Branch("KANDY", "Kandy Branch"));
        slots.saveAndFlush(new ParkingSlot("KANDY-001", kandy));

        service.initialize();

        assertEquals("001", branches.findByNameIgnoreCase("Main Branch").orElseThrow().getCode());
        assertEquals("002", branches.findByNameIgnoreCase("Kandy Branch").orElseThrow().getCode());
        assertTrue(slots.findAll().stream().anyMatch(s ->
                s.getCode().equals("KANDY-001") && s.getBranch().getId().equals(kandy.getId())));
        assertTrue(slots.findAll().stream().anyMatch(s ->
                s.getCode().equals("A-001") && s.getBranch().getId().equals(main.getId())));
    }

    @Test void numericCodesStayStableAcrossRestartAndCreationUsesNextFreeCode() {
        branches.saveAndFlush(new Branch("003", "Colombo Branch"));

        service.initialize();
        Branch kandy = service.create("Kandy Branch", 2);
        service.initialize();
        Branch galle = service.create("Galle Branch", 1);

        assertEquals("002", branches.findByNameIgnoreCase("Kandy Branch").orElseThrow().getCode());
        assertEquals("004", galle.getCode());
        assertTrue(slots.findAll().stream().anyMatch(s -> s.getCode().equals("002-001")));
        assertTrue(slots.findAll().stream().anyMatch(s -> s.getCode().equals("002-002")));
        assertEquals("001,002,003,004", String.join(",", branches.findAll().stream()
                .sorted(Comparator.comparing(Branch::getCode)).map(Branch::getCode).toList()));
    }

    @Test void duplicateBranchNamesAreRejectedWhenCodesAreGenerated() {
        service.initialize();
        service.create("Kandy Branch", 1);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create("kandy branch", 1));

        assertEquals(409, error.getStatusCode().value());
    }
}
