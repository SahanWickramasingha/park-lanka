package lk.sahan.parking.service;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.NoticeRepository;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional(propagation=Propagation.MANDATORY)
public class EventLog {
    private final NoticeRepository notices; private final Clock clock;private final AuditService audit;
    public EventLog(NoticeRepository notices,Clock clock,AuditService audit) { this.notices=notices; this.clock=clock;this.audit=audit; }
    public void business(String category,String title,String description,Branch branch,String cashier,String type,Long id,BigDecimal amount,boolean branchNotice) {
        String before=title.endsWith("started")?"Stopped":title.endsWith("stopped")?"Active":title.contains("deleted")||title.contains("removed")?"Active or stopped":title.equals("Checkout completed")?"Parked":null;
        var after=new java.util.LinkedHashMap<String,Object>();after.put("description",description);if(amount!=null)after.put("amount",amount);
        audit.record(title,type+":"+id,branch==null?null:branch.getId(),null,before==null?null:java.util.Map.of("state",before),after);
        var auth=SecurityContextHolder.getContext().getAuthentication(); String sender=auth==null ? "System" : auth.getName();
        write(UUID.randomUUID().toString(),"ADMIN",null,category,"Normal",title,description,sender,branch,cashier,type,id,amount,null);
        if(branchNotice) write(UUID.randomUUID().toString(),"BRANCH",null,category,"Important",title,description,sender,branch,cashier,type,id,amount,null);
    }
    public void conversation(Long id,Long postId,String ownerKey,Branch branch,String cashier,String sender,boolean fromAdmin,String title,String body,String priority) {
        Notice notice=write(UUID.randomUUID().toString(),fromAdmin?"USER":"ADMIN",fromAdmin?ownerKey:null,"Conversation",priority,title,body,sender,branch,cashier,"CONVERSATION",id,null,null);
        notice.relatedPostId=postId;
    }
    public void broadcast(Broadcast message,boolean revoked) {
        String key="broadcast:"+message.getId()+":"+(revoked?"revoked":"published");
        var auth=SecurityContextHolder.getContext().getAuthentication(); String sender=revoked?(auth==null?"System":auth.getName()):message.getSender();
        if(!notices.existsByEventKey(key)) write(key,"ADMIN",null,"Broadcast","Normal",revoked?"Broadcast revoked":"Broadcast published",message.getTitle(),sender,null,null,"BROADCAST",message.getId(),null,null);
        if(!revoked) broadcastDelivery(message);
    }
    public void broadcastDelivery(Broadcast message) {
        String key="broadcast:"+message.getId()+":delivery";
        if(!notices.existsByEventKey(key)) write(key,"BROADCAST",null,"Broadcast",label(message.getPriority()),message.getTitle(),message.getBody(),message.getSender(),null,null,"BROADCAST",message.getId(),null,message);
    }
    private String label(String value) { return value.charAt(0)+value.substring(1).toLowerCase(java.util.Locale.ROOT); }
    private Notice write(String key,String audience,String target,String category,String priority,String title,String body,String sender,Branch branch,String cashier,String type,Long id,BigDecimal amount,Broadcast broadcast) {
        return notices.save(new Notice(key,audience,target,category,priority,title,body,sender,branch==null?null:branch.getId(),branch==null?null:branch.getCode()+" – "+branch.getName(),cashier,type,id,amount,broadcast==null?clock.instant():broadcast.getSentAt(),broadcast));
    }
}
