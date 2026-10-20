package ca.twintigers.research.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The last known list of held symbols, so the monitor keeps working when Ghostfolio (Docker) is down. */
public interface HoldingsCache {

    record Saved(List<String> symbols, Instant at) {
    }

    void save(List<String> symbols, Instant at);

    Optional<Saved> load();
}
