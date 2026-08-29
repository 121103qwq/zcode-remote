package io.github.xgy.zcoderemote

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.xgy.zcoderemote.data.RemoteSession
import io.github.xgy.zcoderemote.data.SessionStore
import io.github.xgy.zcoderemote.data.TransientSessionVault
import io.github.xgy.zcoderemote.databinding.ActivityMainBinding
import io.github.xgy.zcoderemote.scanner.QrImageDecoder
import io.github.xgy.zcoderemote.scanner.RemoteQrSelector
import io.github.xgy.zcoderemote.scanner.ScannerActivity
import io.github.xgy.zcoderemote.security.RemoteUrlPolicy
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionStore: SessionStore
    private val qrDecodeExecutor = Executors.newSingleThreadExecutor()
    private var decodeRequestId = 0
    private var imageDecodeInProgress = false
    private var suppressInputFeedback = false
    private var lastInputWasValid = false
    private var clearInProgress = false

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val value = result.data?.getStringExtra(ScannerActivity.EXTRA_SCAN_RESULT)
        val parsed = RemoteUrlPolicy.parseOrNull(value)
        if (parsed == null) {
            showInvalidLink(R.string.scanner_not_remote)
            return@registerForActivityResult
        }
        setRemoteLink(parsed, getString(R.string.scan_recognized))
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) decodeQrImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.urlInput.isSaveEnabled = false
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

    override fun onDestroy() {
        decodeRequestId += 1
        imageDecodeInProgress = false
        qrDecodeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
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
            if (suppressInputFeedback) return@doAfterTextChanged
            invalidateImageDecode()
            val value = editable?.toString().orEmpty()
            val parsed = RemoteUrlPolicy.parseOrNull(value)
            val wasValid = lastInputWasValid
            lastInputWasValid = parsed != null
            updateConnectButton(parsed != null)
            when {
                value.isBlank() -> binding.linkPreview.visibility = View.GONE
                parsed != null -> showValidLink(parsed, announce = !wasValid)
                else -> binding.linkPreview.visibility = View.GONE
            }
        }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                connectFromInput()
                true
            } else {
                false
            }
        }
        updateConnectButton(false)
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
                val candidate = text.trim().take(MAX_URL_LENGTH)
                val parsed = RemoteUrlPolicy.parseOrNull(candidate)
                if (parsed == null) {
                    setInputText(candidate)
                    lastInputWasValid = false
                    updateConnectButton(false)
                    showInvalidLink()
                } else {
                    setRemoteLink(parsed, getString(R.string.paste_recognized))
                }
            }
        }

        binding.connectButton.setOnClickListener { connectFromInput() }
        binding.scanButton.setOnClickListener { showQrSourceChooser() }
    }

    private fun connectFromInput() {
        invalidateImageDecode()
        val parsed = RemoteUrlPolicy.parseOrNull(binding.urlInput.text?.toString())
        if (parsed == null) {
            showInvalidLink()
            binding.urlInput.requestFocus()
            return
        }
        openRemote(parsed)
    }

    private fun showQrSourceChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_remote_link)
            .setItems(
                arrayOf(
                    getString(R.string.scan_qr),
                    getString(R.string.read_qr_image),
                ),
            ) { _, which ->
                when (which) {
                    0 -> launchCameraScanner()
                    1 -> launchImagePicker()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchCameraScanner() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Snackbar.make(binding.root, R.string.camera_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
    }

    private fun launchImagePicker() {
        runCatching {
            imagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }.onFailure {
            Snackbar.make(
                binding.root,
                R.string.qr_image_picker_unavailable,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun decodeQrImage(uri: android.net.Uri) {
        val requestId = ++decodeRequestId
        imageDecodeInProgress = true
        updateScanButton(false)
        showStatus(R.string.qr_image_decoding)

        runCatching {
            qrDecodeExecutor.execute {
                val result = try {
                    QrImageDecoder(contentResolver).decode(uri)
                } catch (_: OutOfMemoryError) {
                    QrImageDecoder.Result.ImageTooLarge
                } catch (_: Exception) {
                    QrImageDecoder.Result.ReadFailed
                }
                runOnUiThread {
                    if (requestId != decodeRequestId || isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    imageDecodeInProgress = false
                    updateScanButton(true)
                    handleImageDecodeResult(result)
                }
            }
        }.onFailure {
            imageDecodeInProgress = false
            updateScanButton(true)
            showInvalidLink(R.string.qr_image_unreadable)
        }
    }

    private fun handleImageDecodeResult(result: QrImageDecoder.Result) {
        when (result) {
            is QrImageDecoder.Result.Success -> {
                val parsed = RemoteQrSelector.firstValid(result.texts)
                if (parsed == null) {
                    showInvalidLink(R.string.qr_image_not_remote)
                } else {
                    setRemoteLink(parsed, getString(R.string.image_qr_recognized))
                }
            }

            QrImageDecoder.Result.NoQrCode -> showInvalidLink(R.string.qr_image_not_found)
            QrImageDecoder.Result.ImageTooLarge -> showInvalidLink(R.string.qr_image_too_large)
            QrImageDecoder.Result.UnsupportedImageType,
            QrImageDecoder.Result.UnsupportedSource,
            QrImageDecoder.Result.ReadFailed,
            -> showInvalidLink(R.string.qr_image_unreadable)
        }
    }

    private fun acceptSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val parsed = RemoteUrlPolicy.parseOrNull(text) ?: return
        setRemoteLink(parsed)
        // Do not auto-connect: every external input still requires an explicit user tap.
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    private fun setRemoteLink(parsed: RemoteUrlPolicy.Parsed, message: String? = null) {
        invalidateImageDecode()
        setInputText(parsed.original)
        lastInputWasValid = true
        updateConnectButton(true)
        showValidLink(parsed, message, announce = true)
    }

    private fun setInputText(value: String) {
        invalidateImageDecode()
        suppressInputFeedback = true
        try {
            binding.urlInput.setText(value)
            binding.urlInput.setSelection(binding.urlInput.length())
        } finally {
            suppressInputFeedback = false
        }
    }

    private fun invalidateImageDecode() {
        if (!imageDecodeInProgress) return
        decodeRequestId += 1
        imageDecodeInProgress = false
        updateScanButton(true)
    }

    private fun updateConnectButton(enabled: Boolean) {
        binding.connectButton.isEnabled = enabled
        binding.connectButton.alpha = if (enabled) 1f else DISABLED_CONTROL_ALPHA
    }

    private fun updateScanButton(enabled: Boolean) {
        binding.scanButton.isEnabled = enabled
        binding.scanButton.alpha = if (enabled) 1f else DISABLED_CONTROL_ALPHA
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

    private fun showValidLink(
        parsed: RemoteUrlPolicy.Parsed,
        message: String? = null,
        announce: Boolean = false,
    ) {
        binding.linkPreview.text = getString(
            R.string.valid_link_compact,
            message ?: getString(R.string.valid_link),
            parsed.displayName,
            parsed.displayLocation,
        )
        binding.linkPreview.setTextColor(ContextCompat.getColor(this, R.color.zr_success))
        binding.linkPreview.visibility = View.VISIBLE
        if (announce) binding.linkPreview.announceForAccessibility(binding.linkPreview.text)
    }

    private fun showStatus(@StringRes message: Int) {
        binding.linkPreview.setText(message)
        binding.linkPreview.setTextColor(ContextCompat.getColor(this, R.color.zr_text_secondary))
        binding.linkPreview.visibility = View.VISIBLE
        binding.linkPreview.announceForAccessibility(binding.linkPreview.text)
    }

    private fun showInvalidLink(@StringRes message: Int = R.string.invalid_link) {
        binding.linkPreview.setText(message)
        binding.linkPreview.setTextColor(ContextCompat.getColor(this, R.color.zr_error))
        binding.linkPreview.visibility = View.VISIBLE
        binding.linkPreview.announceForAccessibility(binding.linkPreview.text)
    }

    private fun renderRecentSessions() {
        val sessions = runCatching(sessionStore::list).getOrDefault(emptyList())
        binding.recentContainer.removeAllViews()
        binding.recentEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        sessions.forEachIndexed { index, session ->
            if (index > 0) binding.recentContainer.addView(createDivider())
            binding.recentContainer.addView(createSessionRow(session))
        }
    }

    private fun createSessionRow(session: RemoteSession): View {
        val relativeTime = DateUtils.getRelativeTimeSpanString(
            session.lastUsedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(4), dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
            background = selectableBackground()
            contentDescription = getString(
                R.string.connection_row_content_description,
                session.name,
                session.displayLocation,
                relativeTime,
            )
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

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
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        labels.addView(TextView(this).apply {
            text = buildString {
                append(session.displayLocation)
                append(" · ")
                append(relativeTime)
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.zr_text_secondary))
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })

        val removeButton = AppCompatImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setImageDrawable(
                AppCompatResources.getDrawable(this@MainActivity, R.drawable.ic_delete_24),
            )
            imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.zr_text_secondary)
            background = selectableBorderlessBackground()
            contentDescription = getString(
                R.string.delete_connection_content_description,
                session.name,
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { confirmRemove(session) }
        }
        row.addView(labels)
        row.addView(removeButton)
        row.setOnClickListener {
            val parsed = RemoteUrlPolicy.parseOrNull(session.url) ?: return@setOnClickListener
            openRemote(parsed)
        }
        return row
    }

    private fun createDivider(): View = View(this).apply {
        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.zr_outline))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
    }

    private fun selectableBackground() = TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        AppCompatResources.getDrawable(this, value.resourceId)
    }

    private fun selectableBorderlessBackground() = TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        AppCompatResources.getDrawable(this, value.resourceId)
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
        if (clearInProgress) return
        clearInProgress = true
        val secureDataCleared = runCatching(sessionStore::clear).isSuccess
        TransientSessionVault.clear()
        val webDataCleared = runCatching {
            WebStorage.getInstance().deleteAllData()
            WebView(this).apply {
                clearCache(true)
                clearHistory()
                destroy()
            }
        }.isSuccess
        binding.urlInput.text?.clear()
        renderRecentSessions()

        var completionDelivered = false
        fun complete() {
            if (completionDelivered) return
            completionDelivered = true
            clearInProgress = false
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(
                    this,
                    if (secureDataCleared && webDataCleared) {
                        R.string.data_cleared
                    } else {
                        R.string.data_clear_partial
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        runCatching {
            CookieManager.getInstance().removeAllCookies { complete() }
        }.onFailure { complete() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_URL_LENGTH = 8_192
        const val DISABLED_CONTROL_ALPHA = 0.38f
        const val MENU_CLEAR_DATA = 1
        const val MENU_ABOUT = 2
    }
}
