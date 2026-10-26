package lk.sahan.parking.maintenance;
import lk.sahan.parking.config.LocalEnvironment;
import java.sql.*;
import java.util.*;
/** Never runs migrations, seeds, DDL or business writes. */
public final class DatabaseCheck {
 public static void main(String[] args){
  try{
   var settings=LocalEnvironment.settings();
   String url=settings.getOrDefault("DATABASE_URL",""),user=settings.getOrDefault("DATABASE_USER",""),password=settings.getOrDefault("DATABASE_PASSWORD","");
   if("axona_db".equals(PostgresTools.connectionEnvironment(url,user,password).get("PGDATABASE")))throw new IllegalArgumentException("Unrelated database is prohibited");
   if(user.isBlank()||password.isBlank())throw new IllegalArgumentException("Set DATABASE_USER and DATABASE_PASSWORD locally in backend/.env or the environment");
   Properties auth=new Properties();auth.setProperty("user",user);auth.setProperty("password",password);auth.setProperty("connectTimeout","10");
   try(Connection c=DriverManager.getConnection(url,auth)){
    c.setReadOnly(true);c.setAutoCommit(false);
    try(Statement sql=c.createStatement();ResultSet row=sql.executeQuery("select current_database(), current_user, has_database_privilege(current_user,current_database(),'CONNECT'), has_schema_privilege(current_user,'public','USAGE'), has_schema_privilege(current_user,'public','CREATE'), (select count(*) from information_schema.tables where table_schema='public')")){
     row.next();System.out.println("Connected: database="+row.getString(1)+", role="+row.getString(2));
     System.out.println("CONNECT="+row.getBoolean(3)+", public USAGE="+row.getBoolean(4)+", public CREATE="+row.getBoolean(5)+", visible public tables="+row.getLong(6));
     if(!row.getBoolean(3)||!row.getBoolean(4)||!row.getBoolean(5))throw new IllegalArgumentException("Role needs reviewed CONNECT/USAGE/CREATE permissions for schema migrations; no grants were changed");
    }c.rollback();
   }
  }catch(Exception error){
   System.err.println(error instanceof IllegalArgumentException?error.getMessage():"PostgreSQL connection failed. Verify local password, service, database and role permissions. No changes were made.");System.exit(1);
  }
 }
}
