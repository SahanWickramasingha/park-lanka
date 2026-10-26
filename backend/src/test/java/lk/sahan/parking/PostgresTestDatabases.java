package lk.sahan.parking;
import java.sql.*;
import java.util.*;
final class PostgresTestDatabases {
 static String configuredUrl(){
  String url=System.getenv("PARKING_TEST_POSTGRES_URL");
  if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/parking_pos_test_[a-f0-9]+"))throw new IllegalStateException("Tests require an isolated loopback parking_pos_test_<uuid> database");return url;
 }
 static String user(){return System.getenv("PARKING_TEST_POSTGRES_USER");}
 static String password(){return System.getenv("PARKING_TEST_POSTGRES_PASSWORD");}
 static Connection connect(String url)throws SQLException{return DriverManager.getConnection(url,user(),password());}
 static String create()throws SQLException{
  String name="parking_pos_test_"+UUID.randomUUID().toString().replace("-","");
  try(Connection c=connect(configuredUrl());Statement sql=c.createStatement()){sql.execute("create database "+name);}
  return configuredUrl().substring(0,configuredUrl().lastIndexOf('/')+1)+name;
 }
 static void drop(String url)throws SQLException{
  String name=url.substring(url.lastIndexOf('/')+1);
  if(!name.matches("parking_pos_test_[a-f0-9]+") || url.equals(configuredUrl()))throw new IllegalArgumentException("Refusing non-test database cleanup");
  try(Connection c=connect(configuredUrl());Statement sql=c.createStatement()){sql.execute("drop database "+name+" with (force)");}
 }
}
