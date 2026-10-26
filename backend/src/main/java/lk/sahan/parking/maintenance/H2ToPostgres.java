package lk.sahan.parking.maintenance;

import lk.sahan.parking.config.LocalEnvironment;
import lk.sahan.parking.config.SchemaUpgrade;
import org.flywaydb.core.Flyway;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Offline, JDBC-based transfer. No Spring context, application runners or startup seeding. */
public final class H2ToPostgres {
    private static final String HISTORY="flyway_schema_history", RECEIPTS="data_import_receipts";
    public record Report(Map<String,Long> counts, String fingerprint, long activeSessions,
                         BigDecimal payments, BigDecimal corrections, boolean applied) {}
    record Column(String name,int type,int size,int scale,boolean nullable,boolean identity) {}

    public static void main(String[] args) {
        try {
            Map<String,String> options=new HashMap<>(); boolean apply=false;
            for(int i=0;i<args.length;i++) {
                if(args[i].equals("--apply")){apply=true;continue;}
                if(!Set.of("--source-backup","--confirm").contains(args[i]) || i+1>=args.length) throw new IllegalArgumentException("Use --source-backup FILE; dry-run is the default. Apply requires --apply --confirm IMPORT_STOPPED_BACKUP.");
                if(options.put(args[i],args[++i])!=null)throw new IllegalArgumentException("Duplicate option");
            }
            if(apply && !"IMPORT_STOPPED_BACKUP".equals(options.get("--confirm")))throw new IllegalArgumentException("Explicit stopped-backup confirmation is required for apply");
            Path source=Path.of(Objects.requireNonNull(options.get("--source-backup"),"--source-backup is required")).toAbsolutePath().normalize();
            if(!source.toString().endsWith(".mv.db") || !Files.isRegularFile(source))throw new IllegalArgumentException("Choose an existing consistent stopped-backend backup ending in .mv.db");
            for(Path original:List.of(Path.of("parking-pos-data.mv.db"),Path.of("backend/parking-pos-data.mv.db")))
                if(Files.exists(original) && Files.isSameFile(source,original))throw new IllegalArgumentException("Use a separate verified stopped-backend backup, never the operational H2 file");
            String sourceUrl="jdbc:h2:file:"+source.toString().substring(0,source.toString().length()-6).replace('\\','/')+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;AUTO_SERVER=FALSE";
            Map<String,String> settings=LocalEnvironment.settings();
            String url=required(settings,"DATABASE_URL"),user=required(settings,"DATABASE_USER"),password=required(settings,"DATABASE_PASSWORD");
            if("axona_db".equals(PostgresTools.connectionEnvironment(url,user,password).get("PGDATABASE"))) throw new IllegalArgumentException("The unrelated axona_db database is prohibited");
            Properties auth=new Properties();auth.setProperty("user",user);auth.setProperty("password",password);auth.setProperty("connectTimeout","10");
            try(Connection from=DriverManager.getConnection(sourceUrl,settings.getOrDefault("H2_SOURCE_USER","sa"),settings.getOrDefault("H2_SOURCE_PASSWORD",""));
                Connection to=DriverManager.getConnection(url,auth)) {
                Report result=transfer(from,to,apply,settings.getOrDefault("PARKING_ADMIN_USER",""));
                System.out.println(apply?"Import committed and validated.":"Dry-run complete. No source or target records/schema changed.");
                result.counts().forEach((table,count)->System.out.println(table+": "+count));
                System.out.println("Active sessions: "+result.activeSessions()+"; gross payments: "+result.payments()+"; corrections: "+result.corrections());
                System.out.println("Source fingerprint: "+result.fingerprint());
            }
        } catch(Exception ex) {
            // JDBC/driver exception messages can contain connection details. Do not log their text or stack traces.
            String detail=(ex instanceof IllegalArgumentException || ex instanceof IllegalStateException)?ex.getMessage():"Database access or import failed. Check local credentials, stopped-backup access, privileges and server availability. Import data was not committed.";
            System.err.println(detail);System.exit(1);
        }
    }
    private static String required(Map<String,String> settings,String key){String value=settings.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Set "+key+" locally; do not pass passwords as arguments");return value;}

    public static Report transfer(Connection from,Connection to,boolean apply,String admin) throws Exception {
        if(!from.getMetaData().getDatabaseProductName().equals("H2") || !to.getMetaData().getDatabaseProductName().equals("PostgreSQL"))throw new IllegalArgumentException("An H2 source and PostgreSQL target are required");
        if("axona_db".equals(to.getCatalog()))throw new IllegalArgumentException("The unrelated axona_db database is prohibited");
        from.setAutoCommit(false);from.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        String version=sourceVersion(from);
        // Rehearse the exact source schema and current target schema in disposable memory only.
        String refUrl="jdbc:h2:mem:source_reference_"+UUID.randomUUID();
        try(Connection reference=DriverManager.getConnection(refUrl,"sa","");
            Connection stage=DriverManager.getConnection("jdbc:h2:mem:import_stage_"+UUID.randomUUID(),"sa","")) {
            schemaAtVersion(reference,version);
            validateSchema(from,reference);
            Flyway.configure().dataSource(new org.springframework.jdbc.datasource.SingleConnectionDataSource(stage,true)).load().migrate();
            List<String> order=orderedTables(stage);
            for(String table:order) if(tables(from).contains(table))copy(from,stage,table);
            validateRecords(stage);
            if(!admin.isBlank())try(PreparedStatement query=stage.prepareStatement("select count(*) from cashier_accounts where lower(username)=lower(?)")){
                query.setString(1,admin);try(ResultSet row=query.executeQuery()){row.next();if(row.getLong(1)>0)throw new IllegalStateException("Configured admin conflicts with an imported cashier. Choose a different admin username before importing.");}
            }
            Map<String,Long> counts=counts(stage);
            String fingerprint=fingerprint(stage,order);
            long active=scalar(stage,"select count(*) from parking_sessions where exited_at is null");
            BigDecimal gross=sum(stage,"payments","amount"), corrections=sum(stage,"payment_corrections","amount");
            inspectTarget(to);
            if(!apply)return new Report(counts,fingerprint,active,gross,corrections,false);
            // Schema migrations may persist on a failed apply; business rows and the import receipt are atomic.
            Flyway.configure().dataSource(new org.springframework.jdbc.datasource.SingleConnectionDataSource(to,true)).load().migrate();
            to.setAutoCommit(false);
            try {
                try(Statement lock=to.createStatement()){
                    lock.execute("set local lock_timeout='10s'");
                    try(ResultSet row=lock.executeQuery("select pg_try_advisory_xact_lock(706275901)")){row.next();if(!row.getBoolean(1))throw new IllegalStateException("Another import is running");}
                    lock.execute("lock table "+String.join(",",order.stream().map(H2ToPostgres::q).toList())+" in access exclusive mode");
                }
                requireEmpty(to);
                validateSchema(stage,to);
                for(String table:order)copy(stage,to,table);
                validateRecords(to);
                if(!counts.equals(counts(to)) || !fingerprint.equals(fingerprint(to,order)) || gross.compareTo(sum(to,"payments","amount"))!=0 || corrections.compareTo(sum(to,"payment_corrections","amount"))!=0)throw new IllegalStateException("Imported row counts or content differ; transaction rolled back");
                // RESTART is transactional in PostgreSQL; unlike setval it rolls back on failure.
                for(String table:order)for(Column col:columns(to,table))if(col.identity()){
                    long next=Math.addExact(scalar(to,"select coalesce(max("+q(col.name())+"),0) from "+q(table)),1);
                    try(Statement sql=to.createStatement()){sql.execute("alter table "+q(table)+" alter column "+q(col.name())+" restart with "+Math.max(1,next));}
                }
                try(PreparedStatement receipt=to.prepareStatement("insert into data_import_receipts(source_fingerprint,source_version,imported_at,row_count) values(?,?,?,?)")){
                    receipt.setString(1,fingerprint);receipt.setString(2,version);receipt.setObject(3,OffsetDateTime.now(ZoneOffset.UTC));receipt.setLong(4,counts.values().stream().mapToLong(Long::longValue).sum());receipt.executeUpdate();
                }
                to.commit();
                return new Report(counts,fingerprint,active,gross,corrections,true);
            }catch(Exception ex){to.rollback();throw ex;}finally{to.setAutoCommit(true);}
        }finally{from.rollback();from.setAutoCommit(true);}
    }
    private static void schemaAtVersion(Connection reference,String version){
        Flyway.configure().dataSource(new org.springframework.jdbc.datasource.SingleConnectionDataSource(reference,true)).target(version).load().migrate();
    }
    private static String sourceVersion(Connection c)throws Exception{
        if(!tables(c).contains(HISTORY)){SchemaUpgrade.validateLegacy(c);return "1";}
        Flyway flyway=Flyway.configure().dataSource(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true)).ignoreMigrationPatterns("*:pending").load();
        flyway.validate();
        var current=flyway.info().current();if(current==null)throw new IllegalStateException("Source migration history is empty");
        String version=current.getVersion().getVersion();
        if(Integer.parseInt(version)>11)throw new IllegalStateException("Source schema is newer than this importer");
        return version;
    }
    private static void inspectTarget(Connection c)throws Exception{
        if(scalar(c,"select count(*) from information_schema.tables where table_schema not in ('public','information_schema') and table_schema not like 'pg_%'")!=0)
            throw new IllegalStateException("Target has tables outside the supported public schema; choose a dedicated empty database");
        Set<String> names=tables(c);
        if(names.isEmpty())return;
        if(!names.contains(HISTORY))throw new IllegalStateException("Target is nonempty and unmanaged. No baseline or merge is allowed; use a separate empty target");
        Flyway.configure().dataSource(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true)).ignoreMigrationPatterns("*:pending").load().validate();
        requireEmpty(c);
    }
    private static void requireEmpty(Connection c)throws Exception{
        for(String table:tables(c))if(!table.equals(HISTORY) && scalar(c,"select count(*) from "+q(table))!=0)
            throw new IllegalStateException("Target contains records (including seeds or a previous import). Refusing a merge; choose an empty target without starting the backend first");
    }
    private static Set<String> tables(Connection c)throws SQLException{
        Set<String> result=new TreeSet<>();
        try(ResultSet rows=c.getMetaData().getTables(c.getCatalog(),c.getSchema(),"%",new String[]{"TABLE"})){while(rows.next())result.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));}return result;
    }
    private static String q(String identifier){if(!identifier.matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("Unsupported schema identifier");return '"'+identifier+'"';}
    private static String actual(Connection c,String name)throws SQLException{return c.getMetaData().storesUpperCaseIdentifiers()?name.toUpperCase(Locale.ROOT):name;}
    private static String quoted(Connection c,String name)throws SQLException{return '"'+actual(c,name)+'"';}
    private static List<Column> columns(Connection c,String table)throws SQLException{
        List<Column> result=new ArrayList<>();
        try(ResultSet rs=c.getMetaData().getColumns(c.getCatalog(),c.getSchema(),actual(c,table),"%")){
            while(rs.next())result.add(new Column(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),("timestamptz".equals(rs.getString("TYPE_NAME"))?Types.TIMESTAMP_WITH_TIMEZONE:Set.of("bool","boolean").contains(rs.getString("TYPE_NAME").toLowerCase(Locale.ROOT))?Types.BOOLEAN:rs.getInt("DATA_TYPE")),rs.getInt("COLUMN_SIZE"),rs.getInt("DECIMAL_DIGITS"),rs.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,"YES".equals(rs.getString("IS_AUTOINCREMENT"))));
        }return result;
    }
    private static void validateSchema(Connection source,Connection target)throws Exception{
        Set<String> sourceTables=new TreeSet<>(tables(source)),targetTables=new TreeSet<>(tables(target));sourceTables.remove(HISTORY);targetTables.remove(HISTORY);
        if(!sourceTables.equals(targetTables))throw new IllegalStateException("Business tables do not match the supported source schema; a reviewed intermediate upgrade is required");
        for(String table:sourceTables){
            Map<String,Column> expected=new TreeMap<>(),found=new TreeMap<>();for(Column c:columns(source,table))expected.put(c.name(),c);for(Column c:columns(target,table))found.put(c.name(),c);
            if(!expected.keySet().equals(found.keySet()))throw new IllegalStateException("Unsupported columns in "+table);
            for(Column col:expected.values()){
                Column dest=found.get(col.name());boolean numeric=Set.of(Types.DECIMAL,Types.NUMERIC).contains(col.type()) && Set.of(Types.DECIMAL,Types.NUMERIC).contains(dest.type());
                if((col.type()!=dest.type() && !numeric) || col.nullable()!=dest.nullable() || col.identity()!=dest.identity()
                    || ((col.type()==Types.VARCHAR||numeric) && (col.size()!=dest.size()||col.scale()!=dest.scale())))throw new IllegalStateException("Incompatible schema at "+table+"."+col.name());
            }
        }
    }
    private static List<String> orderedTables(Connection c)throws Exception{
        Set<String> pending=new TreeSet<>(tables(c));pending.remove(HISTORY);pending.remove(RECEIPTS);List<String> result=new ArrayList<>();
        while(!pending.isEmpty()){
            int before=pending.size();
            for(String table:new TreeSet<>(pending)){
                boolean blocked=false;
                try(ResultSet keys=c.getMetaData().getImportedKeys(c.getCatalog(),c.getSchema(),actual(c,table))){while(keys.next())if(pending.contains(keys.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT)))blocked=true;}
                if(!blocked){result.add(table);pending.remove(table);}
            }if(before==pending.size())throw new IllegalStateException("Cyclic foreign keys need a reviewed migration plan");
        }return result;
    }
    private static void copy(Connection from,Connection to,String table)throws Exception{
        List<Column> cols=columns(from,table);
        String select="select "+String.join(",",cols.stream().map(c->{try{return quoted(from,c.name());}catch(SQLException ex){throw new IllegalStateException("Cannot quote source column");}}).toList())+" from "+quoted(from,table);
        String insert="insert into "+quoted(to,table)+" ("+String.join(",",cols.stream().map(c->{try{return quoted(to,c.name());}catch(SQLException ex){throw new IllegalStateException("Cannot quote target column");}}).toList())+") values("+String.join(",",Collections.nCopies(cols.size(),"?"))+")";
        try(Statement read=from.createStatement();ResultSet rows=read.executeQuery(select);PreparedStatement write=to.prepareStatement(insert)){
            int batch=0;
            while(rows.next()){
                for(int i=0;i<cols.size();i++){
                    Object value=value(rows,i+1,cols.get(i).type());
                    if(value==null)write.setNull(i+1,cols.get(i).type());else write.setObject(i+1,value);
                }
                write.addBatch();if(++batch%250==0)write.executeBatch();
            }if(batch%250!=0)write.executeBatch();
        }
    }
    private static Object value(ResultSet row,int index,int type)throws SQLException{
        if(row.getObject(index)==null)return null;
        return switch(type){case Types.TIMESTAMP_WITH_TIMEZONE->row.getObject(index,OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC);
            case Types.NUMERIC,Types.DECIMAL->row.getBigDecimal(index);
            case Types.BOOLEAN->row.getBoolean(index);case Types.BIGINT->row.getLong(index);case Types.INTEGER->row.getInt(index);
            default->row.getString(index);};
    }
    private static Map<String,Long> counts(Connection c)throws Exception{
        Map<String,Long> result=new TreeMap<>();for(String table:tables(c))if(!Set.of(HISTORY,RECEIPTS).contains(table))result.put(table,scalar(c,"select count(*) from "+quoted(c,table)));return result;
    }
    private static String fingerprint(Connection c,List<String> tables)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String table:tables){
            hash.update(table.getBytes(StandardCharsets.UTF_8));List<Column> cols=columns(c,table).stream().sorted(Comparator.comparing(Column::name)).toList();
            List<String> primary=new ArrayList<>();try(ResultSet keys=c.getMetaData().getPrimaryKeys(c.getCatalog(),c.getSchema(),actual(c,table))){while(keys.next())primary.add(keys.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}Collections.sort(primary);
            if(primary.isEmpty())throw new IllegalStateException("No primary key for "+table);
            String order=String.join(",",primary.stream().map(n->{try{return quoted(c,n);}catch(SQLException ex){throw new IllegalStateException();}}).toList());
            try(Statement query=c.createStatement();ResultSet rows=query.executeQuery("select * from "+quoted(c,table)+" order by "+order)){
                while(rows.next())for(Column col:cols){
                    Object v=value(rows,rows.findColumn(actual(c,col.name())),col.type());
                    byte[] encoded=(v==null?"NULL":v instanceof BigDecimal n?n.toPlainString():v.toString()).getBytes(StandardCharsets.UTF_8);
                    hash.update((v==null?"N":"V").getBytes(StandardCharsets.UTF_8));hash.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.length).array());hash.update(encoded);
                }
            }
        }return HexFormat.of().formatHex(hash.digest());
    }
    private static void validateRecords(Connection c)throws Exception{
        String slots=quoted(c,"parking_slots"),sessions=quoted(c,"parking_sessions"),payments=quoted(c,"payments");
        if(scalar(c,"select count(*) from "+slots+" s where (s.occupied=true and (select count(*) from "+sessions+" p where p.slot_id=s.id and p.exited_at is null)<>1) or (s.occupied=false and exists(select 1 from "+sessions+" p where p.slot_id=s.id and p.exited_at is null))")!=0)
            throw new IllegalStateException("Slot occupancy does not match active sessions; reconcile the source before migration");
        if(scalar(c,"select count(*) from "+sessions+" where (exited_at is null and (active_plate is null or active_plate<>plate)) or (exited_at is not null and active_plate is not null)")!=0)
            throw new IllegalStateException("Invalid active-session plate state");
        if(scalar(c,"select count(*) from "+payments+" p join "+sessions+" s on p.session_id=s.id where s.exited_at is null or p.amount<0")!=0)
            throw new IllegalStateException("Invalid paid-session state or payment amount");
        if(scalar(c,"select count(*) from "+quoted(c,"payment_corrections")+" c join "+payments+" p on c.payment_id=p.id where c.amount<=0 or c.amount>p.amount")!=0)
            throw new IllegalStateException("Invalid payment corrections");
        if(scalar(c,"select count(*) from (select p.id from "+payments+" p join "+quoted(c,"payment_corrections")+" x on x.payment_id=p.id group by p.id,p.amount having sum(x.amount)>p.amount or (sum(case when x.type='VOID' then 1 else 0 end)>0 and count(*)>1)) invalid")!=0)
            throw new IllegalStateException("Cumulative corrections exceed gross or mix a void with other corrections");
        // Constraints validate actual FKs during copying, plus ownership relations not declared in V1.
        for(String table:List.of("conversations","broadcast_recipients","cashier_shifts","payment_corrections"))
            if(scalar(c,"select count(*) from "+quoted(c,table)+" x left join "+quoted(c,"branches")+" b on b.id=x.branch_id where b.id is null")!=0)throw new IllegalStateException("Missing branch in "+table);
    }
    private static long scalar(Connection c,String sql)throws SQLException{try(Statement query=c.createStatement();ResultSet row=query.executeQuery(sql)){row.next();return row.getLong(1);}}
    private static BigDecimal sum(Connection c,String table,String column)throws SQLException{try(Statement query=c.createStatement();ResultSet row=query.executeQuery("select coalesce(sum("+column+"),0) from "+quoted(c,table))){row.next();return row.getBigDecimal(1).setScale(2);}}
}
