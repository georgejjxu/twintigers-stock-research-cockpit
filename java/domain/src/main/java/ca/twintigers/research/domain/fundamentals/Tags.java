package ca.twintigers.research.domain.fundamentals;

import java.util.List;

/** Preference-ordered XBRL tags per metric (same lists as research_core/health.py). */
public final class Tags {

    private Tags() {
    }

    public static final List<String> REVENUE = List.of("RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues",
            "SalesRevenueNet", "RevenueFromContractWithCustomerIncludingAssessedTax", "SalesRevenueGoodsNet",
            "ifrs-full:Revenue", "ifrs-full:RevenueFromContractsWithCustomers");
    public static final List<String> OPERATING_INCOME = List.of("OperatingIncomeLoss",
            "ifrs-full:ProfitLossFromOperatingActivities");
    public static final List<String> NET_INCOME = List.of("NetIncomeLoss", "ProfitLoss", "ifrs-full:ProfitLoss");
    public static final List<String> OCF = List.of("NetCashProvidedByUsedInOperatingActivities",
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
            "ifrs-full:CashFlowsFromUsedInOperatingActivities");
    public static final List<String> CAPEX = List.of("PaymentsToAcquirePropertyPlantAndEquipment",
            "PaymentsToAcquireProductiveAssets",
            "ifrs-full:PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities");
    public static final List<String> CASH = List.of("CashAndCashEquivalentsAtCarryingValue",
            "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents", "ifrs-full:CashAndCashEquivalents");
    public static final List<String> DILUTED_SHARES = List.of("WeightedAverageNumberOfDilutedSharesOutstanding",
            "ifrs-full:AdjustedWeightedAverageShares");
    public static final List<String> RECEIVABLES = List.of("AccountsReceivableNetCurrent",
            "ifrs-full:TradeAndOtherCurrentReceivables", "ifrs-full:CurrentTradeReceivables");
    public static final List<String> INVENTORY = List.of("InventoryNet", "ifrs-full:Inventories");

    // Debt. See DebtResolver for how these combine into one consistent scheme per company.
    public static final List<String> DEBT_NONCURRENT = List.of("LongTermDebtNoncurrent",
            "ifrs-full:NoncurrentPortionOfNoncurrentBorrowings", "ifrs-full:LongtermBorrowings");
    public static final List<String> DEBT_CURRENT = List.of("LongTermDebtCurrent", "DebtCurrent",
            "ifrs-full:CurrentPortionOfNoncurrentBorrowings", "ifrs-full:CurrentPortionOfLongtermBorrowings");
    public static final List<String> DEBT_TOTAL_LT = List.of("LongTermDebt");
    public static final List<String> DEBT_AND_LEASE_NONCURRENT = List.of("LongTermDebtAndCapitalLeaseObligations");
    public static final List<String> DEBT_AND_LEASE_CURRENT = List.of("LongTermDebtAndCapitalLeaseObligationsCurrent");
    public static final List<String> SHORT_TERM = List.of("ShortTermBorrowings", "ifrs-full:ShorttermBorrowings");
    public static final List<String> COMMERCIAL_PAPER = List.of("CommercialPaper");
}
