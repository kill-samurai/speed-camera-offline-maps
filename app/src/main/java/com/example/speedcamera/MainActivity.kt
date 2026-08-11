package com.example.speedcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.speedcamera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

class MainActivity : AppCompatActivity() {
    private data class QualityOption(val quality: Quality, val label: String)
    private data class FrameRateOption(val range: Range<Int>?, val label: String)
    private data class PendingNavigationRequest(val query: String, val destination: GeoPoint?)

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private lateinit var overlayEffect: OverlayEffect
    private lateinit var osmTileClient: OsmTileClient
    private lateinit var offlineRegionManager: OfflineRegionManager
    private lateinit var offlineRegionStore: OfflineRegionStore
    private lateinit var offlineRoutingClient: OfflineRoutingClient
    private lateinit var routePersistence: RoutePersistence
    private val overlayThread = HandlerThread("speed-overlay")
    private val routingExecutor = Executors.newSingleThreadExecutor()
    private val suggestionExecutor = Executors.newSingleThreadExecutor()
    private val markerExecutor = Executors.newSingleThreadExecutor()
    private val offlineExecutor = Executors.newSingleThreadExecutor()
    private val routingClient = OsrmRoutingClient()
    private val osmGeocodingClient = OsmGeocodingClient()
    private val suggestionClient = PhotonSuggestionClient()
    private val routeGeneration = AtomicInteger()
    private val markerGeneration = AtomicInteger()
    private val offlineInstallInProgress = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraPreview: Preview? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var locationUpdatesActive = false
    private var recordingPaused = false
    private var qualityOptions: List<QualityOption> = emptyList()
    private var frameRateOptions: List<FrameRateOption> = emptyList()
    private var selectedQuality: Quality = Quality.FHD
    private var selectedFrameRate: Range<Int>? = null
    private var cameraSettingsAvailable = false
    private var pendingNavigationRequest: PendingNavigationRequest? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (::offlineRegionManager.isInitialized && id == offlineRegionManager.pendingDownloadId()) {
                installOfflineDownload(id)
            }
        }
    }

    private val offlineProgressRunnable = object : Runnable {
        override fun run() {
            if (!::offlineRegionManager.isInitialized) return
            val progress = offlineRegionManager.downloadProgress() ?: return
            when (progress.status) {
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED -> {
                    mainHandler.postDelayed(this, OFFLINE_PROGRESS_INTERVAL_MS)
                }
                DownloadManager.STATUS_SUCCESSFUL ->
                    installOfflineDownload(offlineRegionManager.pendingDownloadId())
                DownloadManager.STATUS_FAILED -> {
                    offlineRegionManager.clearFailedDownload()
                    Toast.makeText(this@MainActivity, R.string.offline_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Volatile
    private var latestLocation: Location? = null
    @Volatile
    private var speedKph: Float? = null
    @Volatile
    private var lastLocationRealtimeNanos: Long = 0L
    @Volatile
    private var routeRequestInFlight = false
    @Volatile
    private var customMarkerBitmap: Bitmap? = null
    private var smoothedSpeedKph: Float? = null
    private var lastRerouteElapsedMs = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!shouldAcceptLocation(location)) return
            latestLocation = Location(location)
            updateSpeed(location)
            updateRouteProgress(location)
            pendingNavigationRequest?.let { pending ->
                if (!routeRequestInFlight) {
                    pendingNavigationRequest = null
                    resolveAndRoute(pending.query, pending.destination)
                }
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (!hasEnabledLocationProvider()) {
                speedKph = null
                smoothedSpeedKph = null
            }
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER ||
                provider == LocationManager.NETWORK_PROVIDER
            ) {
                stopLocationUpdates()
                startLocationUpdatesIfAllowed()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }
        startLocationUpdatesIfAllowed()
    }

    private val markerImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(::selectCustomMarker)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationManager = getSystemService(LocationManager::class.java)
        osmTileClient = OsmTileClient(applicationContext)
        offlineRegionManager = OfflineRegionManager(applicationContext)
        offlineRegionStore = OfflineRegionStore(offlineRegionManager)
        offlineRoutingClient = OfflineRoutingClient(offlineRegionStore)
        routePersistence = RoutePersistence(applicationContext)
        binding.previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
        binding.recordButton.backgroundTintList = null
        binding.recordButton.setOnClickListener {
            if (activeRecording == null) startRecording() else stopRecording()
        }
        binding.destinationButton.setOnClickListener { showDestinationDialog() }
        binding.settingsMenuButton.setOnClickListener { showSettingsMenu() }
        binding.pauseButton.setOnClickListener { toggleRecordingPause() }

        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        overlayThread.start()
        overlayEffect = createCameraOverlay()
        restoreCustomMarker()
        restoreSavedRoute()
        resumePendingOfflineDownload()
        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        startLocationUpdatesIfAllowed()
    }

    override fun onStop() {
        stopLocationUpdates()
        if (activeRecording != null) stopRecording()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rotation = binding.previewView.display?.rotation ?: return
        cameraPreview?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        routingExecutor.shutdownNow()
        suggestionExecutor.shutdownNow()
        markerExecutor.shutdownNow()
        offlineExecutor.shutdownNow()
        osmTileClient.close()
        offlineRegionStore.close()
        mainHandler.removeCallbacks(offlineProgressRunnable)
        unregisterReceiver(downloadReceiver)
        overlayEffect.close()
        overlayThread.quitSafely()
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val permissions = buildList {
            if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        if (permissions.isEmpty()) {
            startCamera()
            startLocationUpdatesIfAllowed()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val cameraInfo = CameraSelector.DEFAULT_BACK_CAMERA
                    .filter(provider.availableCameraInfos)
                    .firstOrNull()
                    ?: error(getString(R.string.no_back_camera))
                updateCameraOptions(cameraInfo)

                val rotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder()
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.surfaceProvider = binding.previewView.surfaceProvider }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(selectedQuality))
                    .build()
                val capture = VideoCapture.Builder(recorder)
                    .setTargetRotation(rotation)
                    .apply {
                        selectedFrameRate?.let(::setTargetFrameRate)
                    }
                    .build()

                val useCases = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(capture)
                    .addEffect(overlayEffect)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCases)
                cameraPreview = preview
                videoCapture = capture
                binding.recordButton.isEnabled = true
                cameraSettingsAvailable = true
            } catch (error: Exception) {
                Log.e(LOG_TAG, "Could not bind camera with selected settings", error)
                binding.recordButton.isEnabled = false
                cameraSettingsAvailable = cameraProvider != null
                Toast.makeText(
                    this,
                    error.message ?: getString(R.string.recording_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showSettingsMenu() {
        if (activeRecording != null) return
        PopupMenu(this, binding.settingsMenuButton).apply {
            menu.add(
                android.view.Menu.NONE,
                MENU_CAMERA_SETTINGS,
                0,
                "${getString(R.string.camera_settings_button)} · ${cameraSettingsSummary()}"
            ).isEnabled = cameraSettingsAvailable
            menu.add(android.view.Menu.NONE, MENU_MARKER, 1, R.string.marker_button)
            menu.add(android.view.Menu.NONE, MENU_OFFLINE_MAPS, 2, R.string.offline_maps_button)
            if (NavigationOverlayState.current.route != null || pendingNavigationRequest != null) {
                menu.add(android.view.Menu.NONE, MENU_STOP_ROUTE, 3, R.string.stop_navigation)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CAMERA_SETTINGS -> showCameraSettingsDialog()
                    MENU_MARKER -> showMarkerDialog()
                    MENU_OFFLINE_MAPS -> showOfflineMapsDialog()
                    MENU_STOP_ROUTE -> stopNavigation()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun updateCameraOptions(cameraInfo: CameraInfo) {
        val capabilities = Recorder.getVideoCapabilities(cameraInfo)
        val supportedQualities = capabilities.getSupportedQualities(DynamicRange.SDR)
        qualityOptions = supportedQualities.map { quality ->
            val resolution = QualitySelector.getResolution(cameraInfo, quality)
            val resolutionLabel = resolution?.let { "${it.width} × ${it.height}" }
                ?: qualityName(quality)
            QualityOption(quality, "$resolutionLabel (${qualityName(quality)})")
        }
        if (qualityOptions.isEmpty()) {
            qualityOptions = listOf(QualityOption(Quality.HD, "1280 × 720 (720p)"))
        }

        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val storedQuality = preferences.getString(VIDEO_QUALITY_KEY, null)
        selectedQuality = qualityOptions.firstOrNull { qualityKey(it.quality) == storedQuality }?.quality
            ?: qualityOptions.firstOrNull { it.quality == Quality.FHD }?.quality
            ?: qualityOptions.first().quality

        val availableRanges = cameraInfo.supportedFrameRateRanges
            .filter { it.lower > 0 && it.upper >= it.lower }
            .distinctBy { it.lower to it.upper }
            .sortedWith(compareByDescending<Range<Int>> { it.upper }.thenByDescending { it.lower })
        frameRateOptions = listOf(FrameRateOption(null, getString(R.string.camera_fps_auto))) +
            availableRanges.map { range -> FrameRateOption(range, frameRateLabel(range)) }

        val storedLower = preferences.getInt(VIDEO_FPS_LOWER_KEY, -1)
        val storedUpper = preferences.getInt(VIDEO_FPS_UPPER_KEY, -1)
        selectedFrameRate = availableRanges.firstOrNull {
            it.lower == storedLower && it.upper == storedUpper
        }
    }

    private fun showCameraSettingsDialog() {
        if (activeRecording != null) {
            Toast.makeText(this, R.string.camera_settings_while_recording, Toast.LENGTH_LONG).show()
            return
        }
        if (qualityOptions.isEmpty() || frameRateOptions.isEmpty()) {
            Toast.makeText(this, R.string.camera_settings_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val resolutionLabel = TextView(this).apply {
            text = getString(R.string.camera_resolution)
            setPadding(48, 14, 48, 4)
        }
        val resolutionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                qualityOptions.map { it.label }
            )
            setSelection(qualityOptions.indexOfFirst { it.quality == selectedQuality }.coerceAtLeast(0))
        }
        val fpsLabel = TextView(this).apply {
            text = getString(R.string.camera_frame_rate)
            setPadding(48, 20, 48, 4)
        }
        val fpsSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                frameRateOptions.map { it.label }
            )
            setSelection(frameRateOptions.indexOfFirst { it.range == selectedFrameRate }.coerceAtLeast(0))
        }
        val note = TextView(this).apply {
            text = getString(R.string.camera_settings_note)
            textSize = 12f
            setPadding(48, 18, 48, 12)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(resolutionLabel)
            addView(resolutionSpinner)
            addView(fpsLabel)
            addView(fpsSpinner)
            addView(note)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.camera_settings_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.camera_apply) { _, _ ->
                selectedQuality = qualityOptions[resolutionSpinner.selectedItemPosition].quality
                selectedFrameRate = frameRateOptions[fpsSpinner.selectedItemPosition].range
                saveCameraSettings()
                cameraSettingsAvailable = false
                binding.recordButton.isEnabled = false
                startCamera()
            }
            .show()
    }

    private fun saveCameraSettings() {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit().apply {
            putString(VIDEO_QUALITY_KEY, qualityKey(selectedQuality))
            selectedFrameRate?.let {
                putInt(VIDEO_FPS_LOWER_KEY, it.lower)
                putInt(VIDEO_FPS_UPPER_KEY, it.upper)
            } ?: run {
                remove(VIDEO_FPS_LOWER_KEY)
                remove(VIDEO_FPS_UPPER_KEY)
            }
        }.apply()
    }

    private fun cameraSettingsSummary(): String {
        val quality = qualityName(selectedQuality)
        val fps = selectedFrameRate?.let(::frameRateLabel) ?: getString(R.string.camera_fps_auto_short)
        return getString(R.string.camera_settings_summary, quality, fps)
    }

    private fun qualityName(quality: Quality): String = when (quality) {
        Quality.UHD -> "4K"
        Quality.FHD -> "1080p"
        Quality.HD -> "720p"
        Quality.SD -> "480p"
        else -> quality.toString()
    }

    private fun qualityKey(quality: Quality): String = when (quality) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> quality.toString()
    }

    private fun frameRateLabel(range: Range<Int>): String = if (range.lower == range.upper) {
        getString(R.string.camera_fps_fixed, range.upper)
    } else {
        getString(R.string.camera_fps_range, range.lower, range.upper)
    }

    private fun showOfflineMapsDialog() {
        if (activeRecording != null) {
            Toast.makeText(this, R.string.camera_settings_while_recording, Toast.LENGTH_LONG).show()
            return
        }
        val installed = offlineRegionManager.installedRegion()
        if (!offlineRegionManager.isCatalogConfigured) {
            if (installed != null) {
                showInstalledOfflineRegion(installed)
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.offline_maps_title)
                    .setMessage(R.string.offline_catalog_not_configured)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return
        }
        offlineExecutor.execute {
            runCatching { offlineRegionManager.fetchCatalog() }
                .onSuccess { catalog -> runOnUiThread { showOfflineCatalog(catalog, installed) } }
                .onFailure { error ->
                    Log.e(LOG_TAG, "Could not load offline map catalog", error)
                    runOnUiThread {
                        if (installed != null) showInstalledOfflineRegion(installed) else {
                            Toast.makeText(this, R.string.offline_unavailable, Toast.LENGTH_LONG).show()
                        }
                    }
                }
        }
    }

    private fun showOfflineCatalog(catalog: OfflineCatalog, installed: InstalledOfflineRegion?) {
        val packages = catalog.packages
        val labels = buildList {
            packages.forEach { item ->
                val title = getString(
                    if (item.isFull) R.string.offline_map_full else R.string.offline_map_only
                )
                add(
                    getString(
                        R.string.offline_package_option,
                        title,
                        StorageFormatter.display(item.downloadBytes),
                        StorageFormatter.display(item.installedBytes)
                    )
                )
            }
            if (installed != null) add(getString(R.string.offline_remove))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.offline_maps_title)
            .setMessage(installed?.let {
                getString(
                    R.string.offline_installed_status,
                    it.regionName,
                    it.type,
                    StorageFormatter.display(it.installedBytes)
                )
            })
            .setItems(labels.toTypedArray()) { _, index ->
                if (index < packages.size) confirmOfflineDownload(packages[index])
                else installed?.let(::confirmOfflineRemoval)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showInstalledOfflineRegion(installed: InstalledOfflineRegion) {
        AlertDialog.Builder(this)
            .setTitle(R.string.offline_maps_title)
            .setMessage(
                getString(
                    R.string.offline_installed_status,
                    installed.regionName,
                    installed.type,
                    StorageFormatter.display(installed.installedBytes)
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.offline_remove) { _, _ -> confirmOfflineRemoval(installed) }
            .show()
    }

    private fun confirmOfflineDownload(item: OfflinePackage) {
        val required = offlineRegionManager.requiredTemporaryBytes(item)
        val available = offlineRegionManager.availableBytes()
        val message = getString(
            R.string.offline_download_message,
            StorageFormatter.display(item.downloadBytes),
            StorageFormatter.display(item.installedBytes),
            StorageFormatter.display(required),
            StorageFormatter.display(available)
        )
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.offline_download_title, item.regionName))
            .setMessage(
                if (available >= required) message
                else "$message\n\n${getString(R.string.offline_not_enough_space)}"
            )
            .setNegativeButton(android.R.string.cancel, null)
        if (available >= required) {
            builder.setPositiveButton(R.string.offline_download) { _, _ ->
                runCatching { offlineRegionManager.startDownload(item) }
                    .onSuccess {
                        Toast.makeText(this, R.string.offline_download_started, Toast.LENGTH_SHORT).show()
                        mainHandler.removeCallbacks(offlineProgressRunnable)
                        mainHandler.post(offlineProgressRunnable)
                    }
                    .onFailure(::showOfflineError)
            }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    private fun confirmOfflineRemoval(installed: InstalledOfflineRegion) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.offline_remove_title, installed.regionName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.offline_remove) { _, _ ->
                offlineRegionStore.closeRegion()
                if (offlineRegionManager.deleteInstalledRegion()) {
                    Toast.makeText(this, R.string.offline_removed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun resumePendingOfflineDownload() {
        if (offlineRegionManager.pendingDownloadId() < 0) return
        mainHandler.removeCallbacks(offlineProgressRunnable)
        mainHandler.post(offlineProgressRunnable)
    }

    private fun installOfflineDownload(id: Long) {
        if (id < 0 || !offlineInstallInProgress.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(offlineProgressRunnable)
        offlineExecutor.execute {
            runCatching { offlineRegionManager.installCompletedDownload(id) }
                .onSuccess { installed ->
                    offlineRegionStore.refresh()
                    runOnUiThread {
                        offlineInstallInProgress.set(false)
                        Toast.makeText(
                            this,
                            getString(R.string.offline_installed, installed.regionName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .onFailure { error -> runOnUiThread {
                    offlineInstallInProgress.set(false)
                    showOfflineError(error)
                } }
        }
    }

    private fun showOfflineError(error: Throwable) {
        Log.e(LOG_TAG, "Offline map operation failed", error)
        Toast.makeText(this, error.message ?: getString(R.string.offline_error), Toast.LENGTH_LONG).show()
    }

    private fun createCameraOverlay(): OverlayEffect {
        val effect = OverlayEffect(
            CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
            0,
            Handler(overlayThread.looper)
        ) { error ->
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, error.message ?: "Camera overlay failed", Toast.LENGTH_LONG).show()
            }
        }

        effect.setOnDrawListener { frame ->
            drawOverlay(frame)
            true
        }
        return effect
    }

    private fun drawOverlay(frame: Frame) {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val rawWidth = frame.size.width.toFloat()
        val rawHeight = frame.size.height.toFloat()
        val transform = outputToRawTransform(frame.rotationDegrees, rawWidth, rawHeight)
        val crop = orientedCrop(frame, rawWidth, rawHeight)

        canvas.save()
        canvas.concat(transform)
        NavigationOverlayState.current.takeIf { it.isNavigating }?.let {
            drawNavigationOverlay(canvas, crop, it)
        }
        drawSpeedOverlay(canvas, crop)
        canvas.restore()
    }

    private fun drawSpeedOverlay(canvas: android.graphics.Canvas, crop: RectF) {
        val recentSpeed = speedKph?.takeIf {
            SystemClock.elapsedRealtimeNanos() - lastLocationRealtimeNanos <= LOCATION_STALE_NANOS
        }
        val displaySpeed = recentSpeed ?: if (latestLocation != null) 0f else null
        val text = displaySpeed?.let(SpeedFormatter::display) ?: "-- km/h"
        val textSize = min(crop.width(), crop.height()) * 0.072f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setShadowLayer(textSize * 0.12f, 0f, textSize * 0.06f, Color.BLACK)
        }
        val paddingX = textSize * 0.55f
        val paddingY = textSize * 0.30f
        val centerX = crop.centerX()
        val baseline = crop.bottom - crop.height() * 0.08f
        val halfWidth = textPaint.measureText(text) / 2f + paddingX
        val panel = RectF(
            centerX - halfWidth,
            baseline - textSize - paddingY,
            centerX + halfWidth,
            baseline + paddingY
        )
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
        canvas.drawRoundRect(panel, textSize * 0.35f, textSize * 0.35f, backgroundPaint)
        canvas.drawText(text, centerX, baseline, textPaint)
    }

    private fun drawNavigationOverlay(
        canvas: android.graphics.Canvas,
        crop: RectF,
        data: NavigationDisplayData
    ) {
        val landscape = crop.width() > crop.height()
        val margin = min(crop.width(), crop.height()) * 0.035f
        val panelWidth = crop.width() * if (landscape) 0.34f else 0.43f
        val panelHeight = crop.height() * if (landscape) 0.56f else 0.31f
        val mapRect = RectF(
            crop.right - margin - panelWidth,
            crop.top + margin,
            crop.right - margin,
            crop.top + margin + panelHeight
        )
        val corner = min(mapRect.width(), mapRect.height()) * 0.07f
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x68000000 }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = max(2f, min(crop.width(), crop.height()) * 0.0025f)
        }
        canvas.drawRoundRect(mapRect, corner, corner, panelPaint)
        canvas.drawRoundRect(mapRect, corner, corner, borderPaint)

        canvas.save()
        canvas.clipPath(Path().apply {
            addRoundRect(mapRect, corner, corner, Path.Direction.CW)
        })
        drawRoutePolyline(canvas, mapRect, data)
        canvas.restore()

        val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDDFFFFFF.toInt()
            textSize = min(mapRect.width(), mapRect.height()) * 0.052f
            textAlign = Paint.Align.RIGHT
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
        }
        canvas.drawText(
            getString(R.string.osm_attribution),
            mapRect.right - margin * 0.25f,
            mapRect.bottom - margin * 0.2f,
            attributionPaint
        )

        val bannerRight = mapRect.left - margin
        val bannerWidth = min(crop.width() * 0.63f, bannerRight - crop.left - margin)
        if (bannerWidth <= 0f) return
        val bannerHeight = crop.height() * if (landscape) 0.38f else 0.22f
        val banner = RectF(
            crop.left + margin,
            crop.top + margin,
            crop.left + margin + bannerWidth,
            crop.top + margin + bannerHeight
        )
        canvas.drawRoundRect(banner, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB0000000.toInt()
        })

        val bannerClip = Path().apply {
            addRoundRect(banner, corner, corner, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(bannerClip)
        val arrowSize = banner.height() * 0.46f
        drawTurnArrow(
            canvas,
            banner.left + arrowSize * 0.75f,
            banner.centerY(),
            arrowSize,
            data.instruction.orEmpty()
        )
        val textLeft = banner.left + arrowSize * 1.55f
        val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = banner.height() * 0.25f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = banner.height() * 0.18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDDFFFFFF.toInt()
            textSize = banner.height() * 0.14f
            textAlign = Paint.Align.CENTER
        }
        val availableTextWidth = (banner.right - textLeft - margin * 0.35f).coerceAtLeast(1f)
        val distance = data.distanceToTurnMeters?.let(NavigationDisplayData::formatDistance)
            ?: "Follow route"
        val instruction = if (data.isRecalculating) "Recalculating route…" else {
            data.instruction ?: "Continue route"
        }
        val secondary = data.secondaryLine().orEmpty()
        fitTextToWidth(
            secondaryPaint,
            secondary,
            (banner.width() - margin * 0.70f).coerceAtLeast(1f)
        )
        canvas.drawText(distance, textLeft, banner.top + banner.height() * 0.29f, distancePaint)

        val instructionLines = wrapText(instruction, instructionPaint, availableTextWidth, 2)
        val instructionStartY = banner.top + banner.height() * 0.52f
        instructionLines.forEachIndexed { index, line ->
            canvas.drawText(
                line,
                textLeft,
                instructionStartY + index * instructionPaint.textSize * 1.08f,
                instructionPaint
            )
        }
        canvas.drawText(
            secondary,
            banner.centerX(),
            banner.bottom - banner.height() * 0.10f,
            secondaryPaint
        )
        canvas.restore()
    }

    private fun drawRoutePolyline(
        canvas: android.graphics.Canvas,
        rect: RectF,
        data: NavigationDisplayData
    ) {
        val route = data.route ?: return
        val current = data.currentLocation ?: route.points.getOrNull(data.currentRouteIndex) ?: return
        val inner = RectF(rect).apply { inset(rect.width() * 0.08f, rect.height() * 0.08f) }
        val attributionSpace = rect.height() * 0.12f
        val centerX = inner.centerX()
        val originY = inner.bottom - attributionSpace
        val visibleAhead = (data.distanceToTurnMeters?.times(2.0) ?: 900.0).coerceIn(350.0, 1800.0)
        val scale = (inner.height() - attributionSpace) / visibleAhead.toFloat()
        val heading = Math.toRadians(data.headingDegrees.toDouble())
        val cosHeading = cos(heading)
        val sinHeading = sin(heading)

        if (offlineRegionStore.hasMap()) {
            drawOfflineStreets(
                canvas = canvas,
                rect = rect,
                roads = offlineRegionStore.roadsNear(current, visibleAhead * 1.6),
                current = current,
                scale = scale,
                headingRadians = heading,
                centerX = centerX,
                originY = originY
            )
        } else {
            drawStreetTiles(
                canvas = canvas,
                rect = rect,
                current = current,
                scale = scale,
                headingRadians = heading,
                centerX = centerX,
                originY = originY
            )
        }

        fun projected(point: GeoPoint): Pair<Float, Float> {
            val east = (point.longitude - current.longitude) * 111_320.0 *
                cos(Math.toRadians(current.latitude))
            val north = (point.latitude - current.latitude) * 110_540.0
            val right = east * cosHeading - north * sinHeading
            val forward = east * sinHeading + north * cosHeading
            return Pair(centerX + right.toFloat() * scale, originY - forward.toFloat() * scale)
        }

        val routePath = Path()
        val startIndex = max(0, data.currentRouteIndex - 2)
        val currentDistance = route.cumulativeMeters.getOrElse(data.currentRouteIndex) { 0.0 }
        var started = false
        for (index in startIndex until route.points.size) {
            if (route.cumulativeMeters[index] - currentDistance > visibleAhead * 1.35) break
            val (x, y) = projected(route.points[index])
            if (!started) {
                routePath.moveTo(x, y)
                started = true
            } else {
                routePath.lineTo(x, y)
            }
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC001018.toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(8f, rect.width() * 0.045f)
        }
        val routePaint = Paint(outline).apply {
            color = 0xFF40C4FF.toInt()
            strokeWidth = outline.strokeWidth * 0.55f
        }
        canvas.drawPath(routePath, outline)
        canvas.drawPath(routePath, routePaint)

        drawNavigationMarker(canvas, centerX, originY, max(9f, rect.width() * 0.045f))
    }

    private fun drawOfflineStreets(
        canvas: android.graphics.Canvas,
        rect: RectF,
        roads: List<OfflineRoad>,
        current: GeoPoint,
        scale: Float,
        headingRadians: Double,
        centerX: Float,
        originY: Float
    ) {
        val cosHeading = cos(headingRadians)
        val sinHeading = sin(headingRadians)
        val majorClasses = setOf(
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link"
        )
        val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x82FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = max(1.2f, rect.width() * 0.005f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val majorOutline = Paint(minorPaint).apply {
            color = 0xA0001018.toInt()
            strokeWidth = max(3.5f, rect.width() * 0.015f)
        }
        val majorPaint = Paint(minorPaint).apply {
            color = 0xC8FFFFFF.toInt()
            strokeWidth = max(2.0f, rect.width() * 0.009f)
        }

        fun projected(point: GeoPoint): Pair<Float, Float> {
            val east = (point.longitude - current.longitude) * 111_320.0 *
                cos(Math.toRadians(current.latitude))
            val north = (point.latitude - current.latitude) * 110_540.0
            val right = east * cosHeading - north * sinHeading
            val forward = east * sinHeading + north * cosHeading
            return Pair(centerX + right.toFloat() * scale, originY - forward.toFloat() * scale)
        }

        roads.forEach { road ->
            if (road.points.size < 2) return@forEach
            val path = Path()
            road.points.forEachIndexed { index, point ->
                val (x, y) = projected(point)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (road.roadClass in majorClasses) {
                canvas.drawPath(path, majorOutline)
                canvas.drawPath(path, majorPaint)
            } else {
                canvas.drawPath(path, minorPaint)
            }
        }
    }

    private fun drawStreetTiles(
        canvas: android.graphics.Canvas,
        rect: RectF,
        current: GeoPoint,
        scale: Float,
        headingRadians: Double,
        centerX: Float,
        originY: Float
    ) {
        val metersPerScreenPixel = 1.0 / scale.coerceAtLeast(0.0001f)
        val latitudeRadians = Math.toRadians(current.latitude.coerceIn(-85.0, 85.0))
        val zoom = log2(
            cos(latitudeRadians) * WEB_MERCATOR_CIRCUMFERENCE_METERS /
                (TILE_SIZE * metersPerScreenPixel)
        ).roundToInt().coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        val worldX = (current.longitude + 180.0) / 360.0 * worldSize
        val worldY = (
            1.0 - ln(tan(latitudeRadians) + 1.0 / cos(latitudeRadians)) / Math.PI
            ) / 2.0 * worldSize
        val centerTileX = floor(worldX / TILE_SIZE).toInt()
        val centerTileY = floor(worldY / TILE_SIZE).toInt()
        val metersPerTilePixel = cos(latitudeRadians) * WEB_MERCATOR_CIRCUMFERENCE_METERS / worldSize
        val tileToScreenScale = (metersPerTilePixel * scale).toFloat()
        val requiredRadius = ceil(
            hypot(rect.width().toDouble(), rect.height().toDouble()) /
                (TILE_SIZE * tileToScreenScale).coerceAtLeast(1f)
        ).toInt().coerceIn(1, MAX_TILE_RADIUS)
        val cosHeading = cos(headingRadians).toFloat()
        val sinHeading = sin(headingRadians).toFloat()
        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = MAP_TILE_ALPHA
            isFilterBitmap = true
        }

        for (tileY in centerTileY - requiredRadius..centerTileY + requiredRadius) {
            for (tileX in centerTileX - requiredRadius..centerTileX + requiredRadius) {
                val pixelOffsetX = (tileX * TILE_SIZE - worldX).toFloat()
                val pixelOffsetY = (tileY * TILE_SIZE - worldY).toFloat()
                val transform = Matrix().apply {
                    setValues(
                        floatArrayOf(
                            tileToScreenScale * cosHeading,
                            tileToScreenScale * sinHeading,
                            centerX + tileToScreenScale *
                                (pixelOffsetX * cosHeading + pixelOffsetY * sinHeading),
                            -tileToScreenScale * sinHeading,
                            tileToScreenScale * cosHeading,
                            originY + tileToScreenScale *
                                (-pixelOffsetX * sinHeading + pixelOffsetY * cosHeading),
                            0f,
                            0f,
                            1f
                        )
                    )
                }
                val tileBounds = RectF(0f, 0f, TILE_SIZE.toFloat(), TILE_SIZE.toFloat())
                transform.mapRect(tileBounds)
                if (!RectF.intersects(rect, tileBounds)) continue

                osmTileClient.tile(zoom, tileX, tileY)?.let { bitmap ->
                    canvas.drawBitmap(bitmap, transform, tilePaint)
                }
            }
        }
    }

    private fun drawNavigationMarker(
        canvas: android.graphics.Canvas,
        centerX: Float,
        centerY: Float,
        size: Float
    ) {
        customMarkerBitmap?.let {
            drawCustomMarker(canvas, centerX, centerY, size, it)
            return
        }

        // The minimap is heading-up, so the navigation marker always points to its top edge.
        val marker = Path().apply {
            moveTo(centerX, centerY - size * 1.45f)
            lineTo(centerX + size * 0.90f, centerY + size * 1.10f)
            lineTo(centerX, centerY + size * 0.64f)
            lineTo(centerX - size * 0.90f, centerY + size * 1.10f)
            close()
        }
        canvas.drawPath(marker, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC001018.toInt()
            style = Paint.Style.STROKE
            strokeWidth = size * 0.62f
            strokeJoin = Paint.Join.ROUND
            setShadowLayer(size * 0.45f, 0f, size * 0.18f, Color.BLACK)
        })
        canvas.drawPath(marker, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = size * 0.32f
            strokeJoin = Paint.Join.ROUND
        })
        canvas.drawPath(marker, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1565C0.toInt()
            style = Paint.Style.FILL
        })
    }

    private fun drawCustomMarker(
        canvas: android.graphics.Canvas,
        centerX: Float,
        centerY: Float,
        size: Float,
        bitmap: Bitmap
    ) {
        val maximumSide = size * 2.55f
        val scale = min(maximumSide / bitmap.width, maximumSide / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val destination = RectF(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f
        )
        canvas.drawCircle(centerX, centerY, size * 1.48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC001018.toInt()
            setShadowLayer(size * 0.42f, 0f, size * 0.18f, Color.BLACK)
        })
        canvas.drawCircle(centerX, centerY, size * 1.30f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })
        canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        })
    }

    private fun showMarkerDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.marker_title)
            .setItems(
                arrayOf(
                    getString(R.string.marker_default),
                    getString(R.string.marker_custom)
                )
            ) { _, which ->
                when (which) {
                    0 -> useDefaultMarker()
                    1 -> markerImageLauncher.launch(arrayOf("image/*"))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectCustomMarker(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            Log.w(LOG_TAG, "Could not persist access to custom marker image", it)
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putString(CUSTOM_MARKER_URI_KEY, uri.toString())
            .apply()
        loadCustomMarker(uri, showResult = true)
    }

    private fun restoreCustomMarker() {
        val storedUri = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(CUSTOM_MARKER_URI_KEY, null)
            ?: return
        loadCustomMarker(Uri.parse(storedUri), showResult = false)
    }

    private fun loadCustomMarker(uri: Uri, showResult: Boolean) {
        val generation = markerGeneration.incrementAndGet()
        markerExecutor.execute {
            runCatching { decodeMarkerBitmap(uri) }
                .onSuccess { bitmap ->
                    if (generation != markerGeneration.get()) return@onSuccess
                    customMarkerBitmap = bitmap
                    Log.i(LOG_TAG, "Custom navigation marker loaded")
                    if (showResult) runOnUiThread {
                        Toast.makeText(this, R.string.marker_custom_selected, Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { error ->
                    Log.e(LOG_TAG, "Could not load custom navigation marker", error)
                    getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                        .edit()
                        .remove(CUSTOM_MARKER_URI_KEY)
                        .apply()
                    if (showResult) runOnUiThread {
                        Toast.makeText(this, R.string.marker_load_error, Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun decodeMarkerBitmap(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val largestSide = max(info.size.width, info.size.height)
            if (largestSide > CUSTOM_MARKER_MAX_PIXELS) {
                val ratio = CUSTOM_MARKER_MAX_PIXELS.toFloat() / largestSide
                decoder.setTargetSize(
                    max(1, (info.size.width * ratio).toInt()),
                    max(1, (info.size.height * ratio).toInt())
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun useDefaultMarker() {
        markerGeneration.incrementAndGet()
        customMarkerBitmap = null
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .remove(CUSTOM_MARKER_URI_KEY)
            .apply()
        Toast.makeText(this, R.string.marker_default_selected, Toast.LENGTH_SHORT).show()
    }

    private fun drawTurnArrow(
        canvas: android.graphics.Canvas,
        centerX: Float,
        centerY: Float,
        size: Float,
        instruction: String
    ) {
        val lower = instruction.lowercase(Locale.US)
        val direction = when {
            "left" in lower -> -1f
            "right" in lower -> 1f
            else -> 0f
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF40C4FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = size * 0.15f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        if (direction == 0f) {
            path.moveTo(centerX, centerY + size * 0.42f)
            path.lineTo(centerX, centerY - size * 0.38f)
            path.moveTo(centerX, centerY - size * 0.38f)
            path.lineTo(centerX - size * 0.22f, centerY - size * 0.14f)
            path.moveTo(centerX, centerY - size * 0.38f)
            path.lineTo(centerX + size * 0.22f, centerY - size * 0.14f)
        } else {
            path.moveTo(centerX, centerY + size * 0.4f)
            path.lineTo(centerX, centerY)
            path.lineTo(centerX + direction * size * 0.36f, centerY - size * 0.22f)
            path.moveTo(centerX + direction * size * 0.36f, centerY - size * 0.22f)
            path.lineTo(centerX + direction * size * 0.08f, centerY - size * 0.24f)
            path.moveTo(centerX + direction * size * 0.36f, centerY - size * 0.22f)
            path.lineTo(centerX + direction * size * 0.28f, centerY + size * 0.04f)
        }
        canvas.drawPath(path, paint)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotEmpty()) lines += current
                current = word
                if (lines.size == maxLines - 1) break
            }
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines += current

        val consumedWords = lines.joinToString(" ").split(' ').size
        if (consumedWords < words.size && lines.isNotEmpty()) {
            var last = lines.last()
            while (last.isNotEmpty() && paint.measureText("$last…") > maxWidth) {
                last = last.dropLast(1)
            }
            lines[lines.lastIndex] = last.trimEnd() + "…"
        }
        return lines
    }

    private fun fitTextToWidth(paint: Paint, text: String, maxWidth: Float) {
        val measuredWidth = paint.measureText(text)
        if (measuredWidth > maxWidth && measuredWidth > 0f) {
            paint.textSize *= maxWidth / measuredWidth
        }
    }

    private fun orientedCrop(frame: Frame, rawWidth: Float, rawHeight: Float): RectF {
        val crop = frame.cropRect
        return when (frame.rotationDegrees) {
            90 -> RectF(rawHeight - crop.bottom, crop.left.toFloat(), rawHeight - crop.top, crop.right.toFloat())
            180 -> RectF(rawWidth - crop.right, rawHeight - crop.bottom, rawWidth - crop.left, rawHeight - crop.top)
            270 -> RectF(crop.top.toFloat(), rawWidth - crop.right, crop.bottom.toFloat(), rawWidth - crop.left)
            else -> RectF(crop)
        }
    }

    private fun outputToRawTransform(rotation: Int, rawWidth: Float, rawHeight: Float): Matrix {
        val values = when (rotation) {
            90 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, rawHeight, 0f, 0f, 1f)
            180 -> floatArrayOf(-1f, 0f, rawWidth, 0f, -1f, rawHeight, 0f, 0f, 1f)
            270 -> floatArrayOf(0f, -1f, rawWidth, 1f, 0f, 0f, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        return Matrix().apply { setValues(values) }
    }

    private fun updateSpeed(location: Location) {
        val measuredKph = if (location.hasSpeed()) {
            SpeedFormatter.kilometersPerHour(location.speed)
        } else {
            0f
        }
        val previous = smoothedSpeedKph
        val next = if (previous == null) measuredKph else {
            previous * SPEED_SMOOTHING_OLD + measuredKph * SPEED_SMOOTHING_NEW
        }
        smoothedSpeedKph = next
        speedKph = next
        lastLocationRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun showDestinationDialog() {
        val input = AutoCompleteTextView(this).apply {
            hint = getString(R.string.destination_hint)
            setSingleLine(true)
            setPadding(48, 12, 48, 12)
            threshold = 3
        }
        val suggestionAdapter = ArrayAdapter<AddressSuggestion>(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        )
        val suggestionList = ListView(this).apply {
            adapter = suggestionAdapter
            visibility = View.GONE
            dividerHeight = 1
        }
        val searchStatus = TextView(this).apply {
            setPadding(48, 10, 48, 4)
            text = getString(R.string.suggestion_prompt)
        }
        val attribution = TextView(this).apply {
            setPadding(48, 6, 48, 12)
            text = getString(R.string.search_attribution)
            textSize = 11f
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val match = ViewGroup.LayoutParams.MATCH_PARENT
            addView(input, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(searchStatus, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(suggestionList, LinearLayout.LayoutParams(match, dp(220)))
            addView(attribution, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        var selectedSuggestion: AddressSuggestion? = null
        var searchGeneration = 0
        var pendingSearch: Runnable? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.destination_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.start_navigation) { _, _ ->
                resolveAndRoute(input.text.toString().trim(), selectedSuggestion?.point)
            }
            .create()

        suggestionList.setOnItemClickListener { _, _, position, _ ->
            val suggestion = suggestionAdapter.getItem(position) ?: return@setOnItemClickListener
            input.setText(suggestion.label)
            input.setSelection(input.text.length)
            pendingSearch?.let(mainHandler::removeCallbacks)
            searchGeneration += 1
            selectedSuggestion = suggestion
            suggestionList.visibility = View.GONE
            searchStatus.text = getString(R.string.suggestion_selected)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                selectedSuggestion = null
                pendingSearch?.let(mainHandler::removeCallbacks)
                val query = editable?.toString()?.trim().orEmpty()
                searchGeneration += 1
                val generation = searchGeneration
                suggestionAdapter.clear()
                suggestionList.visibility = View.GONE
                if (query.length < MIN_SUGGESTION_CHARACTERS) {
                    searchStatus.text = getString(R.string.suggestion_prompt)
                    return
                }

                searchStatus.text = getString(R.string.suggestion_searching)
                pendingSearch = Runnable {
                    val near = latestLocation?.toGeoPoint()
                    suggestionExecutor.execute {
                        runCatching {
                            offlineRegionStore.search(query).ifEmpty {
                                suggestionClient.suggestions(query, near)
                            }
                        }
                            .onFailure { Log.w(LOG_TAG, "Address suggestion lookup failed", it) }
                            .onSuccess { results ->
                                runOnUiThread {
                                    if (!dialog.isShowing || generation != searchGeneration) return@runOnUiThread
                                    suggestionAdapter.clear()
                                    suggestionAdapter.addAll(results)
                                    suggestionAdapter.notifyDataSetChanged()
                                    suggestionList.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
                                    searchStatus.text = getString(
                                        if (results.isEmpty()) R.string.suggestion_none else R.string.suggestion_tap
                                    )
                                }
                            }
                    }
                }.also { mainHandler.postDelayed(it, SUGGESTION_DEBOUNCE_MS) }
            }
        })
        dialog.setOnDismissListener {
            pendingSearch?.let(mainHandler::removeCallbacks)
            searchGeneration += 1
        }
        dialog.show()
    }

    private fun resolveAndRoute(query: String, selectedDestination: GeoPoint? = null) {
        if (query.isBlank()) return
        val originLocation = navigationOriginLocation()
        if (originLocation == null) {
            pendingNavigationRequest = PendingNavigationRequest(query, selectedDestination)
            binding.destinationButton.visibility = View.GONE
            startLocationUpdatesIfAllowed()
            return
        }

        pendingNavigationRequest = null
        routeRequestInFlight = true
        val generation = routeGeneration.incrementAndGet()
        Log.i(LOG_TAG, "Destination requested; resolving address")
        routingExecutor.execute {
            try {
                val destination = selectedDestination ?: parseCoordinates(query)
                    ?: geocodeAddress(query, originLocation.toGeoPoint())
                    ?: error(getString(R.string.destination_not_found))
                Log.i(LOG_TAG, "Destination resolved; requesting driving route")
                requestRouteBlocking(
                    origin = originLocation.toGeoPoint(),
                    destination = destination,
                    label = query,
                    recalculating = false,
                    generation = generation
                )
            } catch (error: Exception) {
                Log.e(LOG_TAG, "Destination lookup or route request failed", error)
                runOnUiThread {
                    if (generation != routeGeneration.get()) return@runOnUiThread
                    routeRequestInFlight = false
                    binding.destinationButton.visibility = View.VISIBLE
                    Toast.makeText(this, error.message ?: getString(R.string.route_error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun geocodeAddress(query: String, near: GeoPoint): GeoPoint? {
        val offlineResult = runCatching { offlineRegionStore.search(query, 1).firstOrNull()?.point }
            .onFailure { Log.w(LOG_TAG, "Offline address lookup failed", it) }
            .getOrNull()
        if (offlineResult != null) {
            Log.i(LOG_TAG, "Address resolved by installed offline region")
            return offlineResult
        }

        val osmResult = runCatching { osmGeocodingClient.geocode(query, near) }
            .onFailure { Log.w(LOG_TAG, "OpenStreetMap address lookup failed; trying Android geocoder", it) }
            .getOrNull()
        if (osmResult != null) {
            Log.i(LOG_TAG, "Address resolved by OpenStreetMap Nominatim")
            return osmResult
        }

        val androidResult = geocodeWithAndroid(query)
        if (androidResult != null) Log.i(LOG_TAG, "Address resolved by Android geocoder")
        return androidResult
    }

    @Suppress("DEPRECATION")
    private fun geocodeWithAndroid(query: String): GeoPoint? {
        if (!Geocoder.isPresent()) {
            Log.w(LOG_TAG, "Android geocoder is not available on this device")
            return null
        }
        return runCatching {
            Geocoder(this, Locale.getDefault()).getFromLocationName(query, 1)?.firstOrNull()
                ?.let { GeoPoint(it.latitude, it.longitude) }
        }.onFailure {
            Log.w(LOG_TAG, "Android geocoder lookup failed", it)
        }.getOrNull()
    }

    private fun parseCoordinates(query: String): GeoPoint? {
        val parts = query.split(',').map { it.trim() }
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return GeoPoint(latitude, longitude)
    }

    private fun requestRouteBlocking(
        origin: GeoPoint,
        destination: GeoPoint,
        label: String,
        recalculating: Boolean,
        generation: Int
    ) {
        if (recalculating) {
            NavigationOverlayState.update(NavigationOverlayState.current.copy(isRecalculating = true))
        }
        offlineRegionStore.awaitInitialLoad()
        val route = if (!hasInternetConnectivity() && offlineRegionStore.hasRouting()) {
            offlineRoutingClient.route(origin, destination, label)
        } else {
            runCatching { routingClient.route(origin, destination, label) }
                .onFailure { Log.w(LOG_TAG, "Online routing failed; trying installed offline region", it) }
                .getOrElse { onlineError ->
                    if (offlineRegionStore.hasRouting()) {
                        offlineRoutingClient.route(origin, destination, label)
                    } else {
                        throw onlineError
                    }
                }
        }
        Log.i(LOG_TAG, "Route calculated successfully")
        if (generation != routeGeneration.get()) return
        val initial = NavigationDisplayData(
            route = route,
            currentLocation = origin,
            instruction = route.steps.firstOrNull()?.instruction,
            distanceToDestinationMeters = route.totalDistanceMeters.toInt(),
            timeToDestinationSeconds = route.totalDurationSeconds.toInt()
        )
        routePersistence.save(route)
        NavigationOverlayState.update(initial)
        routeRequestInFlight = false
        runOnUiThread {
            binding.destinationButton.visibility = View.GONE
        }
    }

    private fun updateRouteProgress(location: Location) {
        val state = NavigationOverlayState.current
        val route = state.route ?: return
        val point = location.toGeoPoint()
        val match = RouteProgress.closestPoint(route, point)
        val routeLength = route.cumulativeMeters.lastOrNull()?.coerceAtLeast(1.0) ?: return
        val traveled = route.cumulativeMeters.getOrElse(match.index) { 0.0 }
        val remaining = (routeLength - traveled).coerceAtLeast(0.0)
        if (remaining < ARRIVAL_DISTANCE_METERS) {
            Toast.makeText(this, R.string.arrived, Toast.LENGTH_LONG).show()
            stopNavigation()
            return
        }
        val nextStep = route.steps.firstOrNull { it.routeIndex > match.index + 1 }
            ?: route.steps.lastOrNull()
        val turnDistance = nextStep?.let {
            (route.cumulativeMeters[it.routeIndex] - traveled).coerceAtLeast(0.0).toInt()
        }
        val heading = when {
            location.hasBearing() -> location.bearing
            match.index + 1 < route.points.size -> bearing(point, route.points[match.index + 1])
            else -> state.headingDegrees
        }
        NavigationOverlayState.update(
            state.copy(
                currentLocation = point,
                currentRouteIndex = match.index,
                headingDegrees = heading,
                instruction = nextStep?.instruction ?: "Arrive at ${route.destinationLabel}",
                distanceToTurnMeters = turnDistance,
                distanceToDestinationMeters = remaining.toInt(),
                timeToDestinationSeconds = (route.totalDurationSeconds * remaining / routeLength).toInt(),
                isRecalculating = routeRequestInFlight
            )
        )

        val now = SystemClock.elapsedRealtime()
        if (match.distanceMeters > OFF_ROUTE_DISTANCE_METERS &&
            !routeRequestInFlight &&
            now - lastRerouteElapsedMs >= MIN_REROUTE_INTERVAL_MS
        ) {
            lastRerouteElapsedMs = now
            routeRequestInFlight = true
            val generation = routeGeneration.incrementAndGet()
            routingExecutor.execute {
                try {
                    requestRouteBlocking(
                        point,
                        route.destination,
                        route.destinationLabel,
                        recalculating = true,
                        generation = generation
                    )
                } catch (error: Exception) {
                    Log.e(LOG_TAG, "Automatic reroute failed", error)
                    if (generation != routeGeneration.get()) return@execute
                    routeRequestInFlight = false
                    NavigationOverlayState.update(NavigationOverlayState.current.copy(isRecalculating = false))
                }
            }
        }
    }

    private fun bearing(from: GeoPoint, to: GeoPoint): Float {
        val results = FloatArray(3)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results)
        return results[1]
    }

    private fun stopNavigation() {
        routeGeneration.incrementAndGet()
        pendingNavigationRequest = null
        NavigationOverlayState.clear()
        routePersistence.clear()
        routeRequestInFlight = false
        speedKph = 0f
        smoothedSpeedKph = 0f
        lastLocationRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        binding.destinationButton.visibility = View.VISIBLE
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdatesIfAllowed() {
        if (locationUpdatesActive || !hasAnyLocationPermission()) return

        seedRecentLastKnownLocation()
        var requestedProvider = false
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestedProvider = requestLocationProvider(
                LocationManager.GPS_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MS
            ) || requestedProvider
        }
        requestedProvider = requestLocationProvider(
            LocationManager.NETWORK_PROVIDER,
            NETWORK_LOCATION_UPDATE_INTERVAL_MS
        ) || requestedProvider
        requestedProvider = requestLocationProvider(
            LocationManager.PASSIVE_PROVIDER,
            NETWORK_LOCATION_UPDATE_INTERVAL_MS
        ) || requestedProvider
        locationUpdatesActive = requestedProvider
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationProvider(provider: String, intervalMs: Long): Boolean {
        if (provider !in locationManager.allProviders || !isProviderEnabled(provider)) return false
        return runCatching {
            locationManager.requestLocationUpdates(
                provider,
                intervalMs,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
            true
        }.onFailure { Log.w(LOG_TAG, "Could not request $provider location updates", it) }
            .getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun seedRecentLastKnownLocation() {
        val providers = buildList {
            if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        val cached = providers.asSequence()
            .filter { it in locationManager.allProviders }
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }
                    .onFailure { Log.w(LOG_TAG, "Could not read cached $provider location", it) }
                    .getOrNull()
            }
            .filter { locationAgeMillis(it) <= MAX_CACHED_LOCATION_AGE_MS }
            .minByOrNull(::locationAgeMillis)
            ?: return
        if (latestLocation == null || shouldAcceptLocation(cached)) {
            latestLocation = Location(cached)
        }
    }

    private fun navigationOriginLocation(): Location? {
        val current = latestLocation
        if (current != null && locationAgeMillis(current) <= MAX_CACHED_LOCATION_AGE_MS) {
            return Location(current)
        }
        seedRecentLastKnownLocation()
        return latestLocation?.takeIf { locationAgeMillis(it) <= MAX_CACHED_LOCATION_AGE_MS }
            ?.let(::Location)
    }

    private fun shouldAcceptLocation(candidate: Location): Boolean {
        val previous = latestLocation ?: return true
        if (candidate.provider == LocationManager.GPS_PROVIDER) return true
        if (previous.provider == LocationManager.GPS_PROVIDER &&
            locationAgeMillis(previous) <= GPS_PREFERENCE_WINDOW_MS
        ) {
            return false
        }
        val candidateNanos = candidate.elapsedRealtimeNanos
        val previousNanos = previous.elapsedRealtimeNanos
        return candidateNanos >= previousNanos ||
            candidate.hasAccuracy() && previous.hasAccuracy() && candidate.accuracy + 50f < previous.accuracy
    }

    private fun locationAgeMillis(location: Location): Long {
        val elapsedNanos = location.elapsedRealtimeNanos
        return if (elapsedNanos > 0L) {
            ((SystemClock.elapsedRealtimeNanos() - elapsedNanos) / 1_000_000L).coerceAtLeast(0L)
        } else {
            (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
        }
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesActive) return
        locationManager.removeUpdates(locationListener)
        locationUpdatesActive = false
    }

    private fun hasAnyLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun isProviderEnabled(provider: String): Boolean =
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)

    private fun hasEnabledLocationProvider(): Boolean =
        isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    private fun hasInternetConnectivity(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun restoreSavedRoute() {
        val route = routePersistence.load() ?: return
        NavigationOverlayState.update(
            NavigationDisplayData(
                route = route,
                currentLocation = route.points.firstOrNull(),
                instruction = route.steps.firstOrNull()?.instruction,
                distanceToDestinationMeters = route.totalDistanceMeters.toInt(),
                timeToDestinationSeconds = route.totalDurationSeconds.toInt()
            )
        )
        binding.destinationButton.visibility = View.GONE
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return
        val name = "SpeedCamera_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SpeedCamera")
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = capture.output.prepareRecording(this, output)
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        binding.recordButton.isEnabled = false
        binding.settingsMenuButton.visibility = View.GONE
        recordingPaused = false
        try {
            activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        binding.recordButton.isEnabled = true
                        binding.recordButton.backgroundTintList = null
                        binding.recordButton.setBackgroundResource(R.drawable.recording_button)
                        binding.recordButton.contentDescription = getString(R.string.stop)
                        binding.pauseButton.text = getString(R.string.pause_recording)
                        binding.pauseButton.isEnabled = true
                        binding.pauseButton.visibility = View.VISIBLE
                    }
                    is VideoRecordEvent.Pause -> {
                        recordingPaused = true
                        binding.pauseButton.text = getString(R.string.resume_recording)
                        binding.pauseButton.isEnabled = true
                    }
                    is VideoRecordEvent.Resume -> {
                        recordingPaused = false
                        binding.pauseButton.text = getString(R.string.pause_recording)
                        binding.pauseButton.isEnabled = true
                    }
                    is VideoRecordEvent.Finalize -> {
                        activeRecording?.close()
                        activeRecording = null
                        recordingPaused = false
                        binding.recordButton.isEnabled = videoCapture != null
                        binding.recordButton.backgroundTintList = null
                        binding.recordButton.setBackgroundResource(R.drawable.record_button)
                        binding.recordButton.contentDescription = getString(R.string.record)
                        restoreNonRecordingControls()

                        if (event.hasError()) {
                            Toast.makeText(
                                this,
                                event.cause?.message ?: getString(R.string.recording_error),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(this, R.string.video_saved, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Could not start video recording", error)
            restoreNonRecordingControls()
            binding.recordButton.isEnabled = videoCapture != null
            Toast.makeText(this, error.message ?: getString(R.string.recording_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        binding.recordButton.isEnabled = false
        binding.pauseButton.isEnabled = false
        activeRecording?.stop()
    }

    private fun toggleRecordingPause() {
        val recording = activeRecording ?: return
        binding.pauseButton.isEnabled = false
        if (recordingPaused) recording.resume() else recording.pause()
    }

    private fun restoreNonRecordingControls() {
        binding.settingsMenuButton.visibility = View.VISIBLE
        binding.pauseButton.visibility = View.GONE
        binding.pauseButton.isEnabled = false
    }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val LOG_TAG = "SpeedCamera"
        private const val MENU_CAMERA_SETTINGS = 1
        private const val MENU_MARKER = 2
        private const val MENU_OFFLINE_MAPS = 3
        private const val MENU_STOP_ROUTE = 4
        private const val LOCATION_UPDATE_INTERVAL_MS = 250L
        private const val NETWORK_LOCATION_UPDATE_INTERVAL_MS = 1_000L
        private const val MAX_CACHED_LOCATION_AGE_MS = 30L * 60L * 1_000L
        private const val GPS_PREFERENCE_WINDOW_MS = 10_000L
        private const val LOCATION_STALE_NANOS = 5_000_000_000L
        private const val SPEED_SMOOTHING_OLD = 0.65f
        private const val SPEED_SMOOTHING_NEW = 0.35f
        private const val OFF_ROUTE_DISTANCE_METERS = 75.0
        private const val ARRIVAL_DISTANCE_METERS = 15.0
        private const val MIN_REROUTE_INTERVAL_MS = 30_000L
        private const val MIN_SUGGESTION_CHARACTERS = 3
        private const val SUGGESTION_DEBOUNCE_MS = 650L
        private const val CUSTOM_MARKER_MAX_PIXELS = 512
        private const val PREFERENCES_NAME = "speed_camera_preferences"
        private const val CUSTOM_MARKER_URI_KEY = "custom_marker_uri"
        private const val VIDEO_QUALITY_KEY = "video_quality"
        private const val VIDEO_FPS_LOWER_KEY = "video_fps_lower"
        private const val VIDEO_FPS_UPPER_KEY = "video_fps_upper"
        private const val TILE_SIZE = 256
        private const val MIN_TILE_ZOOM = 12
        private const val MAX_TILE_ZOOM = 18
        private const val MAX_TILE_RADIUS = 3
        private const val MAP_TILE_ALPHA = 150
        private const val WEB_MERCATOR_CIRCUMFERENCE_METERS = 40_075_016.686
        private const val OFFLINE_PROGRESS_INTERVAL_MS = 1_000L
    }
}
