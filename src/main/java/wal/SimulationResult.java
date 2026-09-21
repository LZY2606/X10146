package wal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outcome of materialising a script up to (and including) the simulated crash. */
public final class SimulationResult {
    /** Index of the event during which power was lost (the interrupted write), or -1. */
    public int crashedAtEvent = -1;
    public boolean crashed = false;
    public String crashReason;
    /** Ordered, human-readable trace of every write phase performed. */
    public final List<String> trace = new ArrayList<>();
    /** Segment index currently open after materialisation. */
    public int activeSegment;
    /** Checkpoint ids that were fully installed. */
    public final List<Long> installedCheckpoints = new ArrayList<>();
    /** Reference key/value state as of the crash point. */
    public final Map<String, String> kvAtCrash = new LinkedHashMap<>();
}
