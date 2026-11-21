package com.saad.lockin

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : Activity() {

    private val BG = 0xFF0F1115.toInt()
    private val CARD = 0xFF1B1F27.toInt()
    private val ACCENT = 0xFF6C8CFF.toInt()
    private val DANGER = 0xFFC0474F.toInt()
    private val MUTED = 0xFF9AA3B2.toInt()
    private val OK = 0xFF4CC38A.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var setupView: LinearLayout
    private lateinit var runView: LinearLayout
    private lateinit var tvAccess: TextView
    private lateinit var etFocus: EditText
    private lateinit var etBreak: EditText
    private lateinit var etRounds: EditText
    private lateinit var etSites: EditText
    private lateinit var tvApps: TextView
    private lateinit var tvPhase: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvExit: TextView
    private lateinit var btnExit: Button
    private lateinit var btnConfirm: Button
    private lateinit var btnCancel: Button
    private var selectedApps = mutableSetOf<String>()

    private lateinit var swSound: Switch
    private lateinit var btnSound: Button
    private lateinit var chart: BarChartView
    private lateinit var cToday: StatCard
    private lateinit var cWeek: StatCard
    private lateinit var cStreak: StatCard
    private lateinit var cAll: StatCard
    private var wasRunning = false

    private val PINK = 0xFFF472B6.toInt()
    private val VIOLET = 0xFFA78BFA.toInt()

    /** One number card on the stats section. */
    private inner class StatCard(title: String, color: Int) {
        val box = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = round(CARD)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val value = label("0", 26f, color, true)
        val sub = label("", 12f)

        init {
            box.addView(label(title, 11f))
            box.addView(value)
            box.addView(sub)
        }
    }

    // ------------------------------------------------------------------ small UI helpers
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun label(t: String, size: Float = 14f, color: Int = MUTED, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(t: String, bg: Int = ACCENT, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = round(bg)
        setOnClickListener { onClick() }
    }

    private fun input(numeric: Boolean, lines: Int = 1) = EditText(this).apply {
        setTextColor(Color.WHITE)
        setHintTextColor(MUTED)
        background = round(CARD)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (numeric) {
            inputType = InputType.TYPE_CLASS_NUMBER
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            minLines = lines
            gravity = Gravity.TOP
        }
    }

    private fun add(parent: LinearLayout, v: View, top: Int = 12) {
        parent.addView(
            v,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(top) }
        )
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(40))
        }
        scroll.addView(col)
        setContentView(scroll)

        add(col, label("Lockin", 32f, Color.WHITE, true), 0)
        add(col, label("Focus timer that locks distracting apps and websites.", 14f), 4)

        buildSetup()
        buildRun()
        add(col, setupView, 8)
        add(col, runView, 8)

        selectedApps = LockStore.cfgApps(this).toMutableSet()
        etFocus.setText(LockStore.focusMin(this).toString())
        etBreak.setText(LockStore.breakMin(this).toString())
        etRounds.setText(LockStore.rounds(this).toString())
        etSites.setText(LockStore.cfgSites(this))
        refreshAppsLabel()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        syncSoundUi()
        refreshStats()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        if (!LockStore.sessionActive(this, System.currentTimeMillis())) persistConfig()
        super.onPause()
    }

    // ------------------------------------------------------------------ setup screen
    private fun buildSetup() {
        setupView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        tvAccess = label("", 14f, MUTED, true)
        add(setupView, tvAccess, 12)
        add(setupView, button("Open accessibility settings", CARD) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, 6)

        add(setupView, label("Focus (minutes)", 13f), 20)
        etFocus = input(true); add(setupView, etFocus, 4)
        add(setupView, label("Break (minutes, 0 = none)", 13f), 12)
        etBreak = input(true); add(setupView, etBreak, 4)
        add(setupView, label("Rounds (1 = plain timer; long break after every 4th)", 13f), 12)
        etRounds = input(true); add(setupView, etRounds, 4)

        swSound = Switch(this).apply {
            text = "Notification sounds"
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = LockStore.soundsOn(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                LockStore.setSoundsOn(this@MainActivity, on)
                syncSoundUi()
            }
        }
        add(setupView, swSound, 20)

        add(setupView, label("Apps to lock", 13f), 20)
        tvApps = label("", 15f, Color.WHITE)
        add(setupView, tvApps, 4)
        add(setupView, button("Choose apps", CARD) { pickApps() }, 6)

        add(setupView, label("Websites to lock (one per line)", 13f), 20)
        etSites = input(false, 6); add(setupView, etSites, 4)

        add(setupView, button("Start locked session", ACCENT) { start() }, 24)

        add(setupView, label("Stats", 22f, Color.WHITE, true), 36)
        add(setupView, label("Focus minutes you actually put in.", 13f), 2)
        cToday = StatCard("TODAY", PINK)
        cWeek = StatCard("LAST 7 DAYS", VIOLET)
        cStreak = StatCard("DAY STREAK", OK)
        cAll = StatCard("ALL TIME", ACCENT)
        add(setupView, statsRow(cToday, cWeek), 12)
        add(setupView, statsRow(cStreak, cAll), 12)
        add(setupView, label("Last 14 days", 13f), 20)
        chart = BarChartView(this)
        chart.background = round(CARD)
        add(setupView, chart, 6)
    }

    private fun buildRun() {
        runView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        tvPhase = label("", 16f, ACCENT, true).apply { gravity = Gravity.CENTER }
        tvTime = label("", 64f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        tvInfo = label("", 14f).apply { gravity = Gravity.CENTER }
        tvExit = label("", 14f, Color.WHITE).apply { gravity = Gravity.CENTER }
        add(runView, tvPhase, 40)
        add(runView, tvTime, 8)
        add(runView, tvInfo, 8)

        btnExit = button("Emergency exit (15 min wait)", CARD) {
            LockStore.requestExit(this, System.currentTimeMillis())
            tick()
        }
        btnConfirm = button("Exit now and unlock", DANGER) {
            LockStore.abortSession(this, System.currentTimeMillis())
            stopService(Intent(this, LockService::class.java))
            tick()
        }
        btnCancel = button("Cancel exit, keep going", ACCENT) {
            LockStore.cancelExit(this)
            tick()
        }
        add(runView, tvExit, 48)
        add(runView, btnExit, 8)
        add(runView, btnConfirm, 8)
        add(runView, btnCancel, 8)

        btnSound = button("", CARD) {
            LockStore.setSoundsOn(this, !LockStore.soundsOn(this))
            syncSoundUi()
        }
        add(runView, btnSound, 28)
    }

    // ------------------------------------------------------------------ actions
    private fun refreshAppsLabel() {
        tvApps.text = if (selectedApps.isEmpty()) "None selected" else "${selectedApps.size} app(s) selected"
    }

    private fun pickApps() {
        val pm = packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launch, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }
        val names = apps.map { it.second }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in selectedApps }
        AlertDialog.Builder(this)
            .setTitle("Apps to lock")
            .setMultiChoiceItems(names, checked) { _, i, on ->
                if (on) selectedApps.add(apps[i].first) else selectedApps.remove(apps[i].first)
            }
            .setPositiveButton("Done") { _, _ ->
                persistConfig()
                refreshAppsLabel()
            }
            .show()
    }

    private fun persistConfig() {
        LockStore.saveConfig(
            this,
            etFocus.text.toString().toIntOrNull(),
            etBreak.text.toString().toIntOrNull(),
            etRounds.text.toString().toIntOrNull(),
            selectedApps,
            etSites.text.toString()
        )
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun start() {
        if (!isAccessibilityOn(this)) {
            toast("Turn on the Lockin accessibility service first")
            return
        }
        val f = etFocus.text.toString().toIntOrNull() ?: 0
        val b = etBreak.text.toString().toIntOrNull() ?: -1
        val r = etRounds.text.toString().toIntOrNull() ?: 0
        if (f !in 1..240 || b !in 0..60 || r !in 1..20) {
            toast("Focus 1-240, break 0-60, rounds 1-20")
            return
        }
        val sites = LockStore.normalizeSites(etSites.text.toString())
        if (selectedApps.isEmpty() && sites.isEmpty()) {
            toast("Pick at least one app or website to lock")
            return
        }
        val now = System.currentTimeMillis()
        val plan = LockStore.plan(now, f, b, r)
        val total = plan.last().end - plan.first().start
        if (total > LockStore.MAX_SESSION_MS) {
            toast("Max session is 4 hours. This one is ${total / 60000} min.")
            return
        }
        persistConfig()
        AlertDialog.Builder(this)
            .setTitle("Start locked session?")
            .setMessage(
                "Locked apps and sites stay blocked during focus time. The whole session ends at " +
                    LockStore.hm(plan.last().end) +
                    ". Emergency exit only unlocks after a 15 minute wait."
            )
            .setPositiveButton("Lock in") { _, _ ->
                LockStore.startSession(this, plan, selectedApps, etSites.text.toString())
                LockService.start(this)
                tick()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ per-second refresh
    private fun tick() {
        val now = System.currentTimeMillis()
        LockStore.settleStats(this, now)
        val on = isAccessibilityOn(this)
        tvAccess.text = if (on) "Accessibility service: ON" else "Accessibility service: OFF (required to lock)"
        tvAccess.setTextColor(if (on) OK else DANGER)

        if (!LockStore.sessionActive(this, now)) {
            if (LockStore.hasSession(this)) LockStore.finishSession(this, now)
            if (wasRunning) {
                wasRunning = false
                refreshStats()
            }
            setupView.visibility = View.VISIBLE
            runView.visibility = View.GONE
            return
        }
        wasRunning = true
        setupView.visibility = View.GONE
        runView.visibility = View.VISIBLE

        val w = LockStore.currentWindow(this, now) ?: return
        val all = LockStore.windows(this)
        tvPhase.text = if (w.focus) "FOCUS - LOCKED" else "BREAK - UNLOCKED"
        tvTime.text = LockStore.mmss((w.end - now + 999) / 1000)
        val done = all.count { it.focus && it.end <= now }
        val total = all.count { it.focus }
        tvInfo.text = "Rounds done $done / $total  -  session ends ${LockStore.hm(LockStore.sessionEnd(this))}"

        val req = LockStore.exitRequestedAt(this)
        if (req == 0L) {
            tvExit.text = ""
            btnExit.visibility = View.VISIBLE
            btnConfirm.visibility = View.GONE
            btnCancel.visibility = View.GONE
        } else {
            val left = req + LockStore.EXIT_DELAY_MS - now
            btnExit.visibility = View.GONE
            btnCancel.visibility = View.VISIBLE
            if (left > 0) {
                tvExit.text = "Exit unlocks in " + LockStore.mmss((left + 999) / 1000)
                btnConfirm.visibility = View.GONE
            } else {
                tvExit.text = "Wait is over. You can leave now."
                btnConfirm.visibility = View.VISIBLE
            }
        }
    }

    private fun statsRow(a: StatCard, b: StatCard) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a.box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        addView(b.box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
    }

    private fun syncSoundUi() {
        val on = LockStore.soundsOn(this)
        if (swSound.isChecked != on) swSound.isChecked = on
        btnSound.text = if (on) "Sounds: on" else "Sounds: off"
    }

    private fun refreshStats() {
        val by = LockStore.minutesByDay(this, 14)
        chart.setData(by.mapIndexed { i, (d, m) ->
            BarChartView.Bar(
                d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(1).uppercase(),
                d.dayOfMonth.toString(),
                Math.round(m).toInt(),
                i == by.lastIndex
            )
        })
        val today = by.last().second
        val wk = by.takeLast(7).sumOf { it.second }
        val (tot, n) = LockStore.totals(this)
        val s = LockStore.streak(this)
        cToday.value.text = LockStore.fmtDur(today)
        cToday.sub.text = "focused"
        cWeek.value.text = LockStore.fmtDur(wk)
        cWeek.sub.text = "avg " + LockStore.fmtDur(wk / 7) + " / day"
        cStreak.value.text = s.toString()
        cStreak.sub.text = if (s == 1) "day in a row" else "days in a row"
        cAll.value.text = LockStore.fmtDur(tot)
        cAll.sub.text = "$n sessions"
    }

    private fun isAccessibilityOn(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(c, LockAccessibilityService::class.java)
        val full = cn.flattenToString()
        val short = cn.flattenToShortString()
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }
}
