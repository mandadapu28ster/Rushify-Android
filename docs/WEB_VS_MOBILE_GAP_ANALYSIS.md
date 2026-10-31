# Rushify: web app vs Android app — gap analysis

Date: 2026-10-31. Web = `~/Documents/CursorSample/rushify` **re-verified against `origin/main` @ `c072f95` (29 Oct 2026)** after `git fetch`; first pass was read from `featureEnhance` @ `1886032`. Mobile = this repo at v0.1.0.

**Verification status (origin/main):** 25 of 25 spot checks hold. `origin/main` is a strict superset of `featureEnhance` (nothing on featureEnhance is missing from main). The live server at `90.255.245.88:8040` is running main: it returns main's security headers, `clear-preview` has the main-only `csv_imports` count, and the live web bundle contains main's new Settings label. No routes were added or removed between the two branches. Frontend delta is 25 lines in 6 files (see "What changed on main").
Method: the web source was read page by page (Home, Holdings, Manual, Pulse, AI, Corporate/Actions, Settings, Shell, Login, Set password), live API payloads were compared with the fields the mobile models read, and the web Holdings page was viewed in the browser pane.
"By design" means the plan (`implementation_plan_v2.md`) keeps it web-only.

## What changed on main (and why it does not change the gap list)
- **Frontend (25 lines):** 60 s request timeout and a generic message for invalid responses (`api.ts`); news links pass through `safeHttpUrl` (only `http:`/`https:`) in `PulsePage.tsx`; `role="alert"` on login/set-password errors; Redoc DOM hardening; Settings "Remove data" gains a `csv_imports` category.
- **Backend:** security headers; **password change and admin reset now bump `session_version` and revoke all `uat_` tokens** (that is why tokens now carry an `sv` claim); `/sync/history` `limit` validated 1–200; partner login lockout; cascading user deletes; CSV content capped at 10 MB.
- **Mobile impact:** none of these remove a gap. Two things to note: (1) password change/reset now logs the app out on its next call, which the existing 401 handling already covers; (2) B5 below.

## 0. Bugs found in the mobile app during the study

**Status 2026-10-31: B1–B5 are all fixed, each with regression tests (`BugFixTests`, plus updated device tests).** Verified live: Home position count now equals the radar's `position_count` (36 = 36); Benchmarks 12 months returns +20.01%, the same as the API. B1 (watchlist refresh) is covered by tests only; it was not run against the live server, because forcing a refresh refetches market data for every watchlist symbol.


| # | Bug | Evidence | Fix |
|---|-----|----------|-----|
| B1 ✅ | Pulse → Watchlists refresh does nothing visible. The app posts `/pulse/watchlists/refresh` without `force=true`, so the server honours its 2 h cache (`force: bool = Query(False)`), and it decodes the reply as a watchlist when the reply is `{refreshed, skipped, errors, cache_hours, all_fresh}`. The empty result is discarded by the "never overwrite with empty" rule, so the old data stays on screen. | `backend/app/routers/pulse.py:83-90`, `schemas/watchlist.py:85-90`, web calls `?force=true` (`PulseWatchlistPanel.tsx:717`) | POST `?force=true`, decode `WatchlistRefreshOut`, then re-GET the lists. Show the same messages as web ("Up to date (within 2h)", "Refreshed N symbol(s)"). |
| B2 ✅ | Home says "48 listed positions"; the web/Pulse figure is 41. Broker cash rows (`asset_kind = cash`) are counted as positions. | Radar `position_count` 41 vs mobile 48 | Exclude `asset_kind == "cash"`. |
| B3 ✅ | Dividends yearly view requests `history_count=12`; web default for yearly is 5 years. Growth yearly asks for 10 years, web default 5. Benchmarks use `range=day/week/month` (about 3 months for "month"), web uses monthly/yearly with a count. The same label therefore shows different numbers on web and mobile. | `DividendIncomeSection.tsx`, `DashboardPage.tsx:2290-2330` | Align defaults and add the period selectors (section 2). |
| B4 ✅ | The AI screen always says "Gemini" in its set-up message, and only learns the key is missing after a failed send. The user may use OpenAI. | `AiPage.tsx:201-207, 540-554` | Read `GET /portfolio/credentials` (`gemini_configured`, `openai_configured`, `ai_provider`) on open. |

| B5 ✅ | News headlines open any URL scheme. The app calls `startActivity(ACTION_VIEW, Uri.parse(url))` on third-party RSS data, so a `javascript:`, `intent:` or `file:` URL would be handed to Android. Main hardened the web the same way with `safeHttpUrl` (http/https only). | `Pulse.kt` news tab; web fix `PulsePage.tsx` on main (`b5753bf`) | Open only `http`/`https` URLs, ignore everything else |

## 1. Home

| Web feature | Mobile | Gap |
|---|---|---|
| Investments card: listed value with "(broker B · manual M)" split, cost, realised (with "equity only, excl. dividends" note), unrealised, dividend income with source note, XIRR with note | Listed value, cost, unrealised (+%), realised, XIRR, dividend income | Missing: the broker-vs-manual split, the explanatory notes and tooltips, the dividend source label |
| **Non-equity manual wealth gain/loss** and "% p.a. blended" (property, savings, other) | Absent | **Missing.** The API already sends `non_equity_manual_gain_total_base`, `non_equity_manual_annualized_pct`, `non_equity_manual_basis_lines` |
| "As of … (London)" | Device time zone | Minor |
| Collapsible accordion, one section open at a time | Scrolling cards | Different pattern, fine |
| Broker sync tabs: Trading 212 / SnapTrade / Monzo, "Refresh dashboard" | Separate Sync tab + Monzo screen | See section 6 |
| **Alerts & reminders**: stale snapshot banner, **incomplete manual lots** (`manual_basis_incomplete_count`), **ticker attention with Dismiss** (`POST /portfolio/ticker-attention-banner/dismiss`, also `ticker-resolution-nudge/dismiss`) | Offline banner, ticker banner with no action | **Missing:** incomplete-lots alert, Dismiss, and link to the symbol fix screen |
| Data freshness notice (12–24 h pipeline) | One-line "end-of-day/cached" labels | Missing the explanation |
| **Listed equity over time**: Period/Daily toggle, Totals toggle, per-broker MV and book lines with a custodian picker, Δ MV, tooltips with dates and MV − book | Single MV vs invested line, per period | **Missing:** daily series (`equity_series_step=daily`), per-broker lines (`equity_brokers`), Totals, Δ MV |
| **Wealth over time** (manual property/cash/other snapshots, All/Cash/Property/Other filter). Hidden unless a line has 5+ snapshots and 1+ year | Absent | Missing (`/portfolio/manual-wealth-series`). Not shown on web for this account today |
| **Benchmark**: Monthly/Yearly, Periods 6/12/18/24 months or 3/5/8/10 years, four metric cards, **"Portfolio vs FTSE/S&P: ±x pp"** cards | Day/Week/Month, returns, P/L split, chart | Different period model (B3). **Missing:** period selector and the vs-index cards |
| **Growth attribution**: Periods selector, five summary cards with "% of total", four components shown separately (Price × FX₀, Interaction, Income, Currency), tooltip with dates and stack-sum, "Note on Interaction" | Three components (price + interaction merged as "Capital"), fixed 12/10 periods | **Missing:** periods selector, separate Price/Interaction, summary cards with %, explanation |
| **Dividend income**: Year/Month, History (6–36 mo or 3–15 yr), Stock filter, per-ticker stacked bars, forecast zone shading, average line, growth % under each bar, "How to read this chart" | Monthly/Yearly, symbol chips, single-colour bars, top-5 payers for the selected bar | **Missing:** history selector, per-ticker stacking, average line, forecast zone, growth % labels, explanatory note |
| **Portfolio & net worth pies**: sector donut with **drill-down into holdings per sector**, table with value/cost/gain/alloc; net worth donut and bucket table | Sector weight bars (no gain, no drill-down); net worth donut with legend | **Missing:** sector drill-down and gain/cost columns |
| **Position size: cost vs gain/loss** per ticker | Absent | **Missing** |
| **Net worth by broker** (stacked bars by broker: listed by account type, property, cash by type, other; table/cards) | Absent | **Missing** |
| "View and edit every line in Holdings" link; empty state | Absent | Minor |
| Ideas for later (static) | n/a | Ignore |

## 2. Holdings (largest gap)

| Web feature | Mobile | Gap |
|---|---|---|
| **"Broker accounts (local history)" table**: per account Investments, Realised, Unrealised, Tax, FX fees, Broker cost, Interest on cash, Dividend payouts, Payout, plus a Total row. Source `GET /portfolio/holdings-rollups` | Absent. The app never calls `holdings-rollups` | **Missing** |
| Group header: positions, cash, qty, value, **Realised, Unrealised, Cash payouts (dividends + interest), XIRR** (tooltips) | Positions, cash, total, unrealised only | **Missing:** realised, payouts, XIRR per account |
| **Exposure filter**: All / Open (no sells) / Open (reduced) / Closed (history); exposure badges on rows | Absent | **Missing** |
| **Closed instruments** inside each broker: FIFO realised, bought/sold qty, fill rows, fees, dividends | Absent (closed lines are not shown) | **Missing** |
| Same-ticker lots grouped under a nested summary ("N lots · total qty · value · P/L") | Flat rows, one per line | Missing |
| **Monzo block**: Accounts / Savings & ISAs / Pots with subtotals and badges (ISA, Savings, Joint, Young, Rewards, Pot), native-currency amount | Flat list on the Monzo screen | Missing the grouping and badges |
| Manual groups: Manual listed by broker, Property, Cash by bank/account, Other, with type badges | Grouped similarly by name | Close; missing the badges and nested lots |
| Position card fields: Qty, Ccy, Avg, Last, Value, P/L, **Tax, FX fees, Broker fees, broker-cash dividends block (count, per-currency, total), P/B, PEG, P/S, Revenue**, manual **purchase & cost**, **FX effect** | Sector, qty, avg, price, value, cost, P/L, purchase date, P/E, yield, ROE, D/E, margin | **Missing:** fees/tax, dividends block, P/B, PEG, P/S, revenue, FX effect (API fields `pb_ratio`, `peg_ratio`, `price_to_sales`, `revenue`, `pnl_currency_effect` are unread) |
| "Show sold positions" toggle, "Sold" badge, "Fully exited" note | Sold manual lots are hidden with no way to show them | **Missing** |
| **Sector override** editor (`PUT /portfolio/sector-override`, 15 sectors) | Read-only | Missing (plan: later) |
| **Edit lot**, **Record sale**, **Delete**, non-listed **Edit values / Exit holding**, return-basis callout | Read-only | Missing (plan: later, needs offline conflict handling) |
| Incomplete-basis banner | Absent | Missing |
| Refreshes Monzo on visit (`POST /monzo/refresh-if-stale`, stale > 6 h) | Does not call it | Missing |
| **Manual page**: add stock/ETF with symbol search and quote, add property, cash, other | Absent | Missing (plan: later) |
| **CSV import** (parsers, preview, commit, history, deletes) | Absent | By design (web-only) |

## 3. Pulse

| Web | Mobile | Gap |
|---|---|---|
| Tabs News / Watchlist / Radar (News default) | Radar / News / Watchlists (Radar default) | Order differs |
| **News**: company filter pills, Refresh button, "Updated date · tap Refresh", cached-only note, source label (Yahoo / Google News), summary line per headline, empty-state guidance | Grouped collapsible cards, publisher + time, pull to refresh, stale banner | **Missing:** company filter, source label, summaries, cached-only note, empty-state text |
| **Watchlists — manage**: create list (max 5), delete list, add symbol via search, remove symbol, **toggle "Fetch news"** per symbol, force refresh with cache messages | Read-only view | **Missing: all editing** (APIs exist: `POST/DELETE /pulse/watchlists`, items `POST/PATCH/DELETE`, `/portfolio/symbol-search`). Plan: later |
| Watchlist metrics (table/cards): Added date, Exch, price at add, Now, Ret%, P/E trailing/forward with industry median hint, P/B, PEG, P/S, Rev YoY, EPS, ROE, PM%, D/E + net cash/debt, Mkt cap, 52w, Div%, "Index / fund" tag | Last price, change %, 52-week range text | **Missing most metrics.** Unread API fields: `forward_pe_ratio`, `industry_pe_median`, `industry_peer_count`, `pb_ratio`, `peg_ratio`, `price_to_sales`, `eps`, `net_cash`, `market_cap`, `revenue_growth_pct`, `added_at`, `price_at_add`, `exchange`, `industry`, `fetch_news` |
| **Radar**: scope pills with holdings count, coverage line ("MV coverage: x% scored · y% index/funds · z% not scored"), **rating buckets** (strong buy … index fund, expandable tickers), dimension chips with coverage bars, benchmark ring on the snowflake, score colour bands, disclaimer | Scope chips, gauge, radar, rewards, risks | **Missing:** rating buckets, coverage line and bars, benchmark ring, disclaimer (unread: `rating_buckets`, `scored_mv_pct`, `index_fund_mv_pct`, `not_covered_mv_pct`, `covered_position_count`) |

## 4. AI

| Web | Mobile | Gap |
|---|---|---|
| Provider-aware (Gemini or OpenAI, agent mode), key check up front | Gemini-only wording, discovers a missing key on first send (B4) | **Missing** |
| Chat thread persisted on the device (`rushify_ai_thread_v1`), **Clear chat** button | In memory only (by design); the VM has `clear()` but there is no button | Clear button missing |
| OpenAI quota/rate-limit and "Google's AI is busy" error mapping, standard-mode notice | Generic server message | Partial |
| Key set-up help (Gemini/OpenAI steps) | Points to the web | By design |

## 5. Corporate actions and symbols

| Web | Mobile | Gap |
|---|---|---|
| **Symbols to confirm: edit the listing symbol and Save** (`PUT /portfolio/ticker-maps`), **Remove my map** (`DELETE`), success message and cache clear | **Read-only list** | **Missing: the main action.** The Home banner tells users to resolve symbols but the mobile app cannot. Plan said "list and resolve" |
| Corporate actions list: Confirm / Dismiss (no dialog, no details shown, no error handling) | Details shown, confirmation dialog, error messages | Mobile is better here |

## 6. Sync and broker status

| Web | Mobile | Gap |
|---|---|---|
| T212: "link expired" alert naming Invest/ISA/SIPP with `last_error`, link to Settings | Generic "needs reconnecting" | Missing per-account detail |
| SnapTrade states: **auth failed**, **link disabled/expired with Reconnect**, **no broker linked → open connection portal** (`POST /snaptrade/portal-url`) | "Connected" or "Not connected" only | **Missing** (portal opens a browser) |
| "Delete data & Full reload" checkbox | Deliberately not exposed | By design (plan section 7) |
| Cooldowns (10 s after a failure; 10 min on rate limiting), tooltips explaining why Sync is disabled | None | Missing |
| **Resume a running sync** after a reload (`sessionStorage` pending id, `/sync/manual/t212-running`, `/snaptrade-running`) | Only after a 409 | Missing: the "running" endpoints are unused, so an app restart mid-sync loses progress |
| Monzo tab: status labels (waiting for approval, needs reconnect…), "Check approval / sync", result "Synced N accounts and M pots" | Monzo screen: connected status, sync, balances | Partial: no pending-approval or needs-reconnect hints; result text is generic |

## 7. Settings / Keys, account and shell

| Web | Mobile | Gap |
|---|---|---|
| **Status summary** (T212 Invest/ISA/SIPP configured, AI provider, Monzo, SnapTrade, masked keys) | No equivalent | Missing. Plan MVP said "read-only status" |
| **Linked brokerages** list (SnapTrade connections with Active/Reconnect, add another) | Absent | Missing (`GET /snaptrade/connections`) |
| Enter T212/SnapTrade/AI/Monzo keys, Monzo connect/disconnect, **Remove keys & portfolio data** | Absent | By design (secrets, destructive) |
| **Set your own password** (`POST /auth/replace-password`) | Absent | Missing (plan: later) |
| Version `v0.3.7` and "Educational / testing only" in the header | Server version only on the login screen; the disclaimer only in More | Minor |
| **Admin area** (Overview, Market data, Pulse data, Email, Users, API apps, Security locks); reached from More → Admin for admin accounts | Not present, by design | Admin account signing in on mobile gets no hint why Admin is missing. See section 11 |
| Developer docs | n/a | Never on mobile |

## 8. What the mobile app has that the web does not

1. **Server connect screen**: URL validation, one-time "not encrypted" confirmation, persistent http chip, server version check.
2. **Biometric lock**, **block screenshots (FLAG_SECURE)**, **hide balances** toggle.
3. **Background refresh and local notifications** when a sync finishes or fails (scheduled syncs only on failure).
4. **Offline cache with "as of" labels and an offline banner on every screen** (web caches only the dashboard and rollups).
5. **Sync history timeline**, a confirmation dialog before a manual sync, explicit 429/409 messages. (No sync-history list was found in the web Home source.)
6. **Holdings search and sort** (the web Holdings page has neither) and a **detail bottom sheet**.
7. Extra numbers: **unrealised %** on Home, **Cost** and **purchase date for broker lots** and **profit margin** in the position detail (the web card shows none of these).
8. **Dividends summary tiles** ("Received (shown)", "Forward estimate") and **top-5 payers** for the selected period.
9. **Benchmarks Day and Week ranges** (the web only offers monthly/yearly).
10. **Corporate action details** and a **confirm/dismiss dialog** (the web shows neither).
11. **AI**: lightweight markdown rendering, 4,000-character limit with counter, draft restored after a failure.
12. **Pull-to-refresh** and tap-to-select chart readouts on all screens.
13. Debug-only adb sign-in receiver (developer tool, not a user feature).

## 9. Suggested order of work

**P0 — correctness:** B1 watchlist refresh, B2 position count, B3 period defaults, B4 AI key check.
**P1 — numbers users compare with the web (read-only, APIs already available):**
1. Holdings: broker accounts summary, per-group realised / payouts / XIRR, exposure filter, closed instruments, fees/tax/dividends and P/B, PEG, P/S, revenue, sold toggle.
2. Symbols to confirm: edit and remove (also needed to make the Home banner actionable); Home alerts with Dismiss and incomplete lots.
3. Benchmarks and Growth: monthly/yearly with period selector, vs-index cards, four-part attribution, per-broker/daily equity chart.
4. Home: non-equity manual gain, net worth by broker, sector drill-down, cost vs gain chart.
**P2:** watchlist editing and metrics, News filter, Radar buckets and coverage, Settings status screen, SnapTrade states and portal, resume running sync, Monzo refresh-on-visit and pending-approval hints, AI provider awareness and Clear chat, Wealth over time.
**P2 (small):** admin "web-only" reminder (section 11).
**P3 (needs a write/conflict strategy):** manual holdings add/edit/sell/delete, sector override, change password.
**Keep web-only:** keys, Monzo OAuth, clear data, CSV import, admin, developer docs.


## 10. What the B1–B5 fixes changed (for review)
- **B1:** refresh now sends `force=true`, reads the summary reply, then re-reads the lists; shows the web's messages ("Up to date (within 2h)", "Refreshed N symbols…", "No symbols to refresh").
- **B2:** "listed positions" counts open listed holdings only (not broker cash, Monzo, property, other, sold).
- **B3:** Growth, Benchmarks and Dividends now have the web's Monthly/Yearly + Periods choices (Growth/Benchmarks 6/12/18/24 mo or 3/5/8/10 yr; Dividends 6–36 mo or 3–15 yr) and the web's defaults (12 months / 5 years; Dividends default to Yearly, 5 yr). Benchmarks send `granularity` + `count` like the web; "1 week" and "1 day" stay as mobile extras. The old "Month" chip (a ~3-month window) is gone because it clashed with the web's "Monthly".
- **B4:** the AI screen reads `GET /portfolio/credentials` on open (only the provider and the two configured flags are kept, never the masked key values) and shows "No Gemini/OpenAI API key saved yet." with the composer off, as the web does.
- **B5:** news links open only if they parse as `http`/`https`; everything else is ignored.


## 11. Admin area (looked at read-only on 2026-10-31 with an admin token)

**What exists on the web:** seven tabs: Overview (user count and sync success/failure counts since a baseline, reset control), Market data (end-of-day coverage, price provider, live-fetch switch, symbol maps), Pulse data (news and watchlist limits), Email (Gmail/Resend), Users (create, flags, reset password/sessions, delete), API apps (partner keys), Security locks (unlock users and IPs). About 40 admin API routes, most of them changes (reset, delete, rotate key, unlock).

**Snapshot at the time:** 3 users, nobody locked, 6 successful syncs and 0 failures since 28 Oct, 65 market symbols tracked with none stale, Gemini model `gemini-2.5-flash`, inactivity warning at 80 days then 20 days grace (admins are never auto-deleted).

**Things noticed (not mobile bugs):**
- An API application named "Android app" is active (max 5 users, 0 provisioned, last used 18 Oct). The mobile app does not use it: it signs in with the user's own login, and the plan says a partner key must never ship inside the app. Worth confirming the key is still wanted.
- The Gmail sign-in redirect address is `http://127.0.0.1:8000/...` (a local address). It only matters when re-authorising Gmail, but it would need changing first.
- The app's background refresh counts as activity for non-admin users, so it keeps accounts from reaching the 80-day inactivity warning. Expected, but worth knowing.

**Decision (agreed 2026-10-31): keep admin web-only.**
1. **Add a reminder for admin accounts only** (not built yet, small): the app stores the user's role at sign-in; for `role = admin` show a card at the top of More ("Admin tools are web-only. Open the web app → More → Admin. For your portfolio on this phone, sign in with your personal account.") plus one line on Home. Regular users see nothing new. Reasons: it explains the missing Admin entry, costs almost nothing, adds no new data or endpoints, and nudges admins away from keeping a high-privilege token on a phone (the admin account has no portfolio anyway).
2. **Do not add a read-only admin view for now.** Low value for 3 users, and it would bring other people's emails and usernames onto the phone, need extra role checks and no-caching rules, and the plan says "never on mobile". Revisit only as a counts-only health glance (no emails, no actions) if monitoring from the phone becomes a real need.

## 12. Status after Phases 1–4 (2026-10-31)

Phases 1–4 of section 9 are done and verified against the live account (read-only): Holdings rollups/filters/symbol edit, Home alerts and insight cards, Performance (growth, benchmarks, dividends, daily equity), Monzo, Pulse (radar buckets and coverage, news grouping and filters, watchlist CRUD and metrics), Sync (cooldowns, resume, SnapTrade/T212 states), Connections & keys (flags only), Change password, AI polish.

Found during the live check and fixed: news summaries showed raw `&nbsp;` entities and repeated the headline (now decoded/hidden, `NewsText`).

Tests: 331 JVM, 110 device, lint clean. Still web-only by design: CSV import, API keys, Monzo OAuth, clear data, admin.
Remaining (Phase 5, writes): sector override, manual lots (add/edit/sale/delete), non-listed edit/exit, manual add flows.

## 13. Phase 5: write features (2026-10-31)

Online-only (no offline queue): each save shows the server's message on failure and changes nothing; after success Home, Holdings, rollups, wealth and growth are refreshed. Rules and wording match the web forms and backend schemas.

- Holdings detail → **Edit** actions: Change sector (PUT /portfolio/sector-override, first lot of a listed ticker), Edit lot, Record sale, Remove (manual listed lots), Edit values (property, cash, other: new value as of a date, or close the line; exited lines only change notes/currency/chart flag).
- More → **Add holding**: Stock/ETF (symbol search + quote, then POST), Home/property, Cash savings, Other asset (with optional exit).
- Not offered on purpose: `cash_isa` as a listed account type on edit (backend rejects it); sector override removal (the web never calls DELETE).
- Tests: 39 JVM (rules, wire format, failures) and 16 device (forms, validation, request bodies) against a mock server. Nothing was written to the live account.
- Still web-only by design: CSV import, API keys, Monzo OAuth, clear data, admin.

## 14. Review follow-ups (2026-10-31)

- **Monzo sync moved to the Sync tab** (own card next to Trading 212 and SnapTrade, like the web's "Broker sync" section). More → Monzo is now balances and status only.
- **Sync history removed** from the Sync tab (the data is still used internally to detect when a sync finishes).
- **Home sections collapse like the web's HomeCollapsible**: title plus a one-line summary when closed. Investments opens and closes on its own; Alerts, Portfolio & net worth, Position size, Net worth by broker, Wealth over time and Data freshness are one-at-a-time (opening one closes the open one). Performance, Monzo and Broker sync stay as links to their screens.

## 15. Navigation tidy-up (2026-10-31)

- Bottom bar is now **Home, Pulse, More**. Holdings and Sync open from More (with a back arrow).
- Home no longer has Monzo or Broker sync tiles; Monzo balances stay in Holdings and Net worth by broker, and Monzo sync stays on Sync.
- More → Monzo (and the standalone Monzo screen) removed.
- Holdings: only one account group is open at a time (search still opens every match).
