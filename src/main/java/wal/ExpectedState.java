package wal;

import java.util.LinkedHashMap;
import java.util.List;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reference (no-crash) outcome of a script: materialise the identical script
 * without any fault/truncation into a throwaway directory and recover it.
 * Comparing that map with the crashed recovery shows which committed
 * transactions were lost to the power loss.
 */
public final class ExpectedState {

    private ExpectedState() {}

    public static RecoveryReport compute(Path scratch, List<ScriptOp> script)
            throws java.io.IOException {
        ExperimentStore.deleteRecursively(scratch);
        java.nio.file.Files.createDirectories(scratch);
        new Simulator(scratch, CrashPlan.none()).run(script);
        return new RecoveryEngine(scratch).recover();
    }

    public static Map<String, String> computeKv(Path scratch, List<ScriptOp> script)
            throws java.io.IOException {
        return new LinkedHashMap<>(compute(scratch, script).kv);
    }
}
