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
import android.webkit.WebViewClient
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
import java.net.URLEncoder
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
 * StreakKit Android SDK — Universal Single-File Drop-In (< 140 lines)
 * - Developers define rules directly in JSON / code passed to init()
 * - Auto-provisions and syncs rules with backend seamlessly
 * - 1-line async ping with offline auto-queue
 * - 1-line Duolingo-grade animated embed UI in a native BottomSheetDialog
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
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(JSONObject) -> Unit>()
    private val milestoneListeners = mutableListOf<(ruleSlug: String, milestoneDay: Int) -> Unit>()

    /**
     * Register a listener for when a user unlocks a milestone day (e.g. 3, 7, 14, 30, 100 days).
     */
    fun onMilestoneReached(listener: (ruleSlug: String, milestoneDay: Int) -> Unit) {
        milestoneListeners.add(listener)
    }

    /**
     * Initialize SDK with API keys and declared developer rules (via list or JSON string).
     */
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

        // Register typed rules
        rules.forEach { configuredRules[it.slug] = it }

        // Register JSON rules string if provided
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
     * Records a streak action. Automatically falls back to local queue if offline.
     */
    fun ping(
        context: Context,
        ruleSlug: String,
        userId: String = "user_default",
        value: Int = 1,
        onSuccess: ((JSONObject) -> Unit)? = null
    ) {
        val rule = configuredRules[ruleSlug]
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
                    val milestoneObj = json.optJSONObject("milestone")
                    val justReached = milestoneObj?.optInt("justReached", -1) ?: -1
                    mainHandler.post {
                        listeners.forEach { it(json) }
                        if (justReached > 0) {
                            milestoneListeners.forEach { it(ruleSlug, justReached) }
                        }
                        onSuccess?.invoke(json)
                    }
                    flushQueue(context)
                } else {
                    enqueueOffline(context, payload)
                }
            }
        })
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
            val ruleSlug = json.optString("ruleSlug", "streak")
            val ruleName = configuredRules[ruleSlug]?.name ?: ruleSlug

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
     */
    fun showModal(activity: Activity, ruleSlug: String, userId: String = "user_default") {
        activity.runOnUiThread {
            val dialog = BottomSheetDialog(activity)
            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (activity.resources.displayMetrics.heightPixels * 0.85).toInt()
                )
                setBackgroundColor(Color.parseColor("#0f172a"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

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

                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        val html = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                <style>
                                    body {
                                        background-color: #0f172a;
                                        color: #f8fafc;
                                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                        display: flex;
                                        flex-direction: column;
                                        align-items: center;
                                        justify-content: center;
                                        min-height: 80vh;
                                        margin: 0;
                                        padding: 24px;
                                        text-align: center;
                                        box-sizing: border-box;
                                    }
                                    .icon { font-size: 48px; margin-bottom: 16px; }
                                    h2 { font-size: 20px; font-weight: 700; margin: 0 0 8px 0; color: #fbbf24; }
                                    p { font-size: 14px; color: #94a3b8; margin: 0 0 20px 0; line-height: 1.5; }
                                    .url { font-family: monospace; font-size: 12px; background: #1e293b; padding: 6px 10px; border-radius: 6px; word-break: break-all; margin-bottom: 24px; color: #38bdf8; }
                                    .btn {
                                        background: linear-gradient(135deg, #f59e0b, #d97706);
                                        color: white;
                                        border: none;
                                        border-radius: 9999px;
                                        padding: 12px 28px;
                                        font-weight: 600;
                                        font-size: 14px;
                                        cursor: pointer;
                                    }
                                </style>
                            </head>
                            <body>
                                <div class="icon">★</div>
                                <h2>Connecting to StreakKit...</h2>
                                <p>Unable to connect to the backend server. Make sure the StreakKit backend is running.</p>
                                <div class="url">$failingUrl</div>
                                <button class="btn" onclick="location.reload()">Retry Connection</button>
                            </body>
                            </html>
                        """.trimIndent()
                        view?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                }

                loadUrl("${endpoint.trimEnd('/')}/v1/embed/${Uri.encode(ruleSlug)}/${Uri.encode(userId)}")
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
     * e.g. "You listened to music for 45 mins, but did not protect your deen?"
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
     * Registers a Firebase Cloud Messaging (FCM) or push token with StreakKit.
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
     * Opens the full Duolingo-in-a-Box Winding Path UI (Top Bar with Flag + Streak + Gems + Hearts,
     * Daily XP Goal Bar, Unit Banner, and Winding S-Curve Lesson Nodes) in a native BottomSheetDialog.
     */
    fun showDuolingoPath(
        activity: Activity,
        ruleSlug: String,
        userId: String = "user_default",
        flag: String = "NG",
        unitTitle: String = "Greetings",
        unitSubtitle: String = "Say hello and introduce yourself"
    ) {
        activity.runOnUiThread {
            val dialog = BottomSheetDialog(activity)
            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (activity.resources.displayMetrics.heightPixels * 0.92).toInt()
                )
                setBackgroundColor(Color.parseColor("#131f24"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                val tz = URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")
                val f = URLEncoder.encode(flag, "UTF-8")
                val ut = URLEncoder.encode(unitTitle, "UTF-8")
                val us = URLEncoder.encode(unitSubtitle, "UTF-8")
                loadUrl("${endpoint.trimEnd('/')}/v1/embed/path/$ruleSlug/$userId?timezone=$tz&flag=$f&unitTitle=$ut&unitSubtitle=$us")
            }
            dialog.setContentView(webView)
            dialog.show()
        }
    }

    /**
     * Deducts a heart when a user answers a quiz/lesson question wrong.
     */
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
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val json = try { JSONObject(response.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
                val heartsObj = json.optJSONObject("hearts")
                val current = heartsObj?.optInt("current", 5) ?: 5
                val out = heartsObj?.optBoolean("outOfHearts", false) ?: false
                mainHandler.post { onResult?.invoke(current, out) }
            }
        })
    }

    /**
     * Refills user hearts via GEMS (default 50 gems), PRACTICE (+1 heart), or AD (+1 heart).
     */
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
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val json = try { JSONObject(response.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
                val current = json.optJSONObject("hearts")?.optInt("current", 5) ?: 5
                val gems = json.optJSONObject("gems")?.optInt("balance", 0) ?: 0
                mainHandler.post { onResult?.invoke(current, gems) }
            }
        })
    }

    /**
     * Awards XP toward the daily goal (e.g., +10 XP for completing a lesson).
     */
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
            override fun onFailure(call: Call, e: IOException) {}
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
