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
 * StreakKit Android SDK — Universal Single-File Drop-In (< 120 lines)
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(JSONObject) -> Unit>()

    fun init(context: Context, defaultEndpoint: String = endpoint, defaultKey: String = publicKey) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        endpoint = sp.getString(KEY_ENDPOINT, defaultEndpoint) ?: defaultEndpoint
        publicKey = sp.getString(KEY_PUBLIC_KEY, defaultKey) ?: defaultKey
        flushQueue(context)
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
        val payload = JSONObject().apply {
            put("ruleSlug", ruleSlug)
            put("externalUserId", userId)
            put("value", value)
            put("timezone", TimeZone.getDefault().id)
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
                }, "StreakBridge")

                webViewClient = WebViewClient()
                loadUrl("${endpoint.trimEnd('/')}/v1/embed/${Uri.encode(ruleSlug)}/${Uri.encode(userId)}")
            }

            dialog.setContentView(webView)
            dialog.show()
        }
    }
}
