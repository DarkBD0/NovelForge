import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProvisionMySql {
    private static final Pattern DB_NAME=Pattern.compile("[a-z][a-z0-9_]{2,63}");
    public static void main(String[] args) throws Exception {
        if(args.length<1 || args.length>2) throw new IllegalArgumentException("Usage: ProvisionMySql <database-local-env> [--recreate-empty]");
        Path config=Path.of(args[0]).toAbsolutePath().normalize();
        List<String> lines=Files.readAllLines(config);
        Map<String,String> settings=new LinkedHashMap<>();
        for(String raw:lines) {
            String trimmed=raw.strip().replace("\uFEFF","");
            if(trimmed.isBlank() || trimmed.startsWith("#")) continue;
            int separator=trimmed.indexOf('=');
            if(separator<1) throw new IllegalStateException("Invalid configuration line in "+config);
            settings.put(trimmed.substring(0,separator).strip(),unquote(trimmed.substring(separator+1).strip()));
        }
        String jdbc=settings.get("NOVELFORGE_DB_URL");
        String user=settings.get("NOVELFORGE_DB_USERNAME");
        String password=settings.get("NOVELFORGE_DB_PASSWORD");
        if(jdbc==null || user==null || password==null || !jdbc.startsWith("jdbc:mysql://")) throw new IllegalStateException("NovelForge database configuration is incomplete");
        Matcher matcher=Pattern.compile("^(jdbc:mysql://[^/]+/)([^?]*)(.*)$").matcher(jdbc);
        if(!matcher.matches()) throw new IllegalStateException("Unsupported MySQL JDBC URL");
        String database=matcher.group(2);
        if(!DB_NAME.matcher(database).matches()) throw new IllegalArgumentException("Unsafe database name");
        String serverUrl=matcher.group(1)+matcher.group(3);
        Class.forName("com.mysql.cj.jdbc.Driver");
        try(var connection=DriverManager.getConnection(serverUrl,user,password);var statement=connection.createStatement()) {
            if(args.length==2) {
                if(!"--recreate-empty".equals(args[1])) throw new IllegalArgumentException("Unknown option");
                boolean exists;
                try(var result=statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='"+database+"'")) {
                    result.next(); exists=result.getInt(1)>0;
                }
                if(exists) {
                    long novels=countIfPresent(statement,database,"novels");
                    long projects=countIfPresent(statement,database,"novel_project");
                    if(novels!=0 || projects!=0) throw new IllegalStateException("Refusing to recreate a database that contains novels");
                    statement.executeUpdate("DROP DATABASE `"+database+"`");
                }
            }
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try(var result=statement.executeQuery("SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='"+database+"'")) {
                if(!result.next()) throw new IllegalStateException("Database was not created");
            }
        }
        System.out.println("MySQL database ready: "+database);
    }
    private static long countIfPresent(java.sql.Statement statement,String database,String table) {
        try(var result=statement.executeQuery("SELECT COUNT(*) FROM `"+database+"`.`"+table+"`")) {
            result.next();
            return result.getLong(1);
        } catch(java.sql.SQLException ignored) {
            return 0;
        }
    }
    private static String unquote(String value) {
        if(value.length()>=2 && ((value.startsWith("\"")&&value.endsWith("\""))||(value.startsWith("'")&&value.endsWith("'")))) return value.substring(1,value.length()-1);
        return value;
    }
}
