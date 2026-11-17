package com.btcallrelay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Messages on the phone WITHOUT the SIM, styled after One UI 8's Samsung Messages:
 * conversation list (big title, rounded card, compose button), new conversation with contact search,
 * and the chat screen (One UI bubbles, pill input with a SIM-numbered send button). Texts go out on the SIM phone.
 */
class MessagesActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var pal: Pal
    private var mode = MODE_LIST
    private var thread: String? = null
    private var people: List<Person> = emptyList()
    private val listener: () -> Unit = { render() }
    private var topInset = 0
    private var bottomInset = 0

    // list
    private lateinit var listBox: LinearLayout
    private lateinit var unreadText: TextView
    private lateinit var searchField: EditText
    private var renderedSignature = ""

    // new conversation
    private lateinit var recipient: EditText
    private lateinit var pickBox: LinearLayout

    // chat
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var simBadge: TextView
    private lateinit var linkText: TextView

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.mode != Prefs.MODE_CONTROLLER) {
            Toast.makeText(this, "Messages are used on the controller phone", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        pal = Pal(this)
        val fromLink = intent.data?.takeIf { it.scheme == "sms" || it.scheme == "smsto" }
            ?.let { Uri.decode(it.schemeSpecificPart).substringBefore('?').filter { c -> c.isDigit() || c == '+' } }
        thread = intent.getStringExtra(EXTRA_THREAD) ?: fromLink?.takeIf { it.isNotEmpty() }
        mode = when {
            thread != null -> MODE_CHAT
            intent.getBooleanExtra(EXTRA_NEW, false) -> MODE_NEW
            else -> MODE_LIST
        }
        val root = when (mode) {
            MODE_CHAT -> buildChat(intent.getStringExtra("sms_body"))
            MODE_NEW -> buildNew()
            else -> buildList()
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            topInset = bars.top
            bottomInset = maxOf(bars.bottom, ime.bottom)
            v.setPadding(bars.left, topInset, bars.right, if (mode == MODE_LIST) 0 else bottomInset)
            if (mode == MODE_LIST) (v as? FrameLayout)?.getChildAt(1)?.let {
                (it.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(22); it.requestLayout()
            }
            insets
        }
        setContentView(root)
        if (!pal.dark) window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1)
        ContactsRepo.load(this) { people = it; render() }
    }

    override fun onResume() {
        super.onResume()
        openThread = thread?.let { threadKey(it) }
        RelayState.addListener(listener)
        renderedSignature = ""
        render()
    }

    override fun onPause() {
        openThread = null
        RelayState.removeListener(listener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ContactsRepo.load(this) { people = it; renderedSignature = ""; render() }
    }

    private fun display(number: String) = Contacts.name(this, number) ?: number

    private fun avatar(name: String, sizeDp: Int): TextView = TextView(this).apply {
        val letter = initialOf(name)
        text = if (letter == "#" || name.firstOrNull()?.let { it.isDigit() || it == '+' } == true) "" else letter
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.42f)
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            pal.tile(name).let { intArrayOf(it.first, it.second) }).apply { shape = GradientDrawable.OVAL }
        if (text.isEmpty()) {
            // Unknown numbers get the person glyph, like One UI.
            setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            text = "👤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.38f)
            background = pal.circle(if (pal.dark) 0xFF3A4A5E.toInt() else 0xFFB9C8DB.toInt())
        }
    }

    private fun iconButton(icon: Int, onClick: (View) -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        tint(pal.text)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = pal.circleRipple()
        setOnClickListener { onClick(it) }
    }

    private fun whenShort(t: Long): String = when {
        DateUtils.isToday(t) -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
        DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        System.currentTimeMillis() - t < 6 * DateUtils.DAY_IN_MILLIS -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(t))
    }

    // =====================================================================================
    // Conversation list
    // =====================================================================================

    private fun buildList(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(120)) }
        val h = (resources.displayMetrics.heightPixels * 0.27f).toInt() + dp(21)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, ((h - dp(21)) * 0.14f).toInt() + dp(21))
        }
        header.addView(TextView(this).apply {
            text = "Messages"
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            gravity = Gravity.CENTER
        })
        unreadText = TextView(this).apply {
            setTextColor(pal.blue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        header.addView(unreadText)
        col.addView(header, LinearLayout.LayoutParams(-1, h))

        searchField = EditText(this).apply {
            hint = "Search"
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
                override fun afterTextChanged(s: Editable?) { renderedSignature = ""; render() }
            })
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(16), 0)
            addView(iconButton(R.drawable.ic_search) { toggleSearch() }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(iconButton(R.drawable.ic_more) { listMenu(it) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        col.addView(toolbar)
        col.addView(searchField, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        scroll.addView(col)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // Round compose button, bottom-right.
        val fab = ImageView(this).apply {
            setImageResource(R.drawable.ic_compose)
            tint(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = pal.ripple(pal.circle(pal.blue))
            elevation = dp(6).toFloat()
            setOnClickListener { startActivity(Intent(this@MessagesActivity, MessagesActivity::class.java).putExtra(EXTRA_NEW, true)) }
        }
        frame.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(22) })
        return frame
    }

    private fun toggleSearch() {
        if (searchField.visibility == View.VISIBLE) {
            searchField.setText(""); searchField.visibility = View.GONE
            currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        } else {
            searchField.visibility = View.VISIBLE
            searchField.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(searchField, 0)
        }
    }

    private fun listMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Mark all as read")
        pm.menu.add(0, 2, 1, "SIM for texts")
        pm.menu.add(0, 3, 2, "Settings")
        pm.menu.add(0, 4, 3, AutoReplyUi.summary(this))
        pm.menu.add(0, 5, 4, "Import old texts")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> MessageStore.all(this).map { m -> threadKey(m.number) }.distinct().forEach { k -> MessageStore.markRead(this, k) }
                2 -> chooseSim()
                3 -> startActivity(Intent(this, ControllerSettingsActivity::class.java))
                4 -> AutoReplyUi.show(this)
                5 -> { ControllerService.start(this, ControllerService.ACTION_IMPORT_SMS); Toast.makeText(this, "Importing old texts from the SIM phone…", Toast.LENGTH_SHORT).show() }
            }
            true
        }
        pm.show()
    }

    private fun renderList() {
        val all = MessageStore.all(this)
        val unread = all.count { !it.read }
        unreadText.text = when (unread) { 0 -> ""; 1 -> "1 unread message"; else -> "$unread unread messages" }
        unreadText.visibility = if (unread == 0) View.GONE else View.VISIBLE
        val q = searchField.text.toString().trim().lowercase()
        val sig = "${all.size}|${all.lastOrNull()?.id}|${all.lastOrNull()?.status}|$unread|$q|${people.size}"
        if (sig == renderedSignature) return
        renderedSignature = sig
        listBox.removeAllViews()
        val threads = all.groupBy { threadKey(it.number) }
            .map { (_, msgs) -> msgs.maxBy { it.time } to msgs.count { !it.read } }
            .sortedByDescending { it.first.time }
            .filter { (m, _) -> q.isEmpty() || display(m.number).lowercase().contains(q) || m.number.contains(q) ||
                all.any { threadKey(it.number) == threadKey(m.number) && it.body.lowercase().contains(q) } }
        if (threads.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = if (q.isNotEmpty()) "No results" else "No messages\n\nTexts that arrive on the SIM phone will show up here."
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                setPadding(dp(30), dp(40), dp(30), dp(20))
            })
            return
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pal.rounded(pal.card, dp(26).toFloat())
            clipToOutline = true
        }
        threads.forEachIndexed { i, (last, unreadCount) ->
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(pal.divider) },
                LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(86); rightMargin = dp(20) })
            card.addView(conversationRow(last, unreadCount))
        }
        listBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(10); rightMargin = dp(10) })
    }

    private fun conversationRow(last: Msg, unread: Int): View {
        val name = display(last.number)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(18), dp(14))
            background = pal.ripple(pal.rounded(pal.card, 0f))
            setOnClickListener { openConversation(last.number) }
            setOnLongClickListener { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); threadMenu(last.number); true }
        }
        row.addView(avatar(name, 52), LinearLayout.LayoutParams(dp(52), dp(52)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = name
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, if (unread > 0) Typeface.BOLD else Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(this).apply {
            text = whenShort(last.time)
            setTextColor(if (unread > 0) pal.blue else pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        mid.addView(top)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(TextView(this).apply {
            text = when {
                !last.incoming && last.status == "failed" -> "⚠ Not sent · ${last.body}"
                else -> last.body
            }
            setTextColor(if (!last.incoming && last.status == "failed") pal.red else pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (unread > 0) bottom.addView(TextView(this).apply {
            text = if (unread > 99) "99+" else unread.toString()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            minWidth = dp(22)
            setPadding(dp(6), 0, dp(6), 0)
            background = pal.rounded(pal.blue, dp(11).toFloat())
        }, LinearLayout.LayoutParams(-2, dp(22)).apply { leftMargin = dp(8) })
        mid.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
        return row
    }

    private fun threadMenu(number: String) {
        AlertDialog.Builder(this).setTitle(display(number))
            .setItems(arrayOf("Call", "Delete conversation")) { _, w ->
                when (w) {
                    0 -> callNumber(number)
                    1 -> AlertDialog.Builder(this).setTitle("Delete conversation?")
                        .setMessage("Deletes these messages from this phone. Texts stay on the SIM phone.")
                        .setPositiveButton("Delete") { _, _ -> MessageStore.deleteThread(this, threadKey(number)) }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun openConversation(number: String) =
        startActivity(Intent(this, MessagesActivity::class.java).putExtra(EXTRA_THREAD, number))

    private fun callNumber(number: String) =
        startActivity(Intent(this, ControllerActivity::class.java).setAction(Intent.ACTION_DIAL).setData(Uri.fromParts("tel", number, null)))

    // =====================================================================================
    // New conversation
    // =====================================================================================

    private fun topBar(title: String, subtitle: String?, actions: List<View>): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(10), dp(6))
        }
        bar.addView(iconButton(R.drawable.ic_back) { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, 0, 0) }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (subtitle != null) texts.addView(TextView(this).apply {
            text = subtitle
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        bar.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        actions.forEach { bar.addView(it, LinearLayout.LayoutParams(dp(44), dp(44))) }
        return bar
    }

    private fun buildNew(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        col.addView(topBar("New conversation", null, emptyList()))
        recipient = EditText(this).apply {
            hint = "Enter name or number"
            setSingleLine()
            setTextColor(pal.text)
            setHintTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            background = pal.rounded(pal.card, dp(24).toFloat())
            setPadding(dp(20), dp(14), dp(20), dp(14))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ ->
                val n = text.toString().filter { it.isDigit() || it == '+' }
                if (n.length >= 3) { openConversation(n); finish() }
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = renderNew()
            })
        }
        col.addView(recipient, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(6), dp(14), dp(10)) })
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        pickBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(20)) }
        scroll.addView(pickBox)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        recipient.post {
            recipient.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(recipient, 0)
        }
        return col
    }

    private fun renderNew() {
        if (!::pickBox.isInitialized) return
        pickBox.removeAllViews()
        val q = recipient.text.toString().trim()
        val digitsQ = q.filter { it.isDigit() }
        if (digitsQ.length >= 3 && digitsQ.length == q.filter { it != ' ' && it != '+' }.length) {
            pickBox.addView(pickRow("Send to $q", "", q.filter { it.isDigit() || it == '+' }, null))
        }
        val list = people.filter { p ->
            p.numbers.isNotEmpty() && (q.isEmpty() || p.name.lowercase().contains(q.lowercase()) ||
                (digitsQ.length >= 3 && p.numbers.any { it.filter { c -> c.isDigit() }.contains(digitsQ) }))
        }
        val shown = if (q.isEmpty()) list.sortedWith(compareBy({ !it.starred }, { it.name.lowercase() })).take(60) else list.take(60)
        if (shown.isNotEmpty()) pickBox.addView(TextView(this).apply {
            text = if (q.isEmpty()) "Contacts" else "Matching contacts"
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setPadding(dp(28), dp(12), dp(24), dp(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pal.rounded(pal.card, dp(26).toFloat())
            clipToOutline = true
        }
        for (p in shown) {
            for (n in p.numbers.take(2)) card.addView(pickRow(p.name, n, n, p))
        }
        if (card.childCount > 0) pickBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(10); rightMargin = dp(10) })
    }

    private fun pickRow(title: String, sub: String, number: String, p: Person?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = pal.ripple(pal.rounded(pal.card, if (p == null) dp(26).toFloat() else 0f))
            setOnClickListener { openConversation(number); finish() }
        }
        row.addView(avatar(p?.name ?: number, 44), LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(if (p == null) pal.blue else pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (sub.isNotEmpty()) texts.addView(TextView(this).apply {
            text = sub
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
        return if (p == null) FrameLayout(this).apply {
            setPadding(dp(10), 0, dp(10), dp(6))
            addView(row)
        } else row
    }

    // =====================================================================================
    // Chat
    // =====================================================================================

    private fun buildChat(prefill: String?): View {
        val number = thread!!
        val name = display(number)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        col.addView(topBar(name, if (name != number) number else null, listOf(
            iconButton(R.drawable.ic_call) { callNumber(number) },
            iconButton(R.drawable.ic_more) { chatMenu(it) },
        )))
        linkText = TextView(this).apply {
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        col.addView(linkText, LinearLayout.LayoutParams(-1, -2))

        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(10)) }
        chatScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(chatBox) }
        col.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // One UI input bar: pill field + round send button that shows which SIM it will use.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        input = EditText(this).apply {
            hint = "Enter message"
            setTextColor(pal.text)
            setHintTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 6
            background = pal.rounded(pal.card, dp(24).toFloat())
            setPadding(dp(20), dp(13), dp(20), dp(13))
            prefill?.let { setText(it) }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val send = FrameLayout(this).apply {
            background = pal.ripple(pal.circle(pal.blue))
            setOnClickListener { sendNow() }
            setOnLongClickListener { chooseSim(); true }
        }
        send.addView(ImageView(this).apply { setImageResource(R.drawable.ic_send); tint(Color.WHITE) },
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER).apply { leftMargin = dp(3) })
        simBadge = TextView(this).apply {
            setTextColor(pal.blue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(1), pal.blue) }
        }
        send.addView(simBadge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END))
        bar.addView(send, LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(8) })
        col.addView(bar)
        return col
    }

    private fun chatMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "SIM for texts")
        pm.menu.add(0, 2, 1, "Delete conversation")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> chooseSim()
                2 -> AlertDialog.Builder(this).setTitle("Delete conversation?")
                    .setMessage("Deletes these messages from this phone. Texts stay on the SIM phone.")
                    .setPositiveButton("Delete") { _, _ -> MessageStore.deleteThread(this, threadKey(thread!!)); finish() }
                    .setNegativeButton("Cancel", null).show()
            }
            true
        }
        pm.show()
    }

    /** SIM for texts only (SIM 2 by default); calls keep their own setting. */
    private fun chooseSim() {
        val sims = RelayState.sims
        if (sims.isEmpty()) {
            Toast.makeText(this, "Connect to the SIM phone first to see its SIM cards", Toast.LENGTH_SHORT).show()
            return
        }
        val names = (listOf("SIM phone's default") + sims.map { it.label }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Send texts using")
            .setItems(names) { _, w -> prefs.smsSimChoice = if (w == 0) -1 else sims[w - 1].id; renderedSignature = ""; render() }
            .show()
    }

    private fun sendNow() {
        val number = thread ?: return
        val body = input.text?.toString()?.trim().orEmpty()
        if (body.isEmpty()) return
        if (!RelayState.connected) {
            Toast.makeText(this, "Not connected to the SIM phone", Toast.LENGTH_SHORT).show()
            return
        }
        val id = UUID.randomUUID().toString()
        MessageStore.add(this, Msg(id, number, body, System.currentTimeMillis(), incoming = false, status = "sending"))
        ControllerService.start(this, ControllerService.ACTION_SMS) {
            putExtra(ControllerService.EXTRA_ID, id)
            putExtra(ControllerService.EXTRA_NUMBER, number)
            putExtra(ControllerService.EXTRA_BODY, body)
            putExtra(ControllerService.EXTRA_SIM, prefs.smsSimChoice)
        }
        input.text?.clear()
    }

    private fun dayLabel(t: Long): String = when {
        DateUtils.isToday(t) -> "Today"
        DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date(t))
    }

    private fun renderChat() {
        val key = threadKey(thread!!)
        MessageStore.markRead(this, key)
        val sel = RelayState.sims.firstOrNull { it.id == prefs.smsSimChoice }
        simBadge.text = sel?.let { (it.id + 1).toString() } ?: ""
        simBadge.visibility = if (sel == null) View.GONE else View.VISIBLE
        linkText.text = if (RelayState.connected) "Sending via ${sel?.label ?: "SIM phone"}" else "○ ${RelayState.status}"
        val msgs = MessageStore.all(this).filter { threadKey(it.number) == key }
        val sig = "${msgs.size}|${msgs.lastOrNull()?.id}|${msgs.count { it.status == "sending" }}|${msgs.count { it.status == "failed" }}"
        if (sig == renderedSignature) return
        renderedSignature = sig
        chatBox.removeAllViews()
        val dark = pal.dark
        val inBg = if (dark) 0xFF2A2A2D.toInt() else 0xFFFFFFFF.toInt()
        val outBg = if (dark) 0xFF1F4A7A.toInt() else 0xFFCFE4FF.toInt()
        val r = dp(20).toFloat()
        val small = dp(6).toFloat()
        var lastDay = ""
        msgs.forEachIndexed { i, m ->
            val day = dayLabel(m.time)
            if (day != lastDay) {
                lastDay = day
                chatBox.addView(TextView(this).apply {
                    text = day
                    setTextColor(pal.sub)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(10))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            val prev = msgs.getOrNull(i - 1)
            val next = msgs.getOrNull(i + 1)
            val sameAsPrev = prev != null && prev.incoming == m.incoming && dayLabel(prev.time) == day && m.time - prev.time < 60_000
            val sameAsNext = next != null && next.incoming == m.incoming && dayLabel(next.time) == day && next.time - m.time < 60_000
            val bubble = TextView(this).apply {
                text = m.body
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextIsSelectable(false)
                setLineSpacing(0f, 1.08f)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                // Grouped bubbles get a tighter corner on the tail side, like One UI.
                val tl = if (m.incoming && sameAsPrev) small else r
                val bl = if (m.incoming && sameAsNext) small else r
                val tr = if (!m.incoming && sameAsPrev) small else r
                val br = if (!m.incoming && sameAsNext) small else r
                background = GradientDrawable().apply {
                    setColor(if (m.incoming) inBg else if (m.status == "failed") (if (dark) 0xFF5A2626.toInt() else 0xFFFFD9D9.toInt()) else outBg)
                    cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
                }
                setOnLongClickListener { bubbleMenu(m); true }
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (m.incoming) Gravity.START or Gravity.BOTTOM else Gravity.END or Gravity.BOTTOM
            }
            if (m.incoming) {
                if (!sameAsNext) line.addView(avatar(display(m.number), 34), LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(8) })
                else line.addView(View(this), LinearLayout.LayoutParams(dp(42), 1))
            }
            line.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
                if (m.incoming) rightMargin = dp(56) else leftMargin = dp(72)
            })
            chatBox.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (sameAsPrev) dp(3) else dp(10) })
            if (m.incoming) Otp.find(m.body)?.let { code ->
                chatBox.addView(TextView(this).apply {
                    text = "Copy code  $code"
                    setTextColor(pal.blue)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    background = pal.ripple(GradientDrawable().apply {
                        setColor(pal.card); cornerRadius = dp(18).toFloat(); setStroke(dp(1), pal.blue)
                    })
                    setOnClickListener {
                        Otp.copy(this@MessagesActivity, code)
                        Toast.makeText(this@MessagesActivity, "Code $code copied", Toast.LENGTH_SHORT).show()
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(42); topMargin = dp(6) })
            }
            if (!sameAsNext || m.status == "failed" || m.status == "sending") {
                chatBox.addView(TextView(this).apply {
                    text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(m.time)) + when {
                        m.incoming -> ""
                        m.status == "sending" -> " · Sending…"
                        m.status == "failed" -> " · Not sent. Tap and hold to retry"
                        else -> ""
                    }
                    setTextColor(if (m.status == "failed") pal.red else pal.sub)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = if (m.incoming) Gravity.START else Gravity.END
                    setPadding(if (m.incoming) dp(48) else 0, dp(3), dp(4), 0)
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun bubbleMenu(m: Msg) {
        val items = mutableListOf("Copy text")
        if (!m.incoming && m.status == "failed") items.add(0, "Resend")
        items += "Delete"
        AlertDialog.Builder(this).setItems(items.toTypedArray()) { _, w ->
            when (items[w]) {
                "Copy text" -> {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", m.body))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                "Resend" -> { input.setText(m.body); MessageStore.deleteMessage(this, m.id); sendNow() }
                "Delete" -> MessageStore.deleteMessage(this, m.id)
            }
        }.show()
    }

    private fun render() {
        when (mode) {
            MODE_LIST -> if (::listBox.isInitialized) renderList()
            MODE_NEW -> renderNew()
            MODE_CHAT -> if (::chatBox.isInitialized) renderChat()
        }
    }

    companion object {
        const val EXTRA_THREAD = "thread"
        const val EXTRA_NEW = "new_conversation"
        private const val MODE_LIST = 0
        private const val MODE_NEW = 1
        private const val MODE_CHAT = 2

        /** Conversation on screen right now, so its new texts don't also pop a notification. */
        @Volatile var openThread: String? = null
    }
}
