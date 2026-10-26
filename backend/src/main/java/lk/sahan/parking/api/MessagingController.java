package lk.sahan.parking.api;
import lk.sahan.parking.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api")
public class MessagingController {
    private final NotificationService notifications; private final ConversationService conversations;
    public MessagingController(NotificationService notifications,ConversationService conversations) { this.notifications=notifications; this.conversations=conversations; }
    @GetMapping("/notifications") public NotificationService.Result notifications(Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) String section) { return notifications.list(auth,page,size,section); }
    @GetMapping("/notifications/{id}") public NotificationService.Details notification(@PathVariable Long id,Authentication auth) { return notifications.details(id,auth); }
    @PutMapping("/notifications/{id}/read") public NotificationService.View read(@PathVariable Long id,Authentication auth,@RequestParam(defaultValue="false") boolean opened) { return notifications.read(id,auth,opened); }
    @PutMapping("/notifications/read-all") public NotificationService.Result readAll(Authentication auth) { return notifications.readAll(auth); }
    @GetMapping("/conversations") public ConversationService.PageView<ConversationService.View> conversations(Authentication auth,@RequestParam(required=false) Long branchId,@RequestParam(required=false) String cashier,@RequestParam(required=false) String priority,@RequestParam(required=false) String status,@RequestParam(required=false) Boolean unread,@RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) { return conversations.list(auth,branchId,cashier,priority,status,unread,search,page,size); }
    @PostMapping("/conversations") public ConversationService.View send(@RequestBody ConversationService.Send request,Authentication auth) { return conversations.send(request,auth); }
    @GetMapping("/conversation-messages") public ConversationService.PageView<ConversationService.Message> messages(Authentication auth,@RequestParam(defaultValue="received") String folder,@RequestParam(required=false) Long branchId,@RequestParam(required=false) String cashier,@RequestParam(required=false) String priority,@RequestParam(required=false) String status,@RequestParam(required=false) Boolean unread,@RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) { return conversations.messages(auth,folder,branchId,cashier,priority,status,unread,search,page,size); }
    @GetMapping("/conversations/{id}") public ConversationService.Details conversation(@PathVariable Long id,Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size,@RequestParam(required=false) Long postId) { return conversations.details(id,auth,page,size,postId); }
    public record Open(Long throughPostId,java.util.List<Long> displayedPostIds) {}
    @PutMapping("/conversations/{id}/read") public ConversationService.View open(@PathVariable Long id,@RequestBody Open request,Authentication auth) { return conversations.open(id,request.throughPostId(),request.displayedPostIds(),auth); }
    @PostMapping("/conversations/{id}/replies") public ConversationService.View reply(@PathVariable Long id,@RequestBody ConversationService.Reply request,Authentication auth) { return conversations.reply(id,request,auth); }
    @PutMapping("/admin/conversations/{id}/resolution") public ConversationService.View resolve(@PathVariable Long id,@RequestBody ConversationService.Resolve request,Authentication auth) { return conversations.resolve(id,request,auth); }
}
