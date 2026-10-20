package ca.twintigers.research.mcp;

import ca.twintigers.research.adapters.Wiring;
import ca.twintigers.research.adapters.config.Settings;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.concurrent.CountDownLatch;

/**
 * MCP server over stdio. stdout carries the protocol only; all logging goes to stderr (slf4j-simple).
 * Run: {@code java -jar research-mcp.jar} with RESEARCH_ENV_FILE pointing at secrets.env.
 */
public final class McpMain {

    static final String INSTRUCTIONS = "Java research engine (read-only). financial_health: SEC-sourced health audit with "
            + "rule-based flags, every figure cited (filing, tag) or given with its formula. price_signals: price-action, "
            + "candlestick (K-line) and momentum rules on daily bars, each signal with the bar and indicator values that "
            + "triggered it. replay_rules: how often each rule fired over history and what followed. portfolio_review: "
            + "Ghostfolio holdings with concentration, fundamental flags and recent signals. rule_catalog: definitions and "
            + "thresholds. Quote numbers exactly as returned, keep sources, never compute figures yourself, separate facts "
            + "from interpretation. Signals describe what happened, not what will happen. Nothing here is investment advice.";

    public static void main(String[] args) throws InterruptedException {
        Wiring wiring = new Wiring(Settings.load());
        Tools tools = new Tools(wiring);
        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                .serverInfo("research-java", "0.1.0")
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.specifications())
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        new CountDownLatch(1).await(); // the stdio transport runs on its own threads until the client closes stdin
    }
}
