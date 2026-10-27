package lk.sahan.parking.maintenance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import java.time.Instant;

/** Versioned, checksummed envelope. No datasource credentials are included. */
public final class BackupArchive {
 public static final int CURRENT_SCHEMA=11;
 private static final long MAX_BYTES=50L*1024*1024*1024;
 private static final ObjectMapper JSON=new ObjectMapper();
 public record Manifest(String format,int formatVersion,int schemaVersion,String engine,String createdAt,long bytes,String sha256){}
 public static Manifest pack(Path payload,Path archive,String engine,int schema)throws Exception{
  Manifest manifest=new Manifest("parking-pos-backup",1,schema,engine,Instant.now().toString(),Files.size(payload),hash(payload));
  try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(archive,StandardOpenOption.CREATE_NEW))){zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(JSON.writeValueAsBytes(manifest));zip.closeEntry();zip.putNextEntry(new ZipEntry("database.bin"));Files.copy(payload,zip);zip.closeEntry();}return manifest;
 }
 public static Manifest inspect(Path archive)throws Exception{
  try(ZipFile zip=new ZipFile(archive.toFile())){
   if(zip.size()!=2||zip.getEntry("manifest.json")==null||zip.getEntry("database.bin")==null)throw new IOException("Unrecognized backup envelope");
   Manifest manifest;try(InputStream in=zip.getInputStream(zip.getEntry("manifest.json"))){byte[] data=in.readNBytes(65537);if(data.length>65536)throw new IOException("Oversized manifest");manifest=JSON.readValue(data,Manifest.class);}
   if(!manifest.format().equals("parking-pos-backup")||manifest.formatVersion()!=1||manifest.schemaVersion()<1||manifest.schemaVersion()>CURRENT_SCHEMA||!Set.of("H2","POSTGRESQL").contains(manifest.engine())||manifest.bytes()<1||manifest.bytes()>MAX_BYTES)throw new IOException("Unsupported backup format, engine or schema version");
   MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;try(InputStream in=zip.getInputStream(zip.getEntry("database.bin"))){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1){count+=n;if(count>manifest.bytes())throw new IOException("Backup size mismatch");digest.update(bytes,0,n);}}
   if(count!=manifest.bytes()||!HexFormat.of().formatHex(digest.digest()).equals(manifest.sha256()))throw new IOException("Backup checksum mismatch");return manifest;
  }
 }
 public static void extractPayload(Path archive,Path destination)throws Exception{inspect(archive);try(ZipFile zip=new ZipFile(archive.toFile());InputStream in=zip.getInputStream(zip.getEntry("database.bin"))){Files.copy(in,destination);}}
 public static String hash(Path file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());}
}
