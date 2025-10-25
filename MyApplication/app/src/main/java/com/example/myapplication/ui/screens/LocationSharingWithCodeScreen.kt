package com.example.myapplication.ui.screens

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

    var myLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) } // UI에는 표시하지 않음
    var partnerLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var inputKey by remember { mutableStateOf("") }
    var generatedKey by remember { mutableStateOf("") }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var partnerKeyToWatch by remember { mutableStateOf<String?>(null) }

    // 위치 권한 요청
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // 위치 권한 확인
    LaunchedEffect(Unit) {
        hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // 내 위치 실시간 업데이트 (UI에는 표시하지 않음)
    LaunchedEffect(hasLocationPermission, generatedKey) {
        if (!hasLocationPermission || generatedKey.isEmpty()) return@LaunchedEffect

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

                    // Firebase에 내 위치 업데이트
                    val data = mapOf(
                        "lat" to location.latitude,
                        "lon" to location.longitude,
                        "timestamp" to System.currentTimeMillis()
                    )
                    database.child(generatedKey).setValue(data)
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

    // 앱 실행 시 1회 자동 암호 생성 (영어+숫자+특수문자 12자리)
    LaunchedEffect(Unit) {
        if (generatedKey.isEmpty()) {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*()-_=+"
            val newKey = (1..12).map { chars.random() }.joinToString("")
            generatedKey = newKey
            Toast.makeText(context, "내 암호코드가 자동 생성되었습니다: $newKey", Toast.LENGTH_SHORT).show()
        }
    }

    // 상대방 위치 실시간 감시
    LaunchedEffect(partnerKeyToWatch) {
        if (partnerKeyToWatch.isNullOrEmpty()) return@LaunchedEffect

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

        database.child(partnerKeyToWatch!!).addValueEventListener(listener)
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
                        database.child(inputKey)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(snapshot: DataSnapshot) {
                                    if (snapshot.exists()) {
                                        partnerKeyToWatch = inputKey
                                        Toast.makeText(
                                            context,
                                            "✅ 암호코드 확인 완료! 실시간 추적을 시작합니다.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        // 일치하지 않을 때 메시지
                                        Toast.makeText(
                                            context,
                                            "❌ 암호코드가 일치하지 않습니다.",
                                            Toast.LENGTH_SHORT
                                        ).show()
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
                    } else {
                        Toast.makeText(context, "암호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔍 상대방 위치 추적 시작")
            }
        }

        // 상대방 위치 표시
        partnerLocation?.let { loc ->
            item {
                HorizontalDivider()
                Text("👥 상대방 위치 (실시간)", style = MaterialTheme.typography.titleSmall)
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
        }
    }
}
