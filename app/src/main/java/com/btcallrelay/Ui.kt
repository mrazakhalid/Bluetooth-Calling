package com.btcallrelay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny helpers so the screens can be built in code without extra libraries. */
object Ui {
    const val GREEN = 0xFF2E7D32.toInt()
    const val RED = 0xFFC62828.toInt()
    const val BLUE = 0xFF1E88E5.toInt()
    const val GREY = 0xFF757575.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun isDark(ctx: Context) =
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Scrollable vertical page that keeps clear of the status and navigation bars (Android 16 is edge-to-edge). */
    fun page(act: Activity): LinearLayout {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(act, 16)
            setPadding(p, p, p, p)
        }
        val scroll = ScrollView(act).apply {
            isFillViewport = true
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        act.setContentView(scroll)
        return col
    }

    fun title(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(ctx, 8))
    }

    fun label(ctx: Context, text: String = "", sizeSp: Float = 15f) = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
    }

    fun section(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(GREY)
        setPadding(0, dp(ctx, 20), 0, dp(ctx, 6))
    }

    fun button(ctx: Context, text: String, color: Int? = null, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        if (color != null) {
            background = rounded(color, dp(ctx, 28).toFloat())
            setTextColor(Color.WHITE)
            minHeight = dp(ctx, 56)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 6) }
    }

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 14)
        setPadding(p, p, p, p)
        background = rounded(if (isDark(ctx)) 0xFF263238.toInt() else 0xFFECEFF1.toInt(), dp(ctx, 16).toFloat())
        gravity = Gravity.CENTER_HORIZONTAL
    }

    fun showIf(v: View, show: Boolean) {
        v.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun openAppSettings(act: Activity) {
        act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", act.packageName, null)))
    }

    fun confirmDialog(act: Activity, p: PendingConfirm, self: String): AlertDialog =
        AlertDialog.Builder(act)
            .setTitle("Confirm pairing")
            .setMessage("Pairing with: ${p.peer}\n\nCheck that BOTH phones show this code:\n\n${p.code}\n\n" +
                "Only tap \"Codes match\" if the code on the other phone is exactly the same. ($self)")
            .setCancelable(false)
            .setPositiveButton("Codes match") { _, _ -> p.answer.complete(true) }
            .setNegativeButton("Cancel") { _, _ -> p.answer.complete(false) }
            .show()
}
