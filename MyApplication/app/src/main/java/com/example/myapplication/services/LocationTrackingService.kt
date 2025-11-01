package com.example.myapplication.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase
import com.example.myapplication.R

class LocationTrackingService : Service() {

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var generatedKey: String? = null
    private val database = FirebaseDatabase.getInstance().reference.child("shared_locations")

    companion object {
        private const val CHANNEL_ID = "location_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_GENERATED_KEY = "generated_key"
        const val ACTION_STOP_SERVICE = "com.example.myapplication.STOP_TRACKING"

        fun startService(context: Context, generatedKey: String) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                putExtra(EXTRA_GENERATED_KEY, generatedKey)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        generatedKey = intent?.getStringExtra(EXTRA_GENERATED_KEY)

        if (generatedKey == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Foreground Service 시작
        startForeground(NOTIFICATION_ID, createNotification())

        // 위치 업데이트 시작
        startLocationUpdates()

        return START_STICKY
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L // 10초마다
        ).apply {
            setMinUpdateIntervalMillis(5000L) // 최소 5초
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val key = generatedKey ?: return

                    val data = mapOf(
                        "lat" to location.latitude,
                        "lon" to location.longitude,
                        "timestamp" to System.currentTimeMillis()
                    )

                    // Firebase에 위치 저장
                    database.child(key).setValue(data)
                        .addOnSuccessListener {
                            android.util.Log.d("LocationService",
                                "✅ 백그라운드 위치 저장: ${location.latitude}, ${location.longitude}")

                            // 알림 업데이트
                            updateNotification(location.latitude, location.longitude)
                        }
                        .addOnFailureListener { e ->
                            android.util.Log.e("LocationService", "❌ 위치 저장 실패", e)
                        }
                }
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            android.util.Log.d("LocationService", "🚀 백그라운드 위치 추적 시작")
        } catch (e: SecurityException) {
            android.util.Log.e("LocationService", "❌ 위치 권한 없음", e)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "위치 추적 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "실시간 위치 공유 중"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // 알림 클릭 시 앱 실행
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 중단 버튼
        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 위치 공유 중")
            .setContentText("누군가 내 위치를 추적하고 있습니다")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "중단",
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(lat: Double, lon: Double) {
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 위치 공유 중")
            .setContentText("최근 위치: ${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "중단",
                stopPendingIntent
            )
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()

        // 위치 업데이트 중단
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }

        android.util.Log.d("LocationService", "🛑 백그라운드 위치 추적 중단")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}