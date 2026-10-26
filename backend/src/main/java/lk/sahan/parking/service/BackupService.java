package lk.sahan.parking.service;
import lk.sahan.parking.domain.BackupJob;
import lk.sahan.parking.repo.BackupRepository;
import lk.sahan.parking.maintenance.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.flywaydb.core.Flyway;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service @EnableScheduling
public class BackupService implements AutoCloseable {
 private final BackupRepository jobs;private final DataSource data;private final Flyway flyway;private final AuditService audit;private final MaintenanceGuard guard;private final TransactionTemplate tx;private final ObjectMapper json;
 private final Path directory;private final int keep;private final String pgDump,user,password;private final AtomicBoolean running=new AtomicBoolean();
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"database-backup");t.setDaemon(true);return t;});
 public BackupService(BackupRepository jobs,DataSource data,Flyway flyway,AuditService audit,MaintenanceGuard guard,PlatformTransactionManager tm,ObjectMapper json,
  @Value("${parking.backup.directory}") String directory,@Value("${parking.backup.keep:20}") int keep,@Value("${parking.backup.pg-dump:pg_dump}") String pgDump,
  @Value("${spring.datasource.username}") String user,@Value("${spring.datasource.password}") String password){
  this.jobs=jobs;this.data=data;this.flyway=flyway;this.audit=audit;this.guard=guard;this.tx=new TransactionTemplate(tm);this.json=json;this.directory=Path.of(directory).toAbsolutePath().normalize();this.keep=keep;this.pgDump=pgDump;this.user=user;this.password=password;if(keep<2||keep>10000)throw new IllegalArgumentException("Backup retention must be 2..10000");
 }
 public record History(List<BackupJob> rows,long total,int page,int pages,String serverDirectory,boolean maintenance,boolean busy){}
 @org.springframework.context.annotation.Bean org.springframework.boot.CommandLineRunner recoverInterruptedBackupJobs(){return args->tx.executeWithoutResult(status->{for(BackupJob job:jobs.findByStatusOrderByIdDesc("RUNNING")){job.status="FAILED";job.completedAt=Instant.now();job.failure="The previous backend stopped before this job completed. Validate any archive before relying on it.";}});}
 public History history(int page,int size){if(page<0||size<1||size>100)throw FinancialInputs.bad("Invalid backup pagination");var rows=jobs.findAll(PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"id")));return new History(rows.getContent(),rows.getTotalElements(),page,rows.getTotalPages(),directory.toString(),guard.enabled(),running.get());}
 public BackupJob start(Authentication auth){return enqueue("MANUAL",auth.getName(),null);}
 @Scheduled(cron="${parking.backup.cron:-}",zone="Asia/Colombo") public void scheduled(){if(!running.get())enqueue("SCHEDULED","System",null);}
 public BackupJob prepareRestore(Long source,String confirmation,Authentication auth){
  if(!("PREPARE RESTORE "+source).equals(confirmation))throw FinancialInputs.bad("Type PREPARE RESTORE "+source+" to explicitly confirm");
  if(!guard.enabled())throw conflict("Enable maintenance before preparing restore");guard.requireIdle();BackupJob backup=requireBackup(source);return enqueue("PRE_RESTORE",auth.getName(),backup.id);
 }
 private BackupJob enqueue(String kind,String actor,Long source){
  if(!running.compareAndSet(false,true))throw conflict("A database backup task is already running");
  try{BackupJob job=tx.execute(status->{BackupJob j=new BackupJob();j.jobKey=UUID.randomUUID().toString();j.kind=kind;j.status="RUNNING";j.actor=actor;j.createdAt=Instant.now();j.sourceJobId=source;j=jobs.saveAndFlush(j);audit.record(source==null?"BACKUP_REQUESTED":"RESTORE_PREPARATION_REQUESTED","BACKUP:"+j.id,null,null,null,Map.of("job",j.id,"kind",kind));return j;});worker.submit(()->run(job.id));return job;}
  catch(RuntimeException ex){running.set(false);throw ex;}
 }
 private void run(Long id){Path payload=null,partial=null;
  try{
   Files.createDirectories(directory);if(Files.isSymbolicLink(directory))throw new java.io.IOException("Backup directory must not be a symbolic link");BackupJob job=jobs.findById(id).orElseThrow();
   BackupJob source=job.sourceJobId==null?null:requireBackup(job.sourceJobId);if(source!=null){if(!guard.enabled())throw new java.io.IOException("Maintenance ended");guard.requireIdle();BackupArchive.inspect(resolve(source.fileName));}
   payload=resolve("work-"+job.jobKey+".bin");partial=resolve("partial-"+job.jobKey+".zip");String engine;
   try(var connection=data.getConnection()){
    String url=connection.getMetaData().getURL();String product=connection.getMetaData().getDatabaseProductName();
    if(product.equals("H2")){if(!url.startsWith("jdbc:h2:file:"))throw new java.io.IOException("Consistent H2 backup requires a file database");engine="H2";try(var statement=connection.createStatement()){statement.execute("BACKUP TO '"+payload.toString().replace("'","''")+"'");}}
    else if(product.equals("PostgreSQL")){engine="POSTGRESQL";PostgresTools.run(pgDump,true,url,user,password,payload);}
    else throw new java.io.IOException("Unsupported database engine");
   }
   int schema=Integer.parseInt(flyway.info().current().getVersion().getVersion());String fileName="pos-backup-"+job.jobKey+".zip";BackupArchive.pack(payload,partial,engine,schema);BackupArchive.inspect(partial);Files.move(partial,resolve(fileName),StandardCopyOption.ATOMIC_MOVE);long bytes=Files.size(resolve(fileName));String planName=null;
   if(source!=null){if(!guard.enabled())throw new java.io.IOException("Maintenance ended during restore preparation");guard.requireIdle();planName="restore-plan-"+job.jobKey+".json";
    var plan=Map.of("format","parking-pos-restore-plan","version",1,"approvedBy",job.actor,"approvedAt",Instant.now().toString(),"backup",source.fileName,"backupSha256",BackupArchive.hash(resolve(source.fileName)),"preRestoreBackup",fileName,"preRestoreSha256",BackupArchive.hash(resolve(fileName)),"requiresStoppedServer",true);
    Files.write(resolve(planName),json.writeValueAsBytes(plan),StandardOpenOption.CREATE_NEW);
   }
   String plan=planName;tx.executeWithoutResult(status->{BackupJob j=jobs.findById(id).orElseThrow();j.status=plan==null?"SUCCEEDED":"RESTORE_READY";j.fileName=fileName;j.sizeBytes=bytes;j.engine=engine;j.restorePlan=plan;j.completedAt=Instant.now();audit.record(plan==null?"BACKUP_SUCCEEDED":"RESTORE_PLAN_READY","BACKUP:"+id,null,null,null,Map.of("job",id,"initiatedBy",j.actor,"engine",engine,"bytes",bytes));});retention();
  }catch(Exception ex){tx.executeWithoutResult(status->{BackupJob j=jobs.findById(id).orElseThrow();j.status="FAILED";j.completedAt=Instant.now();j.failure="Backup/restore preparation failed ("+ex.getClass().getSimpleName()+"). Verify engine/tool support, maintenance state, server destination permissions and free disk space. No live restore was attempted.";});}
  finally{try{if(payload!=null)Files.deleteIfExists(payload);if(partial!=null)Files.deleteIfExists(partial);}catch(Exception ignored){}running.set(false);}
 }
 private BackupJob requireBackup(Long id){BackupJob job=jobs.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Backup not found"));if(!Set.of("SUCCEEDED","RESTORE_READY").contains(job.status)||job.fileName==null)throw conflict("Choose a completed retained backup");return job;}
 private Path resolve(String file){if(file==null||!file.matches("[a-zA-Z0-9.-]+"))throw new IllegalArgumentException("Invalid server backup key");Path path=directory.resolve(file).normalize();if(!path.getParent().equals(directory))throw new IllegalArgumentException("Backup escapes configured destination");return path;}
 private void retention()throws Exception{var protectedSources=new HashSet<Long>();for(BackupJob plan:jobs.findByStatusOrderByIdDesc("RESTORE_READY"))if(plan.sourceJobId!=null)protectedSources.add(plan.sourceJobId);var backups=jobs.findByStatusOrderByIdDesc("SUCCEEDED");for(int i=keep;i<backups.size();i++){BackupJob old=backups.get(i);if(old.kind.equals("PRE_RESTORE")||protectedSources.contains(old.id))continue;Files.deleteIfExists(resolve(old.fileName));tx.executeWithoutResult(status->{BackupJob j=jobs.findById(old.id).orElseThrow();j.status="EXPIRED";});}}
 private ResponseStatusException conflict(String text){return new ResponseStatusException(HttpStatus.CONFLICT,text);}
 @Override public void close(){worker.shutdown();try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
