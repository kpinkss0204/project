package com.example.myapplication.features.LocationSharing

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.example.myapplication.ui.components.KakaoMapViewCompose
import com.example.myapplication.services.LocationTrackingService
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.Build

@Composable
fun LocationSharingWithCodeScreen(
    onPartnerLocationChanged: (Pair<Double, Double>?) -> Unit = {}
) {
    val context = LocalContext.current
    val database = FirebaseDatabase.getInstance().reference.child("shared_locations")
    val firestore = FirebaseFirestore.getInstance()
    val sharedPreferences = remember {
        context.getSharedPreferences("location_sharing_prefs", Context.MODE_PRIVATE)
    }

    var partnerLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var inputKey by remember { mutableStateOf("") }
    var generatedKey by remember {
        mutableStateOf(sharedPreferences.getString("generated_key", "") ?: "")
    }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(true) }
    var partnerKeyToWatch by remember { mutableStateOf<String?>(null) }
    var isBeingTracked by remember { mutableStateOf(false) }
    var isServiceRunning by remember { mutableStateOf(false) }

    // 권한 요청 런처
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    // 권한 체크
    LaunchedEffect(Unit) {
        hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Android 13 이상에서는 알림 권한 필요
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 최초 암호 생성 및 Firestore에 저장
    LaunchedEffect(Unit) {
        if (generatedKey.isEmpty()) {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*()-_=+"
            val newKey = (1..12).map { chars.random() }.joinToString("")
            generatedKey = newKey
            sharedPreferences.edit().putString("generated_key", newKey).apply()

            val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = messageDigest.digest(newKey.toByteArray())
            val docId = hashBytes.joinToString("") { "%02x".format(it) }.take(32)

            val data = hashMapOf(
                "originalCode" to newKey,
                "docId" to docId,
                "createdAt" to Timestamp.now()
            )
            firestore.collection("location_keys")
                .document(docId)
                .set(data)
                .addOnSuccessListener {
                    android.util.Log.d("LocationSharing", "✅ 저장 성공 - 원본: $newKey, 문서ID: $docId")
                }
                .addOnFailureListener { exception ->
                    android.util.Log.e("LocationSharing", "❌ 저장 실패", exception)
                    Toast.makeText(context, "암호코드 저장 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // ⭐ Firebase tracking_requests 감시 - 누군가 나를 추적하면 Foreground Service 시작
    DisposableEffect(generatedKey, hasLocationPermission, hasNotificationPermission) {
        if (generatedKey.isEmpty()) return@DisposableEffect onDispose {}

        val trackingRequestRef = FirebaseDatabase.getInstance()
            .reference.child("tracking_requests").child(generatedKey)

        val requestListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val wasTracked = isBeingTracked
                isBeingTracked = snapshot.exists()

                if (isBeingTracked && !wasTracked) {
                    android.util.Log.d("LocationSharing", "🔔 누군가 나를 추적하기 시작!")

                    // 권한 확인
                    if (!hasLocationPermission) {
                        Toast.makeText(context, "위치 권한이 필요합니다", Toast.LENGTH_SHORT).show()
                        return
                    }

                    // Android 13+ 알림 권한 확인
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                        Toast.makeText(context, "알림 권한이 필요합니다", Toast.LENGTH_SHORT).show()
                        return
                    }

                    // Foreground Service 시작
                    LocationTrackingService.startService(context, generatedKey)
                    isServiceRunning = true
                    Toast.makeText(context, "🔔 백그라운드 위치 공유 시작", Toast.LENGTH_SHORT).show()

                } else if (!isBeingTracked && wasTracked) {
                    android.util.Log.d("LocationSharing", "🔕 추적 중단됨")

                    // Foreground Service 중단
                    LocationTrackingService.stopService(context)
                    isServiceRunning = false
                    Toast.makeText(context, "추적이 중단되었습니다", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                isBeingTracked = false
                LocationTrackingService.stopService(context)
                isServiceRunning = false
            }
        }

        trackingRequestRef.addValueEventListener(requestListener)

        onDispose {
            trackingRequestRef.removeEventListener(requestListener)
        }
    }

    // ⭐ P2가 P1의 위치를 실시간으로 받기
    DisposableEffect(partnerKeyToWatch) {
        val watchKey = partnerKeyToWatch
        if (watchKey.isNullOrEmpty()) return@DisposableEffect onDispose {}

        android.util.Log.d("LocationSharing", "👀 상대방 위치 감시 시작: $watchKey")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val lat = snapshot.child("lat").getValue(Double::class.java)
                    val lon = snapshot.child("lon").getValue(Double::class.java)
                    if (lat != null && lon != null) {
                        partnerLocation = lat to lon
                        onPartnerLocationChanged(lat to lon)
                        android.util.Log.d("LocationSharing", "📍 상대방 위치 수신: $lat, $lon")
                    }
                } else {
                    android.util.Log.d("LocationSharing", "⚠️ 상대방 위치 데이터 없음")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                partnerLocation = null
                android.util.Log.e("LocationSharing", "❌ 상대방 위치 수신 실패", error.toException())
            }
        }

        database.child(watchKey).addValueEventListener(listener)

        onDispose {
            android.util.Log.d("LocationSharing", "🛑 상대방 위치 감시 중단: $watchKey")
            database.child(watchKey).removeEventListener(listener)
            partnerLocation = null
        }
    }

    // ⭐ 앱 백그라운드/화면 전환/다른 페이지 이동 시 추적 자동 중단 (P2가 P1을 추적하는 경우)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_PAUSE -> {
                    android.util.Log.d("LocationSharing", "🔄 화면 이탈 감지 - P2 추적 중단")

                    // P2가 P1을 추적 중이었다면 tracking_requests 삭제
                    partnerKeyToWatch?.let { watchKey ->
                        FirebaseDatabase.getInstance()
                            .reference.child("tracking_requests")
                            .child(watchKey)
                            .removeValue()
                            .addOnSuccessListener {
                                android.util.Log.d("LocationSharing", "✅ tracking_requests 삭제 완료")
                            }
                    }

                    // P2 상태 초기화 (P1의 서비스는 계속 실행)
                    partnerKeyToWatch = null
                    partnerLocation = null
                    inputKey = ""
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)

            // 완전히 종료될 때 P2의 추적 중단
            partnerKeyToWatch?.let { watchKey ->
                FirebaseDatabase.getInstance()
                    .reference.child("tracking_requests")
                    .child(watchKey)
                    .removeValue()
            }
        }
    }

    // UI 구성
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("📍 위치 공유 (백그라운드 추적)", style = MaterialTheme.typography.titleMedium)
        }

        // 권한 요청 카드
        if (!hasLocationPermission) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "⚠️ 위치 권한이 필요합니다.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        ) {
                            Text("위치 권한 요청")
                        }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "⚠️ 알림 권한이 필요합니다 (백그라운드 추적용)",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        ) {
                            Text("알림 권한 요청")
                        }
                    }
                }
            }
        }

        // 백그라운드 추적 상태 표시
        if (isBeingTracked && isServiceRunning) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "🔔 백그라운드에서 위치 공유 중",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "앱을 종료해도 위치가 계속 공유됩니다",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("내 고유 암호코드", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (generatedKey.isNotEmpty()) generatedKey else "(생성 중...)",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "이 코드를 상대방에게 공유하세요",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    if (generatedKey.isNotEmpty()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("암호코드", generatedKey))
                        Toast.makeText(context, "클립보드에 복사되었습니다", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = generatedKey.isNotEmpty()
            ) {
                Text("📋 클립보드에 복사")
            }
        }

        // 수동으로 서비스 중단 (테스트용)
        if (isServiceRunning) {
            item {
                OutlinedButton(
                    onClick = {
                        LocationTrackingService.stopService(context)
                        isServiceRunning = false
                        Toast.makeText(context, "백그라운드 추적을 수동으로 중단했습니다", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("🛑 백그라운드 추적 중단 (수동)")
                }
            }
        }

        item { HorizontalDivider() }

        item {
            Text("상대방 추적하기", style = MaterialTheme.typography.titleSmall)
        }

        item {
            OutlinedTextField(
                value = inputKey,
                onValueChange = { inputKey = it },
                label = { Text("상대방 암호 입력") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = partnerKeyToWatch == null
            )
        }

        item {
            Button(
                onClick = {
                    if (inputKey.isNotEmpty()) {
                        // tracking_requests에 추적 요청 등록
                        val trackingRequestRef = FirebaseDatabase.getInstance()
                            .reference.child("tracking_requests").child(inputKey)

                        trackingRequestRef.setValue(true)
                            .addOnSuccessListener {
                                partnerKeyToWatch = inputKey
                                Toast.makeText(context, "✅ 추적 시작", Toast.LENGTH_SHORT).show()
                                android.util.Log.d("LocationSharing", "✅ 추적 시작: $inputKey")
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(context, "추적 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                                android.util.Log.e("LocationSharing", "❌ 추적 시작 실패", e)
                            }
                    } else {
                        Toast.makeText(context, "암호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = partnerKeyToWatch == null && inputKey.isNotEmpty()
            ) {
                Text("🔍 상대방 위치 추적 시작")
            }
        }

        if (partnerKeyToWatch != null) {
            item {
                OutlinedButton(
                    onClick = {
                        val watchKey = partnerKeyToWatch
                        if (watchKey != null) {
                            // tracking_requests에서 삭제
                            val trackingRequestRef = FirebaseDatabase.getInstance()
                                .reference.child("tracking_requests").child(watchKey)

                            trackingRequestRef.removeValue()
                                .addOnSuccessListener {
                                    partnerKeyToWatch = null
                                    partnerLocation = null
                                    inputKey = ""
                                    Toast.makeText(context, "추적을 중단했습니다.", Toast.LENGTH_SHORT).show()
                                    android.util.Log.d("LocationSharing", "🛑 추적 중단: $watchKey")
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("⏹️ 추적 중단")
                }
            }
        }

        // 상대방 위치 지도 표시
        if (partnerKeyToWatch != null) {
            item { HorizontalDivider() }
            item {
                Text(
                    "👥 상대방 위치 추적 중",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            if (partnerLocation != null) {
                item {
                    Text(
                        "위도: ${String.format("%.6f", partnerLocation!!.first)}, 경도: ${String.format("%.6f", partnerLocation!!.second)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    KakaoMapViewCompose(
                        lat = partnerLocation!!.first,
                        lon = partnerLocation!!.second,
                        zoom = 15,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    )
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(
                            "⏳ 상대방의 위치 정보를 기다리는 중...",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}