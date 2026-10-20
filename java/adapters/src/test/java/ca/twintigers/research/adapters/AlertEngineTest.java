package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.adapters.notify.NtfyNotifier;
import ca.twintigers.research.adapters.store.Database;
import ca.twintigers.research.adapters.store.SqliteAlertDb;
import ca.twintigers.research.application.AlertEngine;
import ca.twintigers.research.application.AlertService;
import ca.twintigers.research.application.FinancialHealthService;
import ca.twintigers.research.application.NotificationDispatcher;
import ca.twintigers.research.application.PriceSignalService;
import ca.twintigers.research.domain.alerts.Notification;
import ca.twintigers.research.domain.fundamentals.Thresholds;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.MarketCalendar;
import ca.twintigers.research.domain.market.PriceRuleConfig;
import ca.twintigers.research.domain.market.PriceRules;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.Notifier;
import ca.twintigers.research.ports.PortfolioSource;
import ca.twintigers.research.ports.QuoteSource;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The alert engine end to end on a real SQLite file: dedupe across runs, outbox retry, fallbacks, heartbeat. */
@WireMockTest
class AlertEngineTest {

    /** A clock the test moves forward. */
    static final class MovableClock extends Clock {
        Instant now;

        MovableClock(Instant start) {
            now = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("America/Toronto");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    static final LocalDate TODAY = LocalDate.of(2026, 10, 19); // Monday
    static final Instant AFTER_CLOSE = Instant.parse("2026-10-19T20:30:00Z"); // 16:30 Eastern

    @TempDir
    Path dir;
    MovableClock clock;
    SqliteAlertDb db;
    List<Notification> toasts;
    boolean ghostfolioUp;
    AlertService alerts;

    /** 60 quiet days, then a seller-controlled drop today (close 97.9 on 2× volume). */
    static List<Bar> bars() {
        List<Bar> out = new ArrayList<>();
        for (int i = 60; i >= 1; i--) {
            out.add(new Bar(TODAY.minusDays(i), 100, 100.4, 99.6, 100, 1_000_000));
        }
        out.add(new Bar(TODAY, 100, 100.1, 97.8, 97.9, 2_000_000));
        return out;
    }

    @BeforeEach
    void setUp() {
        clock = new MovableClock(AFTER_CLOSE);
        db = new SqliteAlertDb(new Database(dir.resolve("research.db")));
        toasts = new ArrayList<>();
        ghostfolioUp = true;
        alerts = new AlertService(db, PriceRules.all(PriceRuleConfig.defaults()), clock);
    }

    AlertEngine engine(List<Notifier> notifiers) {
        PortfolioSource portfolio = new FakePortfolioSource(() -> {
            if (!ghostfolioUp) {
                throw new DataSourceException("Ghostfolio is not reachable");
            }
            return new Portfolio("CAD", List.of(new Portfolio.Position("ENB.TO", "Enbridge", "CAD", BigDecimal.ONE,
                    BigDecimal.ONE, BigDecimal.ONE, Map.of())), BigDecimal.ZERO);
        });
        MarketData market = new MarketData() {
            @Override
            public List<Bar> dailyBars(String symbol, LocalDate from, LocalDate to) {
                return bars();
            }

            @Override
            public String source() {
                return "test bars";
            }
        };
        QuoteSource quotes = s -> new QuoteSource.Quote(s, 97.5, clock.instant(), "test");
        FinancialHealthService health = new FinancialHealthService(t -> Optional.empty(), cik -> {
            throw new DataSourceException("no SEC in this test");
        }, Thresholds.defaults());
        PriceSignalService prices = new PriceSignalService(market, PriceRuleConfig.defaults(), clock);
        MarketCalendar cal = new MarketCalendar(Map.of(MarketCalendar.Exchange.TSX, Set.of(), MarketCalendar.Exchange.NYSE, Set.of()));
        return new AlertEngine(portfolio, db, market, quotes, prices, health, Map.of("ENB", ""), db, db,
                new NotificationDispatcher(db, notifiers, clock), cal, Duration.ofHours(24), clock);
    }

    Notifier capturing() {
        return new Notifier() {
            @Override
            public String channel() {
                return "toast";
            }

            @Override
            public void send(Notification n) {
                toasts.add(n);
            }
        };
    }

    @Test
    void closeRunNotifiesOnceAndASecondRunDoesNotDoubleSend() {
        alerts.subscribe("*", "price", "PA02_seller_controlled_drop", "warning", "user");
        alerts.addThreshold("below", "ENB.TO", 98.0, null, null, null, null, "add more?");
        AlertEngine e = engine(List.of(capturing()));

        var r1 = e.run(AlertEngine.Mode.CLOSE);
        assertThat(r1.ran()).isTrue();
        assertThat(toasts).extracting(Notification::title)
                .containsExactlyInAnyOrder("ENB.TO · your alert #1", "ENB.TO · Seller-controlled price drop (warning)");
        assertThat(toasts).anyMatch(n -> n.body().contains("ENB.TO at 97.90 is at or below your level 98.00")
                && n.body().contains("close of 2026-10-19") && n.body().contains("Note: add more?"));

        clock.advance(Duration.ofMinutes(10));
        var r2 = e.run(AlertEngine.Mode.CLOSE);
        assertThat(r2.queued()).isZero();
        assertThat(toasts).hasSize(2);
        assertThat(db.alert(1).orElseThrow().state().status().name()).isEqualTo("TRIGGERED");
    }

    @Test
    void unsubscribedRulesAndMutedAlertsStaySilent() {
        alerts.subscribe("*", "price", "K05_dark_cloud_cover", "warning", "user");
        var a = alerts.addThreshold("below", "ENB.TO", 98.0, null, null, null, null, null);
        alerts.mute("alert", a.id(), Duration.ofDays(1));
        engine(List.of(capturing())).run(AlertEngine.Mode.CLOSE);
        assertThat(toasts).isEmpty();
        assertThat(db.alert(a.id()).orElseThrow().state().status().name()).isEqualTo("TRIGGERED"); // state still tracked
    }

    @Test
    void failedPushIsRetriedAndNeverSentTwice(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/")).inScenario("ntfy").whenScenarioStateIs("Started")
                .willReturn(serverError()).willSetStateTo("down"));
        stubFor(post(urlEqualTo("/")).inScenario("ntfy").whenScenarioStateIs("down")
                .willReturn(serverError()).willSetStateTo("up"));
        stubFor(post(urlEqualTo("/")).inScenario("ntfy").whenScenarioStateIs("up").willReturn(okJson("{\"id\":\"x\"}")));
        Notifier ntfy = new NtfyNotifier(new Http(Duration.ofSeconds(5), 1), wm.getHttpBaseUrl(), "topic-abc", null);
        NotificationDispatcher d = new NotificationDispatcher(db, List.of(capturing(), ntfy), clock);

        d.enqueue(new Notification("signal|ENB.TO|PA02|2026-10-19", "ENB.TO · test", "body", Notification.Priority.HIGH));
        var first = d.dispatch();
        assertThat(first.sent()).isEqualTo(1);   // toast
        assertThat(first.failed()).isEqualTo(1); // ntfy 500
        assertThat(db.pendingCount()).isEqualTo(1);

        assertThat(d.dispatch().sent()).isZero(); // back-off: not due yet
        clock.advance(Duration.ofMinutes(2));
        assertThat(d.dispatch().failed()).isEqualTo(1); // still down
        clock.advance(Duration.ofMinutes(6));
        assertThat(d.dispatch().sent()).isEqualTo(1);
        clock.advance(Duration.ofHours(3));
        assertThat(d.dispatch().sent()).isZero();
        assertThat(d.enqueue(new Notification("signal|ENB.TO|PA02|2026-10-19", "ENB.TO · test", "body", Notification.Priority.HIGH))).isZero();
        verify(3, postRequestedFor(urlEqualTo("/")).withRequestBody(matchingJsonPath("$.topic", equalTo("topic-abc"))));
        assertThat(toasts).hasSize(1);
        assertThat(db.recentEntries(5)).allMatch(x -> x.sentAt() != null);
    }

    @Test
    void ghostfolioDownUsesTheCachedHoldings() {
        alerts.subscribe("*", "price", "PA02_seller_controlled_drop", "warning", "user");
        AlertEngine e = engine(List.of(capturing()));
        db.save(List.of("ENB.TO"), clock.instant().minus(Duration.ofDays(1)));
        ghostfolioUp = false;
        var r = e.run(AlertEngine.Mode.CLOSE);
        assertThat(r.symbols()).isEqualTo(1);
        assertThat(r.notes()).anyMatch(n -> n.startsWith("Ghostfolio unavailable"));
        assertThat(toasts).hasSize(1);
    }

    @Test
    void staleMonitorIsReportedOnce() {
        long old = db.start("close", Instant.parse("2026-10-14T20:30:00Z"));
        db.finish(old, Instant.parse("2026-10-14T20:31:00Z"), true, 7, 0, "");
        AlertEngine e = engine(List.of(capturing()));
        e.run(AlertEngine.Mode.CLOSE);
        assertThat(toasts).extracting(Notification::title).contains("Research monitor was not running");
        clock.advance(Duration.ofMinutes(5));
        e.run(AlertEngine.Mode.CLOSE);
        assertThat(toasts).filteredOn(n -> n.title().startsWith("Research monitor")).hasSize(1);
    }

    @Test
    void autoModeFollowsTheClockAndRunsCloseOncePerDay() {
        AlertEngine e = engine(List.of(capturing()));
        clock.now = Instant.parse("2026-10-19T12:00:00Z"); // Monday 08:00: a manual close run before the open...
        e.run(AlertEngine.Mode.CLOSE);
        clock.now = Instant.parse("2026-10-17T15:00:00Z"); // Saturday
        assertThat(e.resolve(AlertEngine.Mode.AUTO)).isEmpty();
        clock.now = Instant.parse("2026-10-19T14:00:00Z"); // Monday 10:00 Eastern
        assertThat(e.resolve(AlertEngine.Mode.AUTO)).contains(AlertEngine.Mode.INTRADAY);
        clock.now = Instant.parse("2026-10-19T20:20:00Z"); // 16:20
        assertThat(e.resolve(AlertEngine.Mode.AUTO)).contains(AlertEngine.Mode.CLOSE);
        e.run(AlertEngine.Mode.AUTO);
        clock.advance(Duration.ofMinutes(30));
        assertThat(e.resolve(AlertEngine.Mode.AUTO)).isEmpty();
    }

    @Test
    void intradayUsesTheLatestQuote() {
        alerts.addThreshold("below", "ENB.TO", 97.6, null, null, null, null, null);
        AlertEngine e = engine(List.of(capturing()));
        clock.now = Instant.parse("2026-10-19T15:00:00Z"); // 11:00 Eastern, quote 97.5
        var r = e.run(AlertEngine.Mode.INTRADAY);
        assertThat(r.mode()).isEqualTo("intraday");
        assertThat(toasts).singleElement().satisfies(n -> assertThat(n.body()).contains("97.50").contains("delayed"));
    }
}
