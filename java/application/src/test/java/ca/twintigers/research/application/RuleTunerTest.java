package ca.twintigers.research.application;

import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.MarketContext;
import ca.twintigers.research.domain.market.PriceRule;
import ca.twintigers.research.domain.market.PriceRuleConfig;
import ca.twintigers.research.domain.market.Signal.Direction;
import ca.twintigers.research.domain.rules.Severity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The tuner picks on in-sample data only and keeps a rule only if it also holds on the period it never saw. */
class RuleTunerTest {

    private static final LocalDate START = LocalDate.of(2020, 1, 1);
    private static final LocalDate SPLIT = START.plusDays(220);

    /** 400 quiet daily bars; the close falls 0.1 a day, and after the split either keeps falling or rises. */
    private static MarketContext prices(boolean fallsAfterSplit) {
        List<Bar> bars = new ArrayList<>();
        double c = 200;
        for (int i = 0; i < 400; i++) {
            double o = c;
            c += i < 220 || fallsAfterSplit ? -0.1 : 0.1;
            bars.add(new Bar(START.plusDays(i), o, Math.max(o, c) + 0.4, Math.min(o, c) - 0.4, c, 1_000_000));
        }
        return new MarketContext("TEST", bars, PriceRuleConfig.defaults());
    }

    /** Fires every 10th day, whatever the price does. */
    private static PriceRule everyTenthDay(String id, Severity severity, Direction direction) {
        return new PriceRule(id, id, severity, direction, "test rule",
                (ctx, i) -> i % 10 == 0 ? Optional.of(new PriceRule.Hit("fired", Map.of())) : Optional.empty());
    }

    private final RuleTuner tuner = new RuleTuner(55, 15, 8);

    @Test
    void ruleThatHoldsInBothPeriodsIsKeptAsDefined() {
        RuleTuner.Report r = tuner.tune(List.of(prices(true)), List.of(everyTenthDay("X1", Severity.WARNING, Direction.BEARISH)), SPLIT);
        RuleTuner.Decision d = r.decisions().getFirst();
        assertThat(d.keep()).isTrue();
        assertThat(d.chosen().variant().name()).isEqualTo("as defined");
        assertThat(d.chosen().inSample().hit5()).isEqualTo(100.0);
        assertThat(d.chosen().outOfSample().hit20()).isEqualTo(100.0);
        assertThat(RuleTuner.configLines(r)).isEmpty();
    }

    @Test
    void ruleThatOnlyWorkedInSampleIsDemotedToInfo() {
        RuleTuner.Report r = tuner.tune(List.of(prices(false)), List.of(everyTenthDay("X1", Severity.WARNING, Direction.BEARISH)), SPLIT);
        RuleTuner.Decision d = r.decisions().getFirst();
        assertThat(d.chosen()).isNotNull();
        assertThat(d.chosen().inSample().hit5()).isEqualTo(100.0);
        assertThat(d.keep()).isFalse();
        assertThat(d.reason()).startsWith("best in-sample variant did not hold out-of-sample");
        assertThat(RuleTuner.configLines(r)).containsExactly("price.severity.X1=info");
    }

    @Test
    void ruleThatNeverWorkedHasNoChoiceAndInfoAndNeutralRulesAreNotTuned() {
        RuleTuner.Report r = tuner.tune(List.of(prices(true)), List.of(
                everyTenthDay("BULL", Severity.WATCH, Direction.BULLISH),
                everyTenthDay("QUIET", Severity.INFO, Direction.BEARISH),
                everyTenthDay("NEUTRAL", Severity.WARNING, Direction.NEUTRAL)), SPLIT);
        assertThat(r.decisions()).extracting(RuleTuner.Decision::ruleId).containsExactly("BULL");
        assertThat(r.decisions().getFirst().chosen()).isNull();
        assertThat(r.decisions().getFirst().keep()).isFalse();
        assertThat(r.baseRates()).extracting(RuleTuner.BaseRate::down5).containsExactly(100.0, 100.0);
    }
}
