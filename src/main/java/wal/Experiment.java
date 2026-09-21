package wal;

import java.util.ArrayList;
import java.util.List;

/** Persisted experiment: name, editable script and crash configuration. */
public final class Experiment {
    public String id;
    public String name;
    public long createdAt;
    public final List<ScriptOp> script = new ArrayList<>();
    public CrashPlan plan = new CrashPlan();
    /** Incremented whenever script/plan changes, invalidating the materialised media. */
    public int revision = 0;
    /** Revision that was materialised to the media directory. */
    public int materialisedRevision = -1;
}
