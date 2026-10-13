package com.rfsentinel.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.rfsentinel.app.util.applySystemBarInsets
import java.io.File
import java.io.InputStream

/**
 * The theme maker: pick an accent colour (and how the palette grows from it), light / dark / pure black, a font (the phone's
 * families, the DedSec Share Tech Mono, or a font file of your own), a banner picture, the animated header and title on the
 * home screen and the position marker. Light and dark previews update as you go; nothing is saved until you save.
 * A theme can be exported as a file, duplicated, or deleted here.
 */
class ThemeMakerActivity : AppCompatActivity() {

    companion object {
        /** The id of the custom theme to edit; none starts a new one. */
        const val EXTRA_ID = "id"
        private const val STATE = "theme"
        private const val STATE_NEW = "new"

        val accents = listOf(
            0xFF1E6FE0, 0xFF29B6F6, 0xFF0AA39C, 0xFF2E9E4F, 0xFF8BC34A, 0xFFFDD835, 0xFFFB8C00, 0xFFE53935,
            0xFFEC5C9B, 0xFF8E44D6, 0xFF3F51B5, 0xFF795548, 0xFF607D8B, 0xFFF3F4F6
        ).map { it.toInt() }

        fun headerLabel(s: ThemeHeaderView.Style) = when (s) {
            ThemeHeaderView.Style.NIGHT_VISION -> "Night vision"
            ThemeHeaderView.Style.FSOCIETY -> "Fsociety"
            ThemeHeaderView.Style.HUD -> "HUD"
            ThemeHeaderView.Style.CRT -> "CRT"
            else -> s.name.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()

    /** The theme as edited now; the banner and font picked here wait in memory until it is saved. */
    private lateinit var t: CustomTheme
    private var isNew = false
    private var newBanner: ByteArray? = null
    private var newFont: ByteArray? = null
    private var fontPreview: Typeface? = null
    private lateinit var startJson: String

    private lateinit var page: LinearLayout
    private lateinit var previewRow: LinearLayout
    private lateinit var bannerThumb: ImageView
    private lateinit var bannerRemove: MaterialButton
    private lateinit var fontGroup: RadioGroup
    private lateinit var markerSwitch: MaterialSwitch
    private lateinit var markerImage: ImageView
    private lateinit var markerButton: MaterialButton
    private lateinit var swatchRow: LinearLayout

    private val bannerPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bytes = runCatching { contentResolver.openInputStream(uri)!!.use { readLimited(it, CustomThemes.MAX_BANNER_BYTES) } }.getOrNull()
        if (bytes == null || !CustomThemes.isImage(bytes)) {
            Toast.makeText(this, "That isn't a picture (up to 4 MB)", Toast.LENGTH_LONG).show(); return@registerForActivityResult
        }
        newBanner = bytes; t = t.copy(banner = "banner")
        updateBanner(); updatePreview()
    }

    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { syncFontRadios(); return@registerForActivityResult }
        val bytes = runCatching { contentResolver.openInputStream(uri)!!.use { readLimited(it, CustomThemes.MAX_FONT_BYTES) } }.getOrNull()
        val tmp = File(cacheDir, "maker_font")
        val face = bytes?.let { runCatching { tmp.writeBytes(it); CustomThemes.loadFont(tmp) }.getOrNull() }
        if (bytes == null || face == null) {
            Toast.makeText(this, "That isn't a TrueType / OpenType font (.ttf or .otf, up to 6 MB)", Toast.LENGTH_LONG).show()
            syncFontRadios(); return@registerForActivityResult
        }
        newFont = bytes; fontPreview = face; t = t.copy(font = "file", fontFile = "font")
        syncFontRadios(); updatePreview()
    }

    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = runCatching {
            val saved = CustomThemes.get(this, t.id) ?: error("not saved")
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(CustomThemes.toShareJson(this, saved).toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        Toast.makeText(this, if (ok) "Theme file saved - share it, others can import it" else "Couldn't write the file", Toast.LENGTH_LONG).show()
    }

    private fun readLimited(s: InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = s.read(buf); if (n < 0) break
            out.write(buf, 0, n); if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val restored = savedInstanceState?.getString(STATE)?.let { runCatching { CustomTheme.fromJson(org.json.JSONObject(it)) }.getOrNull() }
        val existing = CustomThemes.get(this, id)
        isNew = savedInstanceState?.getBoolean(STATE_NEW) ?: (existing == null)
        t = restored ?: existing ?: CustomTheme(id = CustomThemes.newId(), name = CustomThemes.uniqueName(this, "My theme"))
        startJson = (existing ?: t).toJson().toString()
        fontPreview = FontApplier.typeface(this, t)

        title = if (isNew) "New theme" else "Edit theme"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16f), px(8f), px(16f), px(24f)) }
        scroll.addView(page)
        setContentView(scroll)
        scroll.applySystemBarInsets()
        build()
        updatePreview()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    override fun onSupportNavigateUp(): Boolean { leave(); return true }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE, t.toJson().toString()); outState.putBoolean(STATE_NEW, isNew)
    }

    private fun dirty() = newBanner != null || newFont != null || t.toJson().toString() != startJson

    private fun leave() {
        if (!dirty()) { finish(); return }
        AlertDialog.Builder(this).setTitle("Keep your changes?")
            .setPositiveButton("Save") { _, _ -> if (save() != null) finish() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Keep editing", null).show()
    }

    // ---- building the screen ---------------------------------------------------------------------

    private fun section(title: String, hint: String? = null) {
        page.addView(TextView(this).apply {
            text = title; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(22f), 0, px(2f))
        })
        if (hint != null) page.addView(note(hint))
    }

    private fun note(text: String) = TextView(this).apply { this.text = text; textSize = 12.5f; alpha = 0.75f; setPadding(0, 0, 0, px(6f)) }

    private fun button(text: String, onClick: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        this.text = text; setOnClickListener { onClick() }
    }

    private fun build() {
        // Name
        val name = EditText(this).apply {
            hint = "Theme name"; setText(t.name); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1; filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            doAfterTextChanged { t = t.copy(name = it?.toString().orEmpty()) }
        }
        page.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })

        // Previews
        previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        page.addView(previewRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        if (!CustomColors.available) page.addView(note("This phone is older than Android 11, which can't recolour apps: the colours stay standard here. The font, banner, header and marker still apply."))

        // Colours
        section("Colours", "Pick the main colour; the whole palette (buttons, cards, switches, backgrounds) grows from it, the way Android's Material You does.")
        swatchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(swatchRow) })
        updateSwatches()
        page.addView(button("Custom colour...") { pickColor() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(6f) })

        page.addView(TextView(this).apply { text = "How the palette grows"; setPadding(0, px(12f), 0, 0) })
        page.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@ThemeMakerActivity, android.R.layout.simple_spinner_dropdown_item, CustomColors.Scheme.entries.map { it.label })
            setSelection(CustomColors.Scheme.entries.indexOfFirst { it.key == t.scheme }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val k = CustomColors.Scheme.entries[pos].key
                    if (k != t.scheme) { t = t.copy(scheme = k); updatePreview() }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        })

        page.addView(TextView(this).apply { text = "Background"; setPadding(0, px(12f), 0, 0) })
        val modes = RadioGroup(this)
        CustomColors.Mode.entries.forEach { m ->
            modes.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = m.label; isChecked = m.key == t.mode
                setOnCheckedChangeListener { _, on -> if (on && t.mode != m.key) { t = t.copy(mode = m.key); updatePreview() } }
            })
        }
        page.addView(modes)
        page.addView(note("\"Follow the phone\" switches with Android's light / dark setting. Pure black saves battery on OLED screens."))

        // Font
        section("Font", "Used for the app's texts. System dialogs and the keyboard keep the phone's font.")
        fontGroup = RadioGroup(this)
        page.addView(fontGroup)
        buildFontRadios()
        page.addView(button("Choose a font file (.ttf / .otf)...") { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(4f) })

        // Banner
        section("Banner", "A picture across the top of the home screen (a logo or artwork you own; up to 4 MB). It stays on this phone and goes into a theme file you export.")
        bannerThumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; adjustViewBounds = true; visibility = View.GONE }
        page.addView(bannerThumb, LinearLayout.LayoutParams(-1, px(96f)))
        val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bRow.addView(button("Choose a picture...") { bannerPicker.launch("image/*") })
        bannerRemove = button("Remove") {
            newBanner = null; t = t.copy(banner = null); updateBanner(); updatePreview()
        }
        bRow.addView(bannerRemove, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8f) })
        page.addView(bRow)
        updateBanner()

        // Header
        section("Home screen header", "The animated header at the top of the home screen, and its title.")
        val styles = listOf<ThemeHeaderView.Style?>(null) + ThemeHeaderView.Style.entries
        page.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@ThemeMakerActivity, android.R.layout.simple_spinner_dropdown_item, styles.map { it?.let(::headerLabel) ?: "None (just the banner)" })
            setSelection(styles.indexOfFirst { it?.name == t.header }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val n = styles[pos]?.name
                    if (n != t.header) { t = t.copy(header = n); updatePreview() }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        })
        page.addView(EditText(this).apply {
            hint = "Title (leave empty for \"RF Sentinel\")"; setText(t.title.orEmpty()); maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            doAfterTextChanged { t = t.copy(title = it?.toString()?.takeIf { s -> s.isNotBlank() }); updatePreview() }
        })

        // Marker
        section("Position marker", "The arrow or car on the map. Off: the map uses the marker chosen in Settings.")
        markerSwitch = MaterialSwitch(this).apply { showText = false;
            text = "This theme has its own marker"; isChecked = t.markerStyle != null
            setOnCheckedChangeListener { _, on ->
                t = if (on) t.copy(markerStyle = t.markerStyle ?: "arrow", markerColor = t.markerColor ?: "blue", markerSize = t.markerSize ?: 1.0f)
                    else t.copy(markerStyle = null, markerColor = null, markerSize = null)
                updateMarker()
            }
        }
        page.addView(markerSwitch)
        val mRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        markerImage = ImageView(this)
        mRow.addView(markerImage, LinearLayout.LayoutParams(px(48f), px(72f)))
        markerButton = button("Choose the marker...") {
            val start = MarkerPicker.Choice(t.markerStyle ?: "arrow", t.markerColor ?: "blue", t.markerSize ?: 1.0f)
            MarkerPicker.pick(this, start) { c -> t = t.copy(markerStyle = c.style, markerColor = c.color, markerSize = c.size); updateMarker() }
        }
        mRow.addView(markerButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(12f) })
        page.addView(mRow)
        updateMarker()

        // Actions
        section("Save")
        page.addView(MaterialButton(this).apply { text = "Save and use this theme"; setOnClickListener { useIt() } }, LinearLayout.LayoutParams(-1, -2))
        page.addView(button("Save") { if (save() != null) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }, LinearLayout.LayoutParams(-1, -2))
        page.addView(button("Save and export as a file...") {
            val s = save() ?: return@button
            exporter.launch("rf-sentinel-theme-" + s.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "theme" } + ".json")
        }, LinearLayout.LayoutParams(-1, -2))
        page.addView(button("Duplicate") { duplicate() }, LinearLayout.LayoutParams(-1, -2))
        if (!isNew) page.addView(button("Delete this theme") { confirmDelete() }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun buildFontRadios() {
        fontGroup.removeAllViews()
        for (f in FontApplier.families) {
            fontGroup.addView(RadioButton(this).apply {
                id = View.generateViewId(); tag = f.key; text = f.label; textSize = 16f
                typeface = FontApplier.typeface(this@ThemeMakerActivity, CustomTheme("p", "p", font = f.key))
                isChecked = t.font == f.key
                setOnCheckedChangeListener { _, on -> if (on && t.font != f.key) { t = t.copy(font = f.key, fontFile = null); fontPreview = FontApplier.typeface(this@ThemeMakerActivity, t); updatePreview() } }
            })
        }
        fontGroup.addView(RadioButton(this).apply {
            id = View.generateViewId(); tag = "file"; text = if (newFont != null || t.fontFile != null) "My own font file" else "My own font file (none chosen yet)"; textSize = 16f
            fontPreview?.takeIf { t.font == "file" }?.let { typeface = it }
            isChecked = t.font == "file"
            setOnCheckedChangeListener { _, on ->
                if (on && t.font != "file") {
                    if (newFont != null || t.fontFile != null) { t = t.copy(font = "file"); updatePreview() }
                    else fontPicker.launch(arrayOf("*/*"))
                }
            }
        })
    }

    /** After a font file was chosen (or the choice was cancelled): the radios show the real choice. */
    private fun syncFontRadios() = buildFontRadios()

    private fun updateSwatches() {
        swatchRow.removeAllViews()
        val all = accents + if (t.accent in accents) emptyList() else listOf(t.accent)
        for (c in all) {
            val on = c == t.accent
            swatchRow.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c)
                    setStroke(px(if (on) 4f else 1f), if (on) MaterialColors.getColor(this@ThemeMakerActivity, com.google.android.material.R.attr.colorOnSurface, Color.WHITE) else 0x55888888)
                }
                contentDescription = "Accent colour"
                setOnClickListener { t = t.copy(accent = c); updateSwatches(); updatePreview() }
            }, LinearLayout.LayoutParams(px(40f), px(40f)).apply { marginEnd = px(8f); topMargin = px(6f); bottomMargin = px(6f) })
        }
    }

    // ---- previews ----------------------------------------------------------------------------------

    private fun bannerBitmap(): Bitmap? {
        if (t.banner == null) return null
        val bytes = newBanner ?: CustomThemes.get(this, t.id)?.let { CustomThemes.bannerFile(this, it)?.readBytes() } ?: return null
        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) }.getOrNull()
    }

    private fun updateBanner() {
        val bmp = bannerBitmap()
        bannerThumb.visibility = if (bmp != null) View.VISIBLE else View.GONE
        bannerThumb.setImageBitmap(bmp)
        bannerRemove.isEnabled = t.banner != null
    }

    private fun updateMarker() {
        val own = t.markerStyle != null
        markerSwitch.isChecked = own
        markerButton.isEnabled = own
        markerImage.alpha = if (own) 1f else 0.3f
        val c = MarkerPicker.Choice(t.markerStyle ?: "arrow", t.markerColor ?: "blue", t.markerSize ?: 1.0f)
        markerImage.setImageBitmap(MarkerArt.render(MarkerArt.Style.of(c.style), MarkerArt.colorOf(c.color).argb, px(72f * c.size.coerceIn(0.6f, 1.4f) / 1.4f)))
    }

    private fun updatePreview() {
        previewRow.removeAllViews()
        for (dark in listOf(false, true)) {
            previewRow.addView(preview(dark), LinearLayout.LayoutParams(0, -2, 1f).apply { if (dark) marginStart = px(8f) })
        }
    }

    /** One miniature of the home screen in this theme's colours, light or dark. */
    private fun preview(dark: Boolean): View {
        val ctx = this
        val p = CustomColors.palette(this, t.copy(mode = if (!dark) "light" else if (t.mode == "black") "black" else "dark"), dark)
        val bg = p["background"] ?: if (dark) Color.BLACK else Color.WHITE
        val onBg = p["on_surface"] ?: if (dark) Color.WHITE else Color.BLACK
        val variant = p["on_surface_variant"] ?: onBg
        val primary = p["primary"] ?: t.accent
        val onPrimary = p["on_primary"] ?: Color.WHITE
        val face = fontPreview.takeIf { t.font != "default" }
        fun text(s: String, size: Float, bold: Boolean = false, c: Int = onBg) = TextView(ctx).apply {
            text = s; textSize = size; setTextColor(c)
            setTypeface(face ?: Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(px(8f), px(8f), px(8f), px(10f))
        }
        root.addView(text(if (dark) "Dark" else "Light", 11f, c = variant))
        bannerBitmap()?.let { b ->
            root.addView(ImageView(ctx).apply { setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(-1, px(40f)).apply { topMargin = px(4f) })
        }
        root.addView(text(t.title?.takeIf { it.isNotBlank() } ?: "RF Sentinel", 17f, bold = true, c = primary).apply { setPadding(0, px(4f), 0, px(4f)) })
        val card = MaterialCardView(ctx).apply {
            radius = px(12f).toFloat(); cardElevation = 0f
            setCardBackgroundColor(p["surface_container"] ?: bg); strokeWidth = 0
        }
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10f), px(8f), px(10f), px(8f)) }
        inner.addView(text("Axon body camera", 14f, bold = true))
        inner.addView(text("Probable - 12 m away", 12f, c = variant))
        card.addView(inner)
        root.addView(card, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, px(8f), 0, 0) }
        row.addView(MaterialButton(ctx).apply {
            text = "Scan"; textSize = 12f; typeface = face ?: Typeface.DEFAULT; isAllCaps = false; minHeight = 0; minimumHeight = 0
            insetTop = 0; insetBottom = 0; isClickable = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(primary); setTextColor(onPrimary)
            strokeWidth = 0; iconTint = android.content.res.ColorStateList.valueOf(onPrimary)
        })
        row.addView(MaterialSwitch(ctx).apply {
            showText = false; isChecked = true; isClickable = false
            trackTintList = android.content.res.ColorStateList.valueOf(primary)
            thumbTintList = android.content.res.ColorStateList.valueOf(onPrimary)
            trackDecorationTintList = android.content.res.ColorStateList.valueOf(primary)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6f) })
        row.addView(android.widget.ImageView(ctx).apply {
            // The tertiary and container colours as two small chips.
            setImageDrawable(android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(p["tertiary"] ?: primary) })
        }, LinearLayout.LayoutParams(px(16f), px(16f)).apply { marginStart = px(6f) })
        row.addView(android.widget.ImageView(ctx).apply {
            setImageDrawable(android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(p["primary_container"] ?: primary) })
        }, LinearLayout.LayoutParams(px(16f), px(16f)).apply { marginStart = px(4f) })
        root.addView(row)
        val clip = MaterialCardView(this).apply {
            radius = px(14f).toFloat(); cardElevation = 0f; strokeWidth = px(1f)
            strokeColor = MaterialColors.getColor(this@ThemeMakerActivity, com.google.android.material.R.attr.colorOutlineVariant, 0x55888888)
            addView(root)
        }
        return clip
    }

    // ---- colour dialog ---------------------------------------------------------------------------

    private fun pickColor() {
        val hsv = FloatArray(3).also { Color.colorToHSV(t.accent, it) }
        var changing = false
        val swatch = View(this)
        val hex = EditText(this).apply { hint = "#RRGGBB"; maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT; filters = arrayOf(android.text.InputFilter.LengthFilter(7)) }
        fun current() = Color.HSVToColor(hsv)
        fun bars(max: Int, start: Int, onChange: (Int) -> Unit): SeekBar {
            val sb = SeekBar(this).apply {
                this.max = max; progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            return sb
        }
        lateinit var h: SeekBar; lateinit var s: SeekBar; lateinit var v: SeekBar
        fun refresh(fromHex: Boolean = false) {
            changing = true
            swatch.setBackgroundColor(current())
            if (!fromHex) hex.setText(String.format("#%06X", current() and 0xFFFFFF))
            changing = false
        }
        h = bars(360, hsv[0].toInt()) { hsv[0] = it.toFloat(); refresh() }
        s = bars(100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f; refresh() }
        v = bars(100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f; refresh() }
        hex.doAfterTextChanged { e ->
            if (changing) return@doAfterTextChanged
            val c = runCatching { Color.parseColor(e.toString().let { if (it.startsWith("#")) it else "#$it" }) }.getOrNull() ?: return@doAfterTextChanged
            Color.colorToHSV(c, hsv)
            h.progress = hsv[0].toInt(); s.progress = (hsv[1] * 100).toInt(); v.progress = (hsv[2] * 100).toInt()
            refresh(fromHex = true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(12f), px(20f), 0)
            addView(swatch, LinearLayout.LayoutParams(-1, px(48f)))
            addView(TextView(context).apply { text = "Hue" }); addView(h)
            addView(TextView(context).apply { text = "Saturation" }); addView(s)
            addView(TextView(context).apply { text = "Brightness" }); addView(v)
            addView(hex)
        }
        refresh()
        AlertDialog.Builder(this).setTitle("Custom colour").setView(box)
            .setPositiveButton("Use") { _, _ -> t = t.copy(accent = current() or 0xFF000000.toInt()); updateSwatches(); updatePreview() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- saving ------------------------------------------------------------------------------------

    /** Saves the theme (its picture and font files too); returns it, or null when it can't be saved. */
    private fun save(): CustomTheme? {
        if (isNew && CustomThemes.all(this).size >= CustomThemes.MAX_THEMES) {
            Toast.makeText(this, "That's ${CustomThemes.MAX_THEMES} themes - delete one first", Toast.LENGTH_LONG).show(); return null
        }
        val stored = CustomThemes.get(this, t.id)
        var out = t.copy(name = CustomThemes.uniqueName(this, t.name, t.id))
        // Banner
        val bytes = newBanner
        out = when {
            t.banner == null -> { stored?.let { CustomThemes.clearBanner(this, it) }; out.copy(banner = null) }
            bytes != null -> CustomThemes.setBanner(this, out.copy(banner = stored?.banner), bytes) ?: out.copy(banner = null)
            stored?.banner != null && CustomThemes.bannerFile(this, stored) != null -> out.copy(banner = stored.banner)
            else -> out.copy(banner = null)
        }
        // Font
        val fontBytes = newFont
        out = when {
            out.font != "file" -> { stored?.let { CustomThemes.clearFont(this, it) }; out.copy(fontFile = null) }
            fontBytes != null -> CustomThemes.setFont(this, out.copy(fontFile = stored?.fontFile), fontBytes) ?: CustomThemes.clearFont(this, out)
            stored?.fontFile != null && CustomThemes.fontFile(this, stored) != null -> out.copy(fontFile = stored.fontFile)
            else -> out.copy(font = "default", fontFile = null)
        }
        CustomThemes.save(this, out)
        t = out; newBanner = null; newFont = null; isNew = false
        startJson = out.toJson().toString()
        // If it is the theme in use, open screens restyle themselves.
        if (ThemeManager.custom(this)?.id == out.id) ThemeManager.refresh(this)
        return out
    }

    private fun useIt() {
        val s = save() ?: return
        ThemeManager.setCustom(this, s.id)
        Toast.makeText(this, "\"${s.name}\" is on", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun duplicate() {
        val s = save() ?: return
        val copy = CustomThemes.duplicate(this, s)
        Toast.makeText(this, "Duplicated as \"${copy.name}\"", Toast.LENGTH_SHORT).show()
        startActivity(android.content.Intent(this, ThemeMakerActivity::class.java).putExtra(EXTRA_ID, copy.id))
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this).setTitle("Delete \"${t.name}\"?")
            .setMessage("Its banner and font go too. Export it first if you want to keep a copy.")
            .setPositiveButton("Delete") { _, _ ->
                val wasUsed = ThemeManager.custom(this)?.id == t.id
                if (wasUsed) ThemeManager.set(this, ThemeManager.AppTheme.SYSTEM)
                CustomThemes.delete(this, t.id)
                newBanner = null; newFont = null; startJson = t.toJson().toString()
                finish()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
