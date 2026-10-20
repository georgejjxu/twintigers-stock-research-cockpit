package ca.twintigers.research.adapters;

import ca.twintigers.research.adapters.ghostfolio.GhostfolioClient;
import ca.twintigers.research.adapters.http.Http;
import ca.twintigers.research.ports.PortfolioSource;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/** The contract against the Ghostfolio adapter, with Ghostfolio 3.x answers stubbed by WireMock. */
@WireMockTest
class GhostfolioSourceContractTest extends PortfolioSourceContract {

    private String baseUrl;

    @BeforeEach
    void stub(WireMockRuntimeInfo wm) {
        baseUrl = wm.getHttpBaseUrl();
        stubFor(post(urlEqualTo("/api/v1/auth/anonymous")).willReturn(okJson("{\"authToken\":\"jwt\"}")));
        stubFor(get(urlEqualTo("/api/v1/user")).willReturn(okJson("{\"settings\":{\"baseCurrency\":\"CAD\"}}")));
        stubFor(get(urlEqualTo("/api/v1/account")).willReturn(okJson("""
                {"totalBalanceInBaseCurrency":498851.9,"accounts":[
                  {"name":"Cash CAD","currency":"CAD","balance":400000,"balanceInBaseCurrency":400000},
                  {"name":"Cash USD","currency":"USD","balance":70000,"balanceInBaseCurrency":98851.9},
                  {"name":"RRSP","currency":"CAD","balance":0,"balanceInBaseCurrency":0}]}""")));
        stubFor(get(urlPathEqualTo("/api/v1/portfolio/holdings")).willReturn(okJson("""
                {"holdings":[
                  {"assetProfile":{"symbol":"ENB","name":"Enbridge Inc.","currency":"USD","assetClass":"EQUITY"},
                   "quantity":800,"marketPrice":48.1,"valueInBaseCurrency":54340.3},
                  {"assetProfile":{"symbol":"ENB.TO","name":"Enbridge Inc.","currency":"CAD","assetClass":"EQUITY"},
                   "quantity":2080,"marketPrice":68.04,"valueInBaseCurrency":141523.2},
                  {"assetProfile":{"symbol":"CAD","assetClass":"LIQUIDITY","assetSubClass":"CASH"},"valueInBaseCurrency":400000}]}""")));
        stubFor(get(urlPathEqualTo("/api/v1/activities")).willReturn(okJson("""
                {"count":4,"activities":[
                  {"date":"2026-10-14T20:00:00.000Z","type":"BUY","quantity":1100,"unitPrice":67.5,"fee":0,"currency":"CAD",
                   "account":{"name":"Cash CAD"},"assetProfile":{"symbol":"ENB.TO"}},
                  {"date":"2026-10-15T20:00:00.000Z","type":"DIVIDEND","quantity":980,"unitPrice":0.9425,"fee":0,"currency":"CAD",
                   "account":{"name":"RRSP"},"assetProfile":{"symbol":"ENB.TO"}},
                  {"date":"2026-10-14T20:00:00.000Z","type":"BUY","quantity":980,"unitPrice":67.5,"fee":0,"currency":"CAD",
                   "account":{"name":"RRSP"},"assetProfile":{"symbol":"ENB.TO"}},
                  {"date":"2026-10-13T20:00:00.000Z","type":"BUY","quantity":800,"unitPrice":47.35,"fee":0,"currency":"USD",
                   "account":{"name":"Cash USD"},"assetProfile":{"symbol":"ENB"}}]}""")));
        stubFor(get(urlPathEqualTo("/api/v2/portfolio/performance")).willReturn(okJson("""
                {"performance":{"currentValueInBaseCurrency":195863.5,"totalInvestment":192000,
                 "netPerformanceWithCurrencyEffect":3863.5,"netPerformancePercentageWithCurrencyEffect":0.0201}}""")));
    }

    @Override
    PortfolioSource source() {
        return new GhostfolioClient(new Http(Duration.ofSeconds(5), 1), baseUrl, "token");
    }
}
