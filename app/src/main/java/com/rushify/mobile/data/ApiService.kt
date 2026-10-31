package com.rushify.mobile.data

import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @GET("api/health") suspend fun health(): Health
    @POST("api/auth/login") suspend fun login(@Body body: LoginRequest): TokenResponse
    @GET("api/auth/me") suspend fun me(): Me
    /** Used right after login, before the token is persisted in the session. */
    @GET("api/auth/me") suspend fun meWith(@Header("Authorization") bearer: String): Me
    @GET("api/portfolio/dashboard") suspend fun dashboard(): Dashboard
    @GET("api/portfolio/holdings-rollups") suspend fun holdingsRollups(): Rollups
    @GET("api/pulse/radar") suspend fun radar(@Query("scope") scope: String = "overall"): Radar
    @GET("api/pulse/news") suspend fun news(@Query("refresh") refresh: Boolean? = null): News
    @GET("api/pulse/watchlists") suspend fun watchlists(): Watchlists
    /** force=true bypasses the 2 h server cache, exactly like the web app does. */
    @POST("api/pulse/watchlists/refresh") suspend fun refreshWatchlists(@Query("force") force: Boolean = true): WatchlistRefresh
    @GET("api/sync/status") suspend fun syncStatus(): SyncStatus
    @GET("api/snaptrade/status") suspend fun snaptradeStatus(): SnaptradeStatus
    @GET("api/sync/manual-quota") suspend fun quota(): Quota
    @GET("api/sync/history") suspend fun syncHistory(): List<SyncLog>
    @GET("api/sync/log/{id}") suspend fun syncLog(@Path("id") id: Int): SyncLog
    @POST("api/sync/manual") suspend fun syncT212(@Body body: ManualSyncRequest = ManualSyncRequest()): SyncAccepted
    @POST("api/snaptrade/sync/manual") suspend fun syncSnaptrade(@Body body: ManualSyncRequest = ManualSyncRequest()): SyncAccepted

    @GET("api/portfolio/growth") suspend fun growth(
        @Query("granularity") granularity: String = "monthly",
        @Query("count") count: Int = 12,
        @Query("compact") compact: Boolean = false,
        @Query("equity_series_step") equitySeriesStep: String? = null,
        @Query("equity_brokers") equityBrokers: List<String>? = null,
    ): Growth
    /** Either `range` (day, week) or `granularity` + `count` (monthly 12, yearly 5 …), as the web app sends. Null params are omitted. */
    @GET("api/portfolio/benchmarks") suspend fun benchmarks(
        @Query("range") range: String? = null,
        @Query("granularity") granularity: String? = null,
        @Query("count") count: Int? = null,
    ): Benchmarks
    @GET("api/portfolio/dividend-dashboard") suspend fun dividends(
        @Query("granularity") granularity: String = "monthly",
        @Query("history_count") historyCount: Int = 12,
        @Query("symbol") symbol: String? = null,
    ): Dividends
    @GET("api/portfolio/manual-wealth-series") suspend fun manualWealthSeries(): ManualWealthSeries
    @GET("api/monzo/status") suspend fun monzoStatus(): MonzoStatus
    /** Response<T>: a 204 or empty body is still a success; HTTP errors are raised by the repository. */
    @POST("api/monzo/sync") suspend fun monzoSync(): Response<MonzoSyncResult>
    @POST("api/monzo/refresh-if-stale") suspend fun monzoRefreshIfStale(): MonzoRefreshResult
    @GET("api/corporate-actions") suspend fun corporateActions(): List<CorporateAction>
    @PATCH("api/corporate-actions/{id}") suspend fun resolveCorporateAction(@Path("id") id: Int, @Body body: CorporateActionResolve): CorporateAction
    @GET("api/portfolio/ticker-maps") suspend fun tickerMaps(): TickerMaps
    /** Saves this user's listing symbol for a broker ticker. Any 2xx is success. */
    @PUT("api/portfolio/ticker-maps") suspend fun putTickerMap(@Body body: TickerMapUpsert)
    @DELETE("api/portfolio/ticker-maps") suspend fun deleteTickerMap(@Query("source_key") sourceKey: String)
    @POST("api/portfolio/ticker-attention-banner/dismiss") suspend fun dismissTickerBanner()
    @GET("api/portfolio/credentials") suspend fun aiCredentialsStatus(): AiCredentialsStatus
    @POST("api/ai/insight") suspend fun aiInsight(@Body body: AiRequest): AiResponse

    @POST("api/pulse/watchlists") suspend fun createWatchlist(@Body body: WatchlistCreate): Watchlist
    @DELETE("api/pulse/watchlists/{id}") suspend fun deleteWatchlist(@Path("id") id: Int)
    @POST("api/pulse/watchlists/{id}/items") suspend fun addWatchItem(@Path("id") id: Int, @Body body: WatchItemCreate): Watchlist
    @PATCH("api/pulse/watchlists/{id}/items/{itemId}") suspend fun patchWatchItem(@Path("id") id: Int, @Path("itemId") itemId: Int, @Body body: WatchItemPatch): Watchlist
    @DELETE("api/pulse/watchlists/{id}/items/{itemId}") suspend fun deleteWatchItem(@Path("id") id: Int, @Path("itemId") itemId: Int)
    @GET("api/portfolio/symbol-search") suspend fun symbolSearch(@Query("q") q: String): List<SymbolHit>
    @GET("api/portfolio/symbol-quote") suspend fun symbolQuote(@Query("symbol") symbol: String): SymbolQuote
    // Writes. Bodies are JsonObjects so only the fields the web sends are sent (nulls and omissions are explicit).
    @GET("api/portfolio/manual") suspend fun manualHoldings(): List<ManualHoldingOut>
    @POST("api/portfolio/manual") suspend fun createManual(@Body body: JsonObject)
    @PATCH("api/portfolio/manual/{id}") suspend fun patchManual(@Path("id") id: Int, @Body body: JsonObject)
    @DELETE("api/portfolio/manual/{id}") suspend fun deleteManual(@Path("id") id: Int)
    @POST("api/portfolio/manual/{id}/sell") suspend fun sellManual(@Path("id") id: Int, @Body body: JsonObject)
    @PUT("api/portfolio/sector-override") suspend fun putSectorOverride(@Body body: JsonObject)
    @GET("api/snaptrade/connections") suspend fun snaptradeConnections(): List<SnaptradeConnection>
    @POST("api/snaptrade/portal-url") suspend fun snaptradePortalUrl(@Body body: PortalRequest): PortalResponse
    @GET("api/sync/manual/t212-running") suspend fun t212Running(): T212Running
    @GET("api/sync/manual/snaptrade-running") suspend fun snaptradeRunning(): SnaptradeRunning
    @GET("api/portfolio/credentials") suspend fun credentialFlags(): CredentialFlags
    /** Public endpoint: sent without the session token (see [NO_AUTH_HEADER]). */
    @Headers("X-No-Auth: 1")
    @POST("api/auth/replace-password") suspend fun replacePassword(@Body body: ReplacePasswordRequest)
}
