package com.rfsentinel.app.nav

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.R
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.ChipStyle
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Where to? A search with suggestions as you type, Home and Work, starred places and recent
 * destinations. Returns the chosen [Place] to whoever opened it ([EXTRA_NAME] ... as the result).
 *
 * Your own places that fit what you type are there at once. Network use: what you type (after a short pause,
 * from 3 characters) goes to Photon with your position rounded to about 1 km; a search you submit (the
 * keyboard's search key, or the "Search for" row) also asks OpenStreetMap's Nominatim, and whichever answers
 * first is shown while the other is merged in. Only while this screen is open; a newer search stops the
 * older one's request.
 */
class NavSearchActivity : AppCompatActivity() {

    companion object {
        /** What the choice is for: "go" (a destination), "stop" (a stop on the way), "home" or "work" (save it there). */
        const val EXTRA_MODE = "mode"
        const val MODE_GO = "go"
        const val MODE_STOP = "stop"
        const val MODE_HOME = "home"
        const val MODE_WORK = "work"
        const val EXTRA_NAME = "name"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        private const val DEBOUNCE_MS = 250L
        /** Once one of the two servers has answered a submitted search, the other gets this long. */
        private const val SUBMIT_GRACE_MS = 6_000L

        fun intent(context: Context, mode: String = MODE_GO) =
            Intent(context, NavSearchActivity::class.java).putExtra(EXTRA_MODE, mode)

        /** The place a search result carries, or null. */
        fun placeOf(data: Intent?): Place? {
            val d = data ?: return null
            if (!d.hasExtra(EXTRA_LAT) || !d.hasExtra(EXTRA_LON)) return null
            return Place(d.getStringExtra(EXTRA_NAME).orEmpty(), d.getStringExtra(EXTRA_ADDRESS).orEmpty(),
                d.getDoubleExtra(EXTRA_LAT, 0.0), d.getDoubleExtra(EXTRA_LON, 0.0))
        }
    }

    private val mode get() = intent.getStringExtra(EXTRA_MODE) ?: MODE_GO
    private lateinit var input: EditText
    private lateinit var list: LinearLayout
    private lateinit var note: TextView
    private var searchJob: Job? = null
    private var me: Location? = null
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = when (mode) {
            MODE_STOP -> "Add a stop"; MODE_HOME -> "Set Home"; MODE_WORK -> "Set Work"; else -> "Where to?"
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        me = currentFix()
        if (me == null) askForFix()

        input = EditText(this).apply {
            hint = when (mode) { MODE_HOME -> "Home address"; MODE_WORK -> "Work address"; else -> "Search a place or address" }
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(px(14), px(12), px(14), px(12))
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { search(text.toString(), submit = true); true } else false }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = search(s?.toString().orEmpty(), submit = false)
            })
        }
        note = TextView(this).apply { textSize = 13f; setPadding(px(16), px(8), px(16), px(8)); visibility = View.GONE }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(px(12), px(8), px(12), px(4)) })
            addView(note)
            addView(ScrollView(this@NavSearchActivity).apply { isFillViewport = true; addView(list) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(column)
        column.applySystemBarInsets()
        showStart()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ---- what to show ------------------------------------------------------------------------------

    /** Nothing typed: Home and Work, starred places, recent destinations. */
    private fun showStart() {
        list.removeAllViews()
        note.visibility = View.GONE
        if (mode == MODE_GO || mode == MODE_STOP) {
            val home = Prefs.navHome(this); val work = Prefs.navWork(this)
            list.addView(row(R.drawable.ic_nav_home, "Home", home?.let { it.address.ifBlank { it.name } } ?: "Tap to set your home address",
                home, star = false, onTap = { if (home != null) choose(home) else startActivityForResult(intent(this, MODE_HOME), REQ_HOME) },
                onMore = { startActivityForResult(intent(this, MODE_HOME), REQ_HOME) }))
            list.addView(row(R.drawable.ic_nav_work, "Work", work?.let { it.address.ifBlank { it.name } } ?: "Tap to set your work address",
                work, star = false, onTap = { if (work != null) choose(work) else startActivityForResult(intent(this, MODE_WORK), REQ_WORK) },
                onMore = { startActivityForResult(intent(this, MODE_WORK), REQ_WORK) }))
            categories("Nearby") { categorySearch(it, along = false) }
            if (Navigator.active && Navigator.state != null) categories("Along your route") { categorySearch(it, along = true) }
            section("Starred", Prefs.navFavorites(this), R.drawable.ic_nav_star, rename = true)
            section("Recent", Prefs.recentDestinations(this), R.drawable.ic_nav_history)
        }
        if (list.childCount == 0) setNote("Type at least ${Places.MIN_QUERY} letters to search.")
    }

    private fun header(title: String) = list.addView(TextView(this).apply {
        text = title; textSize = 12f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(px(16), px(14), px(16), px(4)); alpha = 0.7f
    })

    private fun section(title: String, places: List<Place>, icon: Int, rename: Boolean = false) {
        if (places.isEmpty()) return
        header(title)
        places.forEach { p -> list.addView(placeRow(p, icon).also { if (rename) addRename(it as LinearLayout, p) }) }
    }

    /** A row of kinds of place to look for (petrol, food, charging...). */
    private fun categories(title: String, onPick: (PlaceCategories.Category) -> Unit) {
        header(title)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in PlaceCategories.Category.entries) {
            row.addView(com.rfsentinel.app.ui.FitChip(this).apply {
                text = c.label
                setEnsureMinTouchTargetSize(false)
                ChipStyle.apply(this)
                setOnClickListener { onPick(c) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(6) })
        }
        list.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(px(12), 0, px(12), 0)
            addView(row)
        })
    }

    /** Places of one kind near you, or ahead along the route you are following. */
    private fun categorySearch(c: PlaceCategories.Category, along: Boolean) {
        val route = Navigator.route
        val from = Navigator.state?.alongM
        if (along && (route == null || from == null)) return
        searchJob?.cancel()
        list.removeAllViews()
        setNote(if (along) "Looking for ${c.label.lowercase()} along your route..." else "Looking for ${c.label.lowercase()} near you...")
        searchJob = lifecycleScope.launch {
            // Near you needs your position: give a fix just asked for a few seconds to arrive.
            var waited = 0
            while (!along && me == null && waited < 10_000) { delay(250); waited += 250 }
            val here = me
            if (!along && here == null) { setNote("Can't tell where you are yet - is location on?"); return@launch }
            val found = attempt {
                withContext(Dispatchers.IO) {
                    // The public servers are sometimes overloaded: after half a minute say so, rather than wait on.
                    kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    val query = if (along) PlaceCategories.alongQuery(c, PlaceCategories.routeSample(route!!, from!!))
                        else PlaceCategories.nearQuery(c, here!!.latitude, here.longitude)
                    val json = com.rfsentinel.app.alpr.AlprStore.fetchFirst("data=" + java.net.URLEncoder.encode(query, "UTF-8")) { }
                    val places = PlaceCategories.parse(json, c)
                    if (along) PlaceCategories.rankAlong(places, c, route!!, from!!) else PlaceCategories.rankNear(places, c, here!!.latitude, here.longitude)
                    } ?: throw ServersBusy()
                }
            }
            list.removeAllViews()
            found.onSuccess { hits ->
                if (hits.isEmpty()) setNote("No ${c.label.lowercase()} found ${if (along) "along your route" else "within ${NavFormat.distance(PlaceCategories.NEAR_RADIUS_M.toDouble(), NavFormat.miles(this@NavSearchActivity))} of you"}.")
                else { note.visibility = View.GONE; hits.take(25).forEach { list.addView(hitRow(it)) } }
            }.onFailure {
                setNote(if (it is ServersBusy || it.message?.contains("busy") == true)
                    "OpenStreetMap's servers are busy right now - try again in a moment." else "Search failed - ${it.message ?: "no connection?"}")
            }
        }
    }

    /** No answer from the public servers within the time allowed. */
    private class ServersBusy : Exception("busy")

    private fun hitRow(h: PlaceCategories.Hit): View {
        val miles = NavFormat.miles(this)
        val how = if (h.aheadM != null) "${NavFormat.distance(h.aheadM, miles)} ahead · ${NavFormat.distance(h.offM ?: 0.0, miles)} off the road"
            else h.distanceM?.let { NavFormat.distance(it, miles) }.orEmpty()
        return row(R.drawable.ic_nav_place, h.place.name, listOf(how, h.place.address).filter { it.isNotBlank() }.joinToString(" · "), h.place,
            star = true, onTap = { choose(h.place) }, onMore = null)
    }

    private fun placeRow(p: Place, icon: Int) = row(icon, p.name, listOf(distanceFrom(p), p.address).filter { it.isNotBlank() }.joinToString(" · "), p,
        star = true, onTap = { choose(p) }, onMore = null)

    private fun distanceFrom(p: Place): String {
        val here = me ?: return ""
        return NavFormat.distance(DeviceRegistry.metersBetween(here.latitude, here.longitude, p.lat, p.lon), NavFormat.miles(this))
    }

    /** One line: an icon, two lines of text, and (for a place) a star to keep it. */
    private fun row(icon: Int, title: String, sub: String, place: Place?, star: Boolean, onTap: () -> Unit, onMore: (() -> Unit)?): View {
        val (accent, _) = ChipStyle.accent(this)
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(10), px(8), px(10))
            isClickable = true; isFocusable = true
            val out = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) setBackgroundResource(out.resourceId)
            setOnClickListener { onTap() }
        }
        v.addView(ImageView(this).apply { setImageResource(icon); setColorFilter(accent) }, LinearLayout.LayoutParams(px(24), px(24)).apply { marginEnd = px(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 16f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub.isNotBlank()) texts.addView(TextView(this).apply { text = sub; textSize = 13f; alpha = 0.7f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        v.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (star && place != null) {
            v.addView(starButton(place), LinearLayout.LayoutParams(px(44), px(44)))
        } else if (onMore != null && place != null) {
            v.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_settings); setColorFilter(ChipStyle.onSurface(this@NavSearchActivity)); alpha = 0.6f
                background = null; contentDescription = "Change"
                setOnClickListener { onMore() }
            }, LinearLayout.LayoutParams(px(44), px(44)))
        }
        return v
    }

    /** A pencil before a starred place's star, to give it your own name. */
    private fun addRename(row: LinearLayout, p: Place) {
        row.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_nav_edit); setColorFilter(ChipStyle.onSurface(this@NavSearchActivity)); alpha = 0.6f
            background = null; contentDescription = "Rename"
            setOnClickListener { RenamePlace.ask(this@NavSearchActivity, p) { showStart() } }
        }, row.childCount - 1, LinearLayout.LayoutParams(px(44), px(44)))
        row.setOnLongClickListener { RenamePlace.ask(this, p) { showStart() }; true }
    }

    private fun starButton(p: Place) = ImageButton(this).apply {
        val (accent, _) = ChipStyle.accent(this@NavSearchActivity)
        fun style() {
            val on = Prefs.isNavFavorite(this@NavSearchActivity, p)
            setImageResource(if (on) R.drawable.ic_nav_star else R.drawable.ic_nav_star_border)
            setColorFilter(if (on) accent else ChipStyle.onSurface(this@NavSearchActivity))
            alpha = if (on) 1f else 0.6f
            contentDescription = if (on) "Starred - tap to remove" else "Star this place"
        }
        background = null
        style()
        setOnClickListener { Prefs.toggleNavFavorite(this@NavSearchActivity, p); style() }
    }

    private fun setNote(text: String) { note.text = text; note.visibility = View.VISIBLE }

    // ---- searching ---------------------------------------------------------------------------------

    private val userAgent get() = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"

    /** Answers already fetched (this screen only): backing over a word you typed costs nothing. */
    private val cache = object : LinkedHashMap<String, List<Place>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Place>>?) = size > 40
    }

    /** What the servers last answered: a longer query starts from what of it still fits, while its own answer comes. */
    private var lastResults: List<Place> = emptyList()

    /** Your own places that fit the words - there at once, before any server answers. */
    private fun mine(q: String): List<Place> =
        if (mode != MODE_GO && mode != MODE_STOP) emptyList()
        else PlaceRank.mine(q, Prefs.navHome(this), Prefs.navWork(this), Prefs.navFavorites(this), Prefs.recentDestinations(this))

    private fun search(text: String, submit: Boolean) {
        val q = text.trim()
        searchJob?.cancel()
        if (q.isEmpty()) { lastResults = emptyList(); showStart(); return }
        // "48.137, 11.575" is a place already.
        PlaceRank.coordinates(q)?.let { showPlaces(q, emptyList(), listOf(it), null, canSubmit = false); return }

        val here = me
        val la = here?.latitude; val lo = here?.longitude
        val key = PlaceRank.cacheKey(q, la, lo)
        val submitted = cache["s|$key"]
        val done = submitted ?: if (!submit) cache[key] else null
        val own = mine(q)
        // On screen at once: this search's earlier answer, else what an earlier (shorter) search found that still fits.
        val carry = done ?: PlaceRank.rank(q, PlaceRank.stillMatching(q, lastResults), la, lo)
        val ask = done == null && (submit || q.length >= Places.MIN_QUERY)
        val canSubmit = submitted == null && q.length >= Places.MIN_QUERY
        showPlaces(q, own, carry, if (ask) "Searching..." else null, canSubmit)
        if (done != null) lastResults = done
        if (!ask) return

        searchJob = lifecycleScope.launch {
            if (!submit) delay(DEBOUNCE_MS)
            try {
                if (submit) {
                    var latest = carry
                    Places.searchSubmitted(q, la, lo, userAgent, SUBMIT_GRACE_MS) { merged ->
                        latest = merged
                        // Nothing yet from the first answer: keep saying so while the other one is awaited.
                        showPlaces(q, own, merged, if (merged.isEmpty()) "Searching..." else null, canSubmit = false)
                    }
                    cache["s|$key"] = latest; lastResults = latest
                    showPlaces(q, own, latest, null, canSubmit = false)
                } else {
                    val ranked = PlaceRank.rank(q, PlaceRank.dedupe(Places.searchPhoton(q, la, lo, userAgent)), la, lo)
                    cache[key] = ranked; lastResults = ranked
                    showPlaces(q, own, ranked, null, canSubmit = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showPlaces(q, own, carry, "Search failed - no connection? The search key tries again.", canSubmit = true)
            }
        }
    }

    /** The list for [q]: your own places, then [results] (each place once), then the "Search for" row; [status] on top when given. */
    private fun showPlaces(q: String, own: List<Place>, results: List<Place>, status: String?, canSubmit: Boolean) {
        list.removeAllViews()
        own.forEach { list.addView(placeRow(it, iconOf(it))) }
        val others = results.filter { r -> own.none { it.lat == r.lat && it.lon == r.lon } }
        others.forEach { list.addView(placeRow(it, R.drawable.ic_nav_place)) }
        val shown = own.size + others.size
        if (canSubmit) {
            list.addView(row(R.drawable.ic_search, "Search for \u201c$q\u201d", "Look in more places", null, star = false,
                onTap = { search(input.text.toString(), submit = true) }, onMore = null))
        }
        when {
            status != null -> setNote(status)
            shown == 0 && q.length < Places.MIN_QUERY -> setNote("Type at least ${Places.MIN_QUERY} letters to search.")
            shown == 0 -> setNote("Nothing found for \"$q\".")
            else -> note.visibility = View.GONE
        }
    }

    private fun iconOf(p: Place) = when {
        p.name == "Home" -> R.drawable.ic_nav_home
        p.name == "Work" -> R.drawable.ic_nav_work
        Prefs.isNavFavorite(this, p) -> R.drawable.ic_nav_star
        else -> R.drawable.ic_nav_history
    }

    // ---- choosing ----------------------------------------------------------------------------------

    private val REQ_HOME = 11
    private val REQ_WORK = 12

    private fun choose(p: Place) {
        when (mode) {
            MODE_HOME -> { Prefs.setNavHome(this, p); Toast.makeText(this, "Home saved", Toast.LENGTH_SHORT).show() }
            MODE_WORK -> { Prefs.setNavWork(this, p); Toast.makeText(this, "Work saved", Toast.LENGTH_SHORT).show() }
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_MODE, mode).putExtra(EXTRA_NAME, p.name).putExtra(EXTRA_ADDRESS, p.address)
            .putExtra(EXTRA_LAT, p.lat).putExtra(EXTRA_LON, p.lon))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        // Home / Work was just set from here: show it, or go there when this search is for a destination.
        if (resultCode != RESULT_OK || requestCode !in listOf(REQ_HOME, REQ_WORK)) return
        val p = placeOf(data) ?: return
        if (mode == MODE_GO || mode == MODE_STOP) choose(p) else showStart()
    }

    /** No position known yet (the phone has not used GPS lately): ask for one now, so a "near me" search has something to go on. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun askForFix() {
        if (!Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !Permissions.granted(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return
        val noCancel: android.os.CancellationSignal? = null
        runCatching {
            androidx.core.location.LocationManagerCompat.getCurrentLocation(lm, provider, noCancel, androidx.core.content.ContextCompat.getMainExecutor(this),
                androidx.core.util.Consumer<Location?> { l -> if (l != null && me == null) me = l })
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun currentFix(): Location? {
        ScanForegroundService.lastFix?.let { return it }
        if (!Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !Permissions.granted(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = getSystemService(LocationManager::class.java) ?: return null
        return runCatching { lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } }.getOrNull()
    }
}
