package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import eu.kanade.tachiyomi.util.system.WebViewUtil
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.WebViewClientCompat
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.setUserAgent
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Shows the site's own human-operated verification only if J2K's usual
 * automatic Cloudflare handshake failed (or the user explicitly requests it).
 *
 * Does NOT click, solve, forge, or bypass a CAPTCHA. WebView and OkHttp already
 * use Android's CookieManager: successful clearance naturally carries back to
 * the chapter. A login without a clearance cookie uses "Done & retry".
 */
object WebsiteAccessDialog {
    @SuppressLint("SetJavaScriptEnabled")
    fun show(
        activity: ReaderActivity,
        requestedUrl: String?,
        onRetry: () -> Unit,
        onDismiss: () -> Unit,
    ): Boolean {
        val source = activity.viewModel.getSource() ?: return false
        val target = WebsiteAccessPolicy.chooseTarget(source.baseUrl, requestedUrl) ?: return false
        if (activity.isFinishing || activity.isDestroyed ||
            !WebViewUtil.supportsWebView(activity)
        ) return false

        val cookieManager = CookieManager.getInstance()
        val originalCookies = cookieManager.getCookie(target)
        val handler = Handler(Looper.getMainLooper())
        val dialog = BottomSheetDialog(activity)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        activity.lifecycle.addObserver(lifecycleObserver)
        var active = true
        var completed = false

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx, 12.dpToPx, 16.dpToPx, 8.dpToPx)
            isFocusableInTouchMode = true
        }
        val title = TextView(activity).apply {
            text = "Verify website"
            textSize = 19f
            contentDescription = "Site verification, interactive browser"
            setPadding(0, 0, 0, 6.dpToPx)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val status = TextView(activity).apply {
            text = "Automatic verification needs help. Complete the site's check below, then return to the same page."
            textSize = 13f
            setPadding(0, 0, 0, 8.dpToPx)
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val browser = WebView(activity).apply {
            setDefaultSettings()
            // Cloudflare's mobile WebView integration requires cookie support
            // for its embedded challenge iframe. This applies only to this
            // temporary user-operated WebView, not all app browsing sessions.
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            // No native JS interfaces, file access or privileged web capabilities.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            setUserAgent(
                source.headers["User-Agent"] ?: Injekt.get<NetworkHelper>().defaultUserAgent,
            )
            contentDescription = "Website verification browser"
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancel = MaterialButton(activity).apply {
            text = "Cancel"
            contentDescription = "Dismiss website verification without retrying"
            setOnClickListener { dialog.dismiss() }
        }
        val done = MaterialButton(activity).apply {
            text = "Done & retry"
            contentDescription = "Close the website check and retry the original page"
        }

        fun finishAndRetry() {
            if (!active || completed) return
            completed = true
            cookieManager.flush()
            dialog.dismiss()
            // Give the network interceptor a moment to observe the new cookie
            // before re-requesting the exact reader page.
            handler.postDelayed({ if (!activity.isFinishing && !activity.isDestroyed) onRetry() }, 200L)
        }
        done.setOnClickListener { finishAndRetry() }
        actions.addView(cancel)
        actions.addView(done)

        container.addView(title)
        container.addView(status)
        container.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 3.dpToPx))
        container.addView(
            browser,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        container.addView(actions)

        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.isIndeterminate = false
                progress.progress = newProgress
            }
        }
        browser.webViewClient = object : WebViewClientCompat() {
            override fun shouldOverrideUrlCompat(view: WebView, url: String): Boolean {
                // Required by Cloudflare's iframe-based Turnstile implementation.
                // These internal blank/srcdoc frames aren't external destinations.
                if (url == "about:blank" || url.startsWith("about:srcdoc") ||
                    url.startsWith("blob:https://", ignoreCase = true)
                ) return false
                return WebsiteAccessPolicy.host(url) == null
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!active || completed) return
                val website = WebsiteAccessPolicy.host(url) ?: "the source website"
                status.text = "Website: $website. If a checkbox or sign-in appears, complete it here. This sheet closes when access is confirmed."
                val latest = cookieManager.getCookie(target)
                if (WebsiteAccessPolicy.newlyCleared(originalCookies, latest)) finishAndRetry()
            }

            override fun onReceivedErrorCompat(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String,
                isMainFrame: Boolean,
            ) {
                if (isMainFrame) status.text = "This website could not load. You can try again or close the verification."
            }
        }

        val poll = object : Runnable {
            override fun run() {
                if (!active || completed) return
                if (WebsiteAccessPolicy.newlyCleared(originalCookies, cookieManager.getCookie(target))) {
                    finishAndRetry()
                } else {
                    handler.postDelayed(this, 800L)
                }
            }
        }
        dialog.setOnDismissListener {
            activity.lifecycle.removeObserver(lifecycleObserver)
            active = false
            handler.removeCallbacks(poll)
            browser.stopLoading()
            browser.destroy()
            onDismiss()
        }
        dialog.setContentView(container)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val height = (activity.resources.displayMetrics.heightPixels * 0.73f).toInt()
                it.layoutParams.height = height
                it.requestLayout()
                BottomSheetBehavior.from(it).apply {
                    peekHeight = height
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
        dialog.show()
        browser.loadUrl(target)
        handler.postDelayed(poll, 800L)
        return true
    }
}
