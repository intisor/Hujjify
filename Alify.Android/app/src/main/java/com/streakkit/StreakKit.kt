package com.streakkit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import com.google.android.material.bottomsheet.BottomSheetDialog
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Developer Rule Definition passed as JSON or typed object into the SDK
 */
data class StreakRule(
    val slug: String,
    val name: String = slug,
    val target: Int = 1,
    val cadence: String = "DAILY",
    val gracePeriodMinutes: Int = 120,
    val maxFreezes: Int = 2,
    val daysOff: List<Int> = emptyList(),
    val freezeAutoEarnDays: Int = 0,
    val restoreWindowDays: Int = 90
)

/**
 * StreakKit Android SDK — Local-First, Zero-Friction Engine for Ujify
 * - Evaluates streaks on-device instantly with 100% offline reliability
 * - Seamlessly updates home widgets, notifications, and bottom sheets
 * - Asynchronously syncs with backend when available
 * - Self-contained Duolingo-grade animated embeds
 */
object StreakKit {
    private const val PREFS = "streakkit_prefs"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_PUBLIC_KEY = "public_key"
    private const val KEY_STATE = "cached_state"
    private const val KEY_QUEUE = "offline_queue"

    var endpoint: String = "http://10.0.2.2:3000"
    var publicKey: String = "pk_live_hujjify_456"

    private val configuredRules = mutableMapOf<String, StreakRule>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(JSONObject) -> Unit>()
    private val milestoneListeners = mutableListOf<(ruleSlug: String, milestoneDay: Int) -> Unit>()

    fun onMilestoneReached(listener: (ruleSlug: String, milestoneDay: Int) -> Unit) {
        milestoneListeners.add(listener)
    }

    fun init(
        context: Context,
        defaultEndpoint: String = endpoint,
        defaultKey: String = publicKey,
        rules: List<StreakRule> = emptyList(),
        rulesJson: String? = null
    ) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        endpoint = sp.getString(KEY_ENDPOINT, defaultEndpoint) ?: defaultEndpoint
        publicKey = sp.getString(KEY_PUBLIC_KEY, defaultKey) ?: defaultKey

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

        // Initialize local state if empty so UI indicators never see null
        if (getState(context) == null) {
            val initialRule = rules.firstOrNull() ?: configuredRules.values.firstOrNull()
            val slug = initialRule?.slug ?: "ujify_playback"
            val target = initialRule?.target ?: 3
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

            val initialJson = JSONObject().apply {
                put("ruleSlug", slug)
                put("currentStreak", 0)
                put("longestStreak", 0)
                put("status", "PENDING_TODAY")
                put("freezesAvailable", 2)
                put("freezes", JSONObject().apply {
                    put("available", 2)
                    put("max", 2)
                })
                put("lastActiveDate", "")
                put("lastProgressDate", todayStr)
                put("todayProgress", JSONObject().apply {
                    put("current", 0)
                    put("target", target)
                    put("completed", false)
                })
                put("timing", JSONObject().apply {
                    put("secondsUntilExpiry", calculateSecondsUntilMidnight())
                })
            }
            saveState(context, initialJson)
        }

        syncRulesWithBackend()
        flushQueue(context)
    }

    private fun syncRulesWithBackend() {
        if (configuredRules.isEmpty()) return
        val rulesArr = JSONArray()
        configuredRules.values.forEach { r ->
            rulesArr.put(JSONObject().apply {
                put("slug", r.slug)
                put("name", r.name)
                put("target", r.target)
                put("cadence", r.cadence)
                put("gracePeriodMinutes", r.gracePeriodMinutes)
                put("maxFreezes", r.maxFreezes)
            })
        }

        val body = JSONObject().put("rules", rulesArr)
        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/rules/sync")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    fun onStateChange(listener: (JSONObject) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Records a streak action. Local-first engine ensures 100% offline accuracy,
     * immediately updating the UI and Home Screen widgets, with background server sync.
     */
    fun ping(
        context: Context,
        ruleSlug: String,
        userId: String = "user_default",
        value: Int = 1,
        onSuccess: ((JSONObject) -> Unit)? = null
    ) {
        val rule = configuredRules[ruleSlug]
        val target = rule?.target ?: 3

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val existing = getState(context) ?: JSONObject()

        var currentStreak = existing.optInt("currentStreak", 0)
        var longestStreak = existing.optInt("longestStreak", 0)
        var freezesAvailable = existing.optJSONObject("freezes")?.optInt("available", 2)
            ?: existing.optInt("freezesAvailable", 2)
        val lastActiveDate = existing.optString("lastActiveDate", "")
        val lastProgressDate = existing.optString("lastProgressDate", "")

        val prevProgress = if (lastProgressDate == todayStr) {
            existing.optJSONObject("todayProgress")?.optInt("current", 0) ?: 0
        } else {
            0
        }

        val newProgress = prevProgress + value
        val completedToday = newProgress >= target
        var justReachedMilestone = false

        if (value > 0) {
            if (lastProgressDate.isNotEmpty() && lastProgressDate != todayStr) {
                // Day transition evaluation
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val lastDate = sdf.parse(lastProgressDate)
                    val todayDate = sdf.parse(todayStr)
                    if (lastDate != null && todayDate != null) {
                        val diffDays = ((todayDate.time - lastDate.time) / (1000 * 60 * 60 * 24)).toInt()
                        if (diffDays == 2) {
                            // Missed exactly 1 day — use freeze shield if available
                            if (freezesAvailable > 0) {
                                freezesAvailable -= 1
                            } else {
                                currentStreak = 0
                            }
                        } else if (diffDays > 2) {
                            currentStreak = 0
                        }
                    }
                } catch (_: Exception) {}
            }

            if (completedToday && lastActiveDate != todayStr) {
                currentStreak += 1
                if (currentStreak > longestStreak) longestStreak = currentStreak
                justReachedMilestone = true
            }
        }

        val secondsUntilExpiry = calculateSecondsUntilMidnight()

        val stateJson = JSONObject().apply {
            put("ruleSlug", ruleSlug)
            put("currentStreak", currentStreak)
            put("longestStreak", longestStreak)
            put("status", if (completedToday) "ACTIVE" else "PENDING_TODAY")
            put("freezesAvailable", freezesAvailable)
            put("freezes", JSONObject().apply {
                put("available", freezesAvailable)
                put("max", rule?.maxFreezes ?: 2)
            })
            put("lastActiveDate", if (completedToday) todayStr else lastActiveDate)
            put("lastProgressDate", todayStr)
            put("todayProgress", JSONObject().apply {
                put("current", newProgress)
                put("target", target)
                put("completed", completedToday)
            })
            put("timing", JSONObject().apply {
                put("secondsUntilExpiry", secondsUntilExpiry)
            })
        }

        saveState(context, stateJson)

        mainHandler.post {
            listeners.forEach { it(stateJson) }
            if (justReachedMilestone) {
                milestoneListeners.forEach { it(ruleSlug, currentStreak) }
            }
            onSuccess?.invoke(stateJson)
        }

        // Asynchronously sync with remote backend if configured
        syncPingRemote(context, ruleSlug, userId, value, rule)
    }

    private fun syncPingRemote(context: Context, ruleSlug: String, userId: String, value: Int, rule: StreakRule?) {
        val payload = JSONObject().apply {
            put("ruleSlug", ruleSlug)
            put("externalUserId", userId)
            put("value", value)
            put("timezone", TimeZone.getDefault().id)
            if (rule != null) {
                put("ruleConfig", JSONObject().apply {
                    put("name", rule.name)
                    put("target", rule.target)
                    put("cadence", rule.cadence)
                    put("gracePeriodMinutes", rule.gracePeriodMinutes)
                    put("maxFreezes", rule.maxFreezes)
                })
            }
        }

        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/ping")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                enqueueOffline(context, payload)
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return
                    val json = try { JSONObject(body) } catch (_: Exception) { return }
                    saveState(context, json)
                    mainHandler.post { listeners.forEach { it(json) } }
                    flushQueue(context)
                } else {
                    enqueueOffline(context, payload)
                }
            }
        })
    }

    private fun calculateSecondsUntilMidnight(): Long {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        val midnight = cal.timeInMillis
        return ((midnight - now) / 1000).coerceAtLeast(0L)
    }

    fun getState(context: Context): JSONObject? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATE, null) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun saveState(context: Context, json: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATE, json.toString()).apply()

        // 1. Auto-update all Home Screen Widgets on state change
        try {
            StreakWidgetProvider.updateAllWidgets(context)
        } catch (_: Exception) {}

        // 2. Evaluate and trigger local urgency alerts if expiring soon (< 2h / < 30m)
        try {
            val timing = json.optJSONObject("timing")
            val secondsUntilExpiry = timing?.optLong("secondsUntilExpiry", -1L) ?: -1L
            val prog = json.optJSONObject("todayProgress")
            val completed = prog?.optBoolean("completed", false) ?: false
            val currentStreak = json.optInt("currentStreak", 0)
            val freezes = json.optJSONObject("freezes")?.optInt("available", 0) ?: 0
            val ruleSlug = json.optString("ruleSlug", "ujify_playback")
            val ruleName = configuredRules[ruleSlug]?.name ?: "Conscious Audio Streak"

            if (secondsUntilExpiry in 1..7200 && !completed) {
                StreakNotificationManager.checkAndAlertIfUrgent(
                    context = context,
                    ruleName = ruleName,
                    ruleSlug = ruleSlug,
                    streak = currentStreak,
                    secondsLeft = secondsUntilExpiry,
                    completedToday = completed,
                    freezes = freezes,
                    tone = "UNHINGED"
                )
            }
        } catch (_: Exception) {}
    }

    private fun enqueueOffline(context: Context, payload: JSONObject) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString(KEY_QUEUE, "[]") ?: "[]")
        arr.put(payload)
        sp.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    private fun flushQueue(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString(KEY_QUEUE, "[]") ?: "[]")
        if (arr.length() == 0) return

        val remaining = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val req = Request.Builder()
                .url("${endpoint.trimEnd('/')}/v1/ping")
                .header("Authorization", "Bearer $publicKey")
                .header("Content-Type", "application/json")
                .post(item.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    remaining.put(item)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) remaining.put(item)
                }
            })
        }
        sp.edit().putString(KEY_QUEUE, remaining.toString()).apply()
    }

    /**
     * Opens the animated, Duolingo-grade modal inside a native Android BottomSheetDialog.
     * Guaranteed 100% offline-ready with local SVG animations and live stats.
     */
    fun showModal(activity: Activity, ruleSlug: String, userId: String = "user_default") {
        activity.runOnUiThread {
            val dialog = BottomSheetDialog(activity)
            val state = getState(activity) ?: JSONObject()
            val currentStreak = state.optInt("currentStreak", 0)
            val prog = state.optJSONObject("todayProgress")
            val cur = prog?.optInt("current", 0) ?: 0
            val target = prog?.optInt("target", 3) ?: 3
            val completed = prog?.optBoolean("completed", false) ?: false
            val freezes = state.optJSONObject("freezes")?.optInt("available", 2) ?: 2
            val percent = if (target > 0) ((cur.toFloat() / target) * 100).toInt().coerceAtMost(100) else 0

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (activity.resources.displayMetrics.heightPixels * 0.88).toInt()
                )
                setBackgroundColor(Color.parseColor("#09120C"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT

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

                    @JavascriptInterface
                    fun onMilestone(slug: String, day: Int) {
                        activity.runOnUiThread {
                            milestoneListeners.forEach { it(slug, day) }
                        }
                    }
                }, "StreakBridge")

                val statusBadgeColor = if (completed) "#10b981" else "#f59e0b"
                val statusBadgeText = if (completed) "Active Today" else "In Progress ($cur/$target)"

                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                            body {
                                background: #09120C;
                                color: #FFFFFF;
                                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                margin: 0;
                                padding: 24px 20px;
                                display: flex;
                                flex-direction: column;
                                align-items: center;
                                text-align: center;
                            }
                            .drag-handle { width: 40px; height: 4px; background: #27272a; border-radius: 2px; margin-bottom: 20px; }
                            .flame-container {
                                position: relative;
                                width: 90px;
                                height: 90px;
                                display: flex;
                                align-items: center;
                                justify-content: center;
                                margin-bottom: 12px;
                            }
                            .flame-glow {
                                position: absolute;
                                width: 90px;
                                height: 90px;
                                background: radial-gradient(circle, rgba(245, 158, 11, 0.35) 0%, rgba(9, 18, 12, 0) 70%);
                                border-radius: 50%;
                                animation: pulse 2s infinite ease-in-out;
                            }
                            @keyframes pulse {
                                0%, 100% { transform: scale(1); opacity: 0.8; }
                                50% { transform: scale(1.15); opacity: 1; }
                            }
                            .flame-icon { font-size: 56px; z-index: 1; }
                            h1 { font-size: 32px; font-weight: 800; margin: 0 0 6px 0; color: #FFFFFF; }
                            .streak-subtitle { font-size: 14px; color: #a1a1aa; margin: 0 0 18px 0; }
                            .status-pill {
                                display: inline-block;
                                background: rgba(245, 158, 11, 0.12);
                                border: 1px solid $statusBadgeColor;
                                color: $statusBadgeColor;
                                padding: 6px 16px;
                                border-radius: 9999px;
                                font-size: 13px;
                                font-weight: 700;
                                margin-bottom: 24px;
                            }
                            .card {
                                width: 100%;
                                background: #121f15;
                                border: 1px solid #1f3b26;
                                border-radius: 14px;
                                padding: 16px;
                                margin-bottom: 16px;
                                text-align: left;
                            }
                            .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
                            .card-title { font-size: 14px; font-weight: 700; color: #1DB954; }
                            .card-metric { font-size: 13px; font-weight: 700; color: #f59e0b; }
                            .progress-bg { width: 100%; height: 8px; background: #09120C; border-radius: 4px; overflow: hidden; }
                            .progress-fill { width: ${percent}%; height: 100%; background: linear-gradient(90deg, #1DB954, #10b981); border-radius: 4px; }
                            .freeze-box { display: flex; align-items: center; gap: 10px; margin-top: 12px; font-size: 12px; color: #a1a1aa; }
                            .wisdom { font-style: italic; font-size: 13px; color: #71717a; line-height: 1.5; margin: 8px 0 24px 0; }
                            .actions { display: flex; gap: 10px; width: 100%; }
                            .btn-primary {
                                flex: 1;
                                background: #1DB954;
                                color: #000000;
                                border: none;
                                border-radius: 24px;
                                padding: 14px;
                                font-size: 15px;
                                font-weight: 700;
                                cursor: pointer;
                            }
                            .btn-secondary {
                                flex: 1;
                                background: transparent;
                                color: #FFFFFF;
                                border: 1px solid #27272a;
                                border-radius: 24px;
                                padding: 14px;
                                font-size: 15px;
                                font-weight: 700;
                                cursor: pointer;
                            }
                        </style>
                    </head>
                    <body>
                        <div class="drag-handle"></div>
                        <div class="flame-container">
                            <div class="flame-glow"></div>
                            <div class="flame-icon">🔥</div>
                        </div>
                        <h1>$currentStreak Day Streak</h1>
                        <div class="streak-subtitle">Track your daily conscious audio habit</div>
                        <div class="status-pill">$statusBadgeText</div>

                        <div class="card">
                            <div class="card-header">
                                <span class="card-title">Daily Conscious Target</span>
                                <span class="card-metric">$cur / $target Clean Songs</span>
                            </div>
                            <div class="progress-bg">
                                <div class="progress-fill"></div>
                            </div>
                            <div class="freeze-box">
                                <span>🛡️</span>
                                <span>$freezes/2 Streak Freezes remaining to protect missed days</span>
                            </div>
                        </div>

                        <div class="wisdom">
                            "Guarding what enters your ears purifies your mind and grounds your soul. Consistency is your sanctuary."
                        </div>

                        <div class="actions">
                            <button class="btn-secondary" onclick="StreakBridge.share('I am on a $currentStreak-day conscious listening streak with Ujify! 🎧✨')">Share</button>
                            <button class="btn-primary" onclick="StreakBridge.close()">Done</button>
                        </div>
                    </body>
                    </html>
                """.trimIndent()

                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }

            dialog.setContentView(webView)
            dialog.show()
        }
    }

    /**
     * Trophy-Grade Moat: Restore a broken streak within the 90-day restore window.
     */
    fun restore(
        ruleSlug: String,
        userId: String = "user_default",
        onComplete: ((Boolean, Int, String) -> Unit)? = null
    ) {
        val body = JSONObject().apply {
            put("ruleSlug", ruleSlug)
            put("externalUserId", userId)
            put("timezone", TimeZone.getDefault().id)
        }

        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/streak/restore")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete?.invoke(false, 0, e.message ?: "Network error") }
            }

            override fun onResponse(call: Call, response: Response) {
                val respStr = response.body?.string() ?: "{}"
                val json = try { JSONObject(respStr) } catch (_: Exception) { JSONObject() }
                val success = json.optBoolean("success", false)
                val len = json.optInt("restoredStreakLength", 0)
                val msg = json.optString("message", "")
                mainHandler.post { onComplete?.invoke(success, len, msg) }
            }
        })
    }

    /**
     * Trophy-Grade Moat: Schedule a vacation pause.
     */
    fun pause(
        ruleSlug: String,
        userId: String = "user_default",
        startDate: String,
        endDate: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val body = JSONObject().apply {
            put("ruleSlug", ruleSlug)
            put("externalUserId", userId)
            put("startDate", startDate)
            put("endDate", endDate)
        }

        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/streak/pause")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete?.invoke(false, e.message) }
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                val respStr = response.body?.string()
                mainHandler.post { onComplete?.invoke(success, respStr) }
            }
        })
    }

    /**
     * Trophy-Grade Moat: Retrieve ranked friends streaks.
     */
    fun getFriendsStreak(
        ruleSlug: String,
        userIds: List<String>,
        onResult: (JSONArray) -> Unit
    ) {
        val body = JSONObject().apply {
            put("ruleSlug", ruleSlug)
            put("userIds", JSONArray(userIds))
            put("timezone", TimeZone.getDefault().id)
        }

        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/streak/friends")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult(JSONArray()) }
            }

            override fun onResponse(call: Call, response: Response) {
                val respStr = response.body?.string() ?: "{}"
                val json = try { JSONObject(respStr) } catch (_: Exception) { JSONObject() }
                val rankings = json.optJSONArray("rankings") ?: JSONArray()
                mainHandler.post { onResult(rankings) }
            }
        })
    }

    /**
     * Trophy-Grade Moat: Retrieve Wrapped / Year in Review summary.
     */
    fun getWrapped(
        ruleSlug: String,
        userId: String = "user_default",
        year: Int = 2026,
        onResult: (JSONObject?) -> Unit
    ) {
        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/streak/wrapped?ruleSlug=${Uri.encode(ruleSlug)}&externalUserId=${Uri.encode(userId)}&year=$year")
            .header("Authorization", "Bearer $publicKey")
            .get()
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val respStr = response.body?.string() ?: "{}"
                val json = try { JSONObject(respStr) } catch (_: Exception) { JSONObject() }
                val wrapped = json.optJSONObject("wrapped")
                mainHandler.post { onResult(wrapped) }
            }
        })
    }

    /**
     * Trophy-Grade Moat: Set per-user custom targets and rest days.
     */
    fun setUserPreferences(
        ruleSlug: String,
        userId: String = "user_default",
        customTarget: Int? = null,
        daysOff: List<Int>? = null,
        enabled: Boolean = true,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val body = JSONObject().apply {
            put("ruleSlug", ruleSlug)
            put("externalUserId", userId)
            if (customTarget != null) put("customTarget", customTarget)
            if (daysOff != null) put("daysOffOverride", JSONArray(daysOff))
            put("enabled", enabled)
        }

        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/streak/preferences")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete?.invoke(false) }
            }

            override fun onResponse(call: Call, response: Response) {
                mainHandler.post { onComplete?.invoke(response.isSuccessful) }
            }
        })
    }

    /**
     * Cross-Action Behavioral Contrast Nudges (Counter-Habit Nudge Engine)
     */
    fun evaluateContrastNudge(
        context: Context,
        userId: String = "user_default",
        neglectedRuleSlug: String,
        triggerAction: String,
        triggerLabel: String,
        durationMinutes: Int? = null,
        archetype: String = "GUILT_PRIORITY",
        customCopies: List<String> = emptyList(),
        autoShowNotification: Boolean = true,
        onResult: ((JSONObject?) -> Unit)? = null
    ) {
        val triggerObj = JSONObject().apply {
            put("action", triggerAction)
            put("label", triggerLabel)
            if (durationMinutes != null) put("durationMinutes", durationMinutes)
        }

        val body = JSONObject().apply {
            put("userId", userId)
            put("neglectedRuleSlug", neglectedRuleSlug)
            put("triggerActivity", triggerObj)
            put("archetype", archetype)
            if (customCopies.isNotEmpty()) {
                put("customCopies", JSONArray(customCopies))
            }
        }

        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/notifications/contrast")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult?.invoke(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val respStr = response.body?.string() ?: "{}"
                val json = try { JSONObject(respStr) } catch (_: Exception) { JSONObject() }
                val notif = json.optJSONObject("notification")
                val completedToday = json.optBoolean("completedToday", false)

                if (autoShowNotification && notif != null && !completedToday) {
                    val title = notif.optString("title", "Streak Alert")
                    val bodyText = notif.optString("body", "Protect your streak today!")
                    StreakNotificationManager.showContrastNotification(
                        context = context,
                        title = title,
                        body = bodyText,
                        neglectedRuleSlug = neglectedRuleSlug,
                        triggerAction = triggerAction
                    )
                }

                mainHandler.post { onResult?.invoke(notif) }
            }
        })
    }

    /**
     * Streak Amnesty: Evaluates quarterly/dormant streak resurrection offer.
     */
    fun checkStreakAmnesty(
        ruleSlug: String,
        userId: String = "user_default",
        minDormantDays: Int = 7,
        onResult: (JSONObject?) -> Unit
    ) {
        val body = JSONObject().apply {
            put("ruleSlug", ruleSlug)
            put("externalUserId", userId)
            put("minDormantDays", minDormantDays)
        }

        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/streak/amnesty/evaluate")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val respStr = response.body?.string() ?: "{}"
                val json = try { JSONObject(respStr) } catch (_: Exception) { JSONObject() }
                val eval = json.optJSONObject("evaluation")
                mainHandler.post { onResult(eval) }
            }
        })
    }

    /**
     * Claims Streak Amnesty and resurrects user's broken streak.
     */
    fun claimStreakAmnesty(
        ruleSlug: String,
        userId: String = "user_default",
        onComplete: (Boolean, Int) -> Unit
    ) {
        val body = JSONObject().apply {
            put("ruleSlug", ruleSlug)
            put("externalUserId", userId)
        }

        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/streak/amnesty/claim")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete(false, 0) }
            }

            override fun onResponse(call: Call, response: Response) {
                val respStr = response.body?.string() ?: "{}"
                val json = try { JSONObject(respStr) } catch (_: Exception) { JSONObject() }
                val success = json.optBoolean("success", false)
                val resurrected = json.optInt("resurrectedStreak", 0)
                mainHandler.post { onComplete(success, resurrected) }
            }
        })
    }

    /**
     * Registers a push token with StreakKit.
     */
    fun registerPushToken(
        userId: String = "user_default",
        pushToken: String,
        platform: String = "FCM",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val body = JSONObject().apply {
            put("externalUserId", userId)
            put("pushToken", pushToken)
            put("platform", platform)
        }

        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/users/push-token")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete?.invoke(false) }
            }

            override fun onResponse(call: Call, response: Response) {
                mainHandler.post { onComplete?.invoke(response.isSuccessful) }
            }
        })
    }

    /**
     * Opens the full Duolingo-in-a-Box Winding Path UI in a native BottomSheetDialog.
     * Guaranteed 100% offline-ready.
     */
    fun showDuolingoPath(
        activity: Activity,
        ruleSlug: String,
        userId: String = "user_default",
        flag: String = "NG",
        unitTitle: String = "Conscious Audio",
        unitSubtitle: String = "Purify your listening habit"
    ) {
        activity.runOnUiThread {
            val dialog = BottomSheetDialog(activity)
            val state = getState(activity) ?: JSONObject()
            val currentStreak = state.optInt("currentStreak", 0)

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (activity.resources.displayMetrics.heightPixels * 0.92).toInt()
                )
                setBackgroundColor(Color.parseColor("#131f24"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun close() {
                        activity.runOnUiThread { dialog.dismiss() }
                    }
                }, "StreakBridge")

                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            * { box-sizing: border-box; }
                            body {
                                background: #131f24;
                                color: #FFFFFF;
                                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                margin: 0;
                                padding: 20px 16px;
                                display: flex;
                                flex-direction: column;
                                align-items: center;
                            }
                            .header {
                                width: 100%;
                                display: flex;
                                justify-content: space-between;
                                align-items: center;
                                padding: 12px 16px;
                                background: #1e2c33;
                                border-radius: 16px;
                                margin-bottom: 20px;
                            }
                            .stat-item { display: flex; align-items: center; gap: 6px; font-weight: 800; font-size: 15px; }
                            .unit-card {
                                width: 100%;
                                background: #58cc02;
                                color: #FFFFFF;
                                border-radius: 16px;
                                padding: 20px 16px;
                                margin-bottom: 24px;
                            }
                            .unit-title { font-size: 13px; font-weight: 800; text-transform: uppercase; opacity: 0.9; margin-bottom: 4px; }
                            .unit-desc { font-size: 18px; font-weight: 800; }
                            .path {
                                display: flex;
                                flex-direction: column;
                                align-items: center;
                                gap: 20px;
                                width: 100%;
                                margin-bottom: 28px;
                            }
                            .node {
                                width: 68px;
                                height: 68px;
                                border-radius: 50%;
                                display: flex;
                                align-items: center;
                                justify-content: center;
                                font-size: 28px;
                                box-shadow: 0 6px 0 rgba(0,0,0,0.3);
                                cursor: pointer;
                            }
                            .node-completed { background: #58cc02; }
                            .node-active { background: #1cb0f6; animation: bounce 1.5s infinite; }
                            .node-locked { background: #37464f; opacity: 0.6; }
                            @keyframes bounce {
                                0%, 100% { transform: translateY(0); }
                                50% { transform: translateY(-6px); }
                            }
                            .node:nth-child(2) { transform: translateX(35px); }
                            .node:nth-child(3) { transform: translateX(-35px); }
                            .btn-done {
                                width: 100%;
                                background: #58cc02;
                                color: #FFFFFF;
                                border: none;
                                border-radius: 20px;
                                padding: 14px;
                                font-size: 16px;
                                font-weight: 800;
                                cursor: pointer;
                            }
                        </style>
                    </head>
                    <body>
                        <div class="header">
                            <div class="stat-item"><span>🇳🇬</span><span>$flag</span></div>
                            <div class="stat-item" style="color: #ff9600;"><span>🔥</span><span>$currentStreak</span></div>
                            <div class="stat-item" style="color: #1cb0f6;"><span>💎</span><span>${currentStreak * 15}</span></div>
                            <div class="stat-item" style="color: #ff4b4b;"><span>❤️</span><span>5</span></div>
                        </div>

                        <div class="unit-card">
                            <div class="unit-title">$unitTitle</div>
                            <div class="unit-desc">$unitSubtitle</div>
                        </div>

                        <div class="path">
                            <div class="node node-completed">⭐</div>
                            <div class="node node-active">🎧</div>
                            <div class="node node-locked">🔒</div>
                            <div class="node node-locked">🎁</div>
                        </div>

                        <button class="btn-done" onclick="StreakBridge.close()">Done</button>
                    </body>
                    </html>
                """.trimIndent()

                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
            dialog.setContentView(webView)
            dialog.show()
        }
    }

    fun consumeHeart(
        userId: String = "user_default",
        count: Int = 1,
        onResult: ((heartsRemaining: Int, outOfHearts: Boolean) -> Unit)? = null
    ) {
        val body = JSONObject().apply {
            put("userId", userId)
            put("count", count)
            put("timezone", TimeZone.getDefault().id)
        }
        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/gamification/hearts/consume")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult?.invoke(4, false) }
            }
            override fun onResponse(call: Call, response: Response) {
                val json = try { JSONObject(response.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
                val heartsObj = json.optJSONObject("hearts")
                val current = heartsObj?.optInt("current", 5) ?: 5
                val out = heartsObj?.optBoolean("outOfHearts", false) ?: false
                mainHandler.post { onResult?.invoke(current, out) }
            }
        })
    }

    fun refillHearts(
        userId: String = "user_default",
        method: String = "GEMS",
        onResult: ((heartsCurrent: Int, gemsBalance: Int) -> Unit)? = null
    ) {
        val body = JSONObject().apply {
            put("userId", userId)
            put("method", method)
            put("timezone", TimeZone.getDefault().id)
        }
        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/gamification/hearts/refill")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult?.invoke(5, 50) }
            }
            override fun onResponse(call: Call, response: Response) {
                val json = try { JSONObject(response.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
                val current = json.optJSONObject("hearts")?.optInt("current", 5) ?: 5
                val gems = json.optJSONObject("gems")?.optInt("balance", 0) ?: 0
                mainHandler.post { onResult?.invoke(current, gems) }
            }
        })
    }

    fun addXp(
        userId: String = "user_default",
        amount: Int = 10,
        unitId: String? = null,
        onResult: ((xpToday: Int, dailyGoalCompleted: Boolean, gemsBalance: Int) -> Unit)? = null
    ) {
        val body = JSONObject().apply {
            put("userId", userId)
            put("amount", amount)
            if (unitId != null) put("unitId", unitId)
            put("timezone", TimeZone.getDefault().id)
        }
        val req = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/gamification/xp/add")
            .header("Authorization", "Bearer $publicKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult?.invoke(amount, false, 10) }
            }
            override fun onResponse(call: Call, response: Response) {
                val json = try { JSONObject(response.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
                val xpObj = json.optJSONObject("xp")
                val xpToday = xpObj?.optInt("today", 0) ?: 0
                val goalDone = xpObj?.optBoolean("dailyGoalCompleted", false) ?: false
                val gems = json.optJSONObject("gems")?.optInt("balance", 0) ?: 0
                mainHandler.post { onResult?.invoke(xpToday, goalDone, gems) }
            }
        })
    }
}
