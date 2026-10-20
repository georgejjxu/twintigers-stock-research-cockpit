package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.ghostfolio.GhostfolioClient;
import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.ports.PortfolioSource;
import ca.twintigers.research.ports.PortfolioWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The writer contract against the Ghostfolio adapter, talking to a stateful fake Ghostfolio. */
class GhostfolioWriterContractTest extends PortfolioWriterContract {

    FakeGhostfolioServer fake;
    GhostfolioClient client;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeGhostfolioServer();
        client = new GhostfolioClient(new Http(Duration.ofSeconds(5), 1), fake.url(), FakeGhostfolioServer.TOKEN, true,
                LocalSourceContractTest.CLOCK);
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @Override
    PortfolioWriter writer() {
        return client;
    }

    @Override
    PortfolioSource reader() {
        return client;
    }

    @Test
    void sendsGhostfolioTheAccountIdAndYahooDataSource() throws Exception {
        var a = client.create(buy("2026-03-02", "100", "60.25"));
        var stored = fake.activities.get(a.id());
        assertThat(stored).containsEntry("accountId", "acc-rrsp").containsEntry("dataSource", "YAHOO")
                .containsEntry("date", "2026-03-02T00:00:00.000Z").containsEntry("type", "BUY").containsEntry("symbol", "FTS.TO");
    }

    @Test
    void writesAreRefusedUnlessSwitchedOn() {
        var off = new GhostfolioClient(new Http(Duration.ofSeconds(5), 1), fake.url(), FakeGhostfolioServer.TOKEN, false,
                LocalSourceContractTest.CLOCK);
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> off.create(buy("2026-03-02", "1", "1"))).hasMessageContaining("GHOSTFOLIO_WRITES=enabled");
        assertThatThrownBy(() -> off.delete("abc")).hasMessageContaining("GHOSTFOLIO_WRITES=enabled");
        assertThat(fake.writes).isZero();
    }

    @Test
    void invalidInputNeverReachesGhostfolio() {
        assertThatThrownBy(() -> client.create(buy("2030-01-01", "1", "1"))).hasMessageContaining("in the future");
        assertThatThrownBy(() -> client.delete("../account/acc-rrsp")).hasMessageContaining("Not a valid activity id");
        assertThat(fake.writes).isZero();
    }
}
