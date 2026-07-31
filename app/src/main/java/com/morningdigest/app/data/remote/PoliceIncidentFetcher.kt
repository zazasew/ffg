package com.morningdigest.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Public Norwegian Police operational log ("Politiloggen").
 *
 * WHY THE PREVIOUS VERSION FAILED WITH "Couldn't reach the police report
 * service": it was calling `/messages` (with `Take`/`Skip`/`SortBy`/
 * `SortOrder`/`Districts` params) and `/districts`, plus a fabricated backup
 * host `api.politiet.no/politiloggen`. None of those three things are real -
 * `/messages` and `/districts` 404 on the live API, and the backup host
 * doesn't resolve/serve this API at all. Every request failed, so
 * `anyPageSucceeded` stayed false and the app surfaced the "couldn't reach
 * the service" error on every single refresh, regardless of network
 * conditions or the municipality chosen.
 *
 * The actual API contract below is not guessed - it's read directly out of
 * the official Politiloggen app itself (package `no.politiet.politiloggen`).
 * That app is a Hermes/React Native app; its compiled JS bundle
 * (assets/index.android.bundle) embeds these constants verbatim:
 *
 *   BACKEND_URL = "https://api.politiloggen.politiet.no"
 *   ENDPOINTS = {
 *     GetThreads:       "/messagethreads"
 *     GetGeoData:       "/districts/extended"
 *     GetStatusMessage: "/statusmessages?visningsflate=Appen"
 *   }
 *
 * and its request-building code constructs thread-list calls as:
 *
 *   GET {BACKEND_URL}/messagethreads
 *       ?Skip=<n>&Take=<n>&SortByEnum=LastMessageOn&TimeSpanType=LastQuarter
 *       [&IsActiveEnum=<value>]
 *       [&municipalities=<id>&municipalities=<id>...]   (repeated key, one per id -
 *                                                          NOT comma-joined, and each
 *                                                          value is the NUMERIC
 *                                                          municipality id from
 *                                                          /districts/extended,
 *                                                          never its free-text name)
 *       [&category=<name>&category=<name>...]
 *
 *   -> { "messageThreads": [ { id, category, municipality, area, isActive,
 *                              messages: [ { id, text, createdOn }, ... ] }, ... ],
 *        "count": n }
 *
 * `/districts/extended` returns the full district → municipality tree used
 * for the app's own two-step picker:
 *   [ { district: { id, name }, municipalities: [ { id, name }, ... ] }, ... ]
 *
 * There is no API key involved anywhere in this - the whole thing is a
 * public, unauthenticated read-only API, same as the official app uses.
 */
class PoliceIncidentFetcher(private val client: OkHttpClient) {
    /**
     * Translations are cached for the process lifetime (this fetcher is a
     * singleton in [com.morningdigest.app.di.AppContainer]), so the same
     * incident - which shows up again and again across refreshes - is only
     * ever translated once instead of hitting a free translation API on
     * every single refresh.
     */
    private val translationCache = ConcurrentHashMap<String, String>()

    /**
     * Translation hits third-party free services (no SLA) - give them a much
     * shorter leash than the main API client so a slow/hanging translation
     * call can never eat the whole refresh budget.
     */
    private val translationClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    data class DistrictItem(val id: String, val name: String)

    /** One municipality inside a district, as returned by /districts/extended. */
    private data class MunicipalityItem(val id: String, val name: String)

    /** Full district → municipality tree, cached for the process lifetime (this basically never changes). */
    @Volatile private var cachedGeoData: List<Triple<DistrictItem, List<MunicipalityItem>, Unit>>? = null

    data class Incident(
        val id: String,
        val categoryNo: String,
        val categoryEn: String,
        val municipality: String,
        val area: String,
        val createdMillis: Long,
        val norwegianText: String,
        val englishText: String,
        val isActive: Boolean,
        val sourceUrl: String = "https://www.politiet.no/politiloggen"
    )

    companion object {
        const val BASE_URL = "https://api.politiloggen.politiet.no"

        // These match the ENDPOINTS map in the official app's bundle exactly.
        private const val ENDPOINT_GET_THREADS = "/messagethreads"
        private const val ENDPOINT_GET_GEO_DATA = "/districts/extended"

        /** How many messages to request per page when paging through /messagethreads. */
        private const val PAGE_SIZE = 100

        /** Safety cap so a rare/small municipality can't trigger unbounded paging. */
        private const val MAX_PAGES = 8

        /**
         * Hard wall-clock budget for the whole translation batch, regardless
         * of how many incidents are being translated. Whatever isn't done by
         * then keeps its Norwegian text - this is what stops a slow/rate
         * limited translation API from ever making the police report look
         * empty or slow.
         */
        private const val TRANSLATION_BUDGET_MILLIS = 8_000L

        /** How long to stop attempting translation after a fully-failed batch, before retrying once. */
        private const val TRANSLATION_COOLDOWN_MILLIS = 10 * 60 * 1000L

        val CATEGORY_TRANSLATIONS = linkedMapOf(
            "Arrangement" to "Events",
            "Brann" to "Fire",
            "Dyr" to "Animals",
            "Innbrudd" to "Burglary",
            "Redning" to "Rescue",
            "Ro og orden" to "Public order",
            "Savnet" to "Missing person",
            "Sjø" to "Maritime incident",
            "Skadeverk" to "Vandalism / property damage",
            "Trafikk" to "Traffic",
            "Tyveri" to "Theft",
            "Ulykke" to "Accident",
            "Voldshendelse" to "Violence",
            "Vær" to "Weather",
            "Andre hendelser" to "Other incidents"
        )
    }

    /**
     * Fetches (and caches) the real district → municipality tree from
     * `/districts/extended`, the same data source the official app's own
     * "choose district and municipalities" screen uses. Falls back to the
     * curated static [PoliceDistrictCatalog] (municipality names only, no
     * ids) if the network call fails, so the picker still shows *something*
     * offline - though id-less fallback entries will resolve to nothing
     * server-side until a live fetch succeeds.
     */
    private suspend fun geoData(): List<Pair<DistrictItem, List<MunicipalityItem>>> = withContext(Dispatchers.IO) {
        cachedGeoData?.let { cached -> return@withContext cached.map { it.first to it.second } }
        val live = runCatching {
            val body = request("$BASE_URL$ENDPOINT_GET_GEO_DATA")
            val root = JSONArray(body)
            val out = mutableListOf<Pair<DistrictItem, List<MunicipalityItem>>>()
            for (i in 0 until root.length()) {
                val entry = root.optJSONObject(i) ?: continue
                val districtObj = entry.optJSONObject("district") ?: continue
                val districtId = districtObj.opt("id")?.toString()?.trim().orEmpty()
                val districtName = districtObj.optString("name").trim()
                if (districtId.isBlank() || districtName.isBlank()) continue
                val municipalitiesArr = entry.optJSONArray("municipalities") ?: JSONArray()
                val municipalities = mutableListOf<MunicipalityItem>()
                for (j in 0 until municipalitiesArr.length()) {
                    val m = municipalitiesArr.optJSONObject(j) ?: continue
                    val mId = m.opt("id")?.toString()?.trim().orEmpty()
                    val mName = m.optString("name").trim()
                    if (mId.isNotBlank() && mName.isNotBlank()) municipalities.add(MunicipalityItem(mId, mName))
                }
                out.add(DistrictItem(districtId, districtName) to municipalities.sortedBy { it.name.lowercase() })
            }
            out.sortedBy { it.first.name.lowercase() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        if (live != null) {
            cachedGeoData = live.map { Triple(it.first, it.second, Unit) }
            return@withContext live
        }

        // Offline fallback: static catalog, name-only (no numeric ids yet).
        PoliceDistrictCatalog.DISTRICTS.map { entry ->
            DistrictItem(entry.displayName, entry.displayName) to entry.municipalities.map { MunicipalityItem("", it) }
        }
    }

    /** The real police districts - powers the first level ("District") of the Settings picker. */
    suspend fun fetchDistricts(): List<DistrictItem> = geoData().map { it.first }

    /**
     * Municipalities/cities inside one police district - the second level of
     * the picker, straight from the real `/districts/extended` tree (which,
     * unlike scanning recent incidents, is guaranteed complete - a quiet
     * municipality with no recent log entries still shows up here).
     */
    suspend fun fetchMunicipalitiesForDistrict(district: DistrictItem): List<String> =
        geoData().firstOrNull { it.first.id == district.id }?.second?.map { it.name }.orEmpty()

    /** Every municipality across every district - used for the Settings autocomplete. */
    suspend fun fetchMunicipalitySuggestions(): List<String> =
        geoData().flatMap { it.second.map { m -> m.name } }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    suspend fun fetch(municipality: String, enabledCategories: Set<String>, limit: Int = 30): List<Incident> =
        fetch(listOf(municipality), enabledCategories, limit)

    /**
     * Fetches incidents for one or more municipalities. Each requested
     * municipality NAME is resolved to its real numeric id via the cached
     * geo tree, then all ids are sent together as repeated `municipalities=`
     * query parameters to `/messagethreads` in a single paged pass (this
     * filtering happens server-side, unlike the previous version's
     * client-side scan over unrelated nationwide pages).
     */
    suspend fun fetch(municipalities: List<String>, enabledCategories: Set<String>, limit: Int = 30): List<Incident> = withContext(Dispatchers.IO) {
        val targetNames = municipalities.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (targetNames.isEmpty()) return@withContext emptyList()

        val tree = geoData()
        val allMunicipalities = tree.flatMap { it.second }
        val idToName = LinkedHashMap<String, String>()
        val unresolved = mutableListOf<String>()
        targetNames.forEach { name ->
            val match = allMunicipalities.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (match != null && match.id.isNotBlank()) idToName[match.id] = match.name else unresolved += name
        }

        val wanted = limit.coerceIn(1, 200)
        val matches = mutableListOf<Incident>()
        val seenIds = mutableSetOf<String>()
        var skip = 0
        var totalCount = Int.MAX_VALUE
        var anyPageSucceeded = false
        var lastPageError: Exception? = null

        if (idToName.isNotEmpty()) {
            for (page in 0 until MAX_PAGES) {
                if (matches.size >= wanted || skip >= totalCount) break
                val (threads, total) = runCatching { fetchThreadPage(skip = skip, take = PAGE_SIZE, municipalityIds = idToName.keys.toList(), categories = enabledCategories) }
                    .onSuccess { anyPageSucceeded = true }
                    .onFailure { lastPageError = it as? Exception ?: Exception(it) }
                    .getOrElse { JSONArray() to 0 }
                totalCount = total
                if (threads.length() == 0) break

                for (i in 0 until threads.length()) {
                    val thread = threads.optJSONObject(i) ?: continue
                    matches += flattenThread(thread, seenIds)
                }
                skip += PAGE_SIZE
            }
        }

        if (unresolved.isNotEmpty()) {
            // Couldn't resolve this name to a real id (e.g. spelling drift after
            // a municipality merger) - fall back to a nationwide pull filtered
            // locally by name, same safety net as before, so it never just
            // silently shows nothing.
            runCatching {
                val (threads, _) = fetchThreadPage(skip = 0, take = PAGE_SIZE, municipalityIds = emptyList(), categories = enabledCategories)
                anyPageSucceeded = true
                for (i in 0 until threads.length()) {
                    val thread = threads.optJSONObject(i) ?: continue
                    val threadMunicipality = thread.optString("municipality").trim()
                    if (unresolved.any { it.equals(threadMunicipality, ignoreCase = true) }) {
                        matches += flattenThread(thread, seenIds)
                    }
                }
            }.onFailure { if (lastPageError == null) lastPageError = it as? Exception ?: Exception(it) }
        }

        if (!anyPageSucceeded && lastPageError != null) {
            // Every attempt to reach Politiloggen itself failed (DNS, TLS,
            // timeout, HTTP error, or an unexpected response shape...) - this
            // is not "zero incidents", it's "couldn't check". The real cause
            // is folded into the message itself (not just `cause`) because
            // the Settings/Report screens only ever display `e.message` - if
            // this stayed a static string, a genuinely different failure
            // (e.g. a real 404 vs. a timeout vs. a changed response shape)
            // would look identical and be undiagnosable from the UI alone.
            val causeDescription = describeError(lastPageError!!)
            throw PoliceApiException("Couldn't reach the police report service ($causeDescription)", lastPageError)
        }

        val result = matches.sortedByDescending { it.createdMillis }.take(wanted)
        translate(result)
    }

    /** Thrown when Politiloggen itself couldn't be reached at all, as opposed to "reached it, found nothing". */
    class PoliceApiException(message: String, cause: Throwable?) : Exception(message, cause)

    /**
     * A thread can have several timestamped updates in its `messages` array;
     * each one becomes its own [Incident] row (carrying the parent thread's
     * category/municipality/area), matching how the official app treats
     * thread updates as separate log entries.
     */
    private fun flattenThread(thread: JSONObject, seenIds: MutableSet<String>): List<Incident> {
        val category = thread.optString("category")
        val threadId = thread.optString("id").trim()
        val municipalityName = thread.optString("municipality")
        val area = thread.optString("area")
        val isActive = thread.optBoolean("isActive", true)
        val messages = thread.optJSONArray("messages") ?: JSONArray()
        val sourceUrl = "https://www.politiet.no/politiloggen/hendelse/#/$threadId"

        if (messages.length() == 0) {
            val text = thread.optString("text").ifBlank { thread.optString("description") }
            if (threadId.isBlank() || text.isBlank() || !seenIds.add(threadId)) return emptyList()
            return listOf(
                Incident(
                    id = threadId,
                    categoryNo = category,
                    categoryEn = CATEGORY_TRANSLATIONS[category] ?: category,
                    municipality = municipalityName,
                    area = area,
                    createdMillis = parseMillis(thread.optString("createdOn").ifBlank { thread.optString("lastMessageOn") }),
                    norwegianText = text,
                    englishText = text,
                    isActive = isActive,
                    sourceUrl = sourceUrl
                )
            )
        }

        return buildList {
            for (m in 0 until messages.length()) {
                val msg = messages.optJSONObject(m) ?: continue
                val msgId = msg.optString("id").trim().ifBlank { "$threadId-$m" }
                val text = msg.optString("text").ifBlank { msg.optString("description") }
                if (text.isBlank() || !seenIds.add(msgId)) continue
                add(
                    Incident(
                        id = msgId,
                        categoryNo = category,
                        categoryEn = CATEGORY_TRANSLATIONS[category] ?: category,
                        municipality = municipalityName,
                        area = area,
                        createdMillis = parseMillis(msg.optString("createdOn")),
                        norwegianText = text,
                        englishText = text,
                        isActive = isActive,
                        sourceUrl = sourceUrl
                    )
                )
            }
        }
    }

    /**
     * Turns whatever actually went wrong into a short, specific string that's
     * safe to show in the UI - "HTTP 404", "host not found", "timed out",
     * "unexpected reply format (...)" etc. - instead of every failure looking
     * identical. This is what makes a real bug (wrong endpoint, changed
     * response shape, a genuine outage, a device/network TLS problem)
     * distinguishable from the outside without attaching a debugger.
     */
    private fun describeError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "couldn't resolve api.politiloggen.politiet.no - check the device's internet/DNS"
        is java.net.SocketTimeoutException -> "timed out waiting for a reply"
        is javax.net.ssl.SSLException -> "TLS/certificate error: ${e.message}"
        is org.json.JSONException -> "unexpected reply format (${e.message})"
        is IllegalStateException -> e.message ?: "HTTP error"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * One page of /messagethreads, newest first, optionally scoped to one or
     * more municipality ids and/or categories - all sent server-side exactly
     * like the official app. Returns (messageThreads array, count).
     *
     * Parsing intentionally tries a couple of alternate key names for the
     * array/count fields (case variants, and a bare top-level array as a
     * fallback) so a minor, undocumented API response tweak degrades
     * gracefully instead of throwing and surfacing as a hard failure.
     */
    private fun fetchThreadPage(skip: Int, take: Int, municipalityIds: List<String>, categories: Set<String>): Pair<JSONArray, Int> {
        val builder = "$BASE_URL$ENDPOINT_GET_THREADS".toHttpUrl().newBuilder()
            .addQueryParameter("Skip", skip.toString())
            .addQueryParameter("Take", take.toString())
            .addQueryParameter("SortByEnum", "LastMessageOn")
            .addQueryParameter("TimeSpanType", "LastQuarter")
        municipalityIds.forEach { builder.addQueryParameter("municipalities", it) }
        categories.forEach { builder.addQueryParameter("category", it) }
        val url = builder.build().toString()
        val body = requestWithRetry(url)

        val trimmed = body.trimStart()
        if (trimmed.startsWith("[")) {
            // Defensive: if the API ever returns a bare array instead of
            // { messageThreads: [...] }, still use it rather than throwing.
            val arr = JSONArray(body)
            return arr to (skip + arr.length())
        }

        val root = JSONObject(body)
        val threads = root.optJSONArray("messageThreads")
            ?: root.optJSONArray("MessageThreads")
            ?: root.optJSONArray("threads")
            ?: root.optJSONArray("data")
            ?: root.optJSONArray("items")
            ?: JSONArray()
        val count = when {
            root.has("count") -> root.optInt("count")
            root.has("Count") -> root.optInt("Count")
            root.has("totalCount") -> root.optInt("totalCount")
            threads.length() < take -> skip + threads.length()
            else -> Int.MAX_VALUE
        }
        return threads to count
    }

    /**
     * If translation keeps failing outright (e.g. mymemory.translated.net /
     * translate.googleapis.com are blocked or unreachable on this network -
     * some carriers, VPNs, and DNS filters do this), there's no point
     * paying the full timeout on every single refresh forever. After one
     * fully-failed attempt this trips for [TRANSLATION_COOLDOWN_MILLIS], and
     * every incident just keeps its Norwegian text near-instantly instead. It
     * retries automatically once the cooldown passes.
     */
    @Volatile private var translationDisabledUntil: Long = 0L

    /**
     * Best-effort translation with a hard wall-clock budget for the whole
     * batch. Incidents are ALWAYS returned in English where possible - any
     * item whose translation hasn't come back by [TRANSLATION_BUDGET_MILLIS]
     * just keeps its Norwegian text rather than delaying or discarding the
     * whole police report.
     */
    private suspend fun translate(items: List<Incident>): List<Incident> {
        if (items.isEmpty()) return items
        if (System.currentTimeMillis() < translationDisabledUntil) {
            return items.map { item ->
                translationCache[item.norwegianText]?.let { item.copy(englishText = it) } ?: item
            }
        }
        val translated = withTimeoutOrNull(TRANSLATION_BUDGET_MILLIS) {
            coroutineScope {
                items.map { item ->
                    async(Dispatchers.IO) {
                        item.id to translateCached(item.norwegianText)
                    }
                }.awaitAll().toMap()
            }
        }.orEmpty()

        val anySucceeded = translated.values.any { !it.isNullOrBlank() }
        translationDisabledUntil = if (!anySucceeded) System.currentTimeMillis() + TRANSLATION_COOLDOWN_MILLIS else 0L

        return items.map { item ->
            val text = translated[item.id]
            if (text.isNullOrBlank()) item else item.copy(englishText = text)
        }
    }

    private fun translateCached(text: String): String? {
        if (text.isBlank()) return null
        translationCache[text]?.let { return it }
        val result = translateNoToEn(text) ?: return null
        translationCache[text] = result
        return result
    }

    /** Free online translation. The original Norwegian is retained if translation fails. */
    private fun translateNoToEn(text: String): String? {
        val q = URLEncoder.encode(text.take(2000), "UTF-8")
        runCatching {
            val json = JSONObject(request(translationClient, "https://api.mymemory.translated.net/get?q=$q&langpair=no|en"))
            json.optJSONObject("responseData")?.optString("translatedText")?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }
        // Secondary online fallback; if both services fail the Norwegian source text is retained.
        return runCatching {
            val json = JSONArray(request(translationClient, "https://translate.googleapis.com/translate_a/single?client=gtx&sl=no&tl=en&dt=t&q=$q"))
            val first = json.optJSONArray(0)?.optJSONArray(0)?.optString(0)
            first?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun request(url: String): String = request(client, url)

    private fun request(httpClient: OkHttpClient, url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TheBrief/1.2 Android")
            .header("Accept", "application/json")
            .build()
        return httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                val snippet = runCatching { response.body?.string()?.take(200) }.getOrNull()
                error("HTTP ${response.code}${if (!snippet.isNullOrBlank()) " - $snippet" else ""}")
            }
            response.body?.string() ?: error("empty response")
        }
    }

    /** One quick retry on a transient failure before giving up - there is only one real base URL, so no host fallback. */
    private fun requestWithRetry(url: String): String {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return request(url)
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) Thread.sleep(400)
            }
        }
        throw lastError ?: IllegalStateException("Couldn't reach Politiloggen")
    }

    private fun parseMillis(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
}
