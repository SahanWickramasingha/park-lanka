package lk.sahan.parking;

import lk.sahan.parking.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + native desktop; explicitly opt in on a machine with an interactive desktop and a built desktop JAR. */
@EnabledIfEnvironmentVariable(named="PARKING_TEST_POSTGRES_URL",matches=".+")
@EnabledIfEnvironmentVariable(named="PARKING_TEST_DESKTOP_UI",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CheckoutDesktopRuntimeTest.StartupOutage.class)
class CheckoutDesktopRuntimeTest {
 @TestConfiguration static class StartupOutage {
  static final java.util.concurrent.atomic.AtomicLong seconds=new java.util.concurrent.atomic.AtomicLong();
  @Bean @Primary java.time.Clock runtimeClock(){return new java.time.Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return java.time.Clock.fixed(instant(),zone);}public java.time.Instant instant(){return java.time.Instant.now().plusSeconds(seconds.get());}};}
  @Bean FilterRegistrationBean<jakarta.servlet.Filter> shiftOutage(){
   var once=new java.util.concurrent.atomic.AtomicBoolean();
   jakarta.servlet.Filter filter=(request,response,chain)->{
    var http=(jakarta.servlet.http.HttpServletRequest)request;
    if(!Boolean.parseBoolean(System.getenv("PARKING_CHECKOUT_BASELINE"))&&http.getRequestURI().equals("/api/shifts/current")&&once.compareAndSet(false,true)){
     var result=(jakarta.servlet.http.HttpServletResponse)response;result.setStatus(503);result.setContentType("application/json");result.getWriter().write("{\"error\":\"Disposable shift status outage\"}");return;
    }
    chain.doFilter(request,response);
   };
   return new FilterRegistrationBean<>(filter);
  }
 }
 @LocalServerPort int port;@Autowired CashierService cashiers;@Autowired BranchService branches;@Autowired JdbcTemplate sql;@Autowired ParkingService parking;
 private static String database;
 private static synchronized String database(){if(database==null)try{database=PostgresTestDatabases.create();}catch(java.sql.SQLException e){throw new IllegalStateException(e);}return database;}
 @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.datasource.url",CheckoutDesktopRuntimeTest::database);p.add("spring.datasource.username",PostgresTestDatabases::user);p.add("spring.datasource.password",PostgresTestDatabases::password);}
 @Test void actualMouseClickOpensPaymentAgainstRealBackendAndCommitsOnlyAfterConfirmation()throws Exception{
  StartupOutage.seconds.set(0);
  String user="runtime.cashier";long branch=branches.main().getId();cashiers.create(user,"runtime-password",branch);long slot=parking.allSlots(branch).get(0).id();
  long ticket=parking.enter(new ParkingService.EntryRequest("RUNTIME-350","CAR",slot),branch,user).id();
  sql.update("update parking_sessions set policy_rate=350,policy_unit='HOUR',policy_grace_minutes=0 where id=?",ticket);
  Path desktop=Path.of(System.getProperty("user.dir")).getParent().resolve("desktop");Path repo=Path.of(System.getProperty("user.home"),".m2","repository");
  String fx=jars(repo.resolve("org/openjfx"),"21.0.2-win.jar"),jackson=jars(repo.resolve("com/fasterxml/jackson"),"2.17.2.jar"),qr=jars(repo.resolve("com/google/zxing/core"),"core-3.5.3.jar");
  Path log=desktop.resolve("target/checkout-runtime.log"),out=desktop.resolve("target/checkout-runtime");
  var args=List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"--module-path",fx,"--add-modules","javafx.controls","-cp",desktop.resolve("target/parking-desktop-0.2.0.jar")+";"+desktop.resolve("target/test-classes")+";"+jackson+";"+qr,"lk.sahan.parking.desktop.CashierCheckoutRuntime","http://127.0.0.1:"+port,user,Long.toString(ticket),out.toString(),String.valueOf(Boolean.parseBoolean(System.getenv("PARKING_CHECKOUT_BASELINE"))),"true",System.getenv().getOrDefault("PARKING_TEST_DESKTOP_INPUT","mouse"));
  Process process=new ProcessBuilder(args).directory(desktop.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
  try{
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);boolean advanced=false;
   while(process.isAlive()&&System.nanoTime()<deadline){
    if(!advanced&&Files.readString(log).contains("RUNTIME ADVANCE QUOTE CLOCK")){StartupOutage.seconds.addAndGet(121);advanced=true;}
    process.waitFor(100,TimeUnit.MILLISECONDS);
   }
   assertFalse(process.isAlive(),"Native UI timed out; see "+log);assertEquals(0,process.exitValue(),()->{try{return Files.readString(log);}catch(Exception e){return e.getMessage();}});assertTrue(advanced,"Native expiry scenario never requested clock advancement");
  }finally{if(process.isAlive())process.destroy();}
  assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,ticket));
  assertEquals(0,new java.math.BigDecimal("150.00").compareTo(sql.queryForObject("select change_due from payments where session_id=?",java.math.BigDecimal.class,ticket)));
  assertNotNull(sql.queryForObject("select exited_at from parking_sessions where id=?",Object.class,ticket));assertFalse(sql.queryForObject("select occupied from parking_slots where id=?",Boolean.class,slot));
  assertTrue(parking.activeSessions(branch).stream().noneMatch(s->s.id()==ticket));assertNotNull(parking.receiptBySession(ticket,branch));
 }
 private static String jars(Path root,String suffix)throws Exception{try(Stream<Path> paths=Files.walk(root)){return paths.filter(p->p.toString().endsWith(suffix)).map(Path::toString).collect(Collectors.joining(";"));}}
}
