package com.example.myapplication.features.ScheduleSharing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ScheduleSharingScreen() {
    val context = LocalContext.current
    val firestore = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    // 🔹 내 암호키 (위치 공유에서 생성된 Firestore 키 사용)
    val sharedPreferences = remember {
        context.getSharedPreferences("location_sharing_prefs", Context.MODE_PRIVATE)
    }
    val generatedKey = sharedPreferences.getString("generated_key", "") ?: ""

    var inputKey by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var schedules by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var hasNotificationPermission by remember { mutableStateOf(false) }

    // 🔔 Android 13 이상 알림 권한 체크 및 요청
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasNotificationPermission = granted
            if (!granted) {
                Toast.makeText(context, "알림 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }

    // 초기 권한 상태 확인
    LaunchedEffect(Unit) {
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 13 미만은 권한 불필요
        }

        // 권한이 없으면 요청
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 🔔 알림 채널 생성 (API 26 이상)
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "schedule_channel",
                "일정 알림",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    // 🔄 Firestore 일정 실시간 감시
    LaunchedEffect(generatedKey, hasNotificationPermission) {
        if (generatedKey.isEmpty()) {
            Toast.makeText(context, "❌ 암호코드가 없습니다. 위치 공유에서 먼저 생성하세요.", Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }

        // 디버깅: 감시 시작 로그
        Toast.makeText(context, "📡 일정 감시 시작: $generatedKey", Toast.LENGTH_SHORT).show()

        firestore.collection("shared_schedules")
            .document(generatedKey)
            .collection("items")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Toast.makeText(context, "❌ 오류: ${e.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                val newList = snapshot?.documents?.mapNotNull { it.data } ?: emptyList()
                schedules = newList

                // 디버깅: 받은 일정 개수
                Toast.makeText(context, "📥 일정 ${newList.size}개 로드됨", Toast.LENGTH_SHORT).show()

                // 새 일정 도착 알림 (권한이 있을 때만)
                if (hasNotificationPermission) {
                    snapshot?.documentChanges?.forEach { change ->
                        if (change.type.name == "ADDED") {
                            val data = change.document.data
                            val t = data["title"]?.toString() ?: "(제목 없음)"
                            val d = data["date"]?.toString() ?: "(날짜 없음)"
                            showNotification(context, "새 일정 도착", "$t ($d)")
                        }
                    }
                }
            }
    }

    // 🧠 UI 구성
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text("📅 일정 공유 (Firestore 기반)", style = MaterialTheme.typography.titleLarge)
            }

            item {
                Text("내 암호코드: $generatedKey", style = MaterialTheme.typography.bodyLarge)
            }

            // 🔔 알림 권한 상태 표시
            if (!hasNotificationPermission) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "⚠️ 알림 권한 필요",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "새 일정 알림을 받으려면 권한을 허용해주세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Button(
                                    onClick = {
                                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Text("권한 요청")
                                }
                            }
                        }
                    }
                }
            }

            // 🔹 일정 전송 UI
            item {
                OutlinedTextField(
                    value = inputKey,
                    onValueChange = { inputKey = it },
                    label = { Text("상대방 암호 입력") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("일정 제목") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("날짜 (예: 2025-11-05)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = {
                        if (inputKey.isEmpty()) {
                            Toast.makeText(context, "상대방 암호를 입력하세요", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (title.isEmpty()) {
                            Toast.makeText(context, "일정 제목을 입력하세요", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (date.isEmpty()) {
                            Toast.makeText(context, "날짜를 입력하세요", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val newSchedule = mapOf(
                            "title" to title,
                            "date" to date,
                            "createdAt" to Timestamp.now()
                        )

                        // 디버깅: 전송 시도
                        Toast.makeText(context, "📤 전송 중... (암호: $inputKey)", Toast.LENGTH_SHORT).show()

                        firestore.collection("shared_schedules")
                            .document(inputKey)
                            .collection("items")
                            .add(newSchedule)
                            .addOnSuccessListener {
                                Toast.makeText(context, "✅ 일정이 공유되었습니다!", Toast.LENGTH_SHORT).show()
                                // 전송 후 입력 필드 초기화
                                title = ""
                                date = ""
                            }
                            .addOnFailureListener { exception ->
                                Toast.makeText(context, "❌ 전송 실패: ${exception.message}", Toast.LENGTH_LONG).show()
                            }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📤 일정 보내기")
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item {
                Text("📥 받은 일정 목록", style = MaterialTheme.typography.titleMedium)
            }

            if (schedules.isEmpty()) {
                item {
                    Text(
                        "받은 일정이 없습니다",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(schedules) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item["title"].toString(), style = MaterialTheme.typography.titleMedium)
                        Text("날짜: ${item["date"]}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// 🔔 알림 표시 함수 (권한 체크 포함)
fun showNotification(context: Context, title: String, message: String) {
    // Android 13 이상에서 권한 확인
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // 권한이 없으면 알림을 보내지 않음
            return
        }
    }

    val channelId = "schedule_channel"
    val builder = NotificationCompat.Builder(context, channelId)
        .setContentTitle(title)
        .setContentText(message)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)

    try {
        NotificationManagerCompat.from(context).notify(
            System.currentTimeMillis().toInt(),
            builder.build()
        )
    } catch (e: SecurityException) {
        // 권한이 없어서 발생하는 예외 처리
        e.printStackTrace()
    }
}