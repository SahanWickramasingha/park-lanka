package lk.sahan.parking.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A transactional checkout rejection, before a payment/session closure is committed. */
public final class CheckoutRejected extends ResponseStatusException {
    private final String code;
    public CheckoutRejected(HttpStatus status, String code, String message) {
        super(status, message);
        this.code = code;
    }
    public String code() { return code; }
}
