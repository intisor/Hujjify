package com.alify.spotify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alify.spotify.databinding.ActivitySpotifySetupBinding

class SpotifySetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpotifySetupBinding
    private lateinit var authManager: SpotifyAuthManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isExtracted = false

    companion object {
        const val EXTRA_CLIENT_ID = "extra_client_id"
        private const val SPOTIFY_DASHBOARD_URL = "https://developer.spotify.com/dashboard"
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.127 Mobile Safari/537.36"

        fun createIntent(context: Context): Intent {
            return Intent(context, SpotifySetupActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpotifySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = SpotifyAuthManager(this)

        setupToolbar()
        setupWebView()
    }

    override fun onResume() {
        super.onResume()
        checkClipboardForClientId()
    }

    private fun checkClipboardForClientId() {
        if (isExtracted) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
            if (text.matches(Regex("^[0-9a-f]{32}$", RegexOption.IGNORE_CASE))) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Spotify Client ID Detected")
                    .setMessage("Found Client ID in clipboard:\n\n$text\n\nLink this app now?")
                    .setPositiveButton("Link App") { _, _ ->
                        handleClientIdCaptured(text)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnOpenBrowser.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SPOTIFY_DASHBOARD_URL))
                startActivity(intent)
                Toast.makeText(this, "Opened Spotify Dashboard in Chrome. Copy your Client ID and return here.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnForceExtract.setOnClickListener {
            val currentUrl = binding.webView.url
            if (currentUrl != null && currentUrl.contains("developer.spotify.com")) {
                injectAutomationScript(force = true)
                Toast.makeText(this, "Scanning page for Client ID...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please log in first. Automation runs on Developer Dashboard.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = CHROME_USER_AGENT
        }

        binding.webView.addJavascriptInterface(WebAppBridge(), "HujjifyBridge")

        binding.webView.settings.setSupportMultipleWindows(true)
        binding.webView.settings.javaScriptCanOpenWindowsAutomatically = true

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (resultMsg == null) return false
                val newWebView = WebView(this@SpotifySetupActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = CHROME_USER_AGENT
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val nextUrl = request?.url?.toString() ?: ""
                            if (nextUrl.contains("developer.spotify.com") || nextUrl.contains("accounts.spotify.com")) {
                                binding.webView.loadUrl(nextUrl)
                                return true
                            }
                            return false
                        }
                    }
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false // Keep everything inside our WebView
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                updateStatusForUrl(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateStatusForUrl(url)
                // Strict Guard: ONLY inject automation when on the developer dashboard, NEVER on accounts.spotify.com
                if (url != null && url.contains("developer.spotify.com/dashboard")) {
                    injectAutomationScript(force = false)
                }
            }
        }

        binding.webView.loadUrl(SPOTIFY_DASHBOARD_URL)
    }

    private fun updateStatusForUrl(url: String?) {
        if (url == null || isExtracted) return

        when {
            url.contains("accounts.spotify.com") -> {
                binding.tvStatusMessage.text = "Log into Spotify. If using Google/Apple sign-in, tap 'Open in Chrome' above."
                binding.tvHeaderSubtitle.text = "Awaiting Spotify Login"
            }
            url.contains("developer.spotify.com/dashboard") -> {
                binding.tvStatusMessage.text = "Automating: creating 'Hujjify' app with 'hujjify://callback'..."
                binding.tvHeaderSubtitle.text = "Automating Dashboard..."
            }
            url.contains("developer.spotify.com") -> {
                binding.tvStatusMessage.text = "Loading Spotify Developer Portal..."
                binding.tvHeaderSubtitle.text = "Developer Portal"
            }
            else -> {
                binding.tvStatusMessage.text = "Connecting to Spotify services..."
                binding.tvHeaderSubtitle.text = "Connecting..."
            }
        }
    }

    private fun injectAutomationScript(force: Boolean) {
        if (isExtracted && !force) return

        val script = """
            (function() {
                // Strict Guard: Abort immediately if on login or accounts page
                if (!window.location.hostname.includes("developer.spotify.com") || 
                    !window.location.pathname.includes("/dashboard")) {
                    return;
                }

                if (window.__hujjify_automation_active && !$force) return;
                window.__hujjify_automation_active = true;

                const APP_NAME = "Hujjify";
                const REDIRECT_URI = "hujjify://callback";
                const REDIRECT_URI_DESKTOP = "http://127.0.0.1:5001/callback";

                // Monkey patch fetch & XHR to intercept Spotify dashboard API responses
                if (!window.__hujjify_network_patched) {
                    window.__hujjify_network_patched = true;
                    
                    const origFetch = window.fetch;
                    window.fetch = async function(...args) {
                        const response = await origFetch.apply(this, args);
                        try {
                            const clone = response.clone();
                            clone.json().then(data => {
                                inspectPayload(data);
                            }).catch(() => {});
                        } catch(e) {}
                        return response;
                    };

                    const origOpen = XMLHttpRequest.prototype.open;
                    const origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this._url = url;
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        this.addEventListener('load', function() {
                            try {
                                if (this.responseText) {
                                    const data = JSON.parse(this.responseText);
                                    inspectPayload(data);
                                }
                            } catch(e) {}
                        });
                        return origSend.apply(this, arguments);
                    };
                }

                function inspectPayload(obj) {
                    if (!obj || typeof obj !== 'object') return;
                    if (obj.client_id && /^[0-9a-f]{32}$/i.test(obj.client_id)) {
                        notifyClientId(obj.client_id);
                        return;
                    }
                    if (obj.clientId && /^[0-9a-f]{32}$/i.test(obj.clientId)) {
                        notifyClientId(obj.clientId);
                        return;
                    }
                    for (const key in obj) {
                        if (typeof obj[key] === 'object') {
                            inspectPayload(obj[key]);
                        }
                    }
                }

                function notifyClientId(id) {
                    if (window.HujjifyBridge && /^[0-9a-f]{32}$/i.test(id)) {
                        window.HujjifyBridge.postClientId(id);
                    }
                }

                // Periodic DOM Automator
                const interval = setInterval(() => {
                    const currentUrl = window.location.href;

                    // 1. Look for Client ID in DOM
                    const text = document.body ? document.body.innerText : "";
                    const matches = text.match(/\b[0-9a-f]{32}\b/ig);
                    if (matches && (currentUrl.includes('/dashboard/app/') || currentUrl.includes('/applications/'))) {
                        for (const match of matches) {
                            notifyClientId(match);
                            clearInterval(interval);
                            return;
                        }
                    }

                    // Look for Client ID specific elements or copy buttons
                    const copyButtons = Array.from(document.querySelectorAll('button, span, code, div'));
                    for (const el of copyButtons) {
                        const val = el.textContent || el.innerText || "";
                        const trimmed = val.trim();
                        if (/^[0-9a-f]{32}$/i.test(trimmed)) {
                            notifyClientId(trimmed);
                            clearInterval(interval);
                            return;
                        }
                    }

                    // 2. Accept Developer Terms Modal if presented
                    const termsCheckboxes = Array.from(document.querySelectorAll('input[type="checkbox"]'));
                    for (const cb of termsCheckboxes) {
                        if (!cb.checked && (cb.name?.includes('term') || cb.id?.includes('term') || cb.parentElement?.textContent?.includes('Terms'))) {
                            cb.click();
                        }
                    }

                    const acceptTermsBtn = Array.from(document.querySelectorAll('button')).find(
                        b => b.textContent.trim().toLowerCase().includes('accept') || b.textContent.trim().toLowerCase().includes('agree')
                    );
                    if (acceptTermsBtn && !document.querySelector('input[name="name"]')) {
                        acceptTermsBtn.click();
                    }

                    // 3. If on /dashboard and existing Hujjify / Alify app exists, open it
                    if (currentUrl.endsWith('/dashboard') || currentUrl.endsWith('/dashboard/')) {
                        const appLinks = Array.from(document.querySelectorAll('a, button, div')).find(
                            el => (el.textContent.trim().toLowerCase() === 'hujjify' || el.textContent.trim().toLowerCase() === 'alify') &&
                                  el.closest('[data-testid], [class*="app-card"], tr, li')
                        );
                        if (appLinks) {
                            if (window.HujjifyBridge) window.HujjifyBridge.updateStatus("Found existing Hujjify app! Opening...");
                            appLinks.click();
                            return;
                        }

                        // Look for "Create app" button
                        const createBtn = document.querySelector('button[data-testid="create-app-button"]') ||
                                          Array.from(document.querySelectorAll('button, a')).find(
                                              b => b.textContent.trim().toLowerCase().includes('create app') || 
                                                   b.textContent.trim().toLowerCase().includes('create an app')
                                          );
                        if (createBtn) {
                            if (window.HujjifyBridge) window.HujjifyBridge.updateStatus("Creating new Hujjify Spotify app...");
                            createBtn.click();
                            return;
                        }
                    }

                    // 4. Fill App Creation Form (Only inside dialog or form on developer dashboard)
                    const modal = document.querySelector('form') || 
                                  document.querySelector('[role="dialog"]') || 
                                  document.querySelector('[aria-modal="true"]');
                    if (!modal) return;

                    const nameInput = modal.querySelector('input[name="name"]') || 
                                      modal.querySelector('input[id="name"]') ||
                                      modal.querySelector('input[placeholder*="App name"]');
                    if (nameInput) {
                        if (nameInput.value !== APP_NAME) {
                            nameInput.value = APP_NAME;
                            nameInput.dispatchEvent(new Event('input', { bubbles: true }));
                            nameInput.dispatchEvent(new Event('change', { bubbles: true }));
                        }

                        const descInput = document.querySelector('textarea[name="description"]') || 
                                          document.querySelector('input[name="description"]') ||
                                          document.querySelector('textarea');
                        if (descInput && (!descInput.value || descInput.value.length < 5)) {
                            descInput.value = "Personal conscious music sentinel and lyric filter";
                            descInput.dispatchEvent(new Event('input', { bubbles: true }));
                            descInput.dispatchEvent(new Event('change', { bubbles: true }));
                        }

                        // Redirect URIs
                        const uriInput = document.querySelector('input[name="redirect_uri"]') ||
                                         document.querySelector('input[name*="redirect"]') ||
                                         document.querySelector('input[placeholder*="redirect"]');
                        if (uriInput && !uriInput.value) {
                            uriInput.value = REDIRECT_URI;
                            uriInput.dispatchEvent(new Event('input', { bubbles: true }));
                            uriInput.dispatchEvent(new Event('change', { bubbles: true }));

                            const addBtn = Array.from(document.querySelectorAll('button')).find(
                                b => b.textContent.trim().toLowerCase() === 'add' || b.textContent.trim().toLowerCase() === '+ add'
                            );
                            if (addBtn) addBtn.click();
                        }

                        // Check APIs checkbox (Web API)
                        for (const cb of termsCheckboxes) {
                            if (!cb.checked) {
                                cb.click();
                                cb.checked = true;
                                cb.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                        }

                        // Click Save / Submit
                        setTimeout(() => {
                            const saveBtn = document.querySelector('button[type="submit"]') ||
                                            Array.from(document.querySelectorAll('button')).find(
                                                b => b.textContent.trim().toLowerCase() === 'save' || 
                                                     b.textContent.trim().toLowerCase() === 'create'
                                            );
                            if (saveBtn && !saveBtn.disabled) {
                                if (window.HujjifyBridge) window.HujjifyBridge.updateStatus("Saving Hujjify app details...");
                                saveBtn.click();
                            }
                        }, 500);
                    }
                }, 750);
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
    }

    private fun handleClientIdCaptured(clientId: String) {
        if (isExtracted) return
        isExtracted = true

        authManager.customClientId = clientId
        PersistentVaultManager.saveVault(this)

        binding.tvStatusMessage.text = "Client ID Captured: $clientId"
        Toast.makeText(this, "Hujjify App Linked Successfully!", Toast.LENGTH_LONG).show()

        val resultIntent = Intent().apply {
            putExtra(EXTRA_CLIENT_ID, clientId)
        }
        setResult(Activity.RESULT_OK, resultIntent)

        // Close after a short celebratory moment so user sees completion
        mainHandler.postDelayed({
            finish()
        }, 1200)
    }

    inner class WebAppBridge {
        @JavascriptInterface
        fun postClientId(clientId: String) {
            mainHandler.post {
                handleClientIdCaptured(clientId.trim())
            }
        }

        @JavascriptInterface
        fun updateStatus(message: String) {
            mainHandler.post {
                if (!isExtracted) {
                    binding.tvStatusMessage.text = message
                }
            }
        }
    }
}
