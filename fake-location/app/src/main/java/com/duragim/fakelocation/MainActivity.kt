package com.duragim.fakelocation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.duragim.fakelocation.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var marker: Marker? = null
    private var selected: GeoPoint? = null
    private val prefs by lazy { getSharedPreferences("fake", MODE_PRIVATE) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val ok = res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true || hasLocationPermission()
        if (ok) startMock() else toast(R.string.need_perm)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, prefs)
            userAgentValue = packageName
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupMap()

        b.jitterSwitch.isChecked = prefs.getBoolean("jitter", true)
        b.jitterSwitch.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("jitter", v).apply() }

        b.goButton.setOnClickListener { applyTypedCoords() }
        b.coordInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { applyTypedCoords(); true } else false
        }

        b.toggleButton.setOnClickListener {
            if (MockState.status.value is MockState.Status.Running) MockLocationService.stop(this)
            else ensurePermissionsThenStart()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MockState.status.collect { render(it) }
            }
        }

        // Son seçilen konumu geri yükle
        if (prefs.contains("lat")) {
            select(GeoPoint(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lng", 0f).toDouble()), center = true)
        }
        handleGeoIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleGeoIntent(intent)
    }

    override fun onResume() { super.onResume(); b.map.onResume() }
    override fun onPause() { b.map.onPause(); super.onPause() }

    private fun setupMap() = with(b.map) {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        controller.setZoom(12.0)
        controller.setCenter(GeoPoint(41.0082, 28.9784)) // İstanbul
        overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { select(p); return true }
            override fun longPressHelper(p: GeoPoint): Boolean { select(p); return true }
        }))
    }

    private fun select(p: GeoPoint, center: Boolean = false) {
        selected = p
        prefs.edit().putFloat("lat", p.latitude.toFloat()).putFloat("lng", p.longitude.toFloat()).apply()

        val m = marker ?: Marker(b.map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.icon = ContextCompat.getDrawable(this, R.drawable.ic_pin)
            it.setInfoWindow(null)
            b.map.overlays.add(it)
            marker = it
        }
        m.position = p
        b.map.invalidate()
        if (center) { b.map.controller.setZoom(16.0); b.map.controller.setCenter(p) }

        b.coordLabel.text = fmt(p)
        b.toggleButton.isEnabled = true

        // Çalışırken yeni nokta seçilirse anında oraya taşı
        if (MockState.status.value is MockState.Status.Running) startMock()
    }

    private fun applyTypedCoords() {
        val p = parseCoords(b.coordInput.text?.toString().orEmpty())
        if (p == null) { toast(R.string.bad_coord); return }
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(b.coordInput.windowToken, 0)
        select(p, center = true)
    }

    private fun handleGeoIntent(i: Intent?) {
        val data: Uri = i?.data ?: return
        if (data.scheme != "geo") return
        // geo:lat,lng  veya  geo:0,0?q=lat,lng(etiket)
        val raw = data.schemeSpecificPart
        val p = parseCoords(raw.substringBefore('?')).takeIf { it != null && (it.latitude != 0.0 || it.longitude != 0.0) }
            ?: data.getQueryParameter("q")?.let { parseCoords(it.substringBefore('(')) }
        if (p != null) select(p, center = true)
    }

    private fun parseCoords(s: String): GeoPoint? {
        val m = Regex("""(-?\d+(?:[.]\d+)?)\s*[,; ]\s*(-?\d+(?:[.]\d+)?)""").find(s) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return GeoPoint(lat, lng)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf<String>()
        if (!hasLocationPermission()) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) startMock() else permLauncher.launch(needed.toTypedArray())
    }

    private fun startMock() {
        val p = selected ?: return
        MockLocationService.start(this, p.latitude, p.longitude, b.jitterSwitch.isChecked)
    }

    private fun render(s: MockState.Status) {
        val running = s is MockState.Status.Running
        b.statusText.setText(if (running) R.string.running else R.string.idle)
        b.statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.green else R.color.red)
        )
        b.toggleButton.setText(if (running) R.string.stop else R.string.start)
        b.toggleButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.red else R.color.navy)
        )
        if (s is MockState.Status.NotMockApp) {
            MockState.set(MockState.Status.Idle)
            showNotMockDialog()
        }
    }

    private fun showNotMockDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.not_mock_title)
            .setMessage(R.string.not_mock_msg)
            .setPositiveButton(R.string.open_dev) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun fmt(p: GeoPoint) = String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
