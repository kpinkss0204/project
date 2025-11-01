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
import com.google.android.gms.location.*
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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

    var myLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var partnerLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var inputKey by remember { mutableStateOf("") }
    var generatedKey by remember {
        mutableStateOf(sharedPreferences.getString("generated_key", "") ?: "")
    }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var partnerKeyToWatch by remember { mutableStateOf<String?>(null) }
    var isBeingTracked by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    // 최초 암호 생성 및 Firestore에 저장
    LaunchedEffect(Unit) {
        if (generatedKey.isEmpty()) {
            // 특수문자 포함 암호 생성
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*()-_=+"
            val newKey = (1..12).map { chars.random() }.joinToString("")
            generatedKey = newKey
            sharedPreferences.edit().putString("generated_key", newKey).apply()

            // SHA-256 해시를 사용하여 안전한 문서 ID 생성
            val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = messageDigest.digest(newKey.toByteArray())
            val docId = hashBytes.joinToString("") { "%02x".format(it) }.take(32)

            // Firestore에 저장 (문서 ID는 해시값, 내부에 원본 코드 저장)
            val data = hashMapOf(
                "originalCode" to newKey,  // 원본 특수문자 포함 코드
                "docId" to docId,           // 해시된 문서 ID
                "createdAt" to Timestamp.now()
            )
            firestore.collection("location_keys")
                .document(docId)
                .set(data)
                .addOnSuccessListener {
                    android.util.Log.d("LocationSharing", "✅ 저장 성공 - 원본: $newKey, 문서ID: $docId")
                    Toast.makeText(context, "내 암호코드가 생성되었습니다: $newKey", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { exception ->
                    android.util.Log.e("LocationSharing", "❌ 저장 실패", exception)
                    Toast.makeText(context, "암호코드 저장 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // 내 위치 실시간 업데이트 (isBeingTracked == true일 때만 Firebase 업로드)
    LaunchedEffect(hasLocationPermission, generatedKey, isBeingTracked) {
        if (!hasLocationPermission || generatedKey.isEmpty()) return@LaunchedEffect

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L
        ).build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    myLocation = location.latitude to location.longitude

                    if (isBeingTracked) {
                        val data = mapOf(
                            "lat" to location.latitude,
                            "lon" to location.longitude,
                            "timestamp" to System.currentTimeMillis()
                        )
                        database.child(generatedKey).setValue(data)
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                null
            )
        } catch (e: SecurityException) {
            Toast.makeText(context, "위치 권한 오류: ${e.message}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "위치 업데이트 오류: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Firebase tracking_requests 감시 (누군가 내 암호를 입력하면 isBeingTracked = true)
    DisposableEffect(generatedKey) {
        if (generatedKey.isEmpty()) return@DisposableEffect onDispose {}

        val trackingRequestRef = FirebaseDatabase.getInstance()
            .reference.child("tracking_requests").child(generatedKey)

        val requestListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                isBeingTracked = snapshot.exists()
            }

            override fun onCancelled(error: DatabaseError) {
                isBeingTracked = false
            }
        }

        trackingRequestRef.addValueEventListener(requestListener)

        onDispose {
            trackingRequestRef.removeEventListener(requestListener)
            isBeingTracked = false
        }
    }

    // 상대방 위치 실시간 감시 (UI에만 표시, 저장하지 않음)
    DisposableEffect(partnerKeyToWatch) {
        val watchKey = partnerKeyToWatch
        if (watchKey.isNullOrEmpty()) return@DisposableEffect onDispose {}

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val lat = snapshot.child("lat").getValue(Double::class.java)
                    val lon = snapshot.child("lon").getValue(Double::class.java)
                    if (lat != null && lon != null) {
                        partnerLocation = lat to lon
                        onPartnerLocationChanged(lat to lon)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                partnerLocation = null
            }
        }

        database.child(watchKey).addValueEventListener(listener)

        onDispose {
            database.child(watchKey).removeEventListener(listener)
            partnerLocation = null
        }
    }

    // LifecycleObserver로 앱 백그라운드/화면 전환 시 추적 자동 중단
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                partnerKeyToWatch?.let { watchKey ->
                    FirebaseDatabase.getInstance()
                        .reference.child("tracking_requests")
                        .child(watchKey)
                        .removeValue()
                }
                partnerKeyToWatch = null
                partnerLocation = null
                inputKey = ""
                isBeingTracked = false
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
            Text("📍 위치 공유 (실시간 추적)", style = MaterialTheme.typography.titleMedium)
        }

        if (!hasLocationPermission) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "⚠️ 위치 권한이 필요합니다. 설정에서 허용해주세요.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("내 고유 암호코드", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (generatedKey.isNotEmpty()) generatedKey else "(생성 중...)",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📋 클립보드에 복사")
            }
        }

        item { HorizontalDivider() }

        item {
            OutlinedTextField(
                value = inputKey,
                onValueChange = { inputKey = it },
                label = { Text("상대방 암호 입력") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        item {
            Button(
                onClick = {
                    if (inputKey.isNotEmpty()) {
                        val trackingRequestRef = FirebaseDatabase.getInstance()
                            .reference.child("tracking_requests").child(inputKey)
                        trackingRequestRef.setValue(true)
                        partnerKeyToWatch = inputKey
                        Toast.makeText(context, "✅ 추적 시작", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "암호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
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
                            val trackingRequestRef = FirebaseDatabase.getInstance()
                                .reference.child("tracking_requests").child(watchKey)
                            trackingRequestRef.removeValue()

                            partnerKeyToWatch = null
                            partnerLocation = null
                            inputKey = ""
                            isBeingTracked = false
                            Toast.makeText(context, "추적을 중단했습니다.", Toast.LENGTH_SHORT).show()
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
        if (partnerKeyToWatch != null && partnerLocation != null) {
            item { HorizontalDivider() }
            item { Text("👥 상대방 위치 추적 중", style = MaterialTheme.typography.titleSmall) }
            item { Text("위도: ${partnerLocation!!.first}, 경도: ${partnerLocation!!.second}") }
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
        }
    }
}