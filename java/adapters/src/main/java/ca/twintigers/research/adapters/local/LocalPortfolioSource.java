package ca.twintigers.research.adapters.local;

import ca.twintigers.research.adapters.store.Database;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.CorporateAction;
import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.ActivityDraft;
import ca.twintigers.research.domain.portfolio.CashBalance;
import ca.twintigers.research.domain.portfolio.Performance;
import ca.twintigers.research.domain.portfolio.PerformanceCalculator;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.CorporateActions;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.LedgerBook;
import ca.twintigers.research.ports.MarketData;
import ca.twintigers.research.ports.PortfolioSource;
import ca.twintigers.research.ports.PortfolioWriter;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A portfolio source that needs no other application.
 * <ul>
 *   <li>Accounts and cash: the ledger's accounts (schema V5, filled by the Ghostfolio sync or later in the Cockpit);
 *       while there are none, the hand-edited {@code portfolio.toml}.</li>
 *   <li>Transactions: a ledger in the local SQLite database (schema V4), entered in the Cockpit's Transactions page
 *       or mirrored from Ghostfolio ({@link LedgerBook}).</li>
 *   <li>Holdings: from the ledger's buys and sells once it has any (in today's share units, after splits); until then
 *       from {@code portfolio.toml}.</li>
 *   <li>Prices: latest daily close from {@link MarketData}; currency conversion from Yahoo FX pairs (USDCAD=X).</li>
 * </ul>
 * Returns are computed from the ledger by the application's PerformanceService ({@link Capability#PERFORMANCE} is not
 * declared here). See docs/architecture/adr/0008-portfolio-source.md and 0010.
 */
public final class LocalPortfolioSource implements PortfolioSource, PortfolioWriter, LedgerBook {

    private final Path tomlFile;
    private final Database db;
    private final MarketData market;
    private final String baseCurrency;
    private final Clock clock;
    private final CorporateActions actions;
    private ca.twintigers.research.ports.SymbolSearch search;

    /** Looks up company names for symbols the ledger does not know yet (once, then stored). Optional. */
    public LocalPortfolioSource withNameLookup(ca.twintigers.research.ports.SymbolSearch search) {
        this.search = search;
        return this;
    }

    public LocalPortfolioSource(Path tomlFile, Database db, MarketData market, String baseCurrency, Clock clock) {
        this(tomlFile, db, market, null, baseCurrency, clock);
    }

    /** @param actions splits for the holdings' share units; null = no split adjustment */
    public LocalPortfolioSource(Path tomlFile, Database db, MarketData market, CorporateActions actions, String baseCurrency,
                                Clock clock) {
        this.actions = actions;
        this.tomlFile = tomlFile;
        this.db = db;
        this.market = market;
        this.baseCurrency = baseCurrency == null ? "CAD" : baseCurrency.toUpperCase(Locale.ROOT);
        this.clock = clock;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.ACCOUNTS, Capability.ACTIVITIES);
    }

    // --- portfolio.toml ----------------------------------------------------------------------------------

    private record Holding(String account, String symbol, String name, String currency, BigDecimal shares) {
    }

    private record CashLine(String account, String currency, BigDecimal amount) {
    }

    private record Parsed(List<Holding> holdings, List<CashLine> cash, Map<String, String> accountCurrency) {
    }

    private Parsed parse() throws DataSourceException {
        List<Account> ledger = ledgerAccounts();
        if (!ledger.isEmpty()) {
            Map<String, String> accountCurrency = new LinkedHashMap<>();
            List<CashLine> cash = new ArrayList<>();
            for (Account a : ledger) {
                accountCurrency.put(a.name(), a.currency());
                cash.add(new CashLine(a.name(), a.currency(), a.cashBalance()));
            }
            List<Holding> holdings = Files.exists(tomlFile) ? parseToml().holdings() : List.of();
            return new Parsed(holdings, cash, accountCurrency);
        }
        return parseToml();
    }

    private Parsed parseToml() throws DataSourceException {
        if (!Files.isRegularFile(tomlFile)) {
            return new Parsed(List.of(), List.of(), new LinkedHashMap<>()); // a new installation: accounts are added in the Cockpit
        }
        TomlParseResult toml;
        try {
            toml = Toml.parse(Files.readString(tomlFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DataSourceException("Cannot read the holdings file " + tomlFile + ": " + e.getMessage(), e);
        }
        if (toml.hasErrors()) {
            throw new DataSourceException(tomlFile + " is not valid TOML: " + toml.errors().getFirst().toString());
        }
        List<Holding> holdings = new ArrayList<>();
        List<CashLine> cash = new ArrayList<>();
        Map<String, String> accountCurrency = new LinkedHashMap<>();
        TomlArray accounts = toml.getArray("accounts");
        if (accounts == null || accounts.isEmpty()) {
            return new Parsed(List.of(), List.of(), new LinkedHashMap<>());
        }
        for (int i = 0; i < accounts.size(); i++) {
            TomlTable a = accounts.getTable(i);
            String account = a.getString("name", () -> "");
            accountCurrency.put(account, a.getString("currency", () -> baseCurrency).toUpperCase(Locale.ROOT));
            TomlArray hs = a.getArray("holdings");
            for (int k = 0; hs != null && k < hs.size(); k++) {
                TomlTable h = hs.getTable(k);
                String ticker = h.getString("ticker", () -> "").strip().toUpperCase(Locale.ROOT);
                String exchange = h.getString("exchange", () -> "").strip().toUpperCase(Locale.ROOT);
                String symbol = switch (exchange) {
                    case "TSX" -> ticker + ".TO";
                    case "TSXV" -> ticker + ".V";
                    default -> ticker;
                };
                holdings.add(new Holding(account, symbol, h.getString("name"), h.getString("currency", () -> "").toUpperCase(Locale.ROOT),
                        number(h, "shares")));
            }
            TomlArray cs = a.getArray("cash");
            for (int k = 0; cs != null && k < cs.size(); k++) {
                TomlTable c = cs.getTable(k);
                cash.add(new CashLine(account, c.getString("currency", () -> baseCurrency).toUpperCase(Locale.ROOT), number(c, "amount")));
            }
        }
        return new Parsed(holdings, cash, accountCurrency);
    }

    private static BigDecimal number(TomlTable t, String key) {
        Object v = t.get(key);
        if (v instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (v instanceof Double d) {
            return BigDecimal.valueOf(d);
        }
        return BigDecimal.ZERO;
    }

    // --- prices --------------------------------------------------------------------------------------------

    private BigDecimal lastClose(String symbol, Map<String, BigDecimal> cache) throws DataSourceException {
        BigDecimal c = cache.get(symbol);
        if (c != null) {
            return c;
        }
        LocalDate today = LocalDate.now(clock);
        List<Bar> bars = market.dailyBars(symbol, today.minusDays(14), today);
        if (bars.isEmpty()) {
            throw new DataSourceException("No recent price for " + symbol + " from " + market.source() + ".");
        }
        c = BigDecimal.valueOf(bars.getLast().close());
        cache.put(symbol, c);
        return c;
    }

    /** Units of {@code to} per unit of {@code from}, from Yahoo's FROMTO=X daily close. */
    private BigDecimal fx(String from, String to, Map<String, BigDecimal> cache) throws DataSourceException {
        if (from == null || from.isBlank() || from.equals(to)) {
            return BigDecimal.ONE;
        }
        return lastClose(from + to + "=X", cache);
    }

    // --- PortfolioSource -----------------------------------------------------------------------------------

    @Override
    public Portfolio portfolio() throws DataSourceException {
        Parsed p = parse();
        Map<String, String> names = instrumentNames();
        p.holdings().forEach(h -> names.putIfAbsent(h.symbol(), h.name()));
        if (search != null) {
            for (Activity a : activities()) {
                if (!names.containsKey(a.symbol())) {
                    String found = lookupName(a.symbol());
                    names.put(a.symbol(), found == null ? a.symbol() : found);
                }
            }
        }
        List<Holding> holdings = holdingsFromLedger(names).orElse(p.holdings());
        Map<String, BigDecimal> prices = new HashMap<>();
        Map<String, Map<String, BigDecimal>> split = new LinkedHashMap<>();
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        Map<String, Holding> first = new LinkedHashMap<>();
        for (Holding h : holdings) {
            split.computeIfAbsent(h.symbol(), k -> new LinkedHashMap<>()).merge(h.account(), h.shares(), BigDecimal::add);
            shares.merge(h.symbol(), h.shares(), BigDecimal::add);
            first.putIfAbsent(h.symbol(), h);
        }
        List<Portfolio.Position> positions = new ArrayList<>();
        for (Holding h : first.values()) {
            BigDecimal qty = shares.get(h.symbol());
            if (qty.signum() <= 0) {
                continue; // sold out
            }
            split.get(h.symbol()).values().removeIf(v -> v.signum() == 0);
            BigDecimal price = lastClose(h.symbol(), prices);
            BigDecimal value = qty.multiply(price).multiply(fx(h.currency(), baseCurrency, prices), MathContext.DECIMAL64)
                    .setScale(2, RoundingMode.HALF_EVEN);
            positions.add(new Portfolio.Position(h.symbol(), h.name(), h.currency(), qty, price, value, split.get(h.symbol())));
        }
        BigDecimal cash = BigDecimal.ZERO;
        for (CashLine c : p.cash()) {
            cash = cash.add(c.amount().multiply(fx(c.currency(), baseCurrency, prices), MathContext.DECIMAL64));
        }
        positions.sort(Comparator.comparing(Portfolio.Position::valueInBase).reversed());
        return new Portfolio(baseCurrency, positions, cash.setScale(2, RoundingMode.HALF_EVEN));
    }

    /** Net shares per (account, symbol) from the ledger's buys and sells, or empty when the ledger has none. */
    private Optional<List<Holding>> holdingsFromLedger(Map<String, String> names) throws DataSourceException {
        List<Holding> out = new ArrayList<>();
        Map<String, List<CorporateAction>> cache = new HashMap<>();
        boolean any = false;
        for (Activity a : activities()) {
            boolean buy = a.type().equals("BUY");
            if (!buy && !a.type().equals("SELL")) {
                continue;
            }
            any = true;
            BigDecimal q = a.quantity().multiply(BigDecimal.valueOf(PerformanceCalculator.splitFactor(a.symbol(), a.date(),
                    splits(a.symbol(), cache))));
            out.add(new Holding(a.account(), a.symbol(), names.getOrDefault(a.symbol(), a.symbol()), a.currency(),
                    buy ? q : q.negate()));
        }
        return any ? Optional.of(out) : Optional.empty();
    }

    @Override
    public List<Account> accounts() throws DataSourceException {
        Parsed p = parse();
        Map<String, BigDecimal> prices = new HashMap<>();
        List<Account> out = new ArrayList<>();
        for (var e : p.accountCurrency().entrySet()) {
            BigDecimal own = BigDecimal.ZERO;
            BigDecimal base = BigDecimal.ZERO;
            for (CashLine c : p.cash()) {
                if (c.account().equals(e.getKey())) {
                    own = own.add(c.amount().multiply(fx(c.currency(), e.getValue(), prices), MathContext.DECIMAL64));
                    base = base.add(c.amount().multiply(fx(c.currency(), baseCurrency, prices), MathContext.DECIMAL64));
                }
            }
            out.add(new Account(e.getKey(), e.getValue(), own.setScale(2, RoundingMode.HALF_EVEN), base.setScale(2, RoundingMode.HALF_EVEN)));
        }
        return out;
    }

    @Override
    public List<Activity> activities() throws DataSourceException {
        try (Connection c = db.connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM ledger ORDER BY date DESC, id DESC")) {
            List<Activity> out = new ArrayList<>();
            while (rs.next()) {
                out.add(row(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    private static Activity row(ResultSet rs) throws SQLException {
        return new Activity(String.valueOf(rs.getLong("id")), LocalDate.parse(rs.getString("date")), rs.getString("type"),
                rs.getString("account"), rs.getString("symbol"), new BigDecimal(rs.getString("quantity")),
                new BigDecimal(rs.getString("unit_price")), new BigDecimal(rs.getString("fee")), rs.getString("currency"),
                rs.getString("comment"));
    }

    @Override
    public Optional<Performance> performance(String range) {
        return Optional.empty();
    }

    /** The symbol's splits (none when no corporate-action source is wired or it cannot answer). */
    private List<CorporateAction> splits(String symbol, Map<String, List<CorporateAction>> cache) {
        return cache.computeIfAbsent(symbol, s -> {
            if (actions == null) {
                return List.of();
            }
            try {
                LocalDate today = LocalDate.now(clock);
                return actions.corporateActions(s, today.minusYears(30), today).stream().filter(CorporateAction::isSplit).toList();
            } catch (DataSourceException e) {
                return List.of();
            }
        });
    }

    /** The company name from the symbol search, stored in the instruments table; null when not found. */
    private String lookupName(String symbol) {
        try {
            for (var m : search.search(symbol, 5)) {
                if (m.symbol().equalsIgnoreCase(symbol) && m.name() != null && !m.name().isBlank()) {
                    putInstrument(symbol, m.name(), null);
                    return m.name();
                }
            }
        } catch (DataSourceException | RuntimeException e) {
            // keep the symbol as its name; try again next time
        }
        return null;
    }

    private Map<String, String> instrumentNames() throws DataSourceException {
        Map<String, String> out = new HashMap<>();
        try (Connection c = db.connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT symbol, name FROM instruments WHERE name IS NOT NULL")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
            return out;
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    @Override
    public Account addAccount(String name, String currency, BigDecimal cash) throws DataSourceException {
        String n = name == null ? "" : name.strip();
        String c = currency == null ? "" : currency.strip().toUpperCase(Locale.ROOT);
        List<String> existing = accountNames();
        if (n.isEmpty() || n.length() > 60) {
            throw new DataSourceException("The account name must be 1 to 60 characters.");
        }
        if (existing.stream().anyMatch(x -> x.equalsIgnoreCase(n))) {
            throw new DataSourceException("There is already an account named \"" + n + "\".");
        }
        if (!c.matches("[A-Z]{3}")) {
            throw new DataSourceException("The currency must be a 3-letter code such as CAD or USD.");
        }
        if (cash == null || cash.signum() < 0) {
            throw new DataSourceException("Cash must be 0 or more.");
        }
        if (ledgerAccounts().isEmpty()) {
            // the first account added in the Cockpit: keep any accounts from portfolio.toml alongside it
            for (Account a : accounts()) {
                putAccount(a.name(), a.currency(), a.cashBalance());
            }
        }
        putAccount(n, c, cash);
        return accounts().stream().filter(a -> a.name().equals(n)).findFirst().orElseThrow();
    }

    @Override
    public Account setCash(String account, BigDecimal cash) throws DataSourceException {
        PortfolioWriter.checkCash(account, cash, accountNames());
        if (ledgerAccounts().isEmpty()) {
            // first cash edit: the ledger takes over the accounts from portfolio.toml, with their current cash
            for (Account a : accounts()) {
                putAccount(a.name(), a.currency(), a.cashBalance());
            }
        }
        String currency = ledgerAccounts().stream().filter(a -> a.name().equals(account)).map(Account::currency).findFirst().orElseThrow();
        putAccount(account, currency, cash);
        return accounts().stream().filter(a -> a.name().equals(account)).findFirst().orElseThrow();
    }

    // --- LedgerBook (mirror target) ---------------------------------------------------------------------------

    @Override
    public List<Account> ledgerAccounts() throws DataSourceException {
        try (Connection c = db.connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, currency, cash FROM ledger_accounts ORDER BY name")) {
            List<Account> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Account(rs.getString(1), rs.getString(2), new BigDecimal(rs.getString(3)), null));
            }
            return out;
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    /** Sets the account's cash; a changed balance is also recorded in the dated history for today. */
    @Override
    public void putAccount(String name, String currency, BigDecimal cash) throws DataSourceException {
        BigDecimal before = ledgerAccounts().stream().filter(a -> a.name().equals(name)).map(Account::cashBalance).findFirst().orElse(null);
        execute("INSERT INTO ledger_accounts(name, currency, cash, updated_at) VALUES (?,?,?,?) ON CONFLICT(name) DO UPDATE SET "
                + "currency=excluded.currency, cash=excluded.cash, updated_at=excluded.updated_at",
                name, currency.toUpperCase(Locale.ROOT), cash.toPlainString(), clock.instant().toString());
        if (before == null || before.compareTo(cash) != 0) {
            execute("INSERT INTO ledger_cash_history(account, date, cash) VALUES (?,?,?) ON CONFLICT(account, date) DO UPDATE SET "
                    + "cash=excluded.cash", name, LocalDate.now(clock).toString(), cash.toPlainString());
        }
    }

    @Override
    public void removeAccount(String name) throws DataSourceException {
        execute("DELETE FROM ledger_accounts WHERE name=?", name);
        execute("DELETE FROM ledger_cash_history WHERE account=?", name);
    }

    @Override
    public void putCashHistory(String account, List<CashBalance> points) throws DataSourceException {
        execute("DELETE FROM ledger_cash_history WHERE account=?", account);
        for (CashBalance b : points) {
            execute("INSERT INTO ledger_cash_history(account, date, cash) VALUES (?,?,?) ON CONFLICT(account, date) DO UPDATE SET "
                    + "cash=excluded.cash", account, b.date().toString(), b.balance().toPlainString());
        }
    }

    @Override
    public List<CashBalance> cashHistory() throws DataSourceException {
        Map<String, String> currency = new HashMap<>();
        for (Account a : ledgerAccounts()) {
            currency.put(a.name(), a.currency());
        }
        try (Connection c = db.connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT account, date, cash FROM ledger_cash_history ORDER BY date, account")) {
            List<CashBalance> out = new ArrayList<>();
            while (rs.next()) {
                String account = rs.getString(1);
                if (currency.containsKey(account)) {
                    out.add(new CashBalance(account, currency.get(account), LocalDate.parse(rs.getString(2)), new BigDecimal(rs.getString(3))));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    @Override
    public void putInstrument(String symbol, String name, String currency) throws DataSourceException {
        execute("INSERT INTO instruments(symbol, name, currency) VALUES (?,?,?) ON CONFLICT(symbol) DO UPDATE SET "
                + "name=coalesce(excluded.name, instruments.name), currency=coalesce(excluded.currency, instruments.currency)",
                symbol, name, currency);
    }

    @Override
    public void putProfile(ca.twintigers.research.domain.portfolio.InstrumentProfile f) throws DataSourceException {
        tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
        execute("INSERT INTO instrument_profiles(symbol, asset_class, asset_sub_class, sectors, countries) VALUES (?,?,?,?,?) "
                + "ON CONFLICT(symbol) DO UPDATE SET asset_class=excluded.asset_class, asset_sub_class=excluded.asset_sub_class, "
                + "sectors=excluded.sectors, countries=excluded.countries", f.symbol(), f.assetClass(), f.assetSubClass(),
                json.writeValueAsString(f.sectors()), json.writeValueAsString(f.countries()));
    }

    @Override
    public Map<String, ca.twintigers.research.domain.portfolio.InstrumentProfile> profiles() throws DataSourceException {
        tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
        Map<String, ca.twintigers.research.domain.portfolio.InstrumentProfile> out = new LinkedHashMap<>();
        try (Connection c = db.connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT symbol, asset_class, asset_sub_class, sectors, countries FROM instrument_profiles")) {
            while (rs.next()) {
                Map<String, Double> sectors = new LinkedHashMap<>();
                json.readTree(rs.getString(4)).properties().forEach(e -> sectors.put(e.getKey(), e.getValue().asDouble()));
                Map<String, Double> countries = new LinkedHashMap<>();
                json.readTree(rs.getString(5)).properties().forEach(e -> countries.put(e.getKey(), e.getValue().asDouble()));
                out.put(rs.getString(1), new ca.twintigers.research.domain.portfolio.InstrumentProfile(rs.getString(1), rs.getString(2),
                        rs.getString(3), sectors, countries));
            }
            return out;
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Activity> mirrored() throws DataSourceException {
        try (Connection c = db.connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM ledger WHERE external_id IS NOT NULL")) {
            Map<String, Activity> out = new LinkedHashMap<>();
            while (rs.next()) {
                out.put(rs.getString("external_id"), row(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    @Override
    public Activity putMirrored(String externalId, ActivityDraft draft) throws DataSourceException {
        ActivityDraft d = valid(draft);
        try (Connection c = db.connect()) {
            Long existing = null;
            try (PreparedStatement q = c.prepareStatement("SELECT id FROM ledger WHERE external_id=?")) {
                q.setString(1, externalId);
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) {
                        existing = rs.getLong(1);
                    }
                }
            }
            if (existing != null) {
                return update(String.valueOf(existing), d);
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ledger(date, type, account, symbol, quantity, unit_price, fee, "
                    + "currency, comment, created_at, external_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, d);
                ps.setString(10, clock.instant().toString());
                ps.setString(11, externalId);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    k.next();
                    return find(k.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    @Override
    public void removeMirrored(String externalId) throws DataSourceException {
        execute("DELETE FROM ledger WHERE external_id=?", externalId);
    }

    private void execute(String sql, String... args) throws DataSourceException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    // --- PortfolioWriter -----------------------------------------------------------------------------------

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<String> accountNames() throws DataSourceException {
        return List.copyOf(parse().accountCurrency().keySet());
    }

    private ActivityDraft valid(ActivityDraft draft) throws DataSourceException {
        ActivityDraft d = draft.normalized();
        List<String> problems = d.problems(LocalDate.now(clock), accountNames());
        if (!problems.isEmpty()) {
            throw new DataSourceException(String.join(" ", problems));
        }
        return d;
    }

    private static long id(String id) throws DataSourceException {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new DataSourceException("Not a valid transaction id.");
        }
    }

    @Override
    public Activity create(ActivityDraft draft) throws DataSourceException {
        ActivityDraft d = valid(draft);
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("INSERT INTO ledger(date, type, account, symbol, "
                + "quantity, unit_price, fee, currency, comment, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, d);
            ps.setString(10, clock.instant().toString());
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return find(k.getLong(1));
            }
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    @Override
    public Activity update(String id, ActivityDraft draft) throws DataSourceException {
        long n = id(id);
        ActivityDraft d = valid(draft);
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("UPDATE ledger SET date=?, type=?, account=?, symbol=?, "
                + "quantity=?, unit_price=?, fee=?, currency=?, comment=?, updated_at=? WHERE id=?")) {
            bind(ps, d);
            ps.setString(10, clock.instant().toString());
            ps.setLong(11, n);
            if (ps.executeUpdate() == 0) {
                throw new DataSourceException("No transaction #" + id + ".");
            }
            return find(n);
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id) throws DataSourceException {
        long n = id(id);
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("DELETE FROM ledger WHERE id=?")) {
            ps.setLong(1, n);
            if (ps.executeUpdate() == 0) {
                throw new DataSourceException("No transaction #" + id + ".");
            }
        } catch (SQLException e) {
            throw new DataSourceException("Ledger: " + e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, ActivityDraft d) throws SQLException {
        ps.setString(1, d.date().toString());
        ps.setString(2, d.type());
        ps.setString(3, d.account());
        ps.setString(4, d.symbol());
        ps.setString(5, d.quantity().toPlainString());
        ps.setString(6, d.unitPrice().toPlainString());
        ps.setString(7, d.fee().toPlainString());
        ps.setString(8, d.currency());
        ps.setString(9, d.comment());
    }

    private Activity find(long id) throws DataSourceException {
        return activities().stream().filter(a -> a.id().equals(String.valueOf(id))).findFirst()
                .orElseThrow(() -> new DataSourceException("No transaction #" + id + "."));
    }
}
