package lk.sahan.parking.maintenance;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import java.sql.*;

/** Offline maintenance entry point. Always restores to a separate empty destination. */
public final class BackupRestore {
 private static final ObjectMapper JSON=new ObjectMapper();
 public static void main(String[] args)throws Exception{
  Map<String,String> settings=lk.sahan.parking.config.LocalEnvironment.settings();
  Map<String,String> options=new HashMap<>();for(int i=0;i<args.length;i+=2){if(i+1>=args.length)throw new IllegalArgumentException("Expected --plan, --destination and --confirm pairs");options.put(args[i],args[i+1]);}
  if(!"RESTORE_STOPPED_SERVER".equals(options.get("--confirm")))throw new IllegalArgumentException("Stop the correct backend and explicitly confirm RESTORE_STOPPED_SERVER");
  Path plan=Path.of(Objects.requireNonNull(options.get("--plan"),"--plan required")).toAbsolutePath().normalize();Path archive=approvedArchive(plan);var manifest=BackupArchive.inspect(archive);
  if(manifest.engine().equals("H2")){
   Path destination=Path.of(Objects.requireNonNull(options.get("--destination"),"New .mv.db destination required"));restoreH2(plan,destination,"RESTORE_STOPPED_SERVER");System.out.println("Restored to the separate destination. The original database was not overwritten. Follow UPGRADE.md before switching the backend URL.");
  }else{
   String url=requiredSetting(settings,"RESTORE_DATABASE_URL"),user=requiredSetting(settings,"RESTORE_DATABASE_USER"),password=settings.getOrDefault("RESTORE_DATABASE_PASSWORD","");
   var destinationConnection=PostgresTools.connectionEnvironment(url,user,password);
   if("axona_db".equals(destinationConnection.get("PGDATABASE")))throw new IOException("The unrelated axona_db database is prohibited");
   String sourceUrl=settings.get("DATABASE_URL");
   if(sourceUrl!=null && sourceUrl.startsWith("jdbc:postgresql://")){var operational=PostgresTools.connectionEnvironment(sourceUrl,settings.getOrDefault("DATABASE_USER",""),settings.getOrDefault("DATABASE_PASSWORD",""));if(operational.get("PGHOST").equals(destinationConnection.get("PGHOST")) && operational.get("PGPORT").equals(destinationConnection.get("PGPORT")) && operational.get("PGDATABASE").equals(destinationConnection.get("PGDATABASE")))throw new IOException("Restore destination must differ from the operational database");}
   try(Connection c=DriverManager.getConnection(url,user,password);Statement s=c.createStatement();ResultSet rows=s.executeQuery("select count(*) from information_schema.tables where table_schema='public'")){rows.next();if(rows.getLong(1)!=0)throw new IOException("PostgreSQL restore destination must be a separate empty database");}
   Path payload=Files.createTempFile("pos-restore-",".dump");try{Files.delete(payload);BackupArchive.extractPayload(archive,payload);PostgresTools.run(settings.getOrDefault("PARKING_PG_RESTORE","pg_restore"),false,url,user,password,payload);}finally{Files.deleteIfExists(payload);}
   System.out.println("Restored to the empty PostgreSQL destination. Validate records before switching application configuration.");
  }
 }
 private static String requiredSetting(Map<String,String> settings,String name){String value=settings.get(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Set "+name);return value;}
 public static Path approvedArchive(Path plan)throws Exception{
  if(Files.size(plan)>65536)throw new IOException("Invalid restore plan");JsonNode node=JSON.readTree(plan.toFile());
  if(!node.path("format").asText().equals("parking-pos-restore-plan")||node.path("version").asInt()!=1||node.path("approvedBy").asText().isBlank()||!node.path("requiresStoppedServer").asBoolean())throw new IOException("An admin-approved maintenance plan is required");
  Instant approved=Instant.parse(node.path("approvedAt").asText());if(approved.isAfter(Instant.now().plusSeconds(60))||approved.isBefore(Instant.now().minus(Duration.ofHours(24))))throw new IOException("Restore approval has expired; prepare a new plan in maintenance");
  Path root=plan.toAbsolutePath().normalize().getParent();Path archive=contained(root,node.path("backup").asText()),pre=contained(root,node.path("preRestoreBackup").asText());
  if(!BackupArchive.hash(archive).equals(node.path("backupSha256").asText())||!BackupArchive.hash(pre).equals(node.path("preRestoreSha256").asText()))throw new IOException("Restore source/pre-restore backup changed or is unavailable");BackupArchive.inspect(archive);BackupArchive.inspect(pre);return archive;
 }
 private static Path contained(Path root,String name)throws IOException{if(!name.matches("pos-backup-[a-f0-9-]+\\.zip"))throw new IOException("Invalid server backup filename");Path path=root.resolve(name).normalize();if(!path.getParent().equals(root)||Files.isSymbolicLink(path))throw new IOException("Backup path is not contained");return path;}
 public static Path restoreH2(Path plan,Path destination,String confirmation)throws Exception{
  if(!"RESTORE_STOPPED_SERVER".equals(confirmation))throw new IOException("Explicit stopped-server confirmation is required");Path source=approvedArchive(plan);if(!BackupArchive.inspect(source).engine().equals("H2"))throw new IOException("Not an H2 backup");
  Path target=destination.toAbsolutePath().normalize();if(!target.toString().endsWith(".mv.db")||Files.exists(target)||target.startsWith(plan.toAbsolutePath().normalize().getParent()))throw new IOException("Choose a new .mv.db destination outside the backup directory; existing files are never overwritten");
  Files.createDirectories(target.getParent());Path payload=Files.createTempFile(target.getParent(),"restore-payload-",".zip"),staged=Files.createTempFile(target.getParent(),"restore-staged-",".mv.db");boolean done=false;
  try{Files.delete(payload);BackupArchive.extractPayload(source,payload);int count=0;
   try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(payload));OutputStream out=Files.newOutputStream(staged)){ZipEntry entry;while((entry=zip.getNextEntry())!=null){if(entry.isDirectory())continue;if(!entry.getName().endsWith(".mv.db")||++count>1)throw new IOException("Expected exactly one H2 database file");long bytes=0;byte[] buffer=new byte[65536];int n;while((n=zip.read(buffer))!=-1){bytes+=n;if(bytes>50L*1024*1024*1024)throw new IOException("Restored H2 file exceeds size limit");out.write(buffer,0,n);}}}if(count!=1)throw new IOException("H2 database missing from archive");
   // No REPLACE_EXISTING: target creation races fail safely. Original operational files are untouched.
   Files.move(staged,target);done=true;return target;
  }finally{Files.deleteIfExists(payload);if(!done)Files.deleteIfExists(staged);}
 }
}
