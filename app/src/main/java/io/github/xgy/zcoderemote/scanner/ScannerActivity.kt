package io.github.xgy.zcoderemote.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Surface
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.xgy.zcoderemote.R
import io.github.xgy.zcoderemote.databinding.ActivityScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScannerBinding
    private lateinit var analyzerExecutor: ExecutorService
    private lateinit var displayManager: DisplayManager
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var cameraStartGeneration = 0
    private var displayListenerRegistered = false
    private var torchEnabled = false
    private val completed = AtomicBoolean(false)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            updateTargetRotation(displayId)
        }
    }

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        analyzerExecutor = Executors.newSingleThreadExecutor()
        displayManager = getSystemService(DisplayManager::class.java)

        binding.closeButton.setOnClickListener { finish() }
        binding.torchButton.setOnClickListener { toggleTorch() }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Snackbar.make(binding.root, R.string.camera_unavailable, Snackbar.LENGTH_LONG)
                .setAction(R.string.scanner_close) { finish() }
                .show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!displayListenerRegistered) {
            displayManager.registerDisplayListener(displayListener, null)
            displayListenerRegistered = true
        }
        updateTargetRotation()
    }

    override fun onStop() {
        if (displayListenerRegistered) {
            runCatching { displayManager.unregisterDisplayListener(displayListener) }
            displayListenerRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        cameraStartGeneration += 1
        analysisUseCase?.clearAnalyzer()
        unbindOwnedUseCases()
        cameraProvider = null
        if (::analyzerExecutor.isInitialized) analyzerExecutor.shutdownNow()
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

    private fun startCamera() {
        val generation = ++cameraStartGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (!isCurrentCameraStart(generation)) return@addListener
                runCatching {
                    val provider = future.get()
                    if (!isCurrentCameraStart(generation)) return@runCatching

                    cameraProvider = provider
                    unbindOwnedUseCases()

                    val targetRotation = binding.previewView.display?.rotation
                        ?: Surface.ROTATION_0
                    val selector = selectCamera(provider)

                    val preview = Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .build()
                        .also {
                            it.surfaceProvider = binding.previewView.surfaceProvider
                        }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(targetRotation)
                        .build()
                        .also { it.setAnalyzer(analyzerExecutor, ::analyze) }

                    previewUseCase = preview
                    analysisUseCase = analysis
                    camera = provider.bindToLifecycle(
                        this,
                        selector,
                        preview,
                        analysis,
                    )
                    torchEnabled = false
                    binding.torchButton.setText(R.string.scanner_torch_on)
                    binding.torchButton.visibility =
                        if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
                }.onFailure {
                    if (isCurrentCameraStart(generation)) {
                        analysisUseCase?.clearAnalyzer()
                        unbindOwnedUseCases()
                        Snackbar.make(binding.root, R.string.camera_error, Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.scanner_close) { finish() }
                            .show()
                    }
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun isCurrentCameraStart(generation: Int): Boolean =
        generation == cameraStartGeneration &&
            !isFinishing &&
            !isDestroyed &&
            !analyzerExecutor.isShutdown

    private fun selectCamera(provider: ProcessCameraProvider): CameraSelector = when {
        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
            CameraSelector.DEFAULT_FRONT_CAMERA

        else -> error("No CameraX-compatible camera is available")
    }

    private fun unbindOwnedUseCases() {
        val ownedUseCases = listOfNotNull<UseCase>(previewUseCase, analysisUseCase).toTypedArray()
        if (ownedUseCases.isNotEmpty()) {
            runCatching { cameraProvider?.unbind(*ownedUseCases) }
        }
        previewUseCase = null
        analysisUseCase = null
        camera = null
    }

    private fun updateTargetRotation(changedDisplayId: Int? = null) {
        if (!::binding.isInitialized) return
        val display = binding.previewView.display ?: return
        if (changedDisplayId != null && display.displayId != changedDisplayId) return

        previewUseCase?.targetRotation = display.rotation
        analysisUseCase?.targetRotation = display.rotation
    }

    private fun analyze(image: ImageProxy) {
        if (completed.get()) {
            image.close()
            return
        }
        try {
            val yPlane = image.planes.firstOrNull() ?: return
            val raw = copyLumaPlane(yPlane, image.width, image.height)
            val rotated = rotateLuma(raw, image.width, image.height, image.imageInfo.rotationDegrees)
            val source = PlanarYUVLuminanceSource(
                rotated.bytes,
                rotated.width,
                rotated.height,
                0,
                0,
                rotated.width,
                rotated.height,
                false,
            )
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            val text = result.text?.trim().orEmpty()
            if (text.isNotEmpty() && completed.compareAndSet(false, true)) {
                runOnUiThread {
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(EXTRA_SCAN_RESULT, text),
                    )
                    finish()
                }
            }
        } catch (_: NotFoundException) {
            // Expected for most preview frames.
        } catch (_: RuntimeException) {
            // A malformed frame should not terminate the camera analyzer.
        } finally {
            reader.reset()
            image.close()
        }
    }

    private fun copyLumaPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
    ): ByteArray {
        val output = ByteArray(width * height)
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (row in 0 until height) {
            val rowOffset = row * rowStride
            val outputOffset = row * width
            for (column in 0 until width) {
                output[outputOffset + column] = buffer.get(rowOffset + column * pixelStride)
            }
        }
        return output
    }

    private fun rotateLuma(
        source: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): LumaFrame = when (rotationDegrees) {
        90 -> {
            val output = ByteArray(source.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    output[x * height + (height - y - 1)] = source[y * width + x]
                }
            }
            LumaFrame(output, height, width)
        }

        180 -> {
            val output = ByteArray(source.size)
            source.indices.forEach { index -> output[source.lastIndex - index] = source[index] }
            LumaFrame(output, width, height)
        }

        270 -> {
            val output = ByteArray(source.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    output[(width - x - 1) * height + y] = source[y * width + x]
                }
            }
            LumaFrame(output, height, width)
        }

        else -> LumaFrame(source, width, height)
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return
        torchEnabled = !torchEnabled
        activeCamera.cameraControl.enableTorch(torchEnabled)
        binding.torchButton.setText(
            if (torchEnabled) R.string.scanner_torch_off else R.string.scanner_torch_on,
        )
    }

    private fun showPermissionDenied() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camera_permission_title)
            .setMessage(R.string.camera_permission_message)
            .setPositiveButton(R.string.scanner_close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private data class LumaFrame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    companion object {
        const val EXTRA_SCAN_RESULT = "io.github.xgy.zcoderemote.SCAN_RESULT"
    }
}
