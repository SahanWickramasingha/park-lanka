package lk.sahan.parking.service;
import lk.sahan.parking.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Drains in-flight application requests before entering maintenance. Backup metadata is exempt. */
@Component
public class MaintenanceGuard extends OncePerRequestFilter {
 private final ReentrantReadWriteLock gate=new ReentrantReadWriteLock(true);private volatile boolean enabled;
 private final ShiftRepository shifts;private final SessionRepository sessions;
 public MaintenanceGuard(ShiftRepository shifts,SessionRepository sessions,@Value("${parking.maintenance:false}") boolean enabled){this.shifts=shifts;this.sessions=sessions;this.enabled=enabled;}
 public boolean enabled(){return enabled;}
 public void set(boolean value){gate.writeLock().lock();try{if(value)requireIdle();enabled=value;}finally{gate.writeLock().unlock();}}
 public void requireIdle(){if(shifts.countByClosedAtIsNull()!=0||!sessions.findByExitedAtIsNullOrderByEnteredAtAsc().isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Close all active shifts and check out all parked vehicles before maintenance/restore");}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  String path=request.getRequestURI();if(path.equals("/api/admin/maintenance")){chain.doFilter(request,response);return;}
  gate.readLock().lock();try{
   boolean metadata=path.startsWith("/api/admin/backups");boolean mutation=!java.util.Set.of("GET","HEAD").contains(request.getMethod())||path.matches("/api/sessions/[^/]+/ticket");
   if(enabled&&mutation&&!metadata){response.setStatus(503);response.setContentType("application/json");response.getWriter().write("{\"error\":\"Parking operations are paused for database maintenance\",\"code\":\"MAINTENANCE\"}");return;}
   chain.doFilter(request,response);
  }finally{gate.readLock().unlock();}
 }
}
