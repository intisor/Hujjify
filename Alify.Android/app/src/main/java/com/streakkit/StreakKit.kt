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
    val maxFreezes: Int = 2
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
    var publicKey: String = "pk_live_alify_456"

    private val configuredRules = mutableMapOf<String, StreakRule>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(JSONObject) -> Unit>()

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
                    mainHandler.post {
                        listeners.forEach { it(json) }
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
                                <div class="icon">⚡</div>
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
}
