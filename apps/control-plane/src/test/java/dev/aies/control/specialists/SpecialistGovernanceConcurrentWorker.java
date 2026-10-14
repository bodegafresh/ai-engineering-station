package dev.aies.control.specialists;

import java.nio.file.Path;
import java.sql.*;
import java.util.Properties;

/**
 * Separate JVM worker. Exercises SQLite transaction boundaries independently of
 * synchronized Java methods; does not represent the HTTP operator gateway.
 */
public final class SpecialistGovernanceConcurrentWorker {
    private SpecialistGovernanceConcurrentWorker() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("db, key, start-signal required");
        Path db=Path.of(args[0]);
        Path start=Path.of(args[2]);
        long deadline=System.nanoTime()+20_000_000_000L;
        while (!java.nio.file.Files.exists(start)) {
            if (System.nanoTime()>deadline) System.exit(12);
            Thread.sleep(10);
        }
        try(Connection c=new org.sqlite.JDBC().connect("jdbc:sqlite:"+db.toAbsolutePath(),new Properties())) {
            if(c==null) throw new SQLException("SQLite unavailable");
            try(Statement s=c.createStatement()) {
                s.execute("PRAGMA busy_timeout=10000");
                s.execute("BEGIN IMMEDIATE");
                try {
                    try(PreparedStatement p=c.prepareStatement(
                        "INSERT INTO specialist_governance_idempotency " +
                        "(command_key,id,version,action,expected_revision,expected_hash,actor,state,result_revision) " +
                        "VALUES(?,'documentation.specialist','1.0.0','VALIDATE',0,?,'operator-test','VALIDATED',1)")) {
                        p.setString(1,args[1]);
                        p.setString(2,"a".repeat(64));
                        p.executeUpdate();
                    }
                    s.execute("COMMIT");
                    System.exit(0);
                } catch(SQLException e) {
                    s.execute("ROLLBACK");
                    if(e.getMessage()!=null && e.getMessage().contains("UNIQUE constraint")) System.exit(10);
                    throw e;
                }
            }
        }
    }
}
