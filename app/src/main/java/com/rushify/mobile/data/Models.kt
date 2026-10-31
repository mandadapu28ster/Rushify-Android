package com.rushify.mobile.data

import java.math.BigDecimal
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

/** Backend Decimals arrive as JSON strings (sometimes numbers). Never parse via Double. */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): BigDecimal {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement()
        val text = (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: decoder.decodeString()
        return BigDecimal(text.trim())
    }
    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())
}

typealias Dec = @Serializable(with = BigDecimalSerializer::class) BigDecimal

@Serializable
data class Health(val status: String = "", val app: String = "", val version: String = "", @SerialName("git_sha") val gitSha: String? = null)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class TokenResponse(@SerialName("access_token") val accessToken: String, @SerialName("token_type") val tokenType: String = "bearer")

@Serializable
data class Me(
    val id: Int,
    val email: String = "",
    val username: String = "",
    val role: String = "user",
    @SerialName("base_currency") val baseCurrency: String = "GBP",
    @SerialName("snaptrade_configured") val snaptradeConfigured: Boolean = false,
)

@Serializable
data class SectorSlice(val sector: String = "", @SerialName("value_base") val valueBase: Dec? = null, @SerialName("weight_pct") val weightPct: Dec? = null)

@Serializable
data class BreakdownSlice(val bucket: String = "", @SerialName("value_base") val valueBase: Dec? = null, @SerialName("weight_pct") val weightPct: Dec? = null)

@Serializable
data class HoldingRow(
    val ticker: String = "",
    @SerialName("instrument_name") val instrumentName: String? = null,
    val source: String = "",
    @SerialName("broker_label") val brokerLabel: String? = null,
    @SerialName("account_kind") val accountKind: String? = null,
    val sector: String? = null,
    @SerialName("asset_kind") val assetKind: String? = null,
    @SerialName("manual_holding_id") val manualHoldingId: Int? = null,
    @SerialName("snaptrade_account_id") val snaptradeAccountId: String? = null,
    @SerialName("broker_account_ref") val brokerAccountRef: String? = null,
    val quantity: Dec? = null,
    @SerialName("avg_price") val avgPrice: Dec? = null,
    @SerialName("current_price") val currentPrice: Dec? = null,
    val currency: String? = null,
    @SerialName("market_value_base") val marketValueBase: Dec? = null,
    @SerialName("cost_base") val costBase: Dec? = null,
    @SerialName("pnl_stock_base") val pnlStockBase: Dec? = null,
    @SerialName("pe_ratio") val peRatio: Dec? = null,
    @SerialName("dividend_yield") val dividendYield: Dec? = null,
    val roe: Dec? = null,
    @SerialName("debt_to_equity") val debtToEquity: Dec? = null,
    @SerialName("profit_margin") val profitMargin: Dec? = null,
    @SerialName("pb_ratio") val pbRatio: Dec? = null,
    @SerialName("peg_ratio") val pegRatio: Dec? = null,
    @SerialName("price_to_sales") val priceToSales: Dec? = null,
    val revenue: Dec? = null,
    @SerialName("pnl_currency_effect") val pnlCurrencyEffect: Dec? = null,
    @SerialName("cost_base_gbp") val costBaseGbp: Dec? = null,
    @SerialName("fx_to_base") val fxToBase: Dec? = null,
    @SerialName("manual_broker_name") val manualBrokerName: String? = null,
    @SerialName("quote_type") val quoteType: String? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("sold_out_at") val soldOutAt: String? = null,
)

@Serializable
data class Dashboard(
    @SerialName("base_currency") val baseCurrency: String = "GBP",
    @SerialName("total_market_value_base") val totalMarketValueBase: Dec? = null,
    @SerialName("total_cost_base") val totalCostBase: Dec? = null,
    @SerialName("total_pnl_base") val totalPnlBase: Dec? = null,
    @SerialName("total_realised_pnl_base") val totalRealisedPnlBase: Dec? = null,
    @SerialName("portfolio_xirr_annual") val portfolioXirrAnnual: Dec? = null,
    @SerialName("portfolio_xirr_note") val portfolioXirrNote: String? = null,
    @SerialName("dividend_income_total_base") val dividendIncomeTotalBase: Dec? = null,
    @SerialName("dividend_income_broker_cash_base") val dividendIncomeBrokerCashBase: Dec? = null,
    @SerialName("sector_allocation") val sectorAllocation: List<SectorSlice> = emptyList(),
    val rows: List<HoldingRow> = emptyList(),
    @SerialName("net_worth_total_base") val netWorthTotalBase: Dec? = null,
    @SerialName("net_worth_breakdown") val netWorthBreakdown: List<BreakdownSlice> = emptyList(),
    @SerialName("ticker_map_attention_count") val tickerMapAttentionCount: Int = 0,
    @SerialName("manual_basis_incomplete_count") val manualBasisIncompleteCount: Int = 0,
    @SerialName("non_equity_manual_gain_total_base") val nonEquityManualGainTotalBase: Dec? = null,
    @SerialName("non_equity_manual_basis_lines") val nonEquityManualBasisLines: Int = 0,
    @SerialName("non_equity_manual_annualized_pct") val nonEquityManualAnnualizedPct: Dec? = null,
    @SerialName("broker_listed_market_value_base") val brokerListedMarketValueBase: Dec? = null,
    @SerialName("manual_listed_market_value_base") val manualListedMarketValueBase: Dec? = null,
    @SerialName("listed_eod_close_as_of") val listedEodCloseAsOf: String? = null,
    @SerialName("as_of") val asOf: String? = null,
)

@Serializable
data class RadarDimension(val key: String = "", val label: String = "", val score: Int = 0, @SerialName("coverage_pct") val coveragePct: Int = 0)

@Serializable
data class RadarNote(val kind: String = "", val text: String = "")

@Serializable
data class RadarAccount(val key: String = "", val label: String = "", @SerialName("listed_mv_base") val listedMvBase: Dec? = null, @SerialName("position_count") val positionCount: Int = 0)

@Serializable
data class RadarBucket(
    val key: String = "", val label: String = "",
    @SerialName("stock_count") val stockCount: Int = 0,
    @SerialName("mv_pct") val mvPct: Int = 0,
    val tickers: List<String> = emptyList(),
)

@Serializable
data class Radar(
    @SerialName("scope_key") val scopeKey: String = "overall",
    @SerialName("scope_label") val scopeLabel: String = "",
    val headline: String? = null,
    val summary: String? = null,
    @SerialName("health_score") val healthScore: Dec? = null,
    @SerialName("health_max") val healthMax: Dec? = null,
    val dimensions: List<RadarDimension> = emptyList(),
    val rewards: List<RadarNote> = emptyList(),
    val risks: List<RadarNote> = emptyList(),
    val accounts: List<RadarAccount> = emptyList(),
    @SerialName("rating_buckets") val ratingBuckets: List<RadarBucket> = emptyList(),
    @SerialName("position_count") val positionCount: Int = 0,
    @SerialName("covered_position_count") val coveredPositionCount: Int = 0,
    @SerialName("scored_mv_pct") val scoredMvPct: Int = 100,
    @SerialName("index_fund_mv_pct") val indexFundMvPct: Int = 0,
    @SerialName("not_covered_mv_pct") val notCoveredMvPct: Int = 0,
    @SerialName("listed_mv_base") val listedMvBase: Dec? = null,
    @SerialName("base_currency") val baseCurrency: String = "GBP",
    val message: String? = null,
)

@Serializable
data class NewsItem(
    val id: Int = 0,
    val symbol: String? = null,
    val source: String? = null,
    val title: String = "",
    val url: String? = null,
    val publisher: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val summary: String? = null,
)

@Serializable
data class NewsGroup(
    val key: String = "",
    val symbol: String? = null,
    val label: String = "",
    @SerialName("company_name") val companyName: String? = null,
    @SerialName("headline_count") val headlineCount: Int = 0,
    val items: List<NewsItem> = emptyList(),
)

@Serializable
data class News(
    val groups: List<NewsGroup> = emptyList(),
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
    val stale: Boolean = false,
    @SerialName("cached_only") val cachedOnly: Boolean = false,
    val symbols: List<String> = emptyList(),
    val message: String? = null,
)

@Serializable
data class WatchItem(
    val id: Int = 0,
    @SerialName("yahoo_symbol") val yahooSymbol: String = "",
    @SerialName("company_name") val companyName: String? = null,
    val exchange: String? = null,
    val industry: String? = null,
    val currency: String? = null,
    @SerialName("fetch_news") val fetchNews: Boolean = false,
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("price_at_add") val priceAtAdd: Dec? = null,
    @SerialName("last_price") val lastPrice: Dec? = null,
    @SerialName("change_pct") val changePct: Dec? = null,
    @SerialName("week52_high") val week52High: Dec? = null,
    @SerialName("week52_low") val week52Low: Dec? = null,
    @SerialName("pe_ratio") val peRatio: Dec? = null,
    @SerialName("forward_pe_ratio") val forwardPeRatio: Dec? = null,
    @SerialName("industry_pe_median") val industryPeMedian: Dec? = null,
    @SerialName("industry_pb_median") val industryPbMedian: Dec? = null,
    @SerialName("industry_peer_count") val industryPeerCount: Int = 0,
    @SerialName("pb_ratio") val pbRatio: Dec? = null,
    @SerialName("peg_ratio") val pegRatio: Dec? = null,
    @SerialName("price_to_sales") val priceToSales: Dec? = null,
    @SerialName("revenue_growth_pct") val revenueGrowthPct: Dec? = null,
    val eps: Dec? = null,
    val roe: Dec? = null,
    @SerialName("profit_margin") val profitMargin: Dec? = null,
    @SerialName("debt_to_equity") val debtToEquity: Dec? = null,
    @SerialName("net_cash") val netCash: Dec? = null,
    @SerialName("market_cap") val marketCap: Dec? = null,
    @SerialName("dividend_yield") val dividendYield: Dec? = null,
    @SerialName("quote_type") val quoteType: String? = null,
    @SerialName("data_fetched_at") val dataFetchedAt: String? = null,
)

@Serializable
data class Watchlist(val id: Int = 0, val name: String = "", val items: List<WatchItem> = emptyList())

@Serializable
data class WatchCache(@SerialName("all_fresh") val allFresh: Boolean = false, @SerialName("cache_hours") val cacheHours: Int = 2)

@Serializable
data class Watchlists(
    val watchlists: List<Watchlist> = emptyList(),
    @SerialName("max_watchlists") val maxWatchlists: Int = 5,
    @SerialName("max_items_per_watchlist") val maxItemsPerWatchlist: Int = 30,
    @SerialName("watchlist_limits_unlimited") val limitsUnlimited: Boolean = false,
    val cache: WatchCache = WatchCache(),
)

@Serializable data class WatchlistCreate(val name: String)
@Serializable data class WatchItemCreate(
    @SerialName("yahoo_symbol") val yahooSymbol: String, @SerialName("company_name") val companyName: String?,
    val exchange: String?, @SerialName("fetch_news") val fetchNews: Boolean,
)
@Serializable data class WatchItemPatch(@SerialName("fetch_news") val fetchNews: Boolean)

@Serializable
data class SymbolHit(val symbol: String = "", val name: String? = null, val exchange: String? = null, val currency: String? = null, val type: String? = null)

@Serializable
data class SymbolQuote(
    @SerialName("normalized_symbol") val normalizedSymbol: String? = null,
    @SerialName("last_price") val lastPrice: Dec? = null,
    val currency: String? = null,
)

/** One row of GET /portfolio/manual (listed lots and non-listed assets the user entered by hand). */
@Serializable
data class ManualHoldingOut(
    val id: Int = 0,
    @SerialName("asset_kind") val assetKind: String = "listed",
    val symbol: String? = null,
    val label: String? = null,
    @SerialName("broker_name") val brokerName: String? = null,
    @SerialName("account_kind") val accountKind: String? = null,
    val quantity: Dec? = null,
    val currency: String? = null,
    val notes: String? = null,
    @SerialName("home_value") val homeValue: Dec? = null,
    @SerialName("mortgage_balance") val mortgageBalance: Dec? = null,
    @SerialName("cash_balance") val cashBalance: Dec? = null,
    @SerialName("other_value") val otherValue: Dec? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("cost_base_gbp") val costBaseGbp: Dec? = null,
    @SerialName("sold_out_at") val soldOutAt: String? = null,
    @SerialName("opened_date") val openedDate: String? = null,
    @SerialName("opening_value_gbp") val openingValueGbp: Dec? = null,
    @SerialName("exit_date") val exitDate: String? = null,
    @SerialName("exit_value_gbp") val exitValueGbp: Dec? = null,
    @SerialName("show_on_wealth_chart") val showOnWealthChart: Boolean? = null,
    @SerialName("snapshot_count") val snapshotCount: Int? = null,
    @SerialName("chart_eligible") val chartEligible: Boolean? = null,
)

@Serializable
data class T212Account(val configured: Boolean = false, @SerialName("needs_reconnect") val needsReconnect: Boolean = false, @SerialName("last_error") val lastError: String? = null)

@Serializable
data class SyncStatus(
    @SerialName("t212_configured") val t212Configured: Boolean = false,
    @SerialName("t212_needs_reconnect") val t212NeedsReconnect: Boolean = false,
    val accounts: Map<String, T212Account> = emptyMap(),
)

@Serializable
data class SnaptradeStatus(
    @SerialName("snaptrade_configured") val configured: Boolean = false,
    @SerialName("snaptrade_needs_reconnect") val needsReconnect: Boolean = false,
    @SerialName("snaptrade_needs_broker_link") val needsBrokerLink: Boolean = false,
    @SerialName("snaptrade_auth_failed") val authFailed: Boolean = false,
    @SerialName("snaptrade_disabled") val disabled: List<SnaptradeDisabled> = emptyList(),
    @SerialName("snaptrade_connections_count") val connections: Int = 0,
    @SerialName("snaptrade_active_connections_count") val activeConnections: Int = 0,
)

@Serializable
data class SnaptradeDisabled(val id: String = "", @SerialName("broker_name") val brokerName: String? = null)

@Serializable
data class SnaptradeConnection(val id: String = "", val disabled: Boolean = false, @SerialName("broker_name") val brokerName: String? = null, val type: String? = null)

@Serializable data class PortalRequest(@SerialName("reconnect_authorization_id") val reconnectAuthorizationId: String? = null)
@Serializable data class PortalResponse(@SerialName("redirect_uri") val redirectUri: String? = null)

@Serializable
data class T212Running(@SerialName("t212_manual_in_progress") val inProgress: Boolean = false, @SerialName("active_sync_log_id") val activeSyncLogId: Int? = null)

@Serializable
data class SnaptradeRunning(@SerialName("snaptrade_manual_in_progress") val inProgress: Boolean = false, @SerialName("active_sync_log_id") val activeSyncLogId: Int? = null)

/**
 * Only the non-secret fields of GET /portfolio/credentials (which also carries masked key values that are
 * deliberately never modelled). Used for the read-only "Connections" screen.
 */
@Serializable
data class CredentialFlags(
    @SerialName("t212_invest_configured") val t212Invest: Boolean = false,
    @SerialName("t212_isa_configured") val t212Isa: Boolean = false,
    @SerialName("t212_sipp_configured") val t212Sipp: Boolean = false,
    @SerialName("gemini_configured") val gemini: Boolean = false,
    @SerialName("openai_configured") val openai: Boolean = false,
    @SerialName("snaptrade_configured") val snaptrade: Boolean = false,
    @SerialName("ai_provider") val aiProvider: String? = null,
    @SerialName("openai_agent_mode") val openaiAgentMode: String? = null,
)

/** POST /monzo/sync: the status plus what was synced; `sync` is null while the user still has to approve access in the Monzo app. */
@Serializable
data class MonzoSyncResult(
    @SerialName("monzo_configured") val configured: Boolean = false,
    val status: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
    @SerialName("skipped_count") val skippedCount: Int = 0,
    @SerialName("balance_rows") val balanceRows: Int = 0,
    val sync: MonzoSyncCounts? = null,
)

@Serializable data class MonzoSyncCounts(val accounts: Int = 0, val pots: Int = 0)
@Serializable data class MonzoRefreshResult(val refreshed: Boolean = false)

@Serializable
data class ReplacePasswordRequest(
    val username: String,
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
    @SerialName("new_password_confirm") val newPasswordConfirm: String,
)

@Serializable
data class QuotaPart(@SerialName("used_in_window") val used: Int = 0, val remaining: Int = 0, @SerialName("rate_limit_allows") val rateLimitAllows: Boolean = true)

@Serializable
data class Quota(@SerialName("max_per_24h") val maxPer24h: Int = 3, val t212: QuotaPart = QuotaPart(), val snaptrade: QuotaPart = QuotaPart())

@Serializable
data class SyncLog(
    val id: Int = 0,
    val kind: String = "",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val success: Boolean = false,
    @SerialName("error_message") val errorMessage: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ManualSyncRequest(@EncodeDefault @SerialName("order_history_full_refresh") val orderHistoryFullRefresh: Boolean = false)

@Serializable
data class SyncAccepted(@SerialName("sync_log_id") val syncLogId: Int? = null, val detail: String? = null)

@Serializable
data class RunningState(val running: Boolean = false, @SerialName("sync_log_id") val syncLogId: Int? = null)

@Serializable
data class ErrorBody(val detail: JsonElement? = null)

// ---------- Growth, benchmarks, dividends ----------

@Serializable
data class GrowthBroker(
    @SerialName("broker_label") val brokerLabel: String = "",
    @SerialName("listed_equity_mv_end_base") val listedEquityMvEndBase: Dec? = null,
    @SerialName("invested_capital_end_base") val investedCapitalEndBase: Dec? = null,
)

@Serializable
data class PurchaseEvent(val ticker: String = "", val label: String? = null, val source: String = "")

@Serializable
data class GrowthPeriod(
    val label: String = "",
    @SerialName("period_start") val periodStart: String? = null,
    @SerialName("capital_gain_base") val capitalGainBase: Dec? = null,
    @SerialName("price_gain_base") val priceGainBase: Dec? = null,
    @SerialName("interaction_gain_base") val interactionGainBase: Dec? = null,
    @SerialName("income_base") val incomeBase: Dec? = null,
    @SerialName("currency_gain_base") val currencyGainBase: Dec? = null,
    @SerialName("total_growth_base") val totalGrowthBase: Dec? = null,
    @SerialName("listed_equity_mv_end_base") val listedEquityMvEndBase: Dec? = null,
    @SerialName("invested_capital_end_base") val investedCapitalEndBase: Dec? = null,
    @SerialName("purchase_events") val purchaseEvents: List<PurchaseEvent> = emptyList(),
    val brokers: List<GrowthBroker> = emptyList(),
)

@Serializable
data class EquityDailyPoint(
    @SerialName("as_of") val asOf: String = "",
    @SerialName("listed_equity_mv_end_base") val listedEquityMvEndBase: Dec? = null,
    @SerialName("invested_capital_end_base") val investedCapitalEndBase: Dec? = null,
    val brokers: List<GrowthBroker> = emptyList(),
)

@Serializable
data class Growth(
    @SerialName("base_currency") val baseCurrency: String = "GBP",
    val granularity: String = "monthly",
    val periods: List<GrowthPeriod> = emptyList(),
    val note: String? = null,
    val compact: Boolean = false,
    @SerialName("equity_daily") val equityDaily: List<EquityDailyPoint>? = null,
)

@Serializable
data class BenchmarkPoint(
    val date: String = "",
    @SerialName("portfolio_index") val portfolioIndex: Double? = null,
    @SerialName("ftse100_index") val ftse100Index: Double? = null,
    @SerialName("sp100_index") val sp100Index: Double? = null,
)

@Serializable
data class Benchmarks(
    val range: String = "month",
    @SerialName("base_currency") val baseCurrency: String = "GBP",
    @SerialName("portfolio_return_pct") val portfolioReturnPct: Dec? = null,
    @SerialName("ftse100_return_pct") val ftse100ReturnPct: Dec? = null,
    @SerialName("sp100_return_pct") val sp100ReturnPct: Dec? = null,
    @SerialName("period_total_pnl_base") val periodTotalPnlBase: Dec? = null,
    @SerialName("period_price_pnl_base") val periodPricePnlBase: Dec? = null,
    @SerialName("period_fx_pnl_base") val periodFxPnlBase: Dec? = null,
    @SerialName("period_cross_pnl_base") val periodCrossPnlBase: Dec? = null,
    val series: List<BenchmarkPoint> = emptyList(),
    @SerialName("empty_portfolio") val emptyPortfolio: Boolean = false,
)

@Serializable
data class DividendLayer(val ticker: String = "", val layer: String = "actual", @SerialName("amount_base") val amountBase: Dec? = null)

@Serializable
data class DividendPoint(
    val label: String = "",
    val kind: String = "historical",
    @SerialName("total_base") val totalBase: Dec? = null,
    @SerialName("growth_pct_prior") val growthPctPrior: Dec? = null,
    @SerialName("forecast_zone") val forecastZone: Boolean = false,
    val layers: List<DividendLayer> = emptyList(),
)

@Serializable
data class Dividends(
    @SerialName("base_currency") val baseCurrency: String = "GBP",
    val granularity: String = "monthly",
    @SerialName("forecast_method") val forecastMethod: String? = null,
    val note: String? = null,
    val series: List<DividendPoint> = emptyList(),
    val symbols: List<String> = emptyList(),
)

// ---------- Monzo, corporate actions, ticker maps ----------

@Serializable
data class MonzoStatus(
    @SerialName("monzo_configured") val configured: Boolean = false,
    val status: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
    @SerialName("skipped_count") val skippedCount: Int = 0,
    @SerialName("balance_rows") val balanceRows: Int = 0,
)

@Serializable
data class CorporateAction(
    val id: Int = 0,
    val status: String = "pending",
    @SerialName("action_type") val actionType: String = "",
    val ticker: String = "",
    val details: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class CorporateActionResolve(val status: String)

@Serializable
data class TickerAttention(
    @SerialName("source_key") val sourceKey: String = "",
    @SerialName("broker_ticker") val brokerTicker: String? = null,
    @SerialName("yahoo_symbol") val yahooSymbol: String? = null,
    @SerialName("has_user_map") val hasUserMap: Boolean = false,
)

@Serializable
data class TickerMaps(@SerialName("attention_items") val attentionItems: List<TickerAttention> = emptyList())

// ---------- AI ----------

@Serializable
data class AiMessage(val role: String, val content: String)

@Serializable
data class AiRequest(
    val prompt: String,
    @SerialName("use_online_augmentation") val useOnlineAugmentation: Boolean = false,
    @SerialName("conversation_history") val conversationHistory: List<AiMessage> = emptyList(),
)

@Serializable
data class AiResponse(
    val text: String = "",
    val disclaimer: String? = null,
    @SerialName("augmentation_warning") val augmentationWarning: String? = null,
    @SerialName("suggest_online_augmentation") val suggestOnlineAugmentation: Boolean = false,
)

// ---------- Watchlist refresh result, AI key status ----------

/** Reply of POST /pulse/watchlists/refresh. It is a summary, not a watchlist. */
@Serializable
data class WatchlistRefresh(
    val refreshed: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList(),
    @SerialName("cache_hours") val cacheHours: Int = 2,
    @SerialName("all_fresh") val allFresh: Boolean = false,
)

/**
 * Only the three non-secret fields of GET /portfolio/credentials. The same response carries masked
 * key values; they are deliberately not modelled, so they are dropped at parse time and never stored.
 */
@Serializable
data class AiCredentialsStatus(
    @SerialName("gemini_configured") val geminiConfigured: Boolean = false,
    @SerialName("openai_configured") val openaiConfigured: Boolean = false,
    @SerialName("ai_provider") val aiProvider: String? = null,
) {
    val provider: String get() = if (aiProvider.equals("openai", ignoreCase = true)) "openai" else "gemini"
    val providerLabel: String get() = if (provider == "openai") "OpenAI" else "Gemini"
    /** Same rule as the web: the configured flag of the selected provider. */
    val ready: Boolean get() = if (provider == "openai") openaiConfigured else geminiConfigured
}

// ---------- Holdings rollups (GET /portfolio/holdings-rollups) ----------

@Serializable
data class DividendByCurrency(val currency: String = "", val amount: Dec? = null)

/** One broker account's local-history totals (fills, fees, payouts, XIRR). */
@Serializable
data class RollupAccount(
    val source: String = "",
    @SerialName("account_ref") val accountRef: String = "",
    @SerialName("display_label") val displayLabel: String? = null,
    @SerialName("fill_row_count") val fillRowCount: Int = 0,
    @SerialName("realised_gain_base_sum") val realisedGainBaseSum: Dec? = null,
    @SerialName("dividend_payout_count") val dividendPayoutCount: Int = 0,
    @SerialName("dividend_payout_base_sum") val dividendPayoutBaseSum: Dec? = null,
    @SerialName("interest_on_cash_base_sum") val interestOnCashBaseSum: Dec? = null,
    @SerialName("combined_broker_payout_base_sum") val combinedBrokerPayoutBaseSum: Dec? = null,
    @SerialName("unrealised_pnl_base_sum") val unrealisedPnlBaseSum: Dec? = null,
    @SerialName("listed_investments_mv_base_sum") val listedInvestmentsMvBaseSum: Dec? = null,
    @SerialName("xirr_annual") val xirrAnnual: Dec? = null,
    @SerialName("xirr_note") val xirrNote: String? = null,
    @SerialName("fill_tax_base_sum") val fillTaxBaseSum: Dec? = null,
    @SerialName("fill_fx_fees_base_sum") val fillFxFeesBaseSum: Dec? = null,
    @SerialName("fill_broker_fees_base_sum") val fillBrokerFeesBaseSum: Dec? = null,
)

/** One instrument inside an account: open, reduced, or closed history. `exposure` is open_full, open_partial or closed_history. */
@Serializable
data class RollupLine(
    val source: String = "",
    @SerialName("account_ref") val accountRef: String = "",
    @SerialName("broker_ticker") val brokerTicker: String? = null,
    @SerialName("yahoo_ticker") val yahooTicker: String? = null,
    @SerialName("display_ticker") val displayTicker: String? = null,
    @SerialName("open_quantity") val openQuantity: Dec? = null,
    val exposure: String = "",
    @SerialName("buy_fill_qty") val buyFillQty: Dec? = null,
    @SerialName("sell_fill_qty") val sellFillQty: Dec? = null,
    @SerialName("fill_row_count") val fillRowCount: Int = 0,
    @SerialName("fifo_realised_base") val fifoRealisedBase: Dec? = null,
    @SerialName("fill_tax_base_sum") val fillTaxBaseSum: Dec? = null,
    @SerialName("fill_fx_fees_base_sum") val fillFxFeesBaseSum: Dec? = null,
    @SerialName("fill_broker_fees_base_sum") val fillBrokerFeesBaseSum: Dec? = null,
    @SerialName("instrument_name") val instrumentName: String? = null,
    @SerialName("dividend_payout_count") val dividendPayoutCount: Int = 0,
    @SerialName("dividends_by_currency") val dividendsByCurrency: List<DividendByCurrency> = emptyList(),
    @SerialName("dividend_payout_base_sum") val dividendPayoutBaseSum: Dec? = null,
)

@Serializable
data class Rollups(val lines: List<RollupLine> = emptyList(), val accounts: List<RollupAccount> = emptyList())

@Serializable
data class TickerMapUpsert(@SerialName("source_key") val sourceKey: String, @SerialName("yahoo_symbol") val yahooSymbol: String)

// ---------- Manual wealth over time ----------

@Serializable
data class WealthSnapshot(@SerialName("as_of") val asOf: String = "", @SerialName("value_gbp") val valueGbp: Dec? = null, val source: String = "")

@Serializable
data class WealthAsset(
    @SerialName("manual_holding_id") val manualHoldingId: Int = 0,
    val label: String = "",
    @SerialName("asset_kind") val assetKind: String = "",
    @SerialName("show_on_wealth_chart") val showOnWealthChart: Boolean = true,
    @SerialName("snapshot_count") val snapshotCount: Int = 0,
    @SerialName("chart_eligible") val chartEligible: Boolean = false,
    val exited: Boolean = false,
    val snapshots: List<WealthSnapshot> = emptyList(),
)

@Serializable
data class ManualWealthSeries(
    @SerialName("base_currency") val baseCurrency: String = "GBP",
    @SerialName("min_snapshots_for_chart") val minSnapshotsForChart: Int = 5,
    @SerialName("min_hold_days_for_chart") val minHoldDaysForChart: Int = 365,
    val assets: List<WealthAsset> = emptyList(),
)
