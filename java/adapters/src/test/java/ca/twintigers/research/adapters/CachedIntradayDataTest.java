package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.market.CachedIntradayData;
import ca.twintigers.research.adapters.store.Database;
import ca.twintigers.research.domain.market.Candle;
import ca.twintigers.research.domain.market.Interval;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.IntradayData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Intraday bars are stored by interval; the feed is asked only for the new tail, at most once a minute. */
class CachedIntradayDataTest {

    static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    /** A feed with one 5-minute bar every 5 minutes up to "now"; records what was asked. */
    static final class Feed implements IntradayData {
        Instant now;
        final List<Instant> asked = new ArrayList<>();
        boolean down;

        @Override
        public Series intraday(String symbol, Interval interval, Instant from) throws DataSourceException {
            asked.add(from);
            if (down) {
                throw new DataSourceException("feed down");
            }
            List<Candle> out = new ArrayList<>();
            for (Instant t = from.truncatedTo(java.time.temporal.ChronoUnit.HOURS); !t.isAfter(now); t = t.plus(interval.size())) {
                if (!t.isBefore(from)) {
                    double p = 60 + (t.getEpochSecond() / 300 % 10) * 0.1;
                    out.add(new Candle(t, p, p + 0.05, p - 0.05, p, 1000));
                }
            }
            return new Series(symbol, interval, TORONTO, out, "feed", 15, now);
        }
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void storesBarsAndAsksOnlyForTheTail(@TempDir Path dir) throws Exception {
        Instant t0 = Instant.parse("2026-10-19T15:00:00Z");
        MutableClock clock = new MutableClock(t0);
        Feed feed = new Feed();
        feed.now = t0;
        CachedIntradayData cache = new CachedIntradayData(feed, new Database(dir.resolve("r.db")), clock);
        Instant from = t0.minus(Duration.ofHours(2));

        IntradayData.Series first = cache.intraday("ENB.TO", Interval.M5, from);
        assertThat(first.candles()).hasSize(25); // 2 hours of 5-minute bars, both ends included
        assertThat(feed.asked).containsExactly(from);
        assertThat(first.source()).isEqualTo("feed, stored locally");

        clock.now = t0.plusSeconds(30);
        cache.intraday("ENB.TO", Interval.M5, from);
        assertThat(feed.asked).hasSize(1); // within a minute: from the store

        clock.now = t0.plus(Duration.ofMinutes(20));
        feed.now = clock.now;
        IntradayData.Series later = cache.intraday("ENB.TO", Interval.M5, from);
        assertThat(feed.asked).hasSize(2);
        assertThat(feed.asked.get(1)).isEqualTo(t0.minus(Duration.ofMinutes(10))); // last stored bar minus two bars
        assertThat(later.candles()).hasSize(29);
        assertThat(later.candles()).extracting(Candle::start).doesNotHaveDuplicates().isSorted();

        clock.now = t0.plus(Duration.ofMinutes(30));
        feed.down = true;
        assertThat(cache.intraday("ENB.TO", Interval.M5, from).candles()).hasSize(29); // feed down: what is stored
        // other intervals are stored separately: nothing stored for 15-minute bars and the feed down is an error
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.intraday("ENB.TO", Interval.M15, from)).hasMessage("feed down");
    }
}
