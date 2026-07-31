# Nearby Police Incidents

TheBrief now includes a battery-conscious public police-incident feature based on the official Norwegian Police **Politiloggen API**, presented as **Max**'s report - the 7th analyst character (a police shield badge, placed after Anja in the Assistants list).

## Default behavior
- Municipality: Ringerike (editable in Settings) - pick any municipality either by browsing "Police district → municipality/city" (the same two-step picker as the official app - e.g. Finnmark → Kirkenes) or by typing directly into the searchable dropdown, whose suggestion list is populated live from real recent Politiloggen entries. You can add more than one municipality and Max keeps their reports separated.
- All public Politiloggen categories enabled by default
- English category names, full translated text, and exact date + time throughout
- Norwegian incident text is translated online to English; the original Norwegian is retained where relevant
- Reports live under Max in the Assistants tab (not on the main dashboard) - his screen is titled "<Municipality> Police Report" and shows up to 20 incidents, newest first
- A background WorkManager check runs about every 30 minutes - a balance between speed and battery use (Android's WorkManager has an absolute floor of 15 minutes for any app's periodic background work, but checking that often adds up in wake-ups over a day; 30 minutes still catches a new incident well within the same errand or commute) - whenever network is available and battery isn't critically low. This is the closest a plain installable APK can get to "instant" without a server-side listener pushing through Firebase, which would need the app owner's own hosting/credentials (see PUSH_SETUP_REQUIRED.md for why that can't be baked into a public ZIP). Android's Doze mode can still occasionally delay an individual run further, especially with the screen off for a long stretch.
- New incidents trigger a heads-up push notification (own "Nearby Police Incidents" channel, same high-priority treatment as custom weather alerts) and automatically show up in Max's report next time it's opened - no manual refresh needed. The dashboard's top status bar also shows a small 🛡️ icon next to the online/weather-alert icons whenever there's a new report the notification hasn't already covered; tapping it opens Max's full report and clears the icon.
- This all runs automatically after the app has been opened once following install (Android requires that one first launch before it will let any app register background work or notification permission - there's no way around that at the OS level) and keeps running in the background from then on, surviving reboots.
- The first background run establishes a baseline so installing/updating the app does not generate a flood of notifications for old incidents.

## Fix notes (why reports sometimes disappeared entirely, and why refresh could feel slow)
Every incident's Norwegian text was translated to English on **every single refresh**, with no caching, via free third-party translation APIs (MyMemory, then Google as a fallback). OkHttp only runs 5 requests per host at once, so translating even 20-30 incidents queued up into several slow round trips - sometimes 60-90+ seconds combined for a single refresh. The 45-second refresh timeout would then cut the whole operation off, and because translation happened *inline* with fetching, a timed-out translation batch discarded the entire incident list, not just the missing translations - which is exactly why it could look like "no police reports" even though the underlying fetch from Politiloggen had actually succeeded.

Fixed by decoupling translation from the incident data entirely:
- Incidents are now always returned as soon as they're fetched from Politiloggen; translation is a separate best-effort pass afterward.
- Translations are cached for the life of the app - the same incident showing up across refreshes is only ever translated once.
- The whole translation batch now has an 8-second hard budget, and translation calls themselves use a short 5-second timeout instead of the shared 15-second API timeout. Anything not translated in time just keeps its Norwegian text rather than blocking or discarding the report.
- **Circuit breaker**: some networks/carriers/DNS filters block the translation domains outright (this shows up as a consistent ~8-10 second delay on every refresh - the app is paying the full timeout budget every single time because translation is reliably failing on that connection). After one fully-failed translation batch, the app now stops attempting translation for 10 minutes and just uses the original Norwegian text near-instantly, then automatically retries once the cooldown passes in case the network situation changes. This does *not* affect whether Politiloggen itself is reachable - only the optional English-translation step.
- **Real error visibility**: previously, any failure talking to Politiloggen itself (DNS, TLS, timeout, HTTP error) was silently swallowed into "0 incidents", which was indistinguishable from a genuinely quiet day. The fetcher now throws a specific `PoliceApiException` when it truly couldn't reach the service (as opposed to reaching it and finding nothing), and Max's Police Report screen shows that error directly with a "Try again" button instead of a misleadingly empty list.
- The full-report screen (tapping into Max) previously had no timeout at all; it now shares the same bounded-timeout pattern as the dashboard and the background worker.
- The category filter's untouched default (every known category selected) was previously a strict allowlist matched byte-for-byte against a hardcoded Norwegian category list - any naming drift from the live API would silently zero out results. It now behaves as "no filter" until you actually deselect something in Settings.

## API fix: "Couldn't reach the police report service" (the actual root cause)
An earlier revision of this feature called endpoints that simply don't exist on the real Politiloggen API - `GET /messages` and `GET /districts` - plus a fabricated "backup host" (`api.politiet.no/politiloggen`) that doesn't serve this API at all. Every request failed, which is exactly why every refresh showed "Couldn't reach the police report service" regardless of network conditions or which municipality was selected. There is no API key involved anywhere - it's a public, unauthenticated, read-only API - so this was never a credentials problem.

This was re-verified by decompiling the **official Politiloggen app itself** (`no.politiet.politiloggen`, a Hermes/React Native app) rather than guessing at the API shape. Its compiled JS bundle embeds the real contract verbatim:

```
BACKEND_URL = "https://api.politiloggen.politiet.no"
ENDPOINTS = {
  GetThreads:       "/messagethreads"
  GetGeoData:       "/districts/extended"
  GetStatusMessage: "/statusmessages?visningsflate=Appen"
}
```

Thread-list requests are built as:
```
GET {BACKEND_URL}/messagethreads
    ?Skip=<n>&Take=<n>&SortByEnum=LastMessageOn&TimeSpanType=LastQuarter
    [&IsActiveEnum=<value>]
    [&municipalities=<id>&municipalities=<id>...]   (repeated key per id, NOT comma-joined -
                                                       and each value is the NUMERIC municipality
                                                       id from /districts/extended, never its name)
    [&category=<name>&category=<name>...]

-> { "messageThreads": [ { id, category, municipality, area, isActive,
                           messages: [ { id, text, createdOn }, ... ] }, ... ],
     "count": n }
```

`/districts/extended` returns the full district → municipality tree used by the official app's own two-step picker: `[ { district: { id, name }, municipalities: [ { id, name }, ... ] }, ... ]`.

This app now calls exactly that endpoint, with exactly those parameters, and resolves each selected municipality's display name to its real numeric id via that same tree before querying - which is also why municipalities like Oslo previously returned nothing even when the domain itself was reachable: the old code sent the municipality's free-text *name* as a made-up `municipality=` parameter, which the real API has never accepted.

## District → municipality picker
`/districts/extended` returns every municipality for every district directly - it's not limited to "whatever has a recent incident", so quiet/remote municipalities like Kirkenes (Sør-Varanger) under Finnmark are always selectable, exactly like the official app's own picker. The curated offline list in `PoliceDistrictCatalog.kt` is kept only as an offline fallback if the live geo call ever fails (e.g. first launch with no connection yet) - once online, the real tree is always preferred.

## Categories
Events, Fire, Animals, Burglary, Rescue, Public order, Missing person, Maritime incident, Vandalism / property damage, Traffic, Theft, Accident, Violence, Weather, Other incidents.

## Important limitation
Politiloggen is a **public operational log**, not a live feed of every 112 call. Police may omit, delay, generalize, or update incidents for operational, privacy, or security reasons.

## Official source and attribution
The data is from Politiet / Politiloggen and is licensed under the Norwegian Licence for Open Government Data (NLOD) 2.0. The app includes visible attribution and a link to Politiloggen as required by the official usage guidance.

Official API: https://api.politiloggen.politiet.no/
Official public log: https://www.politiet.no/politiloggen

## Fix notes (why some municipalities used to show nothing)
The previous version called `/messages?take=&municipality=` - `municipality` isn't
a real filter on this API (it only supports server-side filtering by police
district), and the request wasn't sorted, so it silently fell back to an
arbitrary small unsorted page of nationwide messages. For a smaller
municipality the odds of any of those happening to be a match were low,
which is why e.g. Ringerike often returned zero results while a big city
might occasionally show something by chance.

The fetcher now calls `/messages?Take=&Skip=&SortBy=Date&SortOrder=Descending`
(the same call the official app makes), pages backward through the newest
messages first, and matches each message's own `municipality` field
client-side against the municipalities you've selected - continuing to page
(up to a safety cap) until enough matches are found. The municipality
suggestion list is built the same way, from real municipality names seen in
the live log, so the picker and the filter always agree on spelling.
