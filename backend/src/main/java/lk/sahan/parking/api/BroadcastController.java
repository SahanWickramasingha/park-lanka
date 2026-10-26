package lk.sahan.parking.api;

import java.util.List;
import lk.sahan.parking.service.BroadcastService;
import lk.sahan.parking.service.BroadcastService.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class BroadcastController {
    private final BroadcastService service;
    public BroadcastController(BroadcastService service) { this.service = service; }
    @PostMapping("/api/admin/broadcasts") public BroadcastView send(@RequestBody SendRequest request, Authentication auth) { return service.send(request, auth.getName()); }
    @GetMapping("/api/admin/broadcasts") public History history(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) { return service.history(page, size); }
    @GetMapping("/api/admin/broadcasts/{id}") public Details details(@PathVariable Long id) { return service.details(id); }
    @PutMapping("/api/admin/broadcasts/{id}/revoke") public BroadcastView revoke(@PathVariable Long id) { return service.revoke(id); }
    @GetMapping("/api/messages") public List<MessageView> messages(Authentication auth) { return service.messages(auth); }
    @PutMapping("/api/messages/{id}/read") public MessageView read(@PathVariable Long id, Authentication auth) { return service.read(id, auth); }
}
