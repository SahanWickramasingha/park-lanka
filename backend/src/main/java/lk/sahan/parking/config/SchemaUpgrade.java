package lk.sahan.parking.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Explicit adoption of the known pre-migration schema; never baseline an unknown database. */
@Configuration
public class SchemaUpgrade {
    @Bean FlywayMigrationStrategy safeMigrations(@Value("${parking.upgrade.adopt-legacy:false}") boolean adopt,
                                                @Value("${parking.upgrade.backup:}") String backup) {
        return flyway -> {
            try (Connection connection=flyway.getConfiguration().getDataSource().getConnection()) {
                Set<String> tables=tables(connection);
                if (!tables.isEmpty() && !tables.contains("flyway_schema_history")) {
                    if (!adopt) throw new IllegalStateException("Existing database requires a verified backup and explicit PARKING_ADOPT_LEGACY_SCHEMA=true. See UPGRADE.md.");
                    if (backup.isBlank() || !Files.isRegularFile(Path.of(backup)) || Files.size(Path.of(backup))==0)
                        throw new IllegalStateException("PARKING_UPGRADE_BACKUP must identify the verified stopped-database backup file");
                    validateLegacy(connection);
                    flyway.baseline(); // version 1, only after the entire known schema is checked
                }
            } catch (Exception ex) { throw new IllegalStateException("Safe database upgrade refused: "+ex.getMessage(),ex); }
            flyway.migrate();
        };
    }
    static Set<String> tables(Connection c) throws SQLException {
        Set<String> names=new TreeSet<>();
        try(ResultSet rs=c.getMetaData().getTables(c.getCatalog(),c.getSchema(),"%",new String[]{"TABLE"})) {
            while(rs.next()) names.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        } return names;
    }
    public static void validateLegacy(Connection c) throws Exception {
        String ddl=new ClassPathResource("db/migration/V1__existing_pos_schema.sql").getContentAsString(StandardCharsets.UTF_8);
        Matcher tables=Pattern.compile("create table (\\w+) \\((.*?)\\);",Pattern.DOTALL).matcher(ddl);
        Set<String> expectedTables=new TreeSet<>();
        while(tables.find()) {
            String name=tables.group(1);expectedTables.add(name);
            Map<String,String> expected=new TreeMap<>();
            for(String definition:tables.group(2).split(", (?![^()]*\\))")) {
                if(definition.startsWith("primary ")||definition.startsWith("unique "))continue;
                expected.put(definition.split(" ")[0],definition);
            }
            String actualTable=c.getMetaData().storesUpperCaseIdentifiers()?name.toUpperCase(Locale.ROOT):name;
            Set<String> actual=new TreeSet<>();
            try(ResultSet rs=c.getMetaData().getColumns(c.getCatalog(),c.getSchema(),actualTable,"%")) {
                while(rs.next()) {
                    String col=rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);actual.add(col);
                    String def=expected.get(col);if(def==null)throw new IllegalStateException("Unexpected legacy column: "+name+"."+col);
                    int type=rs.getInt("DATA_TYPE");
                    boolean valid=def.contains("varchar(")?type==Types.VARCHAR: def.contains("numeric(")?type==Types.NUMERIC||type==Types.DECIMAL:
                        def.contains("timestamp(")?type==Types.TIMESTAMP_WITH_TIMEZONE: def.contains("boolean")?type==Types.BOOLEAN:type==Types.BIGINT;
                    if(!valid || (def.contains("not null")&&rs.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls))
                        throw new IllegalStateException("Incompatible legacy column: "+name+"."+col);
                    Matcher length=Pattern.compile("(?:varchar|numeric)\\((\\d+)(?:,(\\d+))?\\)").matcher(def);
                    if(length.find()&&(rs.getInt("COLUMN_SIZE")!=Integer.parseInt(length.group(1)) || (length.group(2)!=null&&rs.getInt("DECIMAL_DIGITS")!=Integer.parseInt(length.group(2)))))
                        throw new IllegalStateException("Incompatible legacy size: "+name+"."+col);
                }
            }
            if(!actual.equals(expected.keySet()))throw new IllegalStateException("Missing legacy columns in "+name);
            Set<Set<String>> uniques=new HashSet<>();Map<String,Set<String>> indexes=new HashMap<>();
            try(ResultSet rs=c.getMetaData().getIndexInfo(c.getCatalog(),c.getSchema(),actualTable,true,false)) {
                while(rs.next())if(rs.getString("COLUMN_NAME")!=null)indexes.computeIfAbsent(rs.getString("INDEX_NAME"),k->new TreeSet<>()).add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            } uniques.addAll(indexes.values());
            Matcher unique=Pattern.compile("(?:primary key|unique) \\(([^)]+)\\)").matcher(tables.group(2));
            while(unique.find())if(!uniques.contains(new TreeSet<>(Arrays.asList(unique.group(1).split(", *")))))throw new IllegalStateException("Missing legacy uniqueness: "+name);
            if(tables.group(2).contains("session_id bigint not null unique")&&!uniques.contains(Set.of("session_id")))throw new IllegalStateException("Missing unique payment/session");
        }
        if(!tables(c).equals(expectedTables))throw new IllegalStateException("Legacy tables do not match the supported version; rehearse a reviewed upgrade first");
        Matcher fk=Pattern.compile("alter table if exists (\\w+) add constraint \\w+ foreign key \\((\\w+)\\) references (\\w+)").matcher(ddl);
        while(fk.find()) {
            String table=c.getMetaData().storesUpperCaseIdentifiers()?fk.group(1).toUpperCase(Locale.ROOT):fk.group(1);boolean found=false;
            try(ResultSet rs=c.getMetaData().getImportedKeys(c.getCatalog(),c.getSchema(),table)) {
                while(rs.next())if(rs.getString("FKCOLUMN_NAME").equalsIgnoreCase(fk.group(2))&&rs.getString("PKTABLE_NAME").equalsIgnoreCase(fk.group(3)))found=true;
            }if(!found)throw new IllegalStateException("Missing legacy foreign key: "+fk.group(1)+"."+fk.group(2));
        }
    }
}
