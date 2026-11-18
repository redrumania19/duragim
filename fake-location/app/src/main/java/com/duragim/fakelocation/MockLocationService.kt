package com.duragim.fakelocation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.Locale
import kotlin.math.cos
import kotlin.random.Random

/**
 * Seçilen koordinatı saniyede bir sistemin GPS ve ağ sağlayıcılarına,
 * ayrıca Google Play Hizmetleri'nin "fused" sağlayıcısına sahte konum olarak basar.
 */
class MockLocationService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_JITTER = "jitter"
        private const val CHANNEL_ID = "mock"
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 1000L

        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        fun start(ctx: Context, lat: Double, lng: Double, jitter: Boolean) {
            val i = Intent(ctx, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
                .putExtra(EXTRA_JITTER, jitter)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MockLocationService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var lm: LocationManager
    private lateinit var fused: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())

    private var lat = 0.0
    private var lng = 0.0
    private var jitter = false
    private var providersReady = false

    private val tick = object : Runnable {
        override fun run() {
            pushLocation()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_START -> {
                lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                jitter = intent.getBooleanExtra(EXTRA_JITTER, false)
            }
            else -> { shutdown(); return START_NOT_STICKY }
        }

        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Örn. konum izni yokken Android 14+ ön plan servisini reddeder.
            shutdown(); return START_NOT_STICKY
        }

        if (!providersReady && !setupProviders()) {
            MockState.set(MockState.Status.NotMockApp)
            stopForegroundCompat(); stopSelf()
            return START_NOT_STICKY
        }

        handler.removeCallbacks(tick)
        handler.post(tick)
        MockState.set(MockState.Status.Running(lat, lng))
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    /** Test sağlayıcılarını kurar. Uygulama sahte konum uygulaması değilse false döner. */
    @SuppressLint("MissingPermission")
    private fun setupProviders(): Boolean {
        try {
            for (p in PROVIDERS) {
                try { lm.removeTestProvider(p) } catch (_: Exception) { }
                if (Build.VERSION.SDK_INT >= 31) {
                    lm.addTestProvider(
                        p, false, false, false, false, true, true, true,
                        ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    lm.addTestProvider(
                        p, false, false, false, false, true, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                    )
                }
                lm.setTestProviderEnabled(p, true)
            }
        } catch (e: SecurityException) {
            return false
        } catch (e: IllegalArgumentException) {
            // Bazı cihazlarda ağ sağlayıcısı yoktur; GPS yeterli.
        }
        try { fused.setMockMode(true) } catch (_: SecurityException) { }
        providersReady = true
        return true
    }

    @SuppressLint("MissingPermission")
    private fun pushLocation() {
        var la = lat
        var ln = lng
        if (jitter) {
            // ~2 metre civarında rastgele kayma
            val meters = 2.0
            la += (Random.nextDouble() - 0.5) * 2 * meters / 111_320.0
            ln += (Random.nextDouble() - 0.5) * 2 * meters / (111_320.0 * cos(Math.toRadians(lat)))
        }
        for (p in PROVIDERS) {
            try { lm.setTestProviderLocation(p, buildLocation(p, la, ln)) } catch (_: Exception) { }
        }
        try { fused.setMockLocation(buildLocation("fused", la, ln)) } catch (_: Exception) { }
    }

    private fun buildLocation(provider: String, la: Double, ln: Double) = Location(provider).apply {
        latitude = la
        longitude = ln
        altitude = 40.0
        accuracy = if (jitter) 3f + Random.nextFloat() * 4f else 3f
        speed = 0f
        bearing = 0f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= 26) {
            verticalAccuracyMeters = 3f
            speedAccuracyMetersPerSecond = 0.1f
            bearingAccuracyDegrees = 1f
        }
    }

    private fun shutdown() {
        handler.removeCallbacks(tick)
        if (providersReady) {
            for (p in PROVIDERS) { try { lm.removeTestProvider(p) } catch (_: Exception) { } }
            try { fused.setMockMode(false) } catch (_: Exception) { }
            providersReady = false
        }
        if (MockState.status.value is MockState.Status.Running) MockState.set(MockState.Status.Idle)
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (providersReady) {
            for (p in PROVIDERS) { try { lm.removeTestProvider(p) } catch (_: Exception) { } }
            try { fused.setMockMode(false) } catch (_: Exception) { }
        }
        if (MockState.status.value is MockState.Status.Running) MockState.set(MockState.Status.Idle)
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(String.format(Locale.US, "%.6f, %.6f", lat, lng))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }
}
