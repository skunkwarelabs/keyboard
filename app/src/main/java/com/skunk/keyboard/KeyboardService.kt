package com.skunk.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

/**
 * A soft keyboard (IME) with an iOS-style layout, Material You theme, and glide
 * (swipe) typing.
 *
 * Three pages mirror Apple's keyboard — letters, `123` numbers, and `#+=` symbols —
 * switched via the 123 / #+= / ABC keys. Colors come live from the system Material
 * You palette on Android 12+. The letter B always types as 🅱️.
 *
 * Swipe typing has no stock dictionary to lean on, so it ships its own
 * frequency-ranked wordlist (assets/words.txt) and decodes a gesture by comparing
 * its resampled path against each candidate word's ideal key-path (a SHARK²-style
 * shape match), pruned to words that share the swipe's first and last letter.
 */
class KeyboardService : InputMethodService() {

    private enum class Page { LETTERS, NUMBERS, SYMBOLS, EMOJI }

    private enum class Shift { OFF, ONE_SHOT, LOCK }

    /** A single key: an [id] used in logic, plus its flex [weight] in the row. */
    private class Key(val id: String, val weight: Float = 1f)

    private val spacerId = "·spacer"
    // U+1F171 (squared B) + U+FE0F (emoji presentation selector). Spelled out so the
    // variation selector is never lost in source encoding — that's what forces the
    // colorful emoji glyph instead of the plain text-style box.
    private val bEmoji = "🅱️"

    private fun letters(vararg s: String) = s.map { Key(it) }

    // --- Apple-style page layouts ---------------------------------------------

    private val lettersRows = listOf(
        letters("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf(Key(spacerId, 0.5f)) +
            letters("a", "s", "d", "f", "g", "h", "j", "k", "l") +
            listOf(Key(spacerId, 0.5f)),
        listOf(Key("⇧", 1.5f)) + letters("z", "x", "c", "v", "b", "n", "m") + listOf(Key("⌫", 1.5f)),
        listOf(Key("NUM", 2f), Key("EMOJI"), Key("space", 5f), Key("return", 2f)),
    )

    private val numbersRows = listOf(
        letters("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        letters("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
        listOf(Key("SYM", 2f)) + listOf(".", ",", "?", "!", "'").map { Key(it, 1.2f) } + listOf(Key("⌫", 2f)),
        listOf(Key("ABC", 2f), Key("EMOJI"), Key("space", 5f), Key("return", 2f)),
    )

    private val symbolsRows = listOf(
        letters("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
        letters("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"),
        listOf(Key("NUM", 2f)) + listOf(".", ",", "?", "!", "'").map { Key(it, 1.2f) } + listOf(Key("⌫", 2f)),
        listOf(Key("ABC", 2f), Key("EMOJI"), Key("space", 5f), Key("return", 2f)),
    )

    private val functionKeys = setOf("⇧", "⌫", "NUM", "SYM", "ABC", "EMOJI", "QCLOSE", "QDEL")

    private var page = Page.LETTERS
    private var emojiSearch = false
    private val emojiQuery = StringBuilder()
    private var shiftState = Shift.OFF
    private var lastShiftTap = 0L
    private var enterIsAction = false

    private lateinit var palette: Palette
    private var shiftKey: Button? = null
    private var keyboardRoot: LinearLayout? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var backspaceRepeat: Runnable? = null
    private var backspaceCount = 0

    /** Resolved keyboard colors. */
    private class Palette(
        val background: Int, val key: Int, val keyText: Int,
        val special: Int, val specialText: Int, val accent: Int, val accentText: Int,
    )

    /** Frequency-ranked word list (file order = frequency), lowercase a–z only. */
    private val words: List<String> by lazy {
        runCatching {
            assets.open("words.txt").bufferedReader().useLines { seq ->
                seq.map { it.trim().lowercase() }
                    .filter { it.length in 2..18 && it.all { c -> c in 'a'..'z' } }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    override fun onCreateInputView(): View = buildKeyboardView()

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        enterIsAction = action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        shiftState = Shift.OFF
        emojiSearch = false
        emojiQuery.clear()
        if (page != Page.LETTERS) { page = Page.LETTERS; setInputView(buildKeyboardView()) }
    }

    private fun buildKeyboardView(): View {
        palette = resolvePalette()
        shiftKey = null
        if (page == Page.EMOJI) { keyboardRoot = null; return buildEmojiView() }
        val root = KeyboardLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(8))
            setBackgroundColor(palette.background)
        }
        rowsFor(page).forEach { row -> root.addView(buildRow(row)) }
        keyboardRoot = root
        return root
    }

    private fun rowsFor(p: Page) = when (p) {
        Page.LETTERS -> lettersRows
        Page.NUMBERS -> numbersRows
        Page.SYMBOLS -> symbolsRows
        Page.EMOJI -> lettersRows  // unused; emoji page builds its own view
    }

    private class EmojiItem(val emoji: String, val name: String)
    private class Cell(val header: String?, val emoji: String?)

    /** Parsed assets/emojis.txt → ordered list of (category, items). */
    private val emojiData: List<Pair<String, List<EmojiItem>>> by lazy {
        val cats = ArrayList<Pair<String, MutableList<EmojiItem>>>()
        runCatching {
            assets.open("emojis.txt").bufferedReader().forEachLine { line ->
                if (line.startsWith("#")) {
                    cats.add(line.substring(1).trim() to ArrayList())
                } else {
                    val tab = line.indexOf('\t')
                    if (tab > 0) cats.lastOrNull()?.second?.add(
                        EmojiItem(line.substring(0, tab), line.substring(tab + 1))
                    )
                }
            }
        }
        cats.filter { it.second.isNotEmpty() }
    }

    private fun buildEmojiView(): View =
        if (emojiSearch) buildEmojiSearchView() else buildEmojiGridView()

    /** Horizontally-scrolling emoji grid: recently-used first, then each category. */
    private fun buildEmojiGridView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(8))
            setBackgroundColor(palette.background)
        }
        root.addView(searchField())

        // Flat emoji list + the start position of each tab section.
        val cells = ArrayList<Cell>()
        val tabs = ArrayList<Pair<String, Int>>()  // (tab glyph, start position)

        val recents = loadRecents()
        if (recents.isNotEmpty()) {
            tabs.add("🕘" to 0)
            recents.forEach { cells.add(Cell(null, it)) }
        }
        emojiData.forEach { (_, items) ->
            tabs.add(items.first().emoji to cells.size)
            items.forEach { cells.add(Cell(null, it.emoji)) }
        }

        val recycler = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(40 * EMOJI_ROWS))
            layoutManager = GridLayoutManager(
                this@KeyboardService, EMOJI_ROWS, GridLayoutManager.HORIZONTAL, false
            )
            adapter = EmojiAdapter(cells)
            setHasFixedSize(true)
        }

        // Category tabs — tapping scrolls the grid to that section.
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.forEach { (glyph, pos) ->
            tabRow.addView(TextView(this).apply {
                text = glyph
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                isClickable = true
                setOnClickListener {
                    (recycler.layoutManager as GridLayoutManager).scrollToPositionWithOffset(pos, 0)
                }
            }, LinearLayout.LayoutParams(dp(40), dp(36)))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        })

        root.addView(recycler)
        root.addView(buildRow(listOf(Key("ABC", 2f), Key("space", 5f), Key("⌫", 2f))))
        return root
    }

    private fun emojiPrefs() = getSharedPreferences("emoji", Context.MODE_PRIVATE)

    private fun loadRecents(): List<String> =
        emojiPrefs().getString("recents", "").orEmpty().split("").filter { it.isNotEmpty() }

    private fun commitEmoji(e: String) {
        currentInputConnection?.commitText(e, 1)
        // Move to front of the recents list (deduped, capped).
        val updated = (listOf(e) + loadRecents().filter { it != e }).take(RECENTS_MAX)
        emojiPrefs().edit().putString("recents", updated.joinToString("")).apply()
    }

    /** Search mode: query bar, live-filtered results, and a query-editing QWERTY. */
    private fun buildEmojiSearchView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(8))
            setBackgroundColor(palette.background)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(42))
        }
        fun barLp(weight: Float) = LinearLayout.LayoutParams(0, MATCH_PARENT, weight)
            .also { it.setMargins(dp(3), dp(4), dp(3), dp(4)) }
        bar.addView(makeKey("QCLOSE"), barLp(1.5f))
        bar.addView(TextView(this).apply {
            text = if (emojiQuery.isEmpty()) "Search emoji" else emojiQuery.toString()
            setTextColor(if (emojiQuery.isEmpty()) palette.specialText else palette.keyText)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, barLp(6f))
        bar.addView(makeKey("QDEL"), barLp(1.5f))
        root.addView(bar)

        val terms = emojiQuery.toString().trim().lowercase().split(" ").filter { it.isNotEmpty() }
        val results = if (terms.isEmpty()) emptyList()
            else emojiData.asSequence().flatMap { it.second.asSequence() }
                .filter { item -> terms.all { item.name.contains(it) } }
                .take(120).map { Cell(null, it.emoji) }.toList()
        root.addView(RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(112))
            layoutManager = GridLayoutManager(this@KeyboardService, EMOJI_COLS)
            adapter = EmojiAdapter(results)
        })

        listOf(
            letters("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf(Key(spacerId, 0.5f)) +
                letters("a", "s", "d", "f", "g", "h", "j", "k", "l") + listOf(Key(spacerId, 0.5f)),
            listOf(Key(spacerId, 1f)) + letters("z", "x", "c", "v", "b", "n", "m") + listOf(Key(spacerId, 1f)),
            listOf(Key("ABC", 2f), Key("space", 5f)),
        ).forEach { root.addView(buildRow(it)) }
        return root
    }

    private fun searchField(): View = TextView(this).apply {
        text = "🔍  Search emoji"
        setTextColor(palette.specialText)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = roundedBg(palette.special, mix(palette.special, palette.accent, 0.2f))
        isClickable = true
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(38))
        lp.setMargins(dp(3), dp(2), dp(3), dp(6))
        layoutParams = lp
        setOnClickListener { emojiSearch = true; emojiQuery.clear(); setInputView(buildKeyboardView()) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class EmojiAdapter(private val cells: List<Cell>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = cells.size
        override fun getItemViewType(position: Int) = if (cells[position].header != null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(this@KeyboardService)
            if (viewType == 0) {
                tv.setTextColor(palette.specialText)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                tv.setTypeface(tv.typeface, Typeface.BOLD)
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.setPadding(dp(6), dp(6), 0, dp(2))
                tv.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, dp(28))
            } else {
                tv.setTextColor(palette.keyText)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                tv.gravity = Gravity.CENTER
                // Square cell; the grid manager overrides the cross-axis dimension,
                // so this works for both the horizontal grid and vertical search results.
                tv.layoutParams = ViewGroup.LayoutParams(dp(40), dp(40))
                tv.isClickable = true
                tv.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) haptic()
                    false
                }
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val cell = cells[position]
            val tv = holder.itemView as TextView
            if (cell.header != null) {
                tv.text = cell.header.uppercase()
                tv.setOnClickListener(null)
            } else {
                tv.text = cell.emoji
                tv.setOnClickListener { cell.emoji?.let { commitEmoji(it) } }
            }
        }
    }

    private fun buildRow(keys: List<Key>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        keys.forEach { key ->
            val lp = LinearLayout.LayoutParams(0, dp(46), key.weight)
            lp.setMargins(dp(3), dp(4), dp(3), dp(4))
            if (key.id == spacerId) row.addView(View(this), lp) else row.addView(makeKey(key.id), lp)
        }
        return row
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(id: String): Button {
        val accentKey = id == "return" && enterIsAction
        val special = id in functionKeys || accentKey
        val fill = when {
            accentKey -> palette.accent
            special -> palette.special
            else -> palette.key
        }
        val textColor = when {
            accentKey -> palette.accentText
            special -> palette.specialText
            else -> palette.keyText
        }
        val btn = Button(this).apply {
            tag = id
            text = labelFor(id)
            isAllCaps = false
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            stateListAnimator = null
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (id.length > 1 && id != "NUM") 14f else 19f)
            background = roundedBg(fill, mix(fill, palette.accent, 0.35f))
            setOnClickListener { onKey(id) }
        }
        if (id != "⌫") {
            // Fire the buzz on touch-DOWN, not on click (which only lands on finger
            // lift) — that removes the press-to-vibration lag. Return false so the
            // normal click still happens.
            btn.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) haptic()
                false
            }
        }
        if (id == "EMOJI") {
            btn.text = ""  // smiley icon opens the emoji page
            val face = ContextCompat.getDrawable(this, R.drawable.ic_emoji)!!.mutate()
            face.setTint(palette.specialText)
            btn.foreground = face
            btn.foregroundGravity = Gravity.CENTER
        }
        if (id == "b") {
            // Facebook-style 🅱️: a red square tile centered on the keycap with a
            // white B. Still commits the real emoji.
            btn.text = "B"
            btn.setTextColor(Color.WHITE)
            btn.setTypeface(btn.typeface, Typeface.BOLD)
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            btn.background = bEmojiBackground()
        }
        if (id == "⇧") {
            shiftKey = btn
            btn.text = ""           // glyph is drawn as a tinted foreground icon
            applyShiftIcon(btn)
        }
        if (id == "⌫") {
            // Rolling backspace: hold to delete repeatedly, accelerating, then by word.
            btn.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; startBackspace(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false; stopBackspace(); true
                    }
                    else -> false
                }
            }
        }
        return btn
    }

    private fun labelFor(id: String): String = when (id) {
        "space" -> ""
        "return" -> if (enterIsAction) "go" else "return"
        "NUM" -> "123"
        "SYM" -> "#+="
        "ABC" -> "ABC"
        "QDEL" -> "⌫"
        "QCLOSE" -> "✕"
        "b" -> bEmoji
        else -> if (shiftState != Shift.OFF && id.length == 1 && id[0].isLetter()) id.uppercase() else id
    }

    private fun onKey(id: String) {
        // Haptic fires earlier on touch-DOWN (see makeKey); not here, to avoid a
        // second buzz and the click-time lag.
        if (page == Page.EMOJI && emojiSearch) { handleEmojiSearchKey(id); return }
        val ic = currentInputConnection ?: return
        when (id) {
            "⌫" -> deleteOneGrapheme(ic)
            "⇧" -> handleShiftTap()
            "space" -> ic.commitText(" ", 1)
            "EMOJI" -> switchPage(Page.EMOJI)
            "NUM" -> switchPage(Page.NUMBERS)
            "SYM" -> switchPage(Page.SYMBOLS)
            "ABC" -> switchPage(Page.LETTERS)
            "return" -> handleEnter(ic)
            "b" -> {
                ic.commitText(bEmoji, 1)
                consumeShift()
            }
            else -> {
                ic.commitText(if (shiftState != Shift.OFF) id.uppercase() else id, 1)
                consumeShift()  // one-shot shift falls back to lowercase
            }
        }
    }

    private fun switchPage(p: Page) {
        page = p
        emojiSearch = false
        emojiQuery.clear()
        if (p != Page.LETTERS) shiftState = Shift.OFF
        setInputView(buildKeyboardView())
    }

    private fun handleEmojiSearchKey(id: String) {
        when {
            id == "QCLOSE" -> { emojiSearch = false; setInputView(buildKeyboardView()) }
            id == "ABC" -> switchPage(Page.LETTERS)
            id == "QDEL" -> {
                if (emojiQuery.isNotEmpty()) emojiQuery.deleteCharAt(emojiQuery.length - 1)
                setInputView(buildKeyboardView())
            }
            id == "space" -> { emojiQuery.append(' '); setInputView(buildKeyboardView()) }
            id.length == 1 && id[0].isLetter() -> {
                emojiQuery.append(id); setInputView(buildKeyboardView())
            }
        }
    }

    /** A shift tap cycles OFF → shift; a second tap within 300ms locks caps. */
    private fun handleShiftTap() {
        val now = SystemClock.uptimeMillis()
        shiftState = when {
            shiftState == Shift.LOCK -> Shift.OFF
            shiftState == Shift.ONE_SHOT && now - lastShiftTap < 300 -> Shift.LOCK  // double-tap
            shiftState == Shift.ONE_SHOT -> Shift.OFF
            else -> Shift.ONE_SHOT
        }
        lastShiftTap = now
        applyShiftVisuals()
        relabelKeys()
    }

    /** After typing a letter, a one-shot shift drops back to lowercase; caps lock holds. */
    private fun consumeShift() {
        if (shiftState == Shift.ONE_SHOT) {
            shiftState = Shift.OFF
            applyShiftVisuals()
            relabelKeys()
        }
    }

    private fun relabelKeys() = keyboardRoot?.let { relabel(it) }

    @Suppress("DEPRECATION")
    private fun haptic() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Max amplitude (255) for the strongest possible per-key tap.
            v.vibrate(VibrationEffect.createOneShot(VIBE_MS, 255))
        } else {
            v.vibrate(VIBE_MS)
        }
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }

    private fun applyShiftVisuals() = shiftKey?.let { applyShiftIcon(it) }

    private fun applyShiftIcon(btn: Button) {
        val lock = shiftState == Shift.LOCK
        val tint = if (shiftState == Shift.OFF) palette.specialText else palette.accent
        val icon = ContextCompat.getDrawable(
            this, if (lock) R.drawable.ic_shift_lock else R.drawable.ic_shift
        )!!.mutate()
        icon.setTint(tint)
        btn.foreground = icon
        btn.foregroundGravity = Gravity.CENTER
    }

    private fun startBackspace() {
        backspaceCount = 0
        val r = object : Runnable {
            override fun run() {
                deleteStep()
                backspaceCount++
                val delay = when {
                    backspaceCount == 1 -> 350L   // initial hold pause before repeating
                    backspaceCount > 24 -> 12L
                    backspaceCount > 12 -> 25L
                    else -> 55L
                }
                repeatHandler.postDelayed(this, delay)
            }
        }
        backspaceRepeat = r
        repeatHandler.post(r)  // first delete fires immediately
    }

    private fun stopBackspace() {
        backspaceRepeat?.let { repeatHandler.removeCallbacks(it) }
        backspaceRepeat = null
    }

    private fun deleteStep() {
        haptic()  // vibrate on every delete, including each rolling repeat
        val ic = currentInputConnection ?: return
        if (backspaceCount > 34) {
            // After a long hold, delete a word at a time (like iOS).
            val before = ic.getTextBeforeCursor(64, 0) ?: ""
            var i = before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
            val del = before.length - i
            ic.deleteSurroundingText(if (del > 0) del else 1, 0)
        } else {
            deleteOneGrapheme(ic)
        }
    }

    /**
     * Delete one full grapheme cluster (so a 🅱️ — surrogate pair + variation selector —
     * vanishes whole instead of decaying into the bare text-style 🅱 box).
     */
    private fun deleteOneGrapheme(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(32, 0)
        if (before.isNullOrEmpty()) { ic.deleteSurroundingText(1, 0); return }
        val bi = android.icu.text.BreakIterator.getCharacterInstance()
        bi.setText(before.toString())
        val end = bi.last()
        val start = bi.previous()
        val n = if (start == android.icu.text.BreakIterator.DONE) end else end - start
        ic.deleteSurroundingText(if (n > 0) n else 1, 0)
    }

    private fun handleEnter(ic: InputConnection) {
        if (enterIsAction) {
            ic.performEditorAction(currentInputEditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun relabel(group: LinearLayout) {
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is LinearLayout -> relabel(child)
                is Button -> {
                    val base = child.text.toString().lowercase()
                    // Skip the B key — it stays the flat 🅱️, never a plain letter.
                    if ((child.tag as? String) != "b" && base.length == 1 && base[0].isLetter()) {
                        child.text = if (shiftState != Shift.OFF) base.uppercase() else base
                    }
                }
            }
        }
    }

    // --- swipe decoding -------------------------------------------------------

    /** Decode a glide gesture into the best-matching word, or null. */
    private fun decodeSwipe(points: List<PointF>, centers: Map<Char, PointF>): String? {
        if (points.size < 3 || centers.isEmpty()) return null
        val first = nearestLetter(points.first(), centers) ?: return null
        val last = nearestLetter(points.last(), centers) ?: return null
        val gesture = resample(points, SAMPLES)

        var best: String? = null
        var bestScore = Float.MAX_VALUE
        for ((idx, word) in words.withIndex()) {
            if (word.length < 2 || word.first() != first || word.last() != last) continue
            val ideal = resample(word.map { centers[it]!! }, SAMPLES)
            var d = 0f
            for (i in 0 until SAMPLES) d += dist(gesture[i], ideal[i])
            val score = d + idx * 0.01f  // tiny frequency prior breaks near-ties
            if (score < bestScore) { bestScore = score; best = word }
        }
        return best
    }

    private fun nearestLetter(p: PointF, centers: Map<Char, PointF>): Char? =
        centers.minByOrNull { dist(p, it.value) }?.key

    private fun resample(points: List<PointF>, n: Int): List<PointF> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return List(n) { PointF(points[0].x, points[0].y) }
        var total = 0f
        for (i in 1 until points.size) total += dist(points[i - 1], points[i])
        if (total == 0f) return List(n) { PointF(points[0].x, points[0].y) }
        val interval = total / (n - 1)
        val out = ArrayList<PointF>(n)
        out.add(PointF(points[0].x, points[0].y))
        var prev = points[0]
        var acc = 0f
        var i = 1
        while (i < points.size && out.size < n) {
            val curr = points[i]
            val d = dist(prev, curr)
            if (acc + d >= interval && d > 0f) {
                val t = (interval - acc) / d
                val np = PointF(prev.x + t * (curr.x - prev.x), prev.y + t * (curr.y - prev.y))
                out.add(np)
                prev = np
                acc = 0f
            } else {
                acc += d
                prev = curr
                i++
            }
        }
        while (out.size < n) out.add(PointF(points.last().x, points.last().y))
        return out
    }

    private fun dist(a: PointF, b: PointF) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    // --- theming helpers ------------------------------------------------------

    private fun resolvePalette(): Palette {
        val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fun c(id: Int) = resources.getColor(id, theme)
            return if (night) Palette(
                c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral1_700),
                c(android.R.color.system_neutral1_50), c(android.R.color.system_neutral1_800),
                c(android.R.color.system_neutral1_50), c(android.R.color.system_accent1_400),
                c(android.R.color.system_accent1_900),
            ) else Palette(
                c(android.R.color.system_neutral2_100), c(android.R.color.system_neutral1_10),
                c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral2_300),
                c(android.R.color.system_neutral1_900), c(android.R.color.system_accent1_600),
                c(android.R.color.system_accent1_0),
            )
        }
        return if (night) Palette(
            0xFF1C1B1F.toInt(), 0xFF49454F.toInt(), 0xFFE6E1E5.toInt(),
            0xFF36343B.toInt(), 0xFFE6E1E5.toInt(), 0xFFD0BCFF.toInt(), 0xFF381E72.toInt(),
        ) else Palette(
            0xFFECE6F0.toInt(), 0xFFFFFBFE.toInt(), 0xFF1C1B1F.toInt(),
            0xFFE7E0EC.toInt(), 0xFF1C1B1F.toInt(), 0xFF6750A4.toInt(), 0xFFFFFFFF.toInt(),
        )
    }

    /** Keycap with an inset red rounded square tile — the 🅱️ look. */
    private fun bEmojiBackground(): StateListDrawable {
        val red = 0xFFEF4136.toInt()
        fun tile(tileColor: Int): LayerDrawable {
            val keycap = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(palette.key) }
            val square = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(tileColor) }
            return LayerDrawable(arrayOf(keycap, square)).apply {
                setLayerGravity(1, Gravity.CENTER)
                setLayerSize(1, dp(22), dp(22))
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), tile(mix(red, Color.BLACK, 0.18f)))
            addState(intArrayOf(), tile(red))
        }
    }

    private fun roundedBg(fill: Int, pressed: Int): StateListDrawable {
        fun cap(color: Int) = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat(); setColor(color)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), cap(pressed))
            addState(intArrayOf(), cap(fill))
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Keyboard container that captures glide gestures. A small drag past the touch
     * slop on the letters page starts intercepting touches (so child key taps are
     * cancelled); on lift, the traced path is decoded into a word.
     */
    private inner class KeyboardLayout(context: android.content.Context) : LinearLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val points = ArrayList<PointF>()
        private var centers: Map<Char, PointF> = emptyMap()
        private var swiping = false
        private var downX = 0f
        private var downY = 0f
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = dp(5).toFloat()
            color = (palette.accent and 0x00FFFFFF) or (0x96 shl 24)  // ~59% alpha
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (page != Page.LETTERS) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; swiping = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!swiping && hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) > slop * 2.5) {
                        swiping = true
                        if (centers.isEmpty()) centers = computeCenters()
                        points.clear()
                        points.add(PointF(downX, downY))
                        points.add(PointF(ev.x, ev.y))
                        return true  // hand subsequent events to onTouchEvent
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!swiping) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> { points.add(PointF(ev.x, ev.y)); invalidate() }
                MotionEvent.ACTION_UP -> {
                    decodeSwipe(points, centers)?.let { word ->
                        haptic()
                        currentInputConnection?.commitText(word.replace("b", bEmoji) + " ", 1)
                    }
                    swiping = false; points.clear(); invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { swiping = false; points.clear(); invalidate() }
            }
            return true
        }

        private fun computeCenters(): Map<Char, PointF> {
            val m = HashMap<Char, PointF>()
            for (r in 0 until childCount) {
                val row = getChildAt(r) as? LinearLayout ?: continue
                for (c in 0 until row.childCount) {
                    val child = row.getChildAt(c)
                    val t = child.tag as? String ?: continue
                    if (t.length == 1 && t[0] in 'a'..'z') {
                        m[t[0]] = PointF(row.x + child.x + child.width / 2f, row.y + child.y + child.height / 2f)
                    }
                }
            }
            return m
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (swiping && points.size > 1) {
                val path = Path()
                path.moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                canvas.drawPath(path, trailPaint)
            }
        }
    }

    private companion object {
        const val SAMPLES = 24
        const val VIBE_MS = 55L
        const val EMOJI_COLS = 8   // columns in the (vertical) search-results grid
        const val EMOJI_ROWS = 4   // rows in the (horizontal) main emoji grid
        const val RECENTS_MAX = 32
    }
}
