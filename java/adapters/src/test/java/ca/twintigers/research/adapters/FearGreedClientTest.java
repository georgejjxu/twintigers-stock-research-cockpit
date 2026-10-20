package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.market.FearGreedClient;
import ca.twintigers.research.domain.market.Sentiment;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** CNN's and alternative.me's Fear & Greed JSON become one Sentiment shape. */
class FearGreedClientTest {

    @Test
    void cnnScoreComparisonsComponentsAndHistory() throws Exception {
        String body = """
                {"fear_and_greed":{"score":23.3428,"rating":"extreme fear","timestamp":"2026-10-20T10:27:01+00:00",
                  "previous_close":23.1071,"previous_1_week":44.1142,"previous_1_month":30.4285,"previous_1_year":29.857},
                 "fear_and_greed_historical":{"data":[{"x":1760918400000.0,"y":29.857,"rating":"fear"},
                                                      {"x":1792454400000.0,"y":23.1,"rating":"extreme fear"}]},
                 "market_momentum_sp500":{"score":29.4,"rating":"fear"},
                 "junk_bond_demand":{"score":51.6,"rating":"neutral"}}""";
        Sentiment s = FearGreedClient.parseCnn(new ObjectMapper().readTree(body));
        assertThat(s.score()).isEqualTo(23.3);
        assertThat(s.rating()).isEqualTo("extreme fear");
        assertThat(s.previous()).extracting(Sentiment.Reading::label).containsExactly("Previous close", "1 week ago", "1 month ago", "1 year ago");
        assertThat(s.previous().get(1).rating()).isEqualTo("fear");
        assertThat(s.components()).extracting(Sentiment.Reading::score).containsExactly(29.4, 51.6);
        assertThat(s.history()).hasSize(2);
        assertThat(s.history().getFirst().date()).isEqualTo(LocalDate.of(2025, 10, 20));
    }

    @Test
    void cryptoIndex() throws Exception {
        String body = """
                {"name":"Fear and Greed Index","data":[{"value":"55","value_classification":"Greed","timestamp":"1792454400"},
                 {"value":"59","value_classification":"Greed","timestamp":"1792368000"}]}""";
        Sentiment s = FearGreedClient.parseCrypto(new ObjectMapper().readTree(body));
        assertThat(s.score()).isEqualTo(55);
        assertThat(s.rating()).isEqualTo("greed");
        assertThat(s.previous()).extracting(Sentiment.Reading::label).containsExactly("Yesterday");
        assertThat(Sentiment.band(50)).isEqualTo("neutral");
        assertThat(Sentiment.band(80)).isEqualTo("extreme greed");
    }
}
