package ca.twintigers.research.adapters.config;

import ca.twintigers.research.domain.market.MarketCalendar;
import ca.twintigers.research.domain.market.MarketCalendar.Exchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Reads java/config/holidays/TSX.txt and NYSE.txt (one ISO date per line, # comments) into a {@link MarketCalendar}. */
public final class Holidays {

    private Holidays() {
    }

    public static MarketCalendar load(Path dir) {
        Map<Exchange, Set<LocalDate>> m = new EnumMap<>(Exchange.class);
        for (Exchange x : Exchange.values()) {
            Set<LocalDate> days = new HashSet<>();
            Path f = dir.resolve(x.name() + ".txt");
            if (Files.isRegularFile(f)) {
                try {
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        String s = line.replaceAll("#.*", "").strip();
                        if (!s.isEmpty()) {
                            days.add(LocalDate.parse(s));
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read " + f + ": " + e.getMessage(), e);
                }
            }
            m.put(x, days);
        }
        return new MarketCalendar(m);
    }
}
