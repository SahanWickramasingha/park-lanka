package lk.sahan.parking;
import lk.sahan.parking.maintenance.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="PARKING_TEST_POSTGRES_URL",matches=".+")
class PostgresDataMigrationTest {
 @TempDir Path temp;
 private String target;
 @BeforeEach void create()throws Exception{target=PostgresTestDatabases.create();}
 @AfterEach void cleanup()throws Exception{PostgresTestDatabases.drop(target);}
 @Test void dryRunApplyRetrySequencesAndPgDumpRestorePreserveEveryBusinessTable()throws Exception{
  String source="jdbc:h2:mem:source_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
  Flyway.configure().dataSource(source,"sa","").target("8").load().migrate();
  try(Connection from=DriverManager.getConnection(source,"sa","");Connection to=PostgresTestDatabases.connect(target)){
   fixture(from);
   var dry=H2ToPostgres.transfer(from,to,false,"admin");assertFalse(dry.applied());
   assertEquals(0,scalar(to,"select count(*) from information_schema.tables where table_schema='public'"));
   assertEquals(21,dry.counts().size());assertEquals(0,dry.counts().get("checkout_quotes"));
   assertTrue(dry.counts().entrySet().stream().filter(e->!e.getKey().equals("checkout_quotes")).allMatch(e->e.getValue()>0));
   var applied=H2ToPostgres.transfer(from,to,true,"admin");assertTrue(applied.applied());assertEquals(dry.fingerprint(),applied.fingerprint());
   assertEquals("12.34",applied.payments().toPlainString());assertEquals("1.25",applied.corrections().toPlainString());assertEquals(1,applied.activeSessions());
   assertEquals(1,scalar(to,"select count(*) from cashier_accounts where deleted=true and enabled=false and password_hash like '$2%'"));
   assertEquals(1,scalar(to,"select count(*) from conversation_reads where reader_key='bootstrap:legacy.cashier'"));
   assertThrows(IllegalStateException.class,()->H2ToPostgres.transfer(from,to,true,"admin"));
   // Consume each isolated sequence once to prove all imported IDs are behind the next generated ID.
   try(Statement sql=to.createStatement();ResultSet columns=sql.executeQuery("select table_name,column_name from information_schema.columns where table_schema='public' and is_identity='YES'")){
    while(columns.next()){
     String table=columns.getString(1),column=columns.getString(2);
     assertTrue(scalar(to,"select nextval(pg_get_serial_sequence('"+table+"','"+column+"'))")>scalar(to,"select coalesce(max("+column+"),0) from "+table),table);
    }
   }
   String restore=PostgresTestDatabases.create();Path dump=temp.resolve("consistent.dump");
   try{
    String bin=System.getenv("PARKING_TEST_POSTGRES_BIN");
    PostgresTools.run(Path.of(bin,"pg_dump.exe").toString(),true,target,PostgresTestDatabases.user(),PostgresTestDatabases.password(),dump);
    assertTrue(Files.size(dump)>0);
    PostgresTools.run(Path.of(bin,"pg_restore.exe").toString(),false,restore,PostgresTestDatabases.user(),PostgresTestDatabases.password(),dump);
    try(Connection restored=PostgresTestDatabases.connect(restore)){
     for(var entry:applied.counts().entrySet())assertEquals(entry.getValue().longValue(),scalar(restored,"select count(*) from "+entry.getKey()));
     assertEquals(1,scalar(restored,"select count(*) from data_import_receipts"));
    }
   }finally{PostgresTestDatabases.drop(restore);}
  }
 }
 @Test void stoppedSourceBackupIsReadOnlyAndByteIdenticalAfterTransfer()throws Exception{
  String source="jdbc:h2:file:"+temp.resolve("source-backup").toString().replace('\\','/');
  Flyway.configure().dataSource(source,"sa","").target("8").load().migrate();
  try(Connection seed=DriverManager.getConnection(source,"sa","")){fixture(seed);}
  Path file=temp.resolve("source-backup.mv.db");String before=BackupArchive.hash(file);
  try(Connection from=DriverManager.getConnection(source+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;AUTO_SERVER=FALSE","sa","");Connection to=PostgresTestDatabases.connect(target)){
   assertTrue(from.isReadOnly());assertFalse(H2ToPostgres.transfer(from,to,false,"admin").applied());
   assertTrue(H2ToPostgres.transfer(from,to,true,"admin").applied());
  }
  assertEquals(before,BackupArchive.hash(file),"The stopped source backup must remain byte-identical");
 }
 @Test void persistedQuotesAndReceiptBillingCutoffsSurviveTransfer()throws Exception{
  String source="jdbc:h2:mem:quoted_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
  Flyway.configure().dataSource(source,"sa","").target("8").load().migrate();
  try(Connection from=DriverManager.getConnection(source,"sa","");Connection to=PostgresTestDatabases.connect(target)){
   fixture(from);Flyway.configure().dataSource(source,"sa","").load().migrate();
   String quote=UUID.randomUUID().toString();
   try(PreparedStatement insert=from.prepareStatement("insert into checkout_quotes(id,session_id,branch_id,cashier_key,billing_cutoff,expires_at,amount,billable_minutes) select ?,id,41,'bootstrap:legacy.cashier',exited_at,exited_at+INTERVAL '2' MINUTE,12.34,10 from parking_sessions where id=71")){insert.setString(1,quote);insert.executeUpdate();}
   try(PreparedStatement update=from.prepareStatement("update payments set quote_id=?,billing_cutoff=(select exited_at from parking_sessions where id=71) where id=91")){update.setString(1,quote);update.executeUpdate();}
   assertTrue(H2ToPostgres.transfer(from,to,true,"admin").applied());
   assertEquals(1,scalar(to,"select count(*) from payments p join checkout_quotes q on p.quote_id=q.id where p.id=91 and p.billing_cutoff=q.billing_cutoff and q.amount=p.amount"));
  }
 }
 @Test void failedReceiptRollsBackEveryImportedRowAndSafeRetryWorks()throws Exception{
  String source="jdbc:h2:mem:rollback_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
  Flyway.configure().dataSource(source,"sa","").target("8").load().migrate();
  Flyway.configure().dataSource(target,PostgresTestDatabases.user(),PostgresTestDatabases.password()).load().migrate();
  try(Connection from=DriverManager.getConnection(source,"sa","");Connection to=PostgresTestDatabases.connect(target);Statement sql=to.createStatement()){
   fixture(from);
   sql.execute("create function reject_receipt() returns trigger language plpgsql as $$ begin raise exception 'disposable failure injection'; end $$");
   sql.execute("create trigger fail_receipt before insert on data_import_receipts for each row execute function reject_receipt()");
   assertThrows(SQLException.class,()->H2ToPostgres.transfer(from,to,true,"admin"));
   assertEquals(0,scalar(to,"select count(*) from parking_sessions"));assertEquals(0,scalar(to,"select count(*) from branches"));assertEquals(0,scalar(to,"select count(*) from data_import_receipts"));
   assertEquals(1,scalar(to,"select nextval(pg_get_serial_sequence('branches','id'))"));
   sql.execute("drop trigger fail_receipt on data_import_receipts");sql.execute("drop function reject_receipt()");
   assertTrue(H2ToPostgres.transfer(from,to,true,"admin").applied());
  }
 }
 @Test void incompatibleTargetAndInconsistentSourceAreRefusedWithoutWrites()throws Exception{
  String source="jdbc:h2:mem:invalid_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
  Flyway.configure().dataSource(source,"sa","").target("8").load().migrate();
  try(Connection from=DriverManager.getConnection(source,"sa","");Connection to=PostgresTestDatabases.connect(target)){
   fixture(from);try(Statement sql=from.createStatement()){sql.execute("update parking_slots set occupied=false where id=62");}
   assertThrows(IllegalStateException.class,()->H2ToPostgres.transfer(from,to,false,"admin"));
   try(Statement sql=from.createStatement()){sql.execute("update parking_slots set occupied=true where id=62");}
   try(Statement sql=to.createStatement()){sql.execute("create table unexpected(id bigint)");}
   assertThrows(IllegalStateException.class,()->H2ToPostgres.transfer(from,to,false,"admin"));
   assertEquals(1,scalar(to,"select count(*) from information_schema.tables where table_schema='public'"));
  }
 }
 static long scalar(Connection c,String query)throws SQLException{try(Statement sql=c.createStatement();ResultSet row=sql.executeQuery(query)){row.next();return row.getLong(1);}}
 private static void fixture(Connection c)throws Exception{
  String hash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("fixture-password");
  List<String> rows=List.of(
   "insert into branches(id,code,name,deleted,stopped) values(41,'001','Main Branch',false,false),(42,'002','Archived Branch',true,true)",
   "insert into broadcasts(id,all_branches,sent_at,priority,request_key,title,sender,body,audience) values(51,true,CURRENT_TIMESTAMP,'NORMAL','broadcast-key','Notice','admin','Preserved text','All eligible cashiers')",
   "insert into broadcast_branches(broadcast_id,branch_id) values(51,41)",
   "insert into broadcast_recipients(id,branch_id,broadcast_id,read_at,branch,username,recipient_key) values(52,41,51,CURRENT_TIMESTAMP,'001 Main Branch','legacy.cashier','bootstrap:legacy.cashier')",
   "insert into cashier_accounts(id,username,password_hash,branch_id,enabled,deleted) values(53,'archived.cashier','"+hash+"',42,false,true)",
   "insert into parking_rates(id,per_minute) values(1,1.00)",
   "insert into parking_slots(id,code,branch_id,occupied) values(61,'A-001',41,false),(62,'A-002',41,true)",
   "insert into parking_sessions(id,plate,active_plate,vehicle_type,slot_id,entered_at,exited_at,rate_per_minute,entered_by,ticket_token,policy_rate,policy_unit,policy_grace_minutes,policy_daily_cap,policy_source) values(71,'CLOSED',null,'CAR',61,CURRENT_TIMESTAMP-INTERVAL '1' HOUR,CURRENT_TIMESTAMP,1.00,'legacy.cashier','receipt-ticket',1.00,'MINUTE',0,100.00,'Preserved policy'),(72,'OPEN','OPEN','CAR',62,CURRENT_TIMESTAMP,null,1.00,'legacy.cashier','active-ticket',1.00,'MINUTE',0,null,'Legacy')",
   "insert into cashier_shifts(id,owner_key,cashier,branch_id,branch,open_request_key,open_fingerprint,opened_at,closed_at,opening_cash,counted_cash,expected_cash,difference,cash_collections,card_collections,cash_refunds,cash_voids) values(81,'bootstrap:legacy.cashier','legacy.cashier',41,'001 Main Branch','shift-key','shift-fingerprint',CURRENT_TIMESTAMP-INTERVAL '2' HOUR,CURRENT_TIMESTAMP,100.00,111.09,111.09,0,12.34,0,1.25,0)",
   "insert into payments(id,session_id,amount,method,paid_at,cashier_username,request_key,request_fingerprint,cash_tendered,change_due,shift_id) values(91,71,12.34,'CASH',CURRENT_TIMESTAMP,'legacy.cashier','checkout-key','checkout-fingerprint',20.00,7.66,81)",
   "insert into payment_corrections(id,payment_id,type,amount,reason,actor,occurred_at,branch_id,branch,method,movement_shift_id,request_key,fingerprint) values(92,91,'REFUND',1.25,'Preserved reason','admin',CURRENT_TIMESTAMP,41,'001 Main Branch','CASH',81,'refund-key','refund-fingerprint')",
   "insert into pricing_rules(id,branch_id,vehicle_type,rate,billing_unit,grace_minutes,daily_cap) values(93,41,'CAR',5.25,'MINUTE',5,100.00)",
   "insert into conversations(id,owner_key,cashier,branch_id,branch,subject,category,priority,resolved,created_at,updated_at,last_post_id,ticket_id,payment_id) values(101,'bootstrap:legacy.cashier','legacy.cashier',41,'001 Main Branch','Saved subject','GENERAL','NORMAL',false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,102,71,91)",
   "insert into conversation_posts(id,conversation_id,admin,kind,request_key,fingerprint,sender,sender_key,body,sent_at) values(102,101,true,'MESSAGE','post-key','post-fingerprint','admin','admin:admin','Saved reply',CURRENT_TIMESTAMP)",
   "insert into conversation_reads(id,conversation_id,last_post_id,reader_key,read_at) values(103,101,102,'bootstrap:legacy.cashier',CURRENT_TIMESTAMP)",
   "insert into conversation_post_reads(id,post_id,reader_key,read_at) values(104,102,'bootstrap:legacy.cashier',CURRENT_TIMESTAMP)",
   "insert into notices(id,created_at,priority,audience,category,branch_id,branch,cashier,sender,event_key,target_key,title,description,related_type,related_id,related_post_id,broadcast_id) values(105,CURRENT_TIMESTAMP,'Normal','CASHIER','Message',41,'001 Main Branch','legacy.cashier','admin','event-key','bootstrap:legacy.cashier','Saved notice','Saved reply','CONVERSATION',101,102,51)",
   "insert into notice_reads(id,notice_id,reader_key,read_at) values(106,105,'bootstrap:legacy.cashier',CURRENT_TIMESTAMP)",
   "insert into audit_records(id,actor,action,target,branch_id,occurred_at,reason,before_details,after_details) values(107,'admin','PAYMENT_CORRECTED','PAYMENT:91',41,CURRENT_TIMESTAMP,'Preserved reason','{\"amount\":12.34}','{\"refund\":1.25}')",
   "insert into backup_jobs(id,job_key,kind,status,actor,engine,created_at,completed_at,size_bytes,file_name) values(108,'backup-key','MANUAL','SUCCESS','admin','H2',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,512,'historical-backup.zip')"
  );
  try(Statement sql=c.createStatement()){for(String row:rows)sql.execute(row);}
 }
}
