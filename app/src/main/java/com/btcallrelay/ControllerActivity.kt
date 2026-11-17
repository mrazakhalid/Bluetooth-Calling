package com.btcallrelay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Dialer main screen on the phone WITHOUT the SIM, styled after One UI's Phone app:
 * Keypad / Recents / Contacts behind a floating bottom bar, plus a full-screen in-call view.
 */
@SuppressLint("SetTextI18n")
class ControllerActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var pal: Pal
    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() { renderCall(); main.postDelayed(this, 1000) }
    }

    private var topInset = 0
    private var bottomInset = 0
    private var tab = 0

    private lateinit var root: FrameLayout
    private lateinit var pages: List<View>
    private lateinit var nav: LinearLayout
    private val navItems = mutableListOf<Triple<LinearLayout, ImageView, TextView>>()

    // keypad
    private lateinit var numberText: EditText
    private lateinit var suggestions: LinearLayout
    /** The number being typed (lives in the editable field so the cursor can move). */
    private val digits: String get() = if (::numberText.isInitialized) numberText.text.toString() else ""
    private lateinit var nameText: TextView
    private lateinit var simChip: TextView
    private lateinit var linkDot: TextView
    private lateinit var pastePill: View
    private lateinit var backspace: ImageView
    private lateinit var addContactBtn: View
    private var imeOpen = false
    private lateinit var msgBadge: TextView

    // recents
    private lateinit var recentsBox: LinearLayout
    private lateinit var recentsSearch: EditText
    private var recentsFilter = 0 // 0 all, 1 missed, 2 incoming, 3 outgoing
    private var renderedLog: List<CallEntry>? = null
    private var expandedRow: String? = null

    // contacts
    private lateinit var contactsList: ListView
    private lateinit var contactsSearch: EditText
    private lateinit var favGrid: GridLayout
    private lateinit var favLabel: TextView
    private lateinit var contactsPrompt: View
    private var people: List<Person> = emptyList()
    private val contactsAdapter = ContactsAdapter()

    // in-call
    private lateinit var callView: LinearLayout
    private lateinit var callName: TextView
    private lateinit var callNumber: TextView
    private lateinit var callStatus: TextView
    private lateinit var callAvatar: TextView
    private lateinit var callSimText: TextView
    private lateinit var keypadBtn: Pair<View, ImageView>
    private lateinit var holdBtn: Pair<View, ImageView>
    private lateinit var heldBar: TextView
    private lateinit var replyLink: TextView
    private lateinit var silenceLink: TextView
    private lateinit var callKeypad: LinearLayout
    private lateinit var toneDigits: TextView
    private val callActionRows = mutableListOf<View>()
    private var callKeypadOpen = false
    private lateinit var muteBtn: Pair<View, ImageView>
    private lateinit var speakerBtn: Pair<View, ImageView>
    private lateinit var addCallBtn: Pair<View, ImageView>
    private lateinit var btBtn: Pair<View, ImageView>
    private var addingCall = false
    private var pendingDial: String? = null
    private var pendingSince = 0L
    private lateinit var ringRow: View
    private lateinit var endRow: View
    private lateinit var returnBar: TextView
    private var callCollapsed = false
    private var lastCallState = "idle"
    private var dialog: AlertDialog? = null

    private fun dp(v: Int) = Ui.dp(this, v)

    private val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_CONTACTS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.mode != Prefs.MODE_CONTROLLER) {
            Toast.makeText(this, "This phone is set up as the SIM host", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        pal = Pal(this)

        root = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        pages = listOf(buildKeypad(), buildRecents(), buildContacts())
        pages.forEach { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        nav = buildNav()
        root.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        returnBar = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            background = pal.rounded(pal.green, dp(22).toFloat())
            setOnClickListener { callCollapsed = false; render() }
        }
        root.addView(returnBar, FrameLayout.LayoutParams(-1, dp(44), Gravity.TOP).apply {
            leftMargin = dp(16); rightMargin = dp(16)
        })
        callView = buildCallView()
        root.addView(callView, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            topInset = bars.top
            bottomInset = bars.bottom
            pages.forEach { it.setPadding(0, topInset, 0, 0) }
            (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottomInset + dp(6)
            imeOpen = ime.bottom > 0
            updateNav()
            (returnBar.layoutParams as FrameLayout.LayoutParams).topMargin = topInset + dp(6)
            applyCallPadding()
            root.requestLayout()
            insets
        }
        setContentView(root)
        if (!pal.dark) window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)

        selectTab(0)
        handleIntent(intent)
        ensureStarted()
        loadContacts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        RelayState.addListener(listener)
        main.post(tick)
        render()
    }

    override fun onPause() {
        RelayState.removeListener(listener)
        main.removeCallbacks(tick)
        super.onPause()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_QUICK_REPLY, false) == true) {
            i.removeExtra(EXTRA_QUICK_REPLY)
            main.post { quickReply() }
        }
        val data = i?.data ?: return
        if (data.scheme == "tel") {
            setDigits(clean(Uri.decode(data.schemeSpecificPart)))
            selectTab(0)
        }
    }

    // =====================================================================================
    // Bottom bar
    // =====================================================================================

    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pal.rounded(pal.navBg, dp(30).toFloat())
            elevation = dp(6).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        // Sizes measured from One UI 8's Phone app on the S25 Ultra: pill ~234x56dp, items ~76x48dp.
        listOf(R.drawable.ic_dialpad to "Keypad", R.drawable.ic_recents to "Recents", R.drawable.ic_contacts to "Contacts")
            .forEachIndexed { i, (icon, label) ->
                val img = ImageView(this).apply { setImageResource(icon); tint(pal.text) }
                val txt = TextView(this).apply {
                    text = label
                    setTextColor(pal.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    includeFontPadding = false
                }
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(img, LinearLayout.LayoutParams(dp(22), dp(22)))
                    addView(txt, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        // During a call the bar sits over the call screen: minimise it so the tab shows ("Return to call" brings it back).
                        if (callView.visibility == View.VISIBLE) { callCollapsed = true; renderCall() }
                        selectTab(i)
                    }
                }
                navItems += Triple(item, img, txt)
                bar.addView(item, LinearLayout.LayoutParams(dp(76), dp(48)))
            }
        return bar
    }

    /** Like One UI: the bar hides while a number is being typed on the keypad, and while the keyboard is up. */
    private fun updateNav() {
        if (!::nav.isInitialized) return
        val ringing = RelayState.callState == "ringing" && !callCollapsed // nothing may cover Answer / Decline
        nav.visibility = if (imeOpen || ringing || (tab == 0 && digits.isNotEmpty())) View.GONE else View.VISIBLE
    }

    private fun selectTab(i: Int) {
        tab = i
        updateNav()
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { idx, (item, img, txt) ->
            val on = idx == i
            item.background = if (on) pal.rounded(pal.navSel, dp(24).toFloat()) else null
            txt.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            if (idx == 2) img.setImageResource(if (on) R.drawable.ic_contacts_filled else R.drawable.ic_contacts)
        }
        hideKeyboard()
        if (i == 1) { renderedLog = null; renderRecents(); ControllerService.start(this, ControllerService.ACTION_REFRESH_CALLS) }
        if (i == 2 && people.isEmpty()) loadContacts()
    }

    private fun iconButton(icon: Int, onClick: (View) -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        tint(pal.text)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = pal.circleRipple()
        setOnClickListener { onClick(it) }
    }

    /** Right-aligned icon row; One UI spaces these ~36dp apart with the last one ~36dp from the edge. */
    private fun toolbar(vararg buttons: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(16), 0)
        buttons.forEach { addView(it, LinearLayout.LayoutParams(dp(40), dp(40))) }
    }

    /** One UI's big centred page title: 32sp bold inside a block ~30% of the screen tall. */
    private fun bigTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(pal.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
        gravity = Gravity.CENTER
        val h = (resources.displayMetrics.heightPixels * 0.27f).toInt() + dp(21)
        setPadding(0, 0, 0, ((h - dp(21)) * 0.14f).toInt() + dp(21))
        layoutParams = LinearLayout.LayoutParams(-1, h)
    }

    private fun moreMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 2, 1, when (RelayState.hotspotOn) {
            true -> "Turn SIM phone hotspot off"
            false -> "Turn SIM phone hotspot on"
            null -> "SIM phone hotspot (not connected)"
        })
        pm.menu.add(0, 3, 2, "Refresh call history")
        pm.menu.add(0, 4, 3, "Settings")
        pm.menu.add(0, 5, 4, AutoReplyUi.summary(this))
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                2 -> toggleHotspot()
                3 -> ControllerService.start(this, ControllerService.ACTION_REFRESH_CALLS)
                4 -> startActivity(Intent(this, ControllerSettingsActivity::class.java))
                5 -> AutoReplyUi.show(this)
            }
            true
        }
        pm.show()
    }

    private fun searchField(hint: String, onChange: (String) -> Unit) = EditText(this).apply {
        this.hint = hint
        setSingleLine()
        setTextColor(pal.text)
        setHintTextColor(pal.sub)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        background = pal.rounded(pal.card, dp(24).toFloat())
        setPadding(dp(20), dp(12), dp(20), dp(12))
        visibility = View.GONE
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
        })
    }

    private fun toggleSearch(field: EditText) {
        if (field.visibility == View.VISIBLE) {
            field.setText("")
            field.visibility = View.GONE
            hideKeyboard()
        } else {
            field.visibility = View.VISIBLE
            field.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // =====================================================================================
    // Keypad
    // =====================================================================================

    private fun buildKeypad(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.keypadBg)
        }

        // Measured from One UI 8 on the S25 Ultra: icons ~41dp below the status bar, 36dp from the right edge.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(16), 0)
        }
        linkDot = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(pal.sub)
            setOnClickListener { startActivity(Intent(this@ControllerActivity, ControllerSettingsActivity::class.java)) }
        }
        top.addView(linkDot, LinearLayout.LayoutParams(0, -2, 1f))
        addContactBtn = iconButton(R.drawable.ic_add) {
            runCatching {
                startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, digits.toString()))
            }
        }
        top.addView(addContactBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        // Messages, just left of search; a small red dot shows when texts are unread.
        val msgBox = FrameLayout(this).apply {
            addView(iconButton(R.drawable.ic_message_outline) { startActivity(Intent(this@ControllerActivity, MessagesActivity::class.java)) },
                FrameLayout.LayoutParams(dp(40), dp(40)))
            msgBadge = TextView(this@ControllerActivity).apply {
                background = pal.circle(pal.red)
                visibility = View.GONE
            }
            addView(msgBadge, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply { topMargin = dp(7); rightMargin = dp(7) })
        }
        top.addView(msgBox, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(-4) })
        top.addView(iconButton(R.drawable.ic_search) { selectTab(2); main.postDelayed({ toggleSearch(contactsSearch) }, 80) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(-4) })
        top.addView(iconButton(R.drawable.ic_more) { moreMenu(it) }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(-4) })
        page.addView(top)

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setPadding(dp(20), 0, dp(20), dp(14))
        }
        // Editable like One UI: tap to place the blue cursor, keys insert there, backspace deletes before it.
        numberText = EditText(this).apply {
            setTextColor(pal.text)
            gravity = Gravity.CENTER
            isSingleLine = true
            background = null
            setPadding(0, 0, 0, 0)
            showSoftInputOnFocus = false
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            filters = arrayOf(android.text.InputFilter { src, start, end, _, _, _ ->
                val kept = src.subSequence(start, end).filter { it.isDigit() || it in "+*#,;" }
                if (kept.length == end - start) null else kept
            }, android.text.InputFilter.LengthFilter(40))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            textCursorDrawable = GradientDrawable().apply { setColor(pal.blue); setSize(dp(2), dp(56)) }
            highlightColor = 0x5533A0F0
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = renderKeypad()
            })
        }
        suggestions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameText = TextView(this).apply {
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        simChip = TextView(this).apply {
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 0.9f)
            setPadding(0, 0, 0, 0)
            background = pal.ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.keypadBg); setStroke(dp(1), pal.divider) })
            setOnClickListener { chooseSim() }
        }
        val paste = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(14), dp(26), dp(14))
            background = pal.ripple(GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pal.pasteA, pal.pasteB))
                .apply { cornerRadius = dp(28).toFloat() })
            addView(ImageView(this@ControllerActivity).apply { setImageResource(R.drawable.ic_paste); tint(pal.text) },
                LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(TextView(this@ControllerActivity).apply {
                text = "Paste number from clipboard"
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
            setOnClickListener { pasteNumber() }
        }
        pastePill = paste
        display.addView(suggestions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        display.addView(nameText, LinearLayout.LayoutParams(-2, -2))
        display.addView(numberText, LinearLayout.LayoutParams(-1, dp(64)))
        display.addView(paste, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        page.addView(display, LinearLayout.LayoutParams(-1, 0, 1f))

        val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
        // One UI: three ~109dp columns centred on screen, rows 76dp apart, 36sp digits, 13sp letters.
        val grid = GridLayout(this).apply { columnCount = 3; setPadding(dp(29), 0, dp(29), 0) }
        for ((d, letters) in keys) {
            // Every key has the same two fixed-height lines (digit, then letters/icon), so 1, * and # line up
            // with the others exactly like One UI; letters sit ~24dp below the digit's centre.
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                setPadding(0, dp(7), 0, 0)
                background = pal.circleRipple()
                val digitBox = FrameLayout(this@ControllerActivity)
                if (d == "*") digitBox.addView(ImageView(this@ControllerActivity).apply { setImageResource(R.drawable.ic_asterisk); tint(pal.text) },
                    FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                else digitBox.addView(TextView(this@ControllerActivity).apply {
                    text = d
                    setTextColor(pal.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    includeFontPadding = false
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                addView(digitBox, LinearLayout.LayoutParams(-1, dp(42)))
                val sub = FrameLayout(this@ControllerActivity)
                if (d == "1") sub.addView(ImageView(this@ControllerActivity).apply { setImageResource(R.drawable.ic_voicemail); tint(pal.sub) },
                    FrameLayout.LayoutParams(dp(18), dp(14), Gravity.CENTER))
                else if (letters.isNotEmpty()) sub.addView(TextView(this@ControllerActivity).apply {
                    text = letters
                    setTextColor(pal.sub)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (d == "0") 17f else 13f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                addView(sub, LinearLayout.LayoutParams(-1, dp(16)).apply { topMargin = dp(if (d == "0") -1 else -4) })
                setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); typeKey(d) }
                if (d == "0") setOnLongClickListener { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); typeKey("+"); true }
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                .apply { width = 0; height = dp(76) })
        }
        page.addView(grid, LinearLayout.LayoutParams(-1, -2))

        // Call row uses the same three columns: [spare] [65dp green call] [backspace].
        val callRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(29), dp(8), dp(29), 0)
        }
        // Left slot (where One UI shows its video-call button): round SIM picker, Jazz / Onic.
        val simBox = FrameLayout(this)
        simBox.addView(simChip, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
        callRow.addView(simBox, LinearLayout.LayoutParams(0, dp(65), 1f))
        val callBox = FrameLayout(this)
        val callBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_call)
            tint(Color.WHITE)
            setPadding(dp(19), dp(19), dp(19), dp(19))
            background = pal.ripple(pal.circle(pal.green))
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); dialFromKeypad() }
        }
        callBox.addView(callBtn, FrameLayout.LayoutParams(dp(65), dp(65), Gravity.CENTER))
        callRow.addView(callBox, LinearLayout.LayoutParams(0, dp(65), 1f))
        val bsBox = FrameLayout(this)
        backspace = ImageView(this).apply {
            setImageResource(R.drawable.ic_backspace_filled)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = pal.circleRipple()
            setOnClickListener { deleteKey() }
            setOnLongClickListener { setDigits(""); true }
        }
        bsBox.addView(backspace, FrameLayout.LayoutParams(dp(56), dp(48), Gravity.CENTER))
        callRow.addView(bsBox, LinearLayout.LayoutParams(0, dp(65), 1f))
        page.addView(callRow)
        // Space under the call button for the floating bar (One UI keeps the keypad in place while typing).
        page.addView(View(this), LinearLayout.LayoutParams(-1, dp(109)))
        return page
    }

    private fun setDigits(s: String) {
        numberText.setText(s.take(40))
        numberText.setSelection(numberText.length())
        renderKeypad()
    }

    /** Inserts at the cursor (replacing any selected text). */
    private fun typeKey(s: String) {
        val e = numberText
        if (!e.hasFocus()) e.requestFocus()
        val start = e.selectionStart.let { if (it < 0) e.length() else it }
        val end = e.selectionEnd.let { if (it < 0) start else it }
        e.text.replace(minOf(start, end), maxOf(start, end), s)
    }

    /** Deletes the selection, or the character just before the cursor. */
    private fun deleteKey() {
        val e = numberText
        val start = e.selectionStart.let { if (it < 0) e.length() else it }
        val end = e.selectionEnd.let { if (it < 0) start else it }
        if (start != end) e.text.delete(minOf(start, end), maxOf(start, end))
        else if (start > 0) e.text.delete(start - 1, start)
    }

    /** Shrinks the number from 48sp down to 24sp so it always fits on one line, like One UI. */
    private fun fitNumber(n: String) {
        val avail = (numberText.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(40))).toFloat()
        val p = android.graphics.Paint().apply { typeface = numberText.typeface }
        val density = resources.displayMetrics.density * resources.configuration.fontScale
        var sp = 48f
        while (sp > 24f) {
            p.textSize = sp * density
            if (p.measureText(n) <= avail * 0.96f) break
            sp -= 1f
        }
        numberText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private val t9 = mapOf('a' to '2', 'b' to '2', 'c' to '2', 'd' to '3', 'e' to '3', 'f' to '3', 'g' to '4', 'h' to '4',
        'i' to '4', 'j' to '5', 'k' to '5', 'l' to '5', 'm' to '6', 'n' to '6', 'o' to '6', 'p' to '7', 'q' to '7', 'r' to '7',
        's' to '7', 't' to '8', 'u' to '8', 'v' to '8', 'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9')

    private fun toT9(s: String) = s.lowercase().map { c -> if (c.isDigit()) c else t9[c] ?: ' ' }.joinToString("")

    /** Where in [name] the typed digits match (as T9 letters), or null. Tries each word start. */
    private fun t9Match(name: String, d: String): IntRange? {
        val keys = toT9(name)
        for (i in keys.indices) {
            if ((i == 0 || keys[i - 1] == ' ') && keys.startsWith(d, i)) return i until i + d.length
        }
        return null
    }

    /** Contacts matching the typed digits by name (T9, e.g. 7285 → Paul) or by number, shown above the number. */
    private fun renderSuggestions(d: String) {
        suggestions.removeAllViews()
        val q = d.filter { it.isDigit() }
        if (q.isEmpty() || q != d || people.isEmpty()) return
        data class Hit(val p: Person, val number: String, val nameRange: IntRange?, val score: Int)
        val hits = ArrayList<Hit>()
        for (p in people) {
            if (p.numbers.isEmpty()) continue
            val nr = t9Match(p.name, q)
            val byNumber = p.numbers.firstOrNull { it.filter { c -> c.isDigit() }.contains(q) }
            when {
                nr != null -> hits += Hit(p, byNumber ?: p.numbers.first(), nr, if (nr.first == 0) 0 else 1)
                byNumber != null -> hits += Hit(p, byNumber, null, 2)
            }
        }
        if (hits.isEmpty()) return
        val sorted = hits.sortedWith(compareBy<Hit>({ it.score }, { !it.p.starred }, { it.p.name.lowercase() }))
        for (h in sorted.take(3)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = pal.ripple(pal.rounded(pal.keypadBg, dp(16).toFloat()))
                setOnClickListener { setDigits(clean(h.number)) }
            }
            row.addView(TextView(this).apply {
                text = initialOf(h.p.name)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    pal.tile(h.p.name).let { intArrayOf(it.first, it.second) }).apply { shape = GradientDrawable.OVAL }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = highlight(h.p.name, h.nameRange)
            })
            texts.addView(TextView(this).apply {
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                val n = formatNumber(clean(h.number))
                text = if (h.nameRange == null) highlightDigits(n, q) else n
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
            suggestions.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (sorted.size > 3) suggestions.addView(TextView(this).apply {
            text = "${sorted.size - 3} more"
            setTextColor(pal.blue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(64), dp(2), 0, dp(2))
        })
    }

    private fun highlight(s: String, r: IntRange?): CharSequence {
        if (r == null) return s
        return android.text.SpannableString(s).apply {
            setSpan(android.text.style.ForegroundColorSpan(pal.blue), r.first, minOf(r.last + 1, s.length), 0)
        }
    }

    /** Highlights the typed digits inside a formatted number (skipping spaces). */
    private fun highlightDigits(n: String, q: String): CharSequence {
        val at = n.filter { it.isDigit() }.indexOf(q)
        if (at < 0) return n
        var seen = 0
        var from = -1
        var to = -1
        for ((i, c) in n.withIndex()) {
            if (!c.isDigit()) continue
            if (seen == at) from = i
            if (seen == at + q.length - 1) { to = i + 1; break }
            seen++
        }
        if (from < 0 || to < 0) return n
        return android.text.SpannableString(n).apply { setSpan(android.text.style.ForegroundColorSpan(pal.blue), from, to, 0) }
    }

    private fun formatNumber(n: String): String =
        if (n.length == 11 && n.startsWith("03") && n.all { it.isDigit() }) n.substring(0, 4) + " " + n.substring(4) else n

    private fun pasteNumber() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val n = clean(text)
        if (n.isEmpty()) Toast.makeText(this, "No phone number in the clipboard", Toast.LENGTH_SHORT).show() else setDigits(n)
    }

    private fun dialFromKeypad() {
        if (digits.isEmpty()) {
            (RelayState.callLog.firstOrNull { it.type == 2 }?.number ?: prefs.recent.firstOrNull())?.let { setDigits(clean(it)) }
            return
        }
        dial(digits.toString())
    }

    private fun renderKeypad() {
        val n = digits.toString()
        fitNumber(n)
        numberText.isCursorVisible = n.isNotEmpty()
        renderSuggestions(n)
        nameText.text = if (n.length >= 7) Contacts.name(this, n) ?: "" else ""
        nameText.visibility = if (nameText.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        backspace.visibility = if (n.isEmpty()) View.INVISIBLE else View.VISIBLE
        addContactBtn.visibility = if (n.isEmpty()) View.GONE else View.VISIBLE
        updateNav()
        val clipHasText = runCatching {
            getSystemService(ClipboardManager::class.java).primaryClipDescription?.let {
                it.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || it.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            } ?: false
        }.getOrDefault(false)
        pastePill.visibility = if (n.isEmpty() && clipHasText) View.VISIBLE else View.GONE
        val simSel = RelayState.sims.firstOrNull { it.id == prefs.simChoice }
        simChip.text = simSel?.let { "SIM ${it.id + 1}\n${it.label.substringBefore(" (")}" }
            ?: if (RelayState.sims.isEmpty()) "SIM" else "Auto\nSIM"
        simChip.visibility = if (n.isEmpty()) View.GONE else View.VISIBLE
    }

    // =====================================================================================
    // Recents
    // =====================================================================================

    private fun buildRecents(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(pal.bg); isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(130)) }
        col.addView(bigTitle("Phone"))
        val filterBtn = iconButton(R.drawable.ic_filter) { filterMenu(it) }
        recentsSearch = searchField("Search recents") { renderedLog = null; renderRecents() }
        col.addView(toolbar(filterBtn, iconButton(R.drawable.ic_search) { toggleSearch(recentsSearch) }, iconButton(R.drawable.ic_more) { moreMenu(it) }))
        col.addView(recentsSearch, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) })
        recentsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recentsBox)
        scroll.addView(col)
        return scroll
    }

    private fun filterMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        listOf("All calls", "Missed calls", "Incoming calls", "Outgoing calls").forEachIndexed { i, s -> pm.menu.add(0, i, i, s) }
        pm.setOnMenuItemClickListener { recentsFilter = it.itemId; renderedLog = null; renderRecents(); true }
        pm.show()
    }

    private data class Group(val first: CallEntry, val count: Int)

    private fun dayLabel(t: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
        return when {
            DateUtils.isToday(t) -> "Today"
            DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
            sameYear -> SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(t))
            else -> SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date(t))
        }
    }

    private fun isMissed(type: Int) = type == 3 || type == 5 || type == 6

    private fun renderRecents() {
        val log = RelayState.callLog
        if (renderedLog === log) return
        renderedLog = log
        recentsBox.removeAllViews()
        val q = recentsSearch.text.toString().trim().lowercase()
        val filtered = log.filter {
            when (recentsFilter) { 1 -> isMissed(it.type); 2 -> it.type == 1; 3 -> it.type == 2; else -> true }
        }.filter {
            q.isEmpty() || it.number.contains(q) || displayName(it).lowercase().contains(q)
        }
        if (filtered.isEmpty()) {
            recentsBox.addView(TextView(this).apply {
                text = when {
                    !RelayState.connected -> "Connect to the SIM phone to see its call history."
                    q.isNotEmpty() -> "No results"
                    else -> "No calls"
                }
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(48), dp(24), dp(24))
            })
            return
        }
        // Consecutive calls with the same person and direction collapse into one row, e.g. "Yousaf (2)".
        val groups = mutableListOf<Group>()
        for (e in filtered) {
            val last = groups.lastOrNull()
            if (last != null && threadKey(last.first.number) == threadKey(e.number) &&
                (isMissed(last.first.type) == isMissed(e.type)) && last.first.type == e.type &&
                dayLabel(last.first.date) == dayLabel(e.date)) groups[groups.size - 1] = last.copy(count = last.count + 1)
            else groups += Group(e, 1)
        }
        var currentDay = ""
        var card: LinearLayout? = null
        for (g in groups) {
            val day = dayLabel(g.first.date)
            if (day != currentDay) {
                currentDay = day
                recentsBox.addView(TextView(this).apply {
                    text = day
                    setTextColor(pal.sub)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                    setPadding(dp(28), dp(25), dp(24), dp(10))
                })
                card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = pal.rounded(pal.card, dp(26).toFloat())
                    clipToOutline = true
                }
                recentsBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(10); rightMargin = dp(10) })
            } else {
                card!!.addView(View(this).apply { setBackgroundColor(pal.divider) },
                    LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(18); rightMargin = dp(18) })
            }
            card!!.addView(recentRow(g))
        }
    }

    private fun displayName(e: CallEntry) =
        e.name.ifBlank { Contacts.name(this, e.number) ?: formatNumber(e.number).ifBlank { "Unknown" } }

    private fun recentRow(g: Group): View {
        val e = g.first
        val key = "${e.date}|${e.number}"
        val missed = isMissed(e.type)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pal.ripple(pal.rounded(pal.card, 0f))
            setPadding(dp(17), dp(16), dp(19), dp(16))
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(ImageView(this).apply {
            setImageResource(if (e.type == 2) R.drawable.ic_arrow_out else R.drawable.ic_arrow_in)
            tint(when { missed -> pal.red; e.type == 2 -> pal.green; else -> pal.blue })
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        line.addView(TextView(this).apply {
            text = displayName(e) + if (g.count > 1) " (${g.count})" else ""
            setTextColor(if (missed) pal.red else pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        line.addView(TextView(this).apply {
            text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(e.date))
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        line.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_message)
            tint(pal.blue)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = pal.circleRipple()
            contentDescription = "Message"
            setOnClickListener { openThread(e.number) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8); topMargin = -dp(8); bottomMargin = -dp(8) })
        content.addView(line)

        if (expandedRow == key) {
            val kind = when (e.type) { 1 -> "Incoming"; 2 -> "Outgoing"; 3 -> "Missed"; 5 -> "Rejected"; 6 -> "Blocked"; else -> "Call" }
            val dur = if (e.durationSec > 0) " · %d:%02d".format(e.durationSec / 60, e.durationSec % 60) else ""
            content.addView(TextView(this).apply {
                text = "${formatNumber(e.number)}\n$kind call$dur"
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(46), dp(8), 0, dp(10))
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(40), 0, 0, 0) }
            fun act(icon: Int, color: Int, f: () -> Unit) = actions.addView(ImageView(this).apply {
                setImageResource(icon); tint(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = pal.ripple(pal.circle(color))
                setOnClickListener { f() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) })
            act(R.drawable.ic_call, pal.green) { dial(e.number) }
            act(R.drawable.ic_message, pal.blue) { openThread(e.number) }
            act(R.drawable.ic_dialpad, pal.sub) { setDigits(clean(e.number)); selectTab(0) }
            content.addView(actions)
        }
        content.setOnClickListener {
            expandedRow = if (expandedRow == key) null else key
            renderedLog = null
            renderRecents()
        }
        return SwipeRow(this, pal, content, onCall = { dial(e.number) }, onMessage = { openThread(e.number) })
    }

    // =====================================================================================
    // Contacts
    // =====================================================================================

    private fun buildContacts(): View {
        val page = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        contactsList = ListView(this).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(130))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(bigTitle("Contacts"))
        contactsSearch = searchField("Search contacts") { contactsAdapter.rebuild() }
        header.addView(toolbar(
            iconButton(R.drawable.ic_add) {
                runCatching { startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)) }
            },
            iconButton(R.drawable.ic_search) { toggleSearch(contactsSearch) },
            iconButton(R.drawable.ic_more) { moreMenu(it) },
        ))
        header.addView(contactsSearch, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) })
        contactsPrompt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(24))
            addView(TextView(this@ControllerActivity).apply {
                text = "Allow Dialer to read your contacts to show them here."
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@ControllerActivity).apply {
                text = "Allow"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(28), dp(12), dp(28), dp(12))
                background = pal.ripple(pal.rounded(pal.blue, dp(24).toFloat()))
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 3) }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        }
        header.addView(contactsPrompt)
        favLabel = TextView(this).apply {
            text = "★  Favorites"
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(30), dp(18), dp(24), dp(12))
        }
        header.addView(favLabel)
        favGrid = GridLayout(this).apply { columnCount = 3; setPadding(dp(10), 0, dp(10), 0) }
        header.addView(favGrid)
        contactsList.addHeaderView(header, null, false)
        contactsList.adapter = contactsAdapter
        page.addView(contactsList, FrameLayout.LayoutParams(-1, -1))
        return page
    }

    private fun loadContacts() {
        if (!::contactsList.isInitialized) return
        contactsPrompt.visibility = if (ContactsRepo.allowed(this)) View.GONE else View.VISIBLE
        ContactsRepo.load(this) { list ->
            people = list
            renderFavorites()
            contactsAdapter.rebuild()
        }
    }

    private fun renderFavorites() {
        favGrid.removeAllViews()
        val q = contactsSearch.text.toString().trim()
        val favs = if (q.isEmpty()) people.filter { it.starred } else emptyList()
        favLabel.visibility = if (favs.isEmpty()) View.GONE else View.VISIBLE
        favGrid.visibility = favLabel.visibility
        val gap = dp(10)
        val w = (resources.displayMetrics.widthPixels - dp(20) - gap * 2) / 3
        favs.forEachIndexed { i, p ->
            val tile = FrameLayout(this).apply {
                background = pal.gradient(p.name, dp(22).toFloat())
                clipToOutline = true
                foreground = pal.ripple(null)
                setOnClickListener { contactActions(p) }
            }
            val photo = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val ring = TextView(this).apply {
                text = initialOf(p.name)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), 0x66FFFFFF) }
            }
            val name = TextView(this).apply {
                text = p.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(6f, 0f, 1f, 0x55000000)
            }
            tile.addView(photo, FrameLayout.LayoutParams(-1, -1))
            tile.addView(ring, FrameLayout.LayoutParams((w * 0.55f).toInt(), (w * 0.55f).toInt(), Gravity.CENTER_HORIZONTAL or Gravity.TOP)
                .apply { topMargin = (w * 0.15f).toInt() })
            tile.addView(name, FrameLayout.LayoutParams(-1, (w * 0.54f).toInt(), Gravity.BOTTOM).apply { setMargins(dp(6), 0, dp(6), 0) })
            ContactsRepo.loadPhoto(photo, p.photo) { has ->
                ring.visibility = if (has) View.GONE else View.VISIBLE
                photo.visibility = if (has) View.VISIBLE else View.GONE
            }
            favGrid.addView(tile, GridLayout.LayoutParams().apply {
                width = w; height = (w * 1.19f).toInt()
                setMargins(if (i % 3 == 0) 0 else gap / 2, gap / 2, if (i % 3 == 2) 0 else gap / 2, gap / 2)
            })
        }
    }

    private sealed class Item {
        data class Letter(val letter: String) : Item()
        data class Row(val p: Person, val first: Boolean, val last: Boolean) : Item()
    }

    private inner class ContactsAdapter : BaseAdapter() {
        private var items: List<Item> = emptyList()

        fun rebuild() {
            val q = contactsSearch.text.toString().trim().lowercase()
            val list = if (q.isEmpty()) people else people.filter { it.name.lowercase().contains(q) }
            val out = mutableListOf<Item>()
            list.groupBy { initialOf(it.name).let { c -> if (c[0].isLetter()) c else "#" } }.toSortedMap(compareBy { if (it == "#") "￿" else it })
                .forEach { (letter, ps) ->
                    out += Item.Letter(letter)
                    ps.forEachIndexed { i, p -> out += Item.Row(p, i == 0, i == ps.size - 1) }
                }
            items = out
            renderFavorites()
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Item.Letter) 0 else 1
        override fun isEnabled(position: Int) = items[position] is Item.Row

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val it = items[position]) {
                is Item.Letter -> ((convertView as? TextView) ?: TextView(this@ControllerActivity).apply {
                    setTextColor(pal.sub)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(30), dp(20), dp(24), dp(8))
                }).apply { text = it.letter }
                is Item.Row -> {
                    val row = (convertView as? ContactRow) ?: ContactRow()
                    row.bind(it)
                    row
                }
            }
        }
    }

    private inner class ContactRow : FrameLayout(this@ControllerActivity) {
        private val inner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        private val avatarBox = FrameLayout(context)
        private val initial = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER
        }
        private val photo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = pal.circle(Color.TRANSPARENT)
            clipToOutline = true
        }
        private val name = TextView(context).apply {
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val line = View(context).apply { setBackgroundColor(pal.divider) }
        private var person: Person? = null
        /** Rounded (per position in its letter group) and clipping, so the swipe colours keep the card's corners. */
        private val clip = FrameLayout(context).apply { clipToOutline = true }

        init {
            avatarBox.addView(initial, LayoutParams(-1, -1))
            avatarBox.addView(photo, LayoutParams(-1, -1))
            inner.addView(avatarBox, LinearLayout.LayoutParams(dp(46), dp(46)))
            inner.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(18) })
            inner.background = pal.ripple(pal.rounded(pal.card, 0f))
            val swipe = SwipeRow(context, pal, inner,
                onCall = { person?.let { p -> withNumber(p) { dial(it) } } },
                onMessage = { person?.let { p -> withNumber(p) { openThread(it) } } })
            clip.addView(swipe, LayoutParams(-1, -2))
            addView(clip, LayoutParams(-1, -2).apply { leftMargin = dp(10); rightMargin = dp(10) })
            addView(line, LayoutParams(-1, 1, Gravity.BOTTOM).apply { leftMargin = dp(92); rightMargin = dp(28) })
            inner.setOnClickListener { person?.let { contactActions(it) } }
        }

        fun bind(it: Item.Row) {
            person = it.p
            val r = dp(26).toFloat()
            val radii = floatArrayOf(
                if (it.first) r else 0f, if (it.first) r else 0f, if (it.first) r else 0f, if (it.first) r else 0f,
                if (it.last) r else 0f, if (it.last) r else 0f, if (it.last) r else 0f, if (it.last) r else 0f)
            clip.background = GradientDrawable().apply { setColor(pal.card); cornerRadii = radii }
            line.visibility = if (it.last) View.GONE else View.VISIBLE
            name.text = it.p.name
            initial.text = initialOf(it.p.name)
            initial.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                pal.tile(it.p.name).let { c -> intArrayOf(c.first, c.second) }).apply { shape = GradientDrawable.OVAL }
            ContactsRepo.loadPhoto(photo, it.p.photo) { has -> photo.visibility = if (has) View.VISIBLE else View.GONE }
        }
    }

    /** Runs [action] with the contact's number, asking which one when there are several. */
    private fun withNumber(p: Person, action: (String) -> Unit) {
        val numbers = ContactsRepo.numbers(this, p)
        // A saved default skips the question (cleared from the contact's menu).
        prefs.defaultNumber(p.name)?.let { def -> numbers.firstOrNull { threadKey(it) == threadKey(def) }?.let { return action(it) } }
        when (numbers.size) {
            0 -> Toast.makeText(this, "${p.name} has no phone number", Toast.LENGTH_SHORT).show()
            1 -> action(numbers[0])
            else -> pickNumber(p, numbers, action)
        }
    }

    /** Number chooser with "Always use this number", like One UI's "Set as default". */
    private fun pickNumber(p: Person, numbers: List<String>, action: (String) -> Unit) {
        val remember = android.widget.CheckBox(this).apply {
            text = "Always use this number"
            setPadding(dp(8), 0, 0, 0)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(4), dp(20), dp(4)); addView(remember) }
        AlertDialog.Builder(this).setTitle(p.name)
            .setItems(numbers.map { formatNumber(clean(it)) }.toTypedArray()) { _, w ->
                if (remember.isChecked) {
                    prefs.setDefaultNumber(p.name, numbers[w])
                    Toast.makeText(this, "${formatNumber(clean(numbers[w]))} set as default for ${p.name}", Toast.LENGTH_SHORT).show()
                }
                action(numbers[w])
            }
            .setView(box)
            .show()
    }

    private fun contactActions(p: Person) {
        val numbers = ContactsRepo.numbers(this, p)
        val def = prefs.defaultNumber(p.name)?.takeIf { d -> numbers.any { threadKey(it) == threadKey(d) } }
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        // Default number first, marked with a star.
        val ordered = if (def == null) numbers else numbers.sortedBy { threadKey(it) != threadKey(def) }
        fun star(n: String) = if (def != null && threadKey(n) == threadKey(def)) "  ★" else ""
        for (n in ordered) {
            labels += "📞  Call  ${formatNumber(clean(n))}${star(n)}"; actions += { dial(n) }
        }
        for (n in ordered) {
            labels += "💬  Message  ${formatNumber(clean(n))}${star(n)}"; actions += { openThread(n) }
        }
        if (numbers.size > 1) {
            if (def == null) {
                labels += "★  Set default number"
                actions += { pickNumber(p, numbers) { n -> prefs.setDefaultNumber(p.name, n); Toast.makeText(this, "Default set", Toast.LENGTH_SHORT).show() } }
            } else {
                labels += "☆  Clear default number"
                actions += { prefs.setDefaultNumber(p.name, null); Toast.makeText(this, "Default cleared for ${p.name}", Toast.LENGTH_SHORT).show() }
            }
        }
        labels += "👤  Open in Contacts"
        actions += {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, p.id))) }
        }
        AlertDialog.Builder(this).setTitle(p.name)
            .setItems(labels.toTypedArray()) { _, w -> actions[w]() }
            .show()
    }

    // =====================================================================================
    // In-call screen
    // =====================================================================================

    private fun roundAction(icon: Int, label: String, onClick: () -> Unit): Pair<View, ImageView> {
        val img = ImageView(this).apply {
            setImageResource(icon)
            tint(pal.text)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = pal.ripple(pal.circle(pal.roundBtn))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(img, LinearLayout.LayoutParams(dp(68), dp(68)))
            addView(TextView(this@ControllerActivity).apply {
                text = label
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            img.setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
        }
        return box to img
    }

    private fun bigCircle(icon: Int, color: Int, label: String?, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@ControllerActivity).apply {
            setImageResource(icon)
            tint(Color.WHITE)
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = pal.ripple(pal.circle(color))
            elevation = dp(3).toFloat()
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
        }, LinearLayout.LayoutParams(dp(78), dp(78)))
        if (label != null) addView(TextView(this@ControllerActivity).apply {
            text = label
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
    }

    private fun buildCallView(): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if (pal.dark) intArrayOf(0xFF0E1C2B.toInt(), 0xFF000000.toInt()) else intArrayOf(0xFFE3EEF9.toInt(), 0xFFFCFCFC.toInt()))
            isClickable = true
            visibility = View.GONE
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconButton(R.drawable.ic_chevron_down) { callCollapsed = true; render() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        callSimText = TextView(this).apply {
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        top.addView(callSimText, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(View(this), LinearLayout.LayoutParams(dp(48), dp(48)))
        v.addView(top, LinearLayout.LayoutParams(-1, -2))

        callStatus = TextView(this).apply { setTextColor(pal.sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); gravity = Gravity.CENTER }
        callName = TextView(this).apply {
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        callNumber = TextView(this).apply { setTextColor(pal.sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER }
        callAvatar = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            gravity = Gravity.CENTER
        }
        v.addView(callStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(36) })
        v.addView(callName, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        v.addView(callNumber, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        v.addView(callAvatar, LinearLayout.LayoutParams(dp(128), dp(128)).apply { topMargin = dp(40) })
        v.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Two rows like One UI's in-call screen: Add call · Bluetooth · Speaker / Mute · Message · Hide.
        addCallBtn = roundAction(R.drawable.ic_add, "Add call") {
            if (RelayState.liveCalls.size >= 2) {
                ControllerService.start(this, ControllerService.ACTION_MERGE)
                return@roundAction
            }
            addingCall = true
            callCollapsed = true
            setDigits("")
            selectTab(0)
            render()
            Toast.makeText(this, "Dial the second number", Toast.LENGTH_SHORT).show()
        }
        btBtn = roundAction(R.drawable.ic_bluetooth, "Bluetooth") {
            ControllerService.start(this, ControllerService.ACTION_BT) { putExtra(ControllerService.EXTRA_ON, !RelayState.bt) }
        }
        speakerBtn = roundAction(R.drawable.ic_speaker, "Speaker") {
            ControllerService.start(this, ControllerService.ACTION_SPEAKER) { putExtra(ControllerService.EXTRA_ON, !RelayState.speaker) }
        }
        muteBtn = roundAction(R.drawable.ic_mic, "Mute") {
            ControllerService.start(this, ControllerService.ACTION_MUTE) { putExtra(ControllerService.EXTRA_ON, !RelayState.muted) }
        }
        keypadBtn = roundAction(R.drawable.ic_dialpad, "Keypad") { showCallKeypad(true) }
        // Hold / Resume; turns into Swap when there are two calls.
        holdBtn = roundAction(R.drawable.ic_pause, "Hold") {
            val calls = RelayState.liveCalls
            when {
                !RelayState.companion -> showCallKeypad(true) // shows the "Enable call control" hint
                calls.size >= 2 -> ControllerService.start(this, ControllerService.ACTION_SWAP)
                else -> ControllerService.start(this, ControllerService.ACTION_HOLD) {
                    putExtra(ControllerService.EXTRA_ON, calls.none { it.state == "holding" })
                }
            }
        }
        heldBar = TextView(this).apply {
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = pal.ripple(pal.rounded(pal.roundBtn, dp(22).toFloat()))
            visibility = View.GONE
            setOnClickListener { ControllerService.start(this@ControllerActivity, ControllerService.ACTION_SWAP) }
        }
        v.addView(heldBar, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) })
        replyLink = TextView(this).apply {
            text = "💬  Reply with message"
            setTextColor(pal.blue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener { quickReply() }
        }
        silenceLink = TextView(this).apply {
            text = "🔕  Silence"
            setTextColor(pal.blue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener { silenceRinging() }
        }
        val ringLinks = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(silenceLink)
            addView(replyLink)
        }
        v.addView(ringLinks, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(24) })
        // One UI layout: Add call/Merge · Hold/Swap · Speaker  /  Mute · Keypad · Bluetooth.
        // Actions are hidden while the tone keypad is open (One UI swaps them the same way).
        for (row in listOf(listOf(addCallBtn.first, holdBtn.first, speakerBtn.first), listOf(muteBtn.first, keypadBtn.first, btBtn.first))) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { r.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            v.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(26) })
            callActionRows += r
        }
        callKeypad = buildCallKeypad()
        v.addView(callKeypad, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        v.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))

        ringRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(bigCircle(R.drawable.ic_call_end, pal.red, "Decline") { ControllerService.start(this@ControllerActivity, ControllerService.ACTION_END) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(bigCircle(R.drawable.ic_call, pal.green, "Answer") { ControllerService.start(this@ControllerActivity, ControllerService.ACTION_ANSWER) },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        endRow = bigCircle(R.drawable.ic_call_end, pal.red, null) {
            pendingDial = null
            ControllerService.start(this, ControllerService.ACTION_END)
            render()
        }
        v.addView(ringRow, LinearLayout.LayoutParams(-1, -2))
        v.addView(endRow, LinearLayout.LayoutParams(-2, -2))
        return v
    }

    /** Tone keypad for menus ("press 1 for…"): each key is played into the call on the SIM phone. */
    private fun buildCallKeypad(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        toneDigits = TextView(this).apply {
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        box.addView(toneDigits, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) })
        val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
        val grid = GridLayout(this).apply { columnCount = 3; setPadding(dp(5), 0, dp(5), 0) }
        for ((d, letters) in keys) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = pal.circleRipple()
                addView(TextView(this@ControllerActivity).apply {
                    text = d
                    setTextColor(pal.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                })
                addView(TextView(this@ControllerActivity).apply {
                    text = letters
                    setTextColor(pal.sub)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                })
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    toneDigits.append(d)
                    ControllerService.start(this@ControllerActivity, ControllerService.ACTION_DTMF) {
                        putExtra(ControllerService.EXTRA_NUMBER, d)
                    }
                }
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                .apply { width = 0; height = dp(64) })
        }
        box.addView(grid)
        box.addView(TextView(this).apply {
            text = "Hide keypad"
            setTextColor(pal.blue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { showCallKeypad(false) }
        })
        return box
    }

    private fun showCallKeypad(open: Boolean) {
        if (open && !RelayState.companion) {
            AlertDialog.Builder(this).setTitle("Keypad tones")
                .setMessage("To send keypad tones (for menus like \"press 1\"), open Dialer on the SIM phone and tap \"Enable call control\" once.")
                .setPositiveButton("OK", null).show()
            return
        }
        callKeypadOpen = open
        callKeypad.visibility = if (open) View.VISIBLE else View.GONE
        callAvatar.visibility = if (open) View.GONE else View.VISIBLE
        callActionRows.forEach { it.visibility = if (open) View.GONE else View.VISIBLE }
    }

    /** The bottom gap leaves room for the nav bar, which is hidden while ringing. */
    private fun applyCallPadding() {
        val ringing = RelayState.callState == "ringing"
        callView.setPadding(dp(24), topInset + dp(8), dp(24), bottomInset + dp(if (ringing) 40 else 96))
    }

    private fun renderCall() {
        val state = RelayState.callState
        if (state != lastCallState) {
            if (state == "ringing" || (lastCallState == "idle" && state == "offhook" && !addingCall)) callCollapsed = false
            if (state == "ringing" && callKeypadOpen) showCallKeypad(false)
            if (state == "offhook") pendingDial = null
            if (state == "idle") {
                addingCall = false
                pendingDial = null
                if (callKeypadOpen) showCallKeypad(false)
                toneDigits.text = ""
            }
            lastCallState = state
        }
        updateNav()
        applyCallPadding()
        // Show the call screen the moment Call is tapped, before the SIM phone reports back (give up after 12 s).
        if (pendingDial != null && System.currentTimeMillis() - pendingSince > 12_000) pendingDial = null
        val dialing = pendingDial != null && state == "idle"
        val active = state != "idle" || dialing
        if (dialing) {
            callView.visibility = if (callCollapsed) View.GONE else View.VISIBLE
            returnBar.visibility = if (callCollapsed) View.VISIBLE else View.GONE
            returnBar.text = "Return to call  ·  Calling…"
            val n = pendingDial!!
            val name = Contacts.name(this, n)
            callName.text = name ?: formatNumber(n)
            callNumber.text = if (name != null) formatNumber(n) else ""
            callStatus.text = "Calling…"
            callSimText.text = simLabelFor(prefs.simChoice)
            callAvatar.text = initialOf(name ?: "#")
            callAvatar.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                pal.tile(name ?: n).let { intArrayOf(it.first, it.second) }).apply { shape = GradientDrawable.OVAL }
            ringRow.visibility = View.GONE
            endRow.visibility = View.VISIBLE
            callActionRows.forEach { it.visibility = View.VISIBLE }
            return
        }
        callView.visibility = if (active && !callCollapsed) View.VISIBLE else View.GONE
        // With call control the SIM phone tells us when the other person answers, so the timer starts then
        // (like the phone's own call screen). Without it we only know the line is open, so count from dialing.
        val answered = RelayState.answeredAt
        val waiting = RelayState.companion && answered == 0L && state == "offhook"
        val since = if (RelayState.companion && answered > 0L) answered else RelayState.callSince
        val secs = (System.currentTimeMillis() - since) / 1000
        val timer = if (waiting) "Calling…" else "%02d:%02d".format(secs / 60, secs % 60)
        returnBar.visibility = if (active && callCollapsed) View.VISIBLE else View.GONE
        returnBar.text = "Return to call  ·  $timer"
        if (!active) return
        val n = RelayState.callNumber
        val name = n?.let { Contacts.name(this, it) }
        callName.text = name ?: n?.let { formatNumber(clean(it)) } ?: "Unknown"
        callNumber.text = if (name != null) formatNumber(clean(n)) else ""
        callStatus.text = if (state == "ringing") "Incoming call" else timer
        // SIM actually used (from call control), else the SIM chosen for calls.
        val liveSim = RelayState.liveCalls.firstOrNull { it.state == "active" || it.state == "dialing" || it.state == "ringing" }?.sim
            ?: RelayState.liveCalls.firstOrNull()?.sim ?: -1
        callSimText.text = simLabelFor(if (liveSim >= 0) liveSim else if (state == "ringing") -1 else prefs.simChoice)
        callAvatar.text = initialOf(name ?: "#")
        callAvatar.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            pal.tile(name ?: n ?: "?").let { intArrayOf(it.first, it.second) }).apply { shape = GradientDrawable.OVAL }
        ringRow.visibility = if (state == "ringing") View.VISIBLE else View.GONE
        endRow.visibility = if (state == "offhook") View.VISIBLE else View.GONE
        muteBtn.first.visibility = if (state == "offhook") View.VISIBLE else View.INVISIBLE
        speakerBtn.first.visibility = muteBtn.first.visibility
        addCallBtn.first.visibility = muteBtn.first.visibility
        btBtn.first.visibility = muteBtn.first.visibility
        muteBtn.second.background = pal.ripple(pal.circle(if (RelayState.muted) pal.blue else pal.roundBtn))
        muteBtn.second.tint(if (RelayState.muted) Color.WHITE else pal.text)
        speakerBtn.second.background = pal.ripple(pal.circle(if (RelayState.speaker) pal.blue else pal.roundBtn))
        speakerBtn.second.tint(if (RelayState.speaker) Color.WHITE else pal.text)
        btBtn.second.background = pal.ripple(pal.circle(if (RelayState.bt) pal.blue else pal.roundBtn))
        btBtn.second.tint(if (RelayState.bt) Color.WHITE else pal.text)
        keypadBtn.first.visibility = muteBtn.first.visibility
        holdBtn.first.visibility = muteBtn.first.visibility
        replyLink.visibility = if (state == "ringing") View.VISIBLE else View.GONE
        silenceLink.visibility = if (state == "ringing" && !RelayState.silenced) View.VISIBLE else View.GONE
        // While ringing the in-call buttons take no space, otherwise they push Answer / Decline off the screen.
        callActionRows.forEach { it.visibility = if (state == "ringing" || callKeypadOpen) View.GONE else View.VISIBLE }

        // Two calls (from Add call or call waiting): Add call -> Merge, Hold -> Swap, banner for the held one.
        val calls = RelayState.liveCalls
        val two = calls.size >= 2
        val held = calls.firstOrNull { it.state == "holding" }
        val activeConf = calls.firstOrNull { it.conference }
        setAction(addCallBtn, if (two) R.drawable.ic_merge else R.drawable.ic_add, if (two) "Merge" else "Add call")
        addCallBtn.first.alpha = if (two && calls.none { it.canMerge }) 0.4f else 1f
        setAction(holdBtn, if (two) R.drawable.ic_swap else R.drawable.ic_pause,
            when { two -> "Swap"; held != null -> "Resume"; else -> "Hold" })
        val onHold = !two && held != null
        holdBtn.second.background = pal.ripple(pal.circle(if (onHold) pal.blue else pal.roundBtn))
        holdBtn.second.tint(if (onHold) Color.WHITE else pal.text)
        if (onHold) callStatus.text = "On hold"
        if (activeConf != null) {
            callName.text = "Conference call"
            callNumber.text = "${maxOf(activeConf.parts, 2)} people"
        }
        heldBar.visibility = if (two && held != null && state == "offhook") View.VISIBLE else View.GONE
        if (held != null) {
            val hn = held.number
            heldBar.text = "On hold · ${Contacts.name(this, hn) ?: formatNumber(clean(hn)).ifBlank { "Unknown" }} · Tap to swap"
        }
    }

    /** "Jazz" / "Onic" for a SIM slot, or the SIM phone's name when the slot isn't known. */
    private fun simLabelFor(slot: Int): String =
        if (slot >= 0) prefs.simName(slot) else (RelayState.peerName ?: "SIM phone")

    /** Changes a round in-call button's icon and label. */
    private fun setAction(btn: Pair<View, ImageView>, icon: Int, label: String) {
        btn.second.setImageResource(icon)
        ((btn.first as LinearLayout).getChildAt(1) as TextView).text = label
    }

    // =====================================================================================
    // Actions
    // =====================================================================================

    private fun clean(s: String) = s.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun dial(raw: String) {
        val n = clean(raw)
        if (n.isEmpty()) return
        if (!RelayState.connected) {
            Toast.makeText(this, "Not connected to the SIM phone", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.addRecent(n)
        val add = addingCall && RelayState.callState != "idle"
        addingCall = false
        callCollapsed = false
        if (RelayState.callState == "idle") { pendingDial = n; pendingSince = System.currentTimeMillis() }
        setDigits("")
        renderCall()
        ControllerService.start(this, ControllerService.ACTION_DIAL) {
            putExtra(ControllerService.EXTRA_NUMBER, n)
            putExtra(ControllerService.EXTRA_SIM, prefs.simChoice)
            putExtra(ControllerService.EXTRA_ADD, add)
        }
    }

    private fun openThread(number: String) =
        startActivity(Intent(this, MessagesActivity::class.java).putExtra(MessagesActivity.EXTRA_THREAD, number))

    private fun chooseSim() {
        val sims = RelayState.sims
        if (sims.isEmpty()) return Toast.makeText(this, "Connect to the SIM phone to see its SIM cards", Toast.LENGTH_SHORT).show()
        val names = (listOf("SIM phone's default") + sims.map { it.label }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Call using")
            .setItems(names) { _, w -> prefs.simChoice = if (w == 0) -1 else sims[w - 1].id; renderKeypad() }
            .show()
    }

    private fun toggleHotspot() {
        val on = RelayState.hotspotOn ?: return Toast.makeText(this, "Not connected to the SIM phone", Toast.LENGTH_SHORT).show()
        if (!RelayState.hotspotAllowed) {
            Toast.makeText(this, "On the SIM phone, tap \"Allow hotspot control\" first", Toast.LENGTH_LONG).show()
            return
        }
        ControllerService.start(this, ControllerService.ACTION_HOTSPOT) { putExtra(ControllerService.EXTRA_ON, !on) }
        Toast.makeText(this, if (on) "Turning hotspot off…" else "Turning hotspot on…", Toast.LENGTH_SHORT).show()
    }

    /** Stops the ringtone and vibration here without declining (the caller keeps hearing it ring). */
    private fun silenceRinging() = ControllerService.start(this, ControllerService.ACTION_SILENCE)

    /** Like a normal phone: either volume key silences a ringing call. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            RelayState.callState == "ringing" && !RelayState.silenced) {
            silenceRinging()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Decline the ringing call and text the caller from the SIM phone. */
    private fun quickReply() {
        val caller = RelayState.callNumber
        if (RelayState.callState != "ringing" || caller.isNullOrBlank()) {
            Toast.makeText(this, "No incoming call with a known number", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(
            "Can't talk now. I'll call you back.",
            "I'm in a meeting. Will call you later.",
            "I'm driving. Will call you soon.",
            "Please text me.",
            "✏️  Write your own…",
        )
        AlertDialog.Builder(this).setTitle("Reply to ${Contacts.name(this, caller) ?: caller}")
            .setItems(options) { _, w ->
                if (w < options.size - 1) sendReply(caller, options[w])
                else {
                    val field = EditText(this).apply { hint = "Message" }
                    AlertDialog.Builder(this).setTitle("Your message").setView(field)
                        .setPositiveButton("Send & decline") { _, _ ->
                            field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { sendReply(caller, it) }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun sendReply(to: String, body: String) {
        ControllerService.start(this, ControllerService.ACTION_END)
        val id = UUID.randomUUID().toString()
        MessageStore.add(this, Msg(id, to, body, System.currentTimeMillis(), incoming = false, status = "sending"))
        ControllerService.start(this, ControllerService.ACTION_SMS) {
            putExtra(ControllerService.EXTRA_ID, id)
            putExtra(ControllerService.EXTRA_NUMBER, clean(to))
            putExtra(ControllerService.EXTRA_BODY, body)
            putExtra(ControllerService.EXTRA_SIM, prefs.smsSimChoice)
        }
        Toast.makeText(this, "Declined and sent: $body", Toast.LENGTH_SHORT).show()
    }

    // =====================================================================================
    // Render + permissions
    // =====================================================================================

    private fun render() {
        val connected = RelayState.connected
        // Keep the keypad clean: only speak up when the SIM phone isn't reachable (battery/signal live in Settings).
        linkDot.text = if (connected) "" else "○  Not connected · tap for settings"
        linkDot.visibility = if (connected) View.INVISIBLE else View.VISIBLE
        msgBadge.visibility = if (MessageStore.unread(this) > 0) View.VISIBLE else View.GONE
        renderKeypad()
        if (tab == 1) renderRecents()
        renderCall()

        val p = RelayState.pendingConfirm
        if (p != null && dialog == null) {
            dialog = Ui.confirmDialog(this, p, "controller").also { it.setOnDismissListener { dialog = null } }
        } else if (p == null) {
            dialog?.dismiss()
        }
    }


    private fun ensureStarted() {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) ControllerService.start(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            ControllerService.start(this)
        } else if (requestCode == 2) {
            AlertDialog.Builder(this).setTitle("Nearby devices permission needed")
                .setMessage("Dialer needs \"Nearby devices\" to talk to the SIM phone over Bluetooth.")
                .setPositiveButton("Open settings") { _, _ -> Ui.openAppSettings(this) }
                .setNegativeButton("Cancel", null).show()
        }
        loadContacts()
    }

    companion object {
        const val EXTRA_QUICK_REPLY = "quick_reply"
    }
}
