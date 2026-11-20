package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.data.library.ReaderContinuityPlanner
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.WebsiteAccessPolicy
import eu.kanade.tachiyomi.ui.reader.ReaderContinuityDialog
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity

class ReaderErrorView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        lateinit var binding: ReaderErrorBinding
            private set

        var retryPageAction: (() -> Unit)? = null

        var viewer: PagerViewer? = null
            set(value) {
                field = value
                binding.actionRetry.viewer = viewer
                binding.actionOpenInWebView.viewer = viewer
            }

        override fun onFinishInflate() {
            super.onFinishInflate()
            binding = ReaderErrorBinding.bind(this)
        }

        private fun findReaderActivity(): ReaderActivity? {
            var current: Context = context
            repeat(10) {
                if (current is ReaderActivity) return current
                current = (current as? ContextWrapper)?.baseContext ?: return null
            }
            return null
        }

        fun configureView(url: String?): ReaderErrorBinding {
            // A user-visible recovery action at the exact point of failure.
            // The subsequent source search is opt-in, and never migrates progress.
            val reader = findReaderActivity()
            binding.actionTryAlternate.isVisible = reader?.viewModel?.getCurrentChapter() != null
            // An actual failed page may be recoverable through a unique, confirmed
            // linked same-chapter variant. The coordinator refuses ambiguous matches.
            if (reader != null && binding.actionTryAlternate.isVisible) {
                ReaderContinuityDialog.tryAutomaticSameChapter(reader)
            }
            binding.actionTryAlternate.setOnClickListener {
                val host = findReaderActivity() ?: return@setOnClickListener
                ReaderContinuityDialog.show(host, ReaderContinuityPlanner.Direction.SAME_CHAPTER)
            }
            val baseUrl = reader?.viewModel?.getSource()?.baseUrl
            binding.actionVerifyWebsite.isVisible = baseUrl != null &&
                WebsiteAccessPolicy.chooseTarget(baseUrl, url) != null
            binding.actionVerifyWebsite.setOnClickListener {
                val host = findReaderActivity() ?: return@setOnClickListener
                host.requestWebsiteVerification(url, retryPageAction)
            }
            if (url?.startsWith("http", true) == true) {
                binding.actionOpenInWebView.isVisible = true
                binding.actionOpenInWebView.setOnClickListener {
                    context.startActivity(WebViewActivity.newIntent(context, url))
                }
            } else {
                binding.actionOpenInWebView.isVisible = false
            }
            binding.root.isVisible = true
            return binding
        }
    }
