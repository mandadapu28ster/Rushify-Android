package com.rushify.mobile.data

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.KSerializer

data class Cached<T>(val value: T, val fetchedAt: Long)

class RushifyRepository(
    val api: ApiService,
    val session: SessionManager,
    private val dao: CacheDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun key(name: String) = "${session.current.cacheScope}|$name"

    /** Runs an API call and maps every failure to an [ApiError]. */
    suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        throw mapError(t, hadAuth = session.current.token != null || session.current.expired)
    }

    suspend fun <T> cached(name: String, ser: KSerializer<T>): Cached<T>? {
        val e = dao.get(key(name)) ?: return null
        return runCatching { Cached(AppJson.decodeFromString(ser, e.json), e.fetchedAt) }.getOrNull()
    }

    /**
     * Fetches and stores. A response that [isEmpty] never overwrites existing good data:
     * the previous cached copy is returned instead.
     */
    suspend fun <T> refresh(name: String, ser: KSerializer<T>, isEmpty: (T) -> Boolean = { false }, fetch: suspend () -> T): Cached<T> {
        val fresh = call(fetch)
        if (isEmpty(fresh)) {
            cached(name, ser)?.let { return it }
        }
        val now = clock()
        dao.put(CacheEntry(key(name), AppJson.encodeToString(ser, fresh), now))
        return Cached(fresh, now)
    }

    suspend fun wipeCache() = dao.clear()

    suspend fun dashboardCached() = cached("dashboard", Dashboard.serializer())
    suspend fun dashboard() = refresh("dashboard", Dashboard.serializer(), isEmpty = { it.rows.isEmpty() && it.netWorthTotalBase == null }) { api.dashboard() }

    suspend fun rollupsCached() = cached("rollups", Rollups.serializer())
    suspend fun rollups() = refresh("rollups", Rollups.serializer(), isEmpty = { it.lines.isEmpty() && it.accounts.isEmpty() }) { api.holdingsRollups() }

    suspend fun radarCached(scope: String) = cached("radar:$scope", Radar.serializer())
    suspend fun radar(scope: String) = refresh("radar:$scope", Radar.serializer(), isEmpty = { it.dimensions.isEmpty() }) { api.radar(scope) }

    suspend fun newsCached() = cached("news", News.serializer())
    suspend fun news(force: Boolean = false) = refresh("news", News.serializer(), isEmpty = { it.groups.isEmpty() }) { api.news(if (force) true else null) }

    suspend fun watchlistsCached() = cached("watchlists", Watchlists.serializer())
    suspend fun watchlists() = refresh("watchlists", Watchlists.serializer(), isEmpty = { it.watchlists.isEmpty() }) { api.watchlists() }

    /**
     * Forced refresh: ask the server to refetch quotes (force=true), then re-read the lists so the
     * screen shows the new numbers. Returns the summary for the "Refreshed N symbols" message.
     */
    suspend fun refreshWatchlistsNow(): Pair<WatchlistRefresh, Cached<Watchlists>> {
        val summary = call { api.refreshWatchlists(force = true) }
        return summary to watchlists()
    }

    suspend fun growthCached(granularity: String, count: Int) = cached("growth:$granularity:$count", Growth.serializer())
    suspend fun growth(granularity: String, count: Int) = refresh("growth:$granularity:$count", Growth.serializer(), isEmpty = { it.periods.isEmpty() }) {
        api.growth(granularity, count = count)
    }

    /** Daily listed-equity points (compact), optionally limited to some brokers. Cached per selection. */
    suspend fun growthDailyCached(granularity: String, count: Int, brokers: List<String>) = cached("growth-daily:$granularity:$count:${brokers.sorted().joinToString("|")}", Growth.serializer())
    suspend fun growthDaily(granularity: String, count: Int, brokers: List<String>) =
        refresh("growth-daily:$granularity:$count:${brokers.sorted().joinToString("|")}", Growth.serializer(), isEmpty = { it.equityDaily.isNullOrEmpty() }) {
            api.growth(granularity, count = count, compact = true, equitySeriesStep = "daily", equityBrokers = brokers.takeIf { it.isNotEmpty() })
        }

    suspend fun wealthCached() = cached("manual-wealth", ManualWealthSeries.serializer())
    suspend fun wealth() = refresh("manual-wealth", ManualWealthSeries.serializer()) { api.manualWealthSeries() }

    /** `mode` is day, week (server range) or monthly, yearly (granularity + count), as on the web. */
    suspend fun benchmarksCached(mode: String, count: Int) = cached("benchmarks:$mode:$count", Benchmarks.serializer())
    suspend fun benchmarks(mode: String, count: Int) = refresh("benchmarks:$mode:$count", Benchmarks.serializer(), isEmpty = { it.series.isEmpty() }) {
        if (mode == "day" || mode == "week") api.benchmarks(range = mode) else api.benchmarks(granularity = mode, count = count)
    }

    suspend fun dividendsCached(granularity: String, count: Int, symbol: String?) = cached("dividends:$granularity:$count:${symbol ?: "all"}", Dividends.serializer())
    suspend fun dividends(granularity: String, count: Int, symbol: String?) =
        refresh("dividends:$granularity:$count:${symbol ?: "all"}", Dividends.serializer(), isEmpty = { it.series.isEmpty() }) { api.dividends(granularity, count, symbol) }

    suspend fun monzoCached() = cached("monzo", MonzoStatus.serializer())
    suspend fun monzo() = refresh("monzo", MonzoStatus.serializer()) { api.monzoStatus() }

    suspend fun corporateCached() = cached("corporate", corporateSer)
    suspend fun corporate() = refresh("corporate", corporateSer) { api.corporateActions() }
    suspend fun manualHoldings() = call { api.manualHoldings() }

    suspend fun tickerMapsCached() = cached("ticker-maps", TickerMaps.serializer())
    suspend fun tickerMaps() = refresh("ticker-maps", TickerMaps.serializer()) { api.tickerMaps() }

    /** Result of a Monzo sync. `result` is null when the server answered 2xx with an empty body. */
    data class MonzoSyncOutcome(val result: MonzoSyncResult?)

    suspend fun monzoSyncNow(): MonzoSyncOutcome = call {
        val r = api.monzoSync()
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
        MonzoSyncOutcome(r.body())
    }

    /** Like the web: refresh Monzo when a page opens, only if the server says it is stale. Failure is ignored. */
    suspend fun monzoRefreshIfStale(): Boolean = try { call { api.monzoRefreshIfStale().refreshed } } catch (e: ApiError) { false }

    suspend fun credentialFlags(): CredentialFlags = call { api.credentialFlags() }
    suspend fun snaptradeConnections(): List<SnaptradeConnection> = call { api.snaptradeConnections() }

    /** Not cached: it only decides whether the AI composer is enabled. Failure means "unknown", not "not set up". */
    suspend fun aiCredentials(): AiCredentialsStatus? = try { call { api.aiCredentialsStatus() } } catch (e: ApiError) { null }

    suspend fun syncHistoryCached() = cached("sync-history", syncLogsSer)
    suspend fun syncHistory() = refresh("sync-history", syncLogsSer) { api.syncHistory() }

    private val corporateSer = kotlinx.serialization.builtins.ListSerializer(CorporateAction.serializer())
    private val syncLogsSer = kotlinx.serialization.builtins.ListSerializer(SyncLog.serializer())
}
