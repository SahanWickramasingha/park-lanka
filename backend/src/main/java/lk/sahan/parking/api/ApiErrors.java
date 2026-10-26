package lk.sahan.parking.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(lk.sahan.parking.service.ShiftService.AlreadyOpen.class)
    ResponseEntity<Map<String,String>> shiftAlreadyOpen(lk.sahan.parking.service.ShiftService.AlreadyOpen error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getReason(), "code", "SHIFT_ALREADY_OPEN"));
    }
    @ExceptionHandler(lk.sahan.parking.service.CheckoutRejected.class)
    ResponseEntity<Map<String,Object>> checkoutRejected(lk.sahan.parking.service.CheckoutRejected error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason(), "code", error.code(), "attemptRecorded", false));
    }
    @ExceptionHandler(lk.sahan.parking.service.CorrectionService.Rejected.class)
    ResponseEntity<Map<String,String>> correctionRejected(lk.sahan.parking.service.CorrectionService.Rejected error){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",error.getMessage(),"code","CORRECTION_REJECTED"));}
    @ExceptionHandler(lk.sahan.parking.service.ParkingService.QuoteChanged.class)
    ResponseEntity<Map<String,Object>> quoteChanged(lk.sahan.parking.service.ParkingService.QuoteChanged error){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",error.getMessage(),"code","QUOTE_CHANGED","quote",error.quote,"attemptRecorded",false));}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason() == null ? "Request failed" : error.getReason()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String,String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Vehicle or payment already recorded; refresh and retry"));
    }
}
