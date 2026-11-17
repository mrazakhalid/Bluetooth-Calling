package com.btcallrelay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Row that can be swiped like One UI's Phone app: right = green "Call", left = blue "Message".
 * The action fires when the row is released past 30% of its width.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SwipeRow(ctx: Context, pal: Pal, private val content: View,
               private val onCall: () -> Unit, private val onMessage: () -> Unit) : FrameLayout(ctx) {

    private val callBg = side(ctx, pal.green, pal.greenSwipe, R.drawable.ic_call, "Call", Gravity.START)
    private val msgBg = side(ctx, pal.blue, pal.blueSwipe, R.drawable.ic_message, "Message", Gravity.END)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var passed = false

    init {
        addView(callBg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(msgBg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        callBg.visibility = INVISIBLE
        msgBg.visibility = INVISIBLE
    }

    /** Bright strip with icon + label on the side the row moves away from; lighter colour fills the rest. */
    private fun side(ctx: Context, strong: Int, light: Int, icon: Int, label: String, g: Int): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = g or Gravity.CENTER_VERTICAL
            setBackgroundColor(light)
            val strip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(strong)
                setPadding(dp(22), 0, dp(22), 0)
                val img = ImageView(ctx).apply { setImageResource(icon); tint(Color.WHITE) }
                val txt = TextView(ctx).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                }
                if (g == Gravity.START) {
                    addView(img, LinearLayout.LayoutParams(dp(28), dp(28)))
                    addView(txt, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
                } else {
                    addView(txt, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) })
                    addView(img, LinearLayout.LayoutParams(dp(28), dp(28)))
                }
            }
            addView(strip, LinearLayout.LayoutParams(-2, -1))
        }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; passed = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.4f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val dx = e.x - downX
                content.translationX = dx
                callBg.visibility = if (dx > 0) VISIBLE else INVISIBLE
                msgBg.visibility = if (dx < 0) VISIBLE else INVISIBLE
                val over = abs(dx) > width * 0.3f
                if (over != passed) { passed = over; if (over) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                val dx = content.translationX
                dragging = false
                content.animate().translationX(0f).setDuration(220).withEndAction {
                    callBg.visibility = INVISIBLE
                    msgBg.visibility = INVISIBLE
                }.start()
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    if (dx > width * 0.3f) onCall() else if (dx < -width * 0.3f) onMessage()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
