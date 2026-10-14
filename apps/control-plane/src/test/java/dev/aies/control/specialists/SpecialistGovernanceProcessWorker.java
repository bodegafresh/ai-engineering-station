package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Standalone JVM test worker: invokes the actual governance service, not raw SQL. */
public final class SpecialistGovernanceProcessWorker {
    private SpecialistGovernanceProcessWorker() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("db,hash,key,barrier,mode");
        Path db=Path.of(args[0]);
        String hash=args[1], key=args[2], mode=args[4];
        Path barrier=Path.of(args[3]);
        long deadline=System.nanoTime()+20_000_000_000L;
        while (!Files.exists(barrier)) {
            if(System.nanoTime()>deadline) System.exit(13);
            Thread.sleep(10);
        }
        if ("halt-before".equals(mode)) Runtime.getRuntime().halt(31);
        Runnable probe = () -> {
            if ("halt-in-transaction".equals(mode)) Runtime.getRuntime().halt(33);
            if ("halt-in-transaction-signaled".equals(mode)) {
                try {
                    Files.createFile(Path.of(barrier + ".inside"));
                    long until = System.nanoTime() + 20_000_000_000L;
                    while (!Files.exists(Path.of(barrier + ".release"))) {
                        if (System.nanoTime() > until) Runtime.getRuntime().halt(34);
                        Thread.sleep(10);
                    }
                } catch (Exception ex) { throw new IllegalStateException("Crash probe failed", ex); }
                Runtime.getRuntime().halt(33);
            }
        };
        try (var service=("halt-in-transaction".equals(mode) || "halt-in-transaction-signaled".equals(mode))
            ? new SpecialistGovernanceService(db,new ObjectMapper(),probe)
            : new SpecialistGovernanceService(db,new ObjectMapper())) {
            var actor=new SpecialistGovernanceService.Principal("operator-test",Set.of("specialists:validate"));
            service.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,actor,key);
            if ("halt-after".equals(mode)) Runtime.getRuntime().halt(32);
        } catch (java.sql.SQLException | IllegalStateException ex) {
            System.err.println(ex.getClass().getSimpleName()+": "+ex.getMessage());
            System.exit(10);
        }
    }
}
