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
import com.example.myapplication.ui.components.KakaoMapViewCompose
import com.google.android.gms.location.*
import com.google.firebase.database.*

@Composable
fun LocationSharingWithCodeScreen(
    onPartnerLocationChanged: (Pair<Double, Double>?) -> Unit = {}
) {
    val context = LocalContext.current
    val database = FirebaseDatabase.getInstance().reference.child("shared_locations")

    // SharedPreferences에서 저장된 암호코드 불러오기
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
    var isBeingTracked by remember { mutableStateOf(false) } // 누군가 나를 추적 중인지 여부

    // 위치 권한 요청
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // 위치 권한 확인
    LaunchedEffect(Unit) {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        hasLocationPermission = hasFineLocation || hasCoarseLocation

        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // 내 위치 실시간 업데이트 (추적 중일 때만 Firebase에 저장)
    LaunchedEffect(hasLocationPermission, generatedKey, isBeingTracked) {
        if (!hasLocationPermission || generatedKey.isEmpty()) return@LaunchedEffect

        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) {
            Toast.makeText(context, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L
        ).build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val newLocation = location.latitude to location.longitude
                    myLocation = newLocation

                    // 누군가 나를 추적 중일 때만 Firebase에 저장
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

    // 앱 최초 실행 시 1회만 암호 생성 (SharedPreferences에 저장)
    LaunchedEffect(Unit) {
        if (generatedKey.isEmpty()) {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*()-_=+"
            val newKey = (1..12).map { chars.random() }.joinToString("")
            generatedKey = newKey

            // SharedPreferences에 저장
            sharedPreferences.edit().putString("generated_key", newKey).apply()

            Toast.makeText(context, "내 암호코드가 자동 생성되었습니다: $newKey", Toast.LENGTH_SHORT).show()
        }
    }

    // 앱 종료 시 cleanup 처리
    DisposableEffect(Unit) {
        onDispose {
            // 현재 저장된 암호코드 확인
            val currentKey = sharedPreferences.getString("generated_key", "") ?: ""

            // SharedPreferences가 비어있다면 (앱 데이터 삭제 예정) Firebase에서도 삭제
            // 실제로는 앱이 완전히 종료되기 전에 확인이 어려우므로,
            // 다음 실행 시 이전 키가 있었는지 체크하는 방식 사용
        }
    }

    // 앱 실행 시 이전 세션의 암호코드 정리
    LaunchedEffect(Unit) {
        val lastSessionKey = sharedPreferences.getString("last_session_key", "") ?: ""
        val currentKey = sharedPreferences.getString("generated_key", "") ?: ""

        // 이전 세션의 키가 있었는데 현재 키와 다르다면 (앱 데이터 삭제 후 재설치)
        // 또는 현재 키가 비어있다면 이전 키 삭제
        if (lastSessionKey.isNotEmpty() && lastSessionKey != currentKey) {
            database.child(lastSessionKey).removeValue()
                .addOnSuccessListener {
                    // Firebase에서 이전 키 삭제 성공
                }
                .addOnFailureListener {
                    // 삭제 실패 (이미 없거나 네트워크 오류)
                }
        }

        // 현재 키를 last_session_key로 저장
        if (currentKey.isNotEmpty()) {
            sharedPreferences.edit().putString("last_session_key", currentKey).apply()
        }
    }

    // 누군가 내 암호코드로 위치를 조회하는지 실시간 감시
    LaunchedEffect(generatedKey) {
        if (generatedKey.isEmpty()) return@LaunchedEffect

        // tracking_requests 노드에서 내 키에 대한 요청 감시
        val trackingRequestRef = FirebaseDatabase.getInstance()
            .reference.child("tracking_requests").child(generatedKey)

        val requestListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val wasBeingTracked = isBeingTracked
                // 누군가 나를 추적 중이면 데이터가 존재함
                isBeingTracked = snapshot.exists()

                // 추적이 시작되었을 때 즉시 현재 위치를 Firebase에 저장
                if (isBeingTracked && !wasBeingTracked && myLocation != null) {
                    val data = mapOf(
                        "lat" to myLocation!!.first,
                        "lon" to myLocation!!.second,
                        "timestamp" to System.currentTimeMillis()
                    )
                    database.child(generatedKey).setValue(data)
                    Toast.makeText(context, "위치 공유가 시작되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                isBeingTracked = false
            }
        }

        trackingRequestRef.addValueEventListener(requestListener)
    }

    // 상대방 위치 실시간 감시
    LaunchedEffect(partnerKeyToWatch) {
        val watchKey = partnerKeyToWatch
        if (watchKey.isNullOrEmpty()) return@LaunchedEffect

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val lat = snapshot.child("lat").getValue(Double::class.java)
                    val lon = snapshot.child("lon").getValue(Double::class.java)
                    if (lat != null && lon != null) {
                        val newPartnerLocation = lat to lon
                        partnerLocation = newPartnerLocation
                        onPartnerLocationChanged(newPartnerLocation)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "Firebase 오류: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }

        database.child(watchKey).addValueEventListener(listener)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 제목
        item {
            Text("📍 위치 공유 (실시간 추적)", style = MaterialTheme.typography.titleMedium)
        }

        // 권한 상태 표시
        if (!hasLocationPermission) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "⚠️ 위치 권한이 필요합니다. 설정에서 권한을 허용해주세요.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // 생성된 암호 표시
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

        // 클립보드 복사
        item {
            Button(
                onClick = {
                    if (generatedKey.isNotEmpty()) {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("암호코드", generatedKey))
                        Toast.makeText(context, "암호코드가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📋 클립보드에 복사")
            }
        }

        // 구분선
        item { HorizontalDivider() }

        // 상대방 암호 입력
        item {
            OutlinedTextField(
                value = inputKey,
                onValueChange = { inputKey = it },
                label = { Text("상대방 암호 입력") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // 상대방 위치 추적 시작
        item {
            Button(
                onClick = {
                    if (inputKey.isNotEmpty()) {
                        // tracking_requests에 추적 요청 먼저 등록
                        val trackingRequestRef = FirebaseDatabase.getInstance()
                            .reference.child("tracking_requests").child(inputKey)
                        trackingRequestRef.setValue(true)
                            .addOnSuccessListener {
                                // 추적 요청 등록 후 데이터 확인
                                database.child(inputKey)
                                    .addListenerForSingleValueEvent(object : ValueEventListener {
                                        override fun onDataChange(snapshot: DataSnapshot) {
                                            val lat = snapshot.child("lat").getValue(Double::class.java)
                                            val lon = snapshot.child("lon").getValue(Double::class.java)

                                            if (lat != null && lon != null) {
                                                // 이미 위치 데이터가 있는 경우
                                                partnerKeyToWatch = inputKey
                                                partnerLocation = lat to lon
                                                onPartnerLocationChanged(lat to lon)
                                                Toast.makeText(
                                                    context,
                                                    "✅ 암호코드 확인 완료! 실시간 추적을 시작합니다.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                // 위치 데이터가 아직 없는 경우 - 암호코드 존재 여부 확인
                                                // timestamp가 없으면 한 번도 생성된 적 없는 키
                                                if (!snapshot.hasChild("timestamp") && !snapshot.exists()) {
                                                    // tracking_requests 제거
                                                    trackingRequestRef.removeValue()
                                                    Toast.makeText(
                                                        context,
                                                        "❌ 암호코드가 일치하지 않습니다. 다시 확인해주세요.",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    partnerKeyToWatch = inputKey
                                                    Toast.makeText(
                                                        context,
                                                        "✅ 추적 시작! 상대방의 위치 업데이트를 기다리는 중...",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }

                                        override fun onCancelled(error: DatabaseError) {
                                            Toast.makeText(
                                                context,
                                                "Firebase 오류: ${error.message}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    })
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(
                                    context,
                                    "추적 요청 실패: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    } else {
                        Toast.makeText(context, "암호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔍 상대방 위치 추적 시작")
            }
        }

        // 추적 중단 버튼 추가
        if (partnerKeyToWatch != null) {
            item {
                OutlinedButton(
                    onClick = {
                        val watchKey = partnerKeyToWatch
                        if (watchKey != null) {
                            // tracking_requests에서 추적 요청 제거
                            val trackingRequestRef = FirebaseDatabase.getInstance()
                                .reference.child("tracking_requests").child(watchKey)
                            trackingRequestRef.removeValue()

                            partnerKeyToWatch = null
                            partnerLocation = null
                            inputKey = ""

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

        // 내 위치 표시 (추적당하고 있을 때)
        if (isBeingTracked && myLocation != null) {
            item {
                HorizontalDivider()
                Text("📍 내 현재 위치 (공유 중)", style = MaterialTheme.typography.titleSmall)
            }

            item {
                Text("위도: ${myLocation!!.first}, 경도: ${myLocation!!.second}")
            }

            item {
                KakaoMapViewCompose(
                    lat = myLocation!!.first,
                    lon = myLocation!!.second,
                    zoom = 15,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                )
            }
        }

        // 상대방 위치 표시 - 추적 중일 때만 표시
        if (partnerKeyToWatch != null) {
            item {
                HorizontalDivider()
                Text("👥 상대방 위치 추적 중", style = MaterialTheme.typography.titleSmall)
            }

            partnerLocation?.let { loc ->
                item {
                    Text("위도: ${loc.first}, 경도: ${loc.second}")
                }

                item {
                    KakaoMapViewCompose(
                        lat = loc.first,
                        lon = loc.second,
                        zoom = 15,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    )
                }
            } ?: run {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            "⏳ 상대방의 위치 정보를 기다리는 중입니다...\n상대방이 위치 권한을 허용하고 앱을 실행해야 합니다.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // 안내 메시지
        item {
            HorizontalDivider()
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isBeingTracked)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (isBeingTracked) {
                        Text(
                            "🟢 추적 활성화",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "누군가 당신의 위치를 추적 중입니다. GPS 위치가 Firebase에 저장되고 있습니다.",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Text(
                            "ℹ️ 추적 대기 중",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "암호코드는 앱 설치 시 자동으로 생성됩니다. 누군가 당신의 암호코드를 입력하면 위치 공유가 시작됩니다.",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}