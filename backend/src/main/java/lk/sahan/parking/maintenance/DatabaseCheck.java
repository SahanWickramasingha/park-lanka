package lk.sahan.parking.maintenance;
import lk.sahan.parking.config.LocalEnvironment;
import java.sql.*;
import java.util.*;
/** Never runs migrations, seeds, DDL or business writes. */
public final class DatabaseCheck {
 public static void main(String[] args){
  try{
   boolean neon=args.length==1&&args[0].equals("--neon");
   if(args.length>0&&!neon)throw new IllegalArgumentException("Usage: DatabaseCheck [--neon]");
   var settings=neon?LocalEnvironment.neonSettings():LocalEnvironment.settings();
   String url=settings.getOrDefault(neon?"FLYWAY_DATABASE_URL":"DATABASE_URL",""),user=settings.getOrDefault(neon?"FLYWAY_DATABASE_USER":"DATABASE_USER",""),password=settings.getOrDefault(neon?"FLYWAY_DATABASE_PASSWORD":"DATABASE_PASSWORD","");
   if("axona_db".equals(PostgresTools.connectionEnvironment(url,user,password).get("PGDATABASE")))throw new IllegalArgumentException("Unrelated database is prohibited");
   if(user.isBlank()||password.isBlank())throw new IllegalArgumentException("Set DATABASE_USER and DATABASE_PASSWORD locally in backend/.env or the environment");
   Properties auth=new Properties();auth.setProperty("user",user);auth.setProperty("password",password);auth.setProperty("connectTimeout","10");
   try(Connection c=DriverManager.getConnection(url,auth)){
    c.setReadOnly(true);c.setAutoCommit(false);
    try(Statement sql=c.createStatement();ResultSet row=sql.executeQuery("select current_database(), current_user, has_database_privilege(current_user,current_database(),'CONNECT'), has_schema_privilege(current_user,'public','USAGE'), has_schema_privilege(current_user,'public','CREATE'), (select count(*) from information_schema.tables where table_schema='public')")){
     row.next();System.out.println(neon?"Connected to Neon using the direct migration connection.":"Connected: database="+row.getString(1)+", role="+row.getString(2));
     System.out.println("CONNECT="+row.getBoolean(3)+", public USAGE="+row.getBoolean(4)+", public CREATE="+row.getBoolean(5)+", visible public tables="+row.getLong(6));
     if(!row.getBoolean(3)||!row.getBoolean(4)||!row.getBoolean(5))throw new IllegalArgumentException("Role needs reviewed CONNECT/USAGE/CREATE permissions for schema migrations; no grants were changed");
    }
    if(neon){
     // Neon terminates client TLS at its proxy. pg_stat_ssl describes the internal
     // proxy-to-Postgres session, not this client's verified TLS handshake.
     if(!"verify-full".equals(PostgresTools.connectionEnvironment(url,user,password).get("PGSSLMODE")))
      throw new IllegalArgumentException("Neon verification requires sslmode=verify-full");
     System.out.println("Client connection succeeded with enforced TLS certificate and hostname verification (verify-full).");
     Set<String> tables=new TreeSet<>();
     try(Statement sql=c.createStatement();ResultSet rows=sql.executeQuery("select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE'")){
      while(rows.next())tables.add(rows.getString(1));
     }
     System.out.println("Empty public application schema: "+tables.isEmpty());
     if(tables.contains("flyway_schema_history"))try(Statement sql=c.createStatement();ResultSet rows=sql.executeQuery("select count(*), max(installed_rank), bool_and(success) from flyway_schema_history")){
      rows.next();System.out.println("Flyway history: entries="+rows.getLong(1)+", latest rank="+rows.getLong(2)+", all successful="+rows.getBoolean(3));
     }
     for(String table:List.of("branches","cashier_accounts","parking_sessions","payments"))if(tables.contains(table))try(Statement sql=c.createStatement();ResultSet rows=sql.executeQuery("select count(*) from "+table)){
      rows.next();System.out.println(table+" rows="+rows.getLong(1));
     }
    }
    c.rollback();
   }
  }catch(Exception error){
   System.err.println(error instanceof IllegalArgumentException?error.getMessage():"PostgreSQL connection failed. Verify local password, service, database and role permissions. No changes were made.");System.exit(1);
  }
 }
}
