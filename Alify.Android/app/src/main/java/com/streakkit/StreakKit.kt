package com.streakkit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Developer Rule Definition passed as JSON or typed object into the SDK
 */
data class StreakRule(
    val slug: String,
    val name: String = slug,
    val target: Int = 1,
    val cadence: String = "DAILY",
    val gracePeriodMinutes: Int = 120,
    val maxFreezes: Int = 2
)

/**
 * StreakKit Android SDK — 100% Self-Contained Standalone Drop-In
 * - Works completely offline on-device with zero backend or console required.
 * - Local habit calculation engine (streaks, daily targets, freeze shields).
 * - Instant animated Duolingo-grade embed UI rendered in a native BottomSheetDialog.
 */
object StreakKit {
    private const val PREFS = "streakkit_prefs"
    private const val KEY_CURRENT_STREAK = "current_streak"
    private const val KEY_BEST_STREAK = "best_streak"
    private const val KEY_LAST_ACTIVE_DATE = "last_active_date"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_TODAY_COUNT = "today_count"
    private const val KEY_FREEZES_AVAILABLE = "freezes_available"
    private const val KEY_HISTORY = "history_days"

    private val configuredRules = mutableMapOf<String, StreakRule>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(JSONObject) -> Unit>()

    private val dateFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Initialize SDK with local rules.
     */
    fun init(
        context: Context,
        defaultEndpoint: String = "",
        defaultKey: String = "",
        rules: List<StreakRule> = emptyList(),
        rulesJson: String? = null
    ) {
        rules.forEach { configuredRules[it.slug] = it }

        if (!rulesJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(rulesJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val slug = obj.getString("slug")
                    configuredRules[slug] = StreakRule(
                        slug = slug,
                        name = obj.optString("name", slug),
                        target = obj.optInt("target", obj.optInt("targetValue", 1)),
                        cadence = obj.optString("cadence", obj.optString("cadenceType", "DAILY")),
                        gracePeriodMinutes = obj.optInt("gracePeriodMinutes", 120),
                        maxFreezes = obj.optInt("maxFreezes", 2)
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Run local day rollover check
        val defaultRule = rules.firstOrNull()?.slug ?: "hujjify_playback"
        ping(context, defaultRule, value = 0)
    }

    fun onStateChange(listener: (JSONObject) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Records an action toward the streak.
     * Evaluates day rollovers, freeze shields, and daily targets locally on-device.
     */
    @Synchronized
    fun ping(
        context: Context,
        ruleSlug: String = "hujjify_playback",
        userId: String = "user_default",
        value: Int = 1,
        onSuccess: ((JSONObject) -> Unit)? = null
    ) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rule = configuredRules[ruleSlug] ?: StreakRule(ruleSlug, target = 1)
        val todayStr = dateFormat.format(Date())

        var currentStreak = sp.getInt(KEY_CURRENT_STREAK, 0)
        var bestStreak = sp.getInt(KEY_BEST_STREAK, 0)
        var lastActiveDate = sp.getString(KEY_LAST_ACTIVE_DATE, "") ?: ""
        var todayDate = sp.getString(KEY_TODAY_DATE, "") ?: ""
        var todayCount = sp.getInt(KEY_TODAY_COUNT, 0)
        var freezes = sp.getInt(KEY_FREEZES_AVAILABLE, rule.maxFreezes)
        val historySet = sp.getStringSet(KEY_HISTORY, emptySet())?.toMutableSet() ?: mutableSetOf()

        // 1. Day Rollover Detection
        if (todayDate != todayStr) {
            if (lastActiveDate.isNotBlank()) {
                try {
                    val lastDate = dateFormat.parse(lastActiveDate)
                    val todayDateObj = dateFormat.parse(todayStr)
                    if (lastDate != null && todayDateObj != null) {
                        val diffDays = ((todayDateObj.time - lastDate.time) / (1000 * 60 * 60 * 24)).toInt()
                        if (diffDays == 1) {
                            // Consecutive day, streak continues intact!
                        } else if (diffDays == 2) {
                            // Missed exactly 1 day: Consume freeze shield if available
                            if (freezes > 0) {
                                freezes--
                            } else {
                                currentStreak = 0
                            }
                        } else if (diffDays > 2) {
                            // Missed 2+ days: Streak resets
                            currentStreak = 0
                        }
                    }
                } catch (_: Exception) {
                    currentStreak = 0
                }
            }
            todayDate = todayStr
            todayCount = 0
        }

        // 2. Increment Progress
        todayCount += value
        val completed = todayCount >= rule.target

        // 3. Complete Streak Day
        if (completed && lastActiveDate != todayStr) {
            currentStreak += 1
            if (currentStreak > bestStreak) {
                bestStreak = currentStreak
            }
            lastActiveDate = todayStr
            historySet.add(todayStr)
        }

        // 4. Save state
        sp.edit()
            .putInt(KEY_CURRENT_STREAK, currentStreak)
            .putInt(KEY_BEST_STREAK, bestStreak)
            .putString(KEY_LAST_ACTIVE_DATE, lastActiveDate)
            .putString(KEY_TODAY_DATE, todayDate)
            .putInt(KEY_TODAY_COUNT, todayCount)
            .putInt(KEY_FREEZES_AVAILABLE, freezes)
            .putStringSet(KEY_HISTORY, historySet)
            .apply()

        val json = buildStateJson(ruleSlug, currentStreak, bestStreak, lastActiveDate, freezes, todayCount, rule.target, completed, historySet)

        mainHandler.post {
            listeners.forEach { it(json) }
            onSuccess?.invoke(json)
        }
    }

    /**
     * Reads current cached streak state as JSON.
     */
    fun getState(context: Context): JSONObject? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ruleSlug = configuredRules.keys.firstOrNull() ?: "hujjify_playback"
        val rule = configuredRules[ruleSlug] ?: StreakRule(ruleSlug, target = 1)
        val todayStr = dateFormat.format(Date())

        val currentStreak = sp.getInt(KEY_CURRENT_STREAK, 0)
        val bestStreak = sp.getInt(KEY_BEST_STREAK, 0)
        val lastActiveDate = sp.getString(KEY_LAST_ACTIVE_DATE, "") ?: ""
        val todayDate = sp.getString(KEY_TODAY_DATE, "") ?: ""
        val todayCount = if (todayDate == todayStr) sp.getInt(KEY_TODAY_COUNT, 0) else 0
        val freezes = sp.getInt(KEY_FREEZES_AVAILABLE, rule.maxFreezes)
        val historySet = sp.getStringSet(KEY_HISTORY, emptySet()) ?: emptySet()
        val completed = todayCount >= rule.target

        return buildStateJson(ruleSlug, currentStreak, bestStreak, lastActiveDate, freezes, todayCount, rule.target, completed, historySet)
    }

    private fun buildStateJson(
        ruleSlug: String,
        currentStreak: Int,
        bestStreak: Int,
        lastActiveDate: String,
        freezes: Int,
        todayCount: Int,
        target: Int,
        completed: Boolean,
        historySet: Set<String>
    ): JSONObject {
        return JSONObject().apply {
            put("ruleSlug", ruleSlug)
            put("currentStreak", currentStreak)
            put("bestStreak", bestStreak)
            put("lastActiveDate", lastActiveDate)
            put("freezesAvailable", freezes)
            put("todayProgress", JSONObject().apply {
                put("current", todayCount)
                put("target", target)
                put("completed", completed)
            })
            put("history", JSONArray(historySet.toList()))
        }
    }

    /**
     * Opens the animated, self-contained Duolingo-grade modal inside a native BottomSheetDialog.
     * Renders 100% locally with zero network connection or backend server required.
     */
    fun showModal(activity: Activity, ruleSlug: String = "hujjify_playback", userId: String = "user_default") {
        activity.runOnUiThread {
            val state = getState(activity) ?: JSONObject()
            val currentStreak = state.optInt("currentStreak", 0)
            val prog = state.optJSONObject("todayProgress")
            val cur = prog?.optInt("current", 0) ?: 0
            val target = prog?.optInt("target", 1) ?: 1
            val completed = prog?.optBoolean("completed", false) ?: false
            val freezes = state.optInt("freezesAvailable", 2)
            val history = state.optJSONArray("history") ?: JSONArray()
            val historyDates = mutableSetOf<String>()
            for (i in 0 until history.length()) {
                historyDates.add(history.getString(i))
            }

            // Generate Week Days (Monday to Sunday of the current week)
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val weekDayHtml = StringBuilder()
            val dayNames = listOf("M", "T", "W", "T", "F", "S", "S")
            val todayStr = dateFormat.format(Date())

            for (i in 0 until 7) {
                val dStr = dateFormat.format(cal.time)
                val isToday = dStr == todayStr
                val isDone = historyDates.contains(dStr)

                val bubbleClass = when {
                    isDone -> "day-bubble active"
                    isToday && !completed -> "day-bubble today-pending"
                    else -> "day-bubble"
                }

                val bubbleContent = when {
                    isDone -> "✓"
                    isToday -> "🔥"
                    else -> "·"
                }

                weekDayHtml.append("""
                    <div class="day-col">
                        <span class="day-label">${dayNames[i]}</span>
                        <div class="$bubbleClass">$bubbleContent</div>
                    </div>
                """.trimIndent())
                cal.add(Calendar.DAY_OF_WEEK, 1)
            }

            val subtitleText = if (completed) {
                "<span style='color: #22c55e; font-weight: 600;'>✓ Goal completed today!</span> You're building a strong conscious audio habit."
            } else {
                "Listen to ${maxOf(1, target - cur)} more clean song(s) today to build your flame!"
            }

            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                    <style>
                        * { box-sizing: border-box; margin: 0; padding: 0; }
                        body {
                            background-color: #0f172a;
                            color: #f8fafc;
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            display: flex;
                            flex-direction: column;
                            align-items: center;
                            padding: 20px 20px 28px 20px;
                            min-height: 100vh;
                            overflow-x: hidden;
                        }
                        .header-bar {
                            width: 100%;
                            display: flex;
                            justify-content: flex-end;
                            margin-bottom: 4px;
                        }
                        .close-btn {
                            background: rgba(255, 255, 255, 0.1);
                            border: none;
                            color: #94a3b8;
                            font-size: 18px;
                            width: 32px;
                            height: 32px;
                            border-radius: 50%;
                            cursor: pointer;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                        }
                        .flame-container {
                            position: relative;
                            margin: 8px 0 16px 0;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                        }
                        .flame-glow {
                            position: absolute;
                            width: 110px;
                            height: 110px;
                            border-radius: 50%;
                            background: radial-gradient(circle, rgba(245, 158, 11, 0.4) 0%, rgba(217, 119, 6, 0) 70%);
                            animation: pulse 2s infinite ease-in-out;
                        }
                        .flame-icon {
                            font-size: 64px;
                            position: relative;
                            z-index: 2;
                            filter: drop-shadow(0 0 14px rgba(245, 158, 11, 0.65));
                            animation: bounce 2s infinite ease-in-out;
                        }
                        @keyframes pulse {
                            0%, 100% { transform: scale(1); opacity: 0.6; }
                            50% { transform: scale(1.25); opacity: 0.95; }
                        }
                        @keyframes bounce {
                            0%, 100% { transform: translateY(0); }
                            50% { transform: translateY(-6px); }
                        }
                        .streak-title {
                            font-size: 26px;
                            font-weight: 800;
                            color: #f59e0b;
                            text-align: center;
                            margin-bottom: 6px;
                            letter-spacing: -0.5px;
                        }
                        .streak-subtitle {
                            font-size: 13px;
                            color: #94a3b8;
                            text-align: center;
                            margin-bottom: 22px;
                            max-width: 290px;
                            line-height: 1.4;
                        }
                        .week-container {
                            width: 100%;
                            background: #1e293b;
                            border: 1px solid rgba(255, 255, 255, 0.08);
                            border-radius: 16px;
                            padding: 16px 12px;
                            margin-bottom: 18px;
                        }
                        .week-days {
                            display: flex;
                            justify-content: space-around;
                            align-items: center;
                        }
                        .day-col {
                            display: flex;
                            flex-direction: column;
                            align-items: center;
                            gap: 6px;
                        }
                        .day-label {
                            font-size: 11px;
                            font-weight: 600;
                            color: #64748b;
                            text-transform: uppercase;
                        }
                        .day-bubble {
                            width: 34px;
                            height: 34px;
                            border-radius: 50%;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            font-size: 13px;
                            font-weight: bold;
                            background: #0f172a;
                            border: 1px solid #334155;
                            color: #475569;
                        }
                        .day-bubble.active {
                            background: #f59e0b;
                            border-color: #fbbf24;
                            color: #0f172a;
                            box-shadow: 0 0 10px rgba(245, 158, 11, 0.45);
                        }
                        .day-bubble.today-pending {
                            border: 2px dashed #f59e0b;
                            color: #f59e0b;
                        }
                        .stats-grid {
                            width: 100%;
                            display: grid;
                            grid-template-columns: 1fr 1fr;
                            gap: 12px;
                            margin-bottom: 22px;
                        }
                        .stat-card {
                            background: #1e293b;
                            border: 1px solid rgba(255, 255, 255, 0.06);
                            border-radius: 14px;
                            padding: 14px;
                            display: flex;
                            flex-direction: column;
                            gap: 4px;
                        }
                        .stat-card-title {
                            font-size: 11px;
                            color: #94a3b8;
                            font-weight: 600;
                            text-transform: uppercase;
                            letter-spacing: 0.5px;
                        }
                        .stat-card-val {
                            font-size: 16px;
                            font-weight: 700;
                            color: #f8fafc;
                        }
                        .btn-share {
                            width: 100%;
                            background: linear-gradient(135deg, #f59e0b, #d97706);
                            color: #ffffff;
                            border: none;
                            border-radius: 28px;
                            padding: 14px;
                            font-size: 15px;
                            font-weight: 700;
                            cursor: pointer;
                            margin-bottom: 10px;
                            box-shadow: 0 4px 14px rgba(245, 158, 11, 0.35);
                        }
                        .btn-close {
                            background: transparent;
                            color: #94a3b8;
                            border: none;
                            font-size: 14px;
                            font-weight: 600;
                            padding: 8px;
                            cursor: pointer;
                        }
                    </style>
                </head>
                <body>
                    <div class="header-bar">
                        <button class="close-btn" onclick="StreakBridge.close()">✕</button>
                    </div>
                    <div class="flame-container">
                        <div class="flame-glow"></div>
                        <div class="flame-icon">🔥</div>
                    </div>
                    <div class="streak-title">$currentStreak Day Streak!</div>
                    <div class="streak-subtitle">$subtitleText</div>
                    <div class="week-container">
                        <div class="week-days">
                            $weekDayHtml
                        </div>
                    </div>
                    <div class="stats-grid">
                        <div class="stat-card">
                            <div class="stat-card-title">Today's Progress</div>
                            <div class="stat-card-val" style="color: #22c55e;">$cur / $target Clean Track</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-card-title">Streak Shield</div>
                            <div class="stat-card-val" style="color: #38bdf8;">🛡️ $freezes/2 Freezes</div>
                        </div>
                    </div>
                    <button class="btn-share" onclick="StreakBridge.share('🔥 I\'m on a $currentStreak day conscious listening streak on Hujjify! Listen clean with me: https://github.com/intisor/Alify')">Share Streak Milestone</button>
                    <button class="btn-close" onclick="StreakBridge.close()">Continue Listening</button>
                </body>
                </html>
            """.trimIndent()

            val dialog = BottomSheetDialog(activity)
            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (activity.resources.displayMetrics.heightPixels * 0.78).toInt()
                )
                setBackgroundColor(Color.parseColor("#0f172a"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun close() {
                        activity.runOnUiThread { dialog.dismiss() }
                    }

                    @JavascriptInterface
                    fun share(text: String) {
                        activity.runOnUiThread {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            activity.startActivity(Intent.createChooser(intent, "Share Streak Milestone"))
                        }
                    }
                }, "StreakBridge")

                loadDataWithBaseURL("https://streakkit.local", html, "text/html", "utf-8", null)
            }

            dialog.setContentView(webView)
            dialog.show()
        }
    }
}
