package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.ghostfolio.GhostfolioClient;
import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.adapters.market.CachedMarketData;
import ca.twintigers.research.adapters.market.FallbackMarketData;
import ca.twintigers.research.adapters.market.YahooChartClient;
import ca.twintigers.research.adapters.sec.SecEdgarClient;
import ca.twintigers.research.adapters.store.SqliteBarStore;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.DataSourceException;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adapters against stub HTTP servers (WireMock): request shape, parsing, error messages, caching. */
@WireMockTest
class AdapterHttpTest {

    private static final String TOKEN = "secret-security-token-123";
    private final Http http = new Http(Duration.ofSeconds(5), 2);

    static final String YAHOO = """
            {"chart":{"result":[{"meta":{"exchangeTimezoneName":"America/Toronto"},
              "timestamp":[1792152000,1792238400,1792324800],
              "indicators":{"quote":[{"open":[67.5,null,68.0],"high":[68.2,null,68.5],"low":[67.1,null,67.6],
                                     "close":[67.9,null,68.04],"volume":[16000000,null,15000000]}]}}],"error":null}}""";

    @Test
    void yahooParsesBarsAndSkipsGaps(WireMockRuntimeInfo wm) throws Exception {
        stubFor(get(urlPathEqualTo("/v8/finance/chart/ENB.TO")).willReturn(okJson(YAHOO)));
        List<Bar> bars = new YahooChartClient(http, wm.getHttpBaseUrl())
                .dailyBars("ENB.TO", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20));
        assertThat(bars).hasSize(2);
        assertThat(bars.getLast().close()).isEqualTo(68.04);
        assertThat(bars.getLast().volume()).isEqualTo(15_000_000L);
        verify(getRequestedFor(urlPathEqualTo("/v8/finance/chart/ENB.TO")).withQueryParam("interval", equalTo("1d")));
    }

    @Test
    void yahooUnknownSymbolIsAClearError(WireMockRuntimeInfo wm) {
        stubFor(get(urlPathMatching("/v8/finance/chart/.*")).willReturn(notFound()));
        assertThatThrownBy(() -> new YahooChartClient(http, wm.getHttpBaseUrl()).dailyBars("ENB", LocalDate.now(), LocalDate.now()))
                .isInstanceOf(DataSourceException.class).hasMessageContaining(".TO suffix");
    }

    @Test
    void retriesOnServerErrorThenSucceeds(WireMockRuntimeInfo wm) throws Exception {
        stubFor(get(urlPathEqualTo("/v8/finance/chart/X")).inScenario("flaky").whenScenarioStateIs("Started")
                .willReturn(serverError()).willSetStateTo("ok"));
        stubFor(get(urlPathEqualTo("/v8/finance/chart/X")).inScenario("flaky").whenScenarioStateIs("ok").willReturn(okJson(YAHOO)));
        assertThat(new YahooChartClient(http, wm.getHttpBaseUrl()).dailyBars("X", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20))).hasSize(2);
    }

    @Test
    void barsAreCachedSoEachSymbolIsFetchedOncePerDay(WireMockRuntimeInfo wm, @TempDir Path dir) throws Exception {
        stubFor(get(urlPathEqualTo("/v8/finance/chart/ENB.TO")).willReturn(okJson(YAHOO)));
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T20:00:00Z"), ZoneId.of("America/Toronto"));
        var cached = new CachedMarketData(new FallbackMarketData(List.of(new YahooChartClient(http, wm.getHttpBaseUrl()))),
                new SqliteBarStore(dir.resolve("market.db")), clock);
        LocalDate from = LocalDate.of(2026, 10, 1);
        assertThat(cached.dailyBars("ENB.TO", from, LocalDate.of(2026, 10, 19))).hasSize(2);
        assertThat(cached.dailyBars("ENB.TO", from, LocalDate.of(2026, 10, 19))).hasSize(2);
        verify(1, getRequestedFor(urlPathEqualTo("/v8/finance/chart/ENB.TO")));
    }

    @Test
    void secSendsUserAgentCachesAndExplains403(WireMockRuntimeInfo wm, @TempDir Path dir) throws Exception {
        stubFor(get(urlEqualTo("/files/company_tickers.json")).willReturn(okJson(
                "{\"0\":{\"cik_str\":895728,\"ticker\":\"ENB\",\"title\":\"ENBRIDGE INC\"}}")));
        stubFor(get(urlEqualTo("/submissions/CIK0000895728.json")).willReturn(okJson("{\"sic\":\"4610\",\"sicDescription\":\"Pipe Lines\"}")));
        Clock clock = Clock.systemUTC();
        var sec = new SecEdgarClient(http, "Jane Doe jane@example.com", dir, false, clock,
                wm.getHttpBaseUrl() + "/files/company_tickers.json", wm.getHttpBaseUrl());
        var ref = sec.lookup("enb").orElseThrow();
        assertThat(ref.cik()).isEqualTo(895728L);
        assertThat(ref.sic()).isEqualTo(4610);
        sec.lookup("ENB");
        verify(1, getRequestedFor(urlEqualTo("/files/company_tickers.json")).withHeader("User-Agent", equalTo("Jane Doe jane@example.com")));
        assertThat(sec.lookup("NOPE")).isEmpty();

        stubFor(get(urlPathMatching("/api/xbrl/companyfacts/.*")).willReturn(forbidden()));
        assertThatThrownBy(() -> sec.companyFacts(895728)).hasMessageContaining("SEC_USER_AGENT");
    }

    @Test
    void ghostfolioLogsInAndMapsHoldingsWithoutLeakingTheToken(WireMockRuntimeInfo wm) throws Exception {
        stubFor(post(urlEqualTo("/api/v1/auth/anonymous")).withRequestBody(matchingJsonPath("$.accessToken", equalTo(TOKEN)))
                .willReturn(okJson("{\"authToken\":\"jwt-1\"}")));
        stubFor(get(urlEqualTo("/api/v1/user")).withHeader("Authorization", equalTo("Bearer jwt-1"))
                .willReturn(okJson("{\"settings\":{\"baseCurrency\":\"CAD\"}}")));
        stubFor(get(urlEqualTo("/api/v1/account")).willReturn(okJson("{\"totalBalanceInBaseCurrency\":498851.9,\"accounts\":[]}")));
        stubFor(get(urlPathEqualTo("/api/v1/portfolio/holdings")).willReturn(okJson("""
                {"holdings":[
                  {"assetProfile":{"symbol":"ENB.TO","name":"Enbridge Inc.","currency":"CAD","assetClass":"EQUITY"},
                   "quantity":2080,"marketPrice":68.04,"valueInBaseCurrency":141523.2},
                  {"assetProfile":{"symbol":"ENB","name":"Enbridge Inc.","currency":"USD","assetClass":"EQUITY"},
                   "quantity":800,"marketPrice":48.1,"valueInBaseCurrency":54340.3},
                  {"assetProfile":{"symbol":"CAD","assetClass":"LIQUIDITY","assetSubClass":"CASH"},"valueInBaseCurrency":1}]}""")));
        stubFor(get(urlPathEqualTo("/api/v1/activities")).willReturn(okJson("""
                {"count":3,"activities":[
                  {"type":"BUY","quantity":1100,"account":{"name":"Cash CAD"},"assetProfile":{"symbol":"ENB.TO"}},
                  {"type":"BUY","quantity":980,"account":{"name":"RRSP"},"assetProfile":{"symbol":"ENB.TO"}},
                  {"type":"BUY","quantity":800,"account":{"name":"Cash USD"},"assetProfile":{"symbol":"ENB"}}]}""")));

        Portfolio p = new GhostfolioClient(http, wm.getHttpBaseUrl(), TOKEN).portfolio();
        assertThat(p.baseCurrency()).isEqualTo("CAD");
        assertThat(p.positions()).extracting(Portfolio.Position::symbol).containsExactly("ENB.TO", "ENB");
        assertThat(p.positions().getFirst().sharesByAccount()).containsEntry("RRSP", new BigDecimal("980"));
        Portfolio.IssuerWeight enb = p.issuerWeights(20).getFirst();
        assertThat(enb.symbols()).containsExactly("ENB.TO", "ENB");
        assertThat(enb.pctOfNetWorth()).isEqualByComparingTo("28.19"); // 195,863.50 / (195,863.50 + 498,851.90)
        assertThat(enb.overLimit()).isTrue();
    }

    @Test
    void ghostfolioErrorsAreClearAndSecretFree(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/api/v1/auth/anonymous")).willReturn(forbidden()));
        assertThatThrownBy(() -> new GhostfolioClient(http, wm.getHttpBaseUrl(), TOKEN).portfolio())
                .hasMessageContaining("rejected GHOSTFOLIO_TOKEN").hasMessageNotContaining(TOKEN);
        assertThatThrownBy(() -> new GhostfolioClient(http, "http://127.0.0.1:9", TOKEN).portfolio())
                .hasMessageContaining("not reachable").hasMessageContaining("start.ps1").hasMessageNotContaining(TOKEN);
    }
}
