package com.btcallrelay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.View
import android.widget.ImageView

/** One UI–style colours, light and dark. */
class Pal(ctx: Context) {
    val dark = Ui.isDark(ctx)
    val bg = if (dark) 0xFF000000.toInt() else 0xFFF3F3F5.toInt()
    val keypadBg = if (dark) 0xFF000000.toInt() else 0xFFFCFCFC.toInt()
    val card = if (dark) 0xFF1B1B1D.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFF4F4F4.toInt() else 0xFF0F0F0F.toInt()
    val sub = if (dark) 0xFF9C9C9F.toInt() else 0xFF767679.toInt()
    val divider = if (dark) 0xFF2B2B2E.toInt() else 0xFFE7E7EA.toInt()
    val navBg = if (dark) 0xFF232325.toInt() else 0xFFFFFFFF.toInt()
    val navSel = if (dark) 0xFF3A3A3D.toInt() else 0xFFE4E4E6.toInt()
    val roundBtn = if (dark) 0xFF2A2A2D.toInt() else 0xFFEDEDF0.toInt()
    val ripple = if (dark) 0x33FFFFFF else 0x22000000
    val green = 0xFF14A85B.toInt()
    val greenSwipe = 0xFF4BBE81.toInt()
    val blue = 0xFF33A0F0.toInt()
    val blueSwipe = 0xFF66B9F5.toInt()
    val red = 0xFFE5383B.toInt()
    val pasteA = if (dark) 0xFF1E2A3A.toInt() else 0xFFDDE9FF.toInt()
    val pasteB = if (dark) 0xFF183232.toInt() else 0xFFE3F8F4.toInt()

    /** Soft gradients for contact tiles and avatars, picked by name. */
    private val tiles = listOf(
        0xFF8FD694 to 0xFFA8E08F, 0xFF5FC9C6 to 0xFF7FD6C2, 0xFFF0907F to 0xFFF4A98E,
        0xFF5AB7E8 to 0xFF7CCFE0, 0xFFF08FB0 to 0xFFD58BD8, 0xFF9AA5F5 to 0xFFB7A0F0,
        0xFFFFB648 to 0xFFFFC56B, 0xFF8C9CE0 to 0xFF7E8FD6,
    )

    fun tile(key: String): Pair<Int, Int> =
        tiles[(key.lowercase().hashCode() and 0x7fffffff) % tiles.size].let { it.first.toInt() to it.second.toInt() }

    fun gradient(key: String, radius: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        tile(key).let { intArrayOf(it.first, it.second) }).apply { cornerRadius = radius }

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Ripple clipped to [shape] (or borderless when null). */
    fun ripple(shape: android.graphics.drawable.Drawable?) =
        RippleDrawable(ColorStateList.valueOf(ripple), shape, shape ?: null)

    fun circleRipple() = RippleDrawable(ColorStateList.valueOf(ripple), null, ShapeDrawable(OvalShape()))
}

fun ImageView.tint(color: Int) { imageTintList = ColorStateList.valueOf(color) }

fun View.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** SIM phone status, e.g. "🔋 82%    Jazz ▂▄▆█    Onic ▂▄", with unfilled bars greyed out. Empty if unknown. */
fun phoneStatusLine(pal: Pal, prefs: Prefs): CharSequence {
    val sb = android.text.SpannableStringBuilder()
    val b = RelayState.battery
    if (b >= 0) {
        val start = sb.length
        sb.append(if (RelayState.charging) "⚡$b%" else "🔋 $b%")
        if (b <= 15 && !RelayState.charging)
            sb.setSpan(android.text.style.ForegroundColorSpan(pal.red), start, sb.length, 0)
    }
    for ((slot, bars, _) in RelayState.signals) {
        sb.append(if (sb.isEmpty()) "" else "    ").append(prefs.simName(slot)).append(" ")
        val glyphs = "▂▄▆█"
        for (i in 0 until 4) {
            val s = sb.length
            sb.append(glyphs[i])
            sb.setSpan(android.text.style.ForegroundColorSpan(if (i < bars) pal.text else pal.divider), s, sb.length, 0)
        }
    }
    return sb
}

fun initialOf(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

