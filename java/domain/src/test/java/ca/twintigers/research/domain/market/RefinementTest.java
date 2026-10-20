package ca.twintigers.research.domain.market;

import ca.twintigers.research.domain.rules.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Refinements only ever narrow a rule, record why it still fired, and come from rules.properties. */
class RefinementTest {

    private static final String DROP = "PA02_seller_controlled_drop";

    private static PriceRule rule(MarketContext c, String id) {
        return PriceRules.all(c.cfg).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    /** A seller-controlled drop (volume 2× average) on the last bar after 60 quiet days. */
    private static Series drop() {
        return new Series().drift(60, 0).bar(100, 100.1, 97.8, 97.9, 2_000_000);
    }

    @Test
    void volumeFilterKeepsOnlyHeavierDays() {
        MarketContext c = drop().ctx();
        int last = c.size() - 1;
        PriceRule base = rule(c, DROP);
        assertThat(base.evaluate(c, last)).isPresent();

        Optional<Signal> strict = new Refinement(2.5, null, null, false, false, null).apply(base).evaluate(c, last);
        assertThat(strict).isEmpty();

        Signal loose = new Refinement(2.0, null, null, false, false, null).apply(base).evaluate(c, last).orElseThrow();
        assertThat(loose.evidence()).containsEntry("refine_rel_volume", 2.0);
    }

    @Test
    void confirmationReportsOnTheNextDayOnlyWhenItClosesBelowTheSignalLow() {
        Refinement confirm = new Refinement(null, null, null, false, true, null);

        MarketContext yes = drop().bar(97.9, 98.0, 97.0, 97.2, 1_000_000).ctx();
        Signal s = confirm.apply(rule(yes, DROP)).evaluate(yes, yes.size() - 1).orElseThrow();
        assertThat(s.date()).isEqualTo(yes.bar(yes.size() - 1).date());
        assertThat(s.evidence()).containsEntry("refine_signal_date", yes.bar(yes.size() - 2).date().toString())
                .containsEntry("refine_signal_low", 97.8).containsEntry("refine_confirm_close", 97.2);
        // the signal day itself no longer fires on its own
        assertThat(confirm.apply(rule(yes, DROP)).evaluate(yes, yes.size() - 2)).isEmpty();

        MarketContext no = drop().bar(97.9, 98.5, 97.85, 98.3, 1_000_000).ctx();
        assertThat(confirm.apply(rule(no, DROP)).evaluate(no, no.size() - 1)).isEmpty();
    }

    @Test
    void trendFilterNeedsTwoHundredDaysOfHistory() {
        MarketContext c = drop().ctx(); // only 61 bars: no 200-day average yet
        assertThat(new Refinement(null, null, null, true, false, null).apply(rule(c, DROP)).evaluate(c, c.size() - 1)).isEmpty();
    }

    @Test
    void configDemotesAndRefinesByRuleId() {
        MarketContext c = drop().ctx();
        List<PriceRule> all = PriceRules.all(c.cfg);
        Map<String, Refinement> m = Refinement.fromMap(Map.of(
                "price.severity." + DROP, "info",
                "price.refine.PA01_buyer_controlled_jump.minRelVolume", "2.5",
                "price.refine.PA01_buyer_controlled_jump.confirm", "true",
                "price.something.else", "ignored"), all);
        assertThat(m).containsOnlyKeys(DROP, "PA01_buyer_controlled_jump");
        assertThat(m.get(DROP).isNone()).isTrue();
        assertThat(m.get("PA01_buyer_controlled_jump").label()).isEqualTo("rvol≥2.5 + next-day confirm");

        PriceRule demoted = Refinement.applyAll(all, m).stream().filter(r -> r.id().equals(DROP)).findFirst().orElseThrow();
        assertThat(demoted.severity()).isEqualTo(Severity.INFO);
        assertThat(demoted.evaluate(c, c.size() - 1)).isPresent(); // demoted, still reported as info
        PriceRule base = rule(c, DROP);
        assertThat(Refinement.NONE.apply(base)).isSameAs(base);
    }
}
