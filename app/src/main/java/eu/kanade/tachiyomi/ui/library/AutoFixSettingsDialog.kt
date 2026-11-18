package eu.kanade.tachiyomi.ui.library

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import eu.kanade.tachiyomi.data.library.RecoveryControls
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.materialAlertDialog

/**
 * Clear, persistent options. Each toggle saves immediately without dismissing
 * this screen or rebuilding/resetting Library.
 */
object AutoFixSettingsDialog {
    fun show(activity: AppCompatActivity) {
        val context = activity.applicationContext
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx, 8.dpToPx, 20.dpToPx, 12.dpToPx)
        }

        fun setting(title: String, explanation: String, initial: Boolean, save: (Boolean) -> Unit) {
            content.addView(SwitchCompat(activity).apply {
                text = title
                textSize = 16f
                isChecked = initial
                minHeight = 56.dpToPx
                setOnCheckedChangeListener { _, checked -> save(checked) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(TextView(activity).apply {
                text = explanation
                textSize = 13f
                alpha = 0.85f
                setPadding(0, 0, 0, 16.dpToPx)
            })
        }

        setting(
            "Automatically retry temporary connection errors",
            "ON by default. For timeouts and temporary server errors during library updates, retry a small number of times with increasing delays. Never hammer rate-limited websites.",
            RecoveryControls.retryTemporaryConnections(context),
            { RecoveryControls.setRetryTemporaryConnections(context, it) },
        )
        setting(
            "Offer website verification when a page is blocked",
            "ON by default. J2K first tries normal Cloudflare recovery. If the current page fails, show a small browser only when human verification is needed.",
            RecoveryControls.offerWebsiteVerification(context),
            { RecoveryControls.setOfferWebsiteVerification(context, it) },
        )
        content.addView(TextView(activity).apply {
            text = "Auto-fix does not install or trust extensions, remove manga, switch sources, or move reading progress without your approval. Missing sources and other problems appear under Fix issues."
            textSize = 13f
            setPadding(0, 4.dpToPx, 0, 0)
        })
        val scroll = ScrollView(activity).apply { addView(content) }
        activity.materialAlertDialog()
            .setTitle("Auto-fix settings")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }
}
