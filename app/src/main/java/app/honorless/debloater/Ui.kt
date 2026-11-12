package app.honorless.debloater

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** Colours for light and dark mode. */
class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF0F1115.toInt() else 0xFFF3F5F8.toInt()
    val card = if (dark) 0xFF1A1D23.toInt() else 0xFFFFFFFF.toInt()
    val field = if (dark) 0xFF24282F.toInt() else 0xFFEDF0F4.toInt()
    val text = if (dark) 0xFFE8EAED.toInt() else 0xFF1B1D21.toInt()
    val muted = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6670.toInt()
    val divider = if (dark) 0x1FFFFFFF else 0x14000000
    val accent = if (dark) 0xFF7FB2FF.toInt() else 0xFF2563EB.toInt()
    val accentSoft = if (dark) 0xFF1B2A44.toInt() else 0xFFE8F0FE.toInt()   // a highlighted card
    val onAccent = if (dark) 0xFF0B1B33.toInt() else 0xFFFFFFFF.toInt()
    val good = if (dark) 0xFF5FD39A.toInt() else 0xFF14804A.toInt()
    val warn = if (dark) 0xFFF2B35B.toInt() else 0xFFB45309.toInt()
    val bad = if (dark) 0xFFFF8A80.toInt() else 0xFFC62828.toInt()
    val ripple = if (dark) 0x33FFFFFF else 0x22000000

    companion object {
        fun isDark(ctx: Context) =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}

/** Small view builders, so the screen code reads as layout rather than plumbing. */
class Ui(private val ctx: Context, val pal: Palette) {

    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int) = (v * density + 0.5f).toInt()

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun withAlpha(color: Int, alpha: Float) = Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun ripple(content: Drawable?, radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(pal.ripple), content, rounded(Color.WHITE, radiusDp))

    fun vertical(padH: Int = 0, padV: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    fun horizontal() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(tint: Int? = null): LinearLayout = vertical(16, 14).apply {
        background = rounded(tint ?: pal.card, 20)
    }

    fun lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, top: Int = 0, bottom: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    fun text(s: CharSequence, size: Float = 14f, color: Int = pal.text, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = medium
        setLineSpacing(0f, 1.1f)
    }

    fun muted(s: CharSequence, size: Float = 13f) = text(s, size, pal.muted)

    fun mono(s: CharSequence, size: Float = 11f) = text(s, size, pal.muted).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    /** A small coloured label, e.g. "Hidden". */
    fun pill(s: String, color: Int) = TextView(ctx).apply {
        text = s
        textSize = 11f
        typeface = medium
        setTextColor(color)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = rounded(withAlpha(color, if (pal.dark) 0.18f else 0.12f), 999)
        maxLines = 1
    }

    enum class Kind { PRIMARY, SECONDARY, DANGER, TEXT }

    fun button(s: String, kind: Kind = Kind.SECONDARY, onClick: () -> Unit) = Button(ctx).apply {
        text = s
        isAllCaps = false
        textSize = 14f
        typeface = medium
        stateListAnimator = null
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val (fill, fg) = when (kind) {
            Kind.PRIMARY -> pal.accent to pal.onAccent
            Kind.SECONDARY -> withAlpha(pal.accent, if (pal.dark) 0.16f else 0.10f) to pal.accent
            Kind.DANGER -> withAlpha(pal.bad, if (pal.dark) 0.18f else 0.10f) to pal.bad
            Kind.TEXT -> Color.TRANSPARENT to pal.accent
        }
        setTextColor(fg)
        background = ripple(rounded(fill, 999), 999)
        setOnClickListener { onClick() }
    }

    /** One option of a segmented choice: its label, and the colour it lights up in when chosen. */
    class Segment(val label: String, val color: Int, val description: String)

    /**
     * A row of pill-shaped options, one chosen at a time (Leave / Hide / Remove). Built from radio
     * buttons, so screen readers announce it as a choice. [onPick] gets the index the user chose.
     */
    fun segmented(options: List<Segment>, chosen: Int, onPick: (RadioGroup, Int) -> Unit) = RadioGroup(ctx).apply {
        orientation = RadioGroup.HORIZONTAL
        background = rounded(pal.field, 999)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        val ids = options.mapIndexed { i, opt ->
            val b = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = opt.label
                contentDescription = opt.description
                textSize = 13f
                typeface = medium
                buttonDrawable = null
                gravity = Gravity.CENTER
                minWidth = dp(64); minimumWidth = dp(64)
                minHeight = 0; minimumHeight = 0
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), rounded(opt.color, 999))
                    addState(intArrayOf(), rounded(Color.TRANSPARENT, 999))
                }
                setTextColor(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(if (pal.dark) 0xFF0F1115.toInt() else 0xFFFFFFFF.toInt(), pal.muted)
                ))
            }
            addView(b, RadioGroup.LayoutParams(RadioGroup.LayoutParams.WRAP_CONTENT, RadioGroup.LayoutParams.WRAP_CONTENT))
            b.id
        }
        check(ids[chosen.coerceIn(0, ids.lastIndex)])
        setOnCheckedChangeListener { group, id ->
            val i = ids.indexOf(id)
            if (i >= 0) onPick(group, i)
        }
    }

    fun field(hint: String, mono: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(pal.text)
        setHintTextColor(pal.muted)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(pal.field, 14)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true)
        if (mono) typeface = Typeface.MONOSPACE
    }

    fun editor(lines: Int) = EditText(ctx).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(pal.text)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(pal.field, 12)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(false)
        minLines = lines
        maxLines = lines * 2
        gravity = Gravity.TOP or Gravity.START
        isVerticalScrollBarEnabled = true
    }

    fun divider() = View(ctx).apply {
        setBackgroundColor(pal.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2))
    }

    /** A tappable heading row with a chevron that turns when the section is open. */
    fun header(title: String, subtitle: String?, open: Boolean): Pair<LinearLayout, TextView> {
        val row = horizontal().apply {
            isClickable = true
            isFocusable = true
            background = ripple(null, 14)
            setPadding(0, dp(4), 0, dp(4))
        }
        val texts = vertical()
        texts.addView(text(title, 16f, bold = true))
        if (subtitle != null) texts.addView(muted(subtitle).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        row.addView(texts, lp(0, weight = 1f))
        val chevron = text("›", 22f, pal.muted).apply {
            rotation = if (open) 90f else 0f
            gravity = Gravity.CENTER
        }
        row.addView(chevron, LinearLayout.LayoutParams(dp(32), dp(32)))
        return row to chevron
    }
}
