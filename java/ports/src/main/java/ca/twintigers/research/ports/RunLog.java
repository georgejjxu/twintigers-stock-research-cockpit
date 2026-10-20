package ca.twintigers.research.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Heartbeat: one row per worker run, so a stopped monitor can be detected. */
public interface RunLog {

    record Run(long id, String mode, Instant startedAt, Instant finishedAt, boolean ok, int symbols, int notifications,
               String detail) {
    }

    long start(String mode, Instant at);

    void finish(long id, Instant at, boolean ok, int symbols, int notifications, String detail);

    /** Last run that finished successfully and actually evaluated something (mode close or intraday). */
    Optional<Run> lastSuccessfulEvaluation();

    List<Run> recentRuns(int limit);
}
