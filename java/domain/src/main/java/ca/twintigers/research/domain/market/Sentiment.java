package ca.twintigers.research.domain.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A market sentiment index such as CNN's Fear &amp; Greed Index (0 = extreme fear, 100 = extreme greed), with the
 * readings it compares against, its components and its history. As published; nothing here is recomputed.
 *
 * @param previous label to score, e.g. "Previous close", "1 week ago"
 */
public record Sentiment(String name, String source, double score, String rating, Instant asOf, List<Reading> previous,
                        List<Reading> components, List<Point> history) {

    public record Reading(String label, double score, String rating) {
    }

    public record Point(LocalDate date, double score) {
    }

    /** The published bands: 0–25 extreme fear, 25–45 fear, 45–55 neutral, 55–75 greed, 75–100 extreme greed. */
    public static String band(double score) {
        return score < 25 ? "extreme fear" : score < 45 ? "fear" : score <= 55 ? "neutral" : score <= 75 ? "greed" : "extreme greed";
    }
}
