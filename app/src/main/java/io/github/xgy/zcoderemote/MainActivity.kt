package io.github.xgy.zcoderemote

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.xgy.zcoderemote.data.RemoteSession
import io.github.xgy.zcoderemote.data.SessionStore
import io.github.xgy.zcoderemote.data.TransientSessionVault
import io.github.xgy.zcoderemote.databinding.ActivityMainBinding
import io.github.xgy.zcoderemote.scanner.ScannerActivity
import io.github.xgy.zcoderemote.security.RemoteUrlPolicy

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionStore: SessionStore

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val value = result.data?.getStringExtra(ScannerActivity.EXTRA_SCAN_RESULT)
        val parsed = RemoteUrlPolicy.parseOrNull(value)
        if (parsed == null) {
            binding.urlInput.setText(value.orEmpty().take(8_192))
            showInvalidLink()
            return@registerForActivityResult
        }
        binding.urlInput.setText(parsed.original)
        binding.urlInput.setSelection(parsed.original.length)
        showValidLink(parsed, getString(R.string.scan_recognized))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        sessionStore = SessionStore(this)
        configureToolbar()
        configureInput()
        configureActions()
        acceptSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedText(intent)
    }

    override fun onResume() {
        super.onResume()
        renderRecentSessions()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun configureToolbar() {
        binding.toolbar.menu.add(Menu.NONE, MENU_CLEAR_DATA, Menu.NONE, R.string.menu_clear_data)
        binding.toolbar.menu.add(Menu.NONE, MENU_ABOUT, Menu.NONE, R.string.menu_about)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CLEAR_DATA -> {
                    confirmClearData()
                    true
                }

                MENU_ABOUT -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.app_name)
                        .setMessage(R.string.about_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    true
                }

                else -> false
            }
        }
    }

    private fun configureInput() {
        binding.urlInput.doAfterTextChanged { editable ->
            val value = editable?.toString().orEmpty()
            if (value.isBlank()) {
                binding.urlInputLayout.error = null
                binding.linkPreview.visibility = View.GONE
                return@doAfterTextChanged
            }
            val parsed = RemoteUrlPolicy.parseOrNull(value)
            if (parsed == null) showInvalidLink() else showValidLink(parsed)
        }
    }

    private fun configureActions() {
        binding.pasteButton.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val text = clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
            if (text.isNullOrBlank()) {
                Snackbar.make(binding.root, R.string.clipboard_empty, Snackbar.LENGTH_SHORT).show()
            } else {
                binding.urlInput.setText(text.trim().take(8_192))
                binding.urlInput.setSelection(binding.urlInput.length())
            }
        }

        binding.scanButton.setOnClickListener {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                Snackbar.make(binding.root, R.string.camera_unavailable, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
        }

        binding.connectButton.setOnClickListener {
            val parsed = RemoteUrlPolicy.parseOrNull(binding.urlInput.text?.toString())
            if (parsed == null) {
                showInvalidLink()
                binding.urlInput.requestFocus()
                return@setOnClickListener
            }
            openRemote(parsed)
        }
    }

    private fun acceptSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val parsed = RemoteUrlPolicy.parseOrNull(text) ?: return
        binding.urlInput.setText(parsed.original)
        binding.urlInput.setSelection(parsed.original.length)
        showValidLink(parsed)
        // Do not auto-connect: every external input still requires an explicit user tap.
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    private fun openRemote(parsed: RemoteUrlPolicy.Parsed) {
        val session = try {
            sessionStore.remember(parsed)
        } catch (_: Exception) {
            Snackbar.make(
                binding.root,
                R.string.secure_store_unavailable,
                Snackbar.LENGTH_LONG,
            ).show()
            TransientSessionVault.put(parsed)
        }

        binding.urlInput.text?.clear()
        startActivity(RemoteActivity.createIntent(this, session.id))
    }

    private fun showValidLink(parsed: RemoteUrlPolicy.Parsed, message: String? = null) {
        binding.urlInputLayout.error = null
        binding.linkPreview.text = buildString {
            append(message ?: getString(R.string.valid_link))
            append("\n")
            append(parsed.displayName)
            append(" · ")
            append(parsed.displayLocation)
        }
        binding.linkPreview.setTextColor(ContextCompat.getColor(this, R.color.zr_success))
        binding.linkPreview.visibility = View.VISIBLE
    }

    private fun showInvalidLink() {
        binding.urlInputLayout.error = getString(R.string.invalid_link)
        binding.linkPreview.visibility = View.GONE
    }

    private fun renderRecentSessions() {
        val sessions = runCatching(sessionStore::list).getOrDefault(emptyList())
        binding.recentContainer.removeAllViews()
        binding.recentEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        sessions.forEach { session -> binding.recentContainer.addView(createSessionCard(session)) }
    }

    private fun createSessionCard(session: RemoteSession): View {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@MainActivity, R.color.zr_outline)
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.zr_surface))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.connection_card_content_description, session.name)
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(8), dp(14))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(this).apply {
            text = session.name
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.zr_text))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        })
        labels.addView(TextView(this).apply {
            text = buildString {
                append(session.displayLocation)
                append(" · ")
                append(
                    DateUtils.getRelativeTimeSpanString(
                        session.lastUsedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ),
                )
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.zr_text_secondary))
            textSize = 13f
            maxLines = 2
        })

        val removeButton = MaterialButton(this).apply {
            text = getString(R.string.remove)
            contentDescription = getString(R.string.delete_connection_content_description)
            setOnClickListener { confirmRemove(session) }
        }
        row.addView(labels)
        row.addView(removeButton)
        card.addView(row)
        card.setOnClickListener {
            val parsed = RemoteUrlPolicy.parseOrNull(session.url) ?: return@setOnClickListener
            openRemote(parsed)
        }
        return card
    }

    private fun confirmRemove(session: RemoteSession) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_session)
            .setMessage(R.string.remove_session_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                runCatching { sessionStore.remove(session.id) }
                renderRecentSessions()
            }
            .show()
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data_title)
            .setMessage(R.string.clear_data_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ -> clearLocalData() }
            .show()
    }

    private fun clearLocalData() {
        runCatching(sessionStore::clear)
        TransientSessionVault.clear()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            WebView(this).apply {
                clearCache(true)
                clearHistory()
                destroy()
            }
        }
        binding.urlInput.text?.clear()
        renderRecentSessions()
        Toast.makeText(this, R.string.data_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MENU_CLEAR_DATA = 1
        const val MENU_ABOUT = 2
    }
}
