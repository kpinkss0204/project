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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

data class Schedule(
    val id: String,
    val title: String,
    val date: String,
    val time: String,
    val senderKey: String
)

@Composable
fun ScheduleListScreen() {
    val context = LocalContext.current
    val firestore = FirebaseFirestore.getInstance()

    // 내 암호키
    val sharedPreferences = remember {
        context.getSharedPreferences("location_sharing_prefs", Context.MODE_PRIVATE)
    }
    val generatedKey = sharedPreferences.getString("generated_key", "") ?: ""

    var schedules by remember { mutableStateOf<List<Schedule>>(emptyList()) }
    var hasNotificationPermission by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<Schedule?>(null) }
    var isDeleting by remember { mutableStateOf(false) }

    // 알림 권한 요청 런처
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
            true
        }

        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 알림 채널 생성
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

    // Firestore 일정 실시간 감시
    LaunchedEffect(generatedKey, hasNotificationPermission) {
        if (generatedKey.isEmpty()) {
            Toast.makeText(context, "❌ 암호코드가 없습니다. 위치 공유에서 먼저 생성하세요.", Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }

        firestore.collection("shared_schedules")
            .document(generatedKey)
            .collection("items")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Toast.makeText(context, "❌ 오류: ${e.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                val newList = snapshot?.documents?.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    Schedule(
                        id = doc.id,
                        title = data["title"]?.toString() ?: "",
                        date = data["date"]?.toString() ?: "",
                        time = data["time"]?.toString() ?: "",
                        senderKey = data["senderKey"]?.toString() ?: ""
                    )
                } ?: emptyList()

                schedules = newList

                // 새 일정 도착 알림
                if (hasNotificationPermission) {
                    snapshot?.documentChanges?.forEach { change ->
                        if (change.type.name == "ADDED") {
                            val data = change.document.data
                            val t = data["title"]?.toString() ?: "(제목 없음)"
                            val d = data["date"]?.toString() ?: ""
                            val time = data["time"]?.toString() ?: ""
                            val dateTimeStr = if (time.isNotEmpty()) "$d $time" else d
                            showNotification(context, "📅 새 일정 도착", "$t\n$dateTimeStr")
                        }
                    }
                }
            }
    }

    // 삭제 확인 다이얼로그
    showDeleteDialog?.let { schedule ->
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = null },
            title = { Text("일정 삭제") },
            text = { Text("'${schedule.title}' 일정을 삭제하시겠습니까?") },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleting = true
                        firestore.collection("shared_schedules")
                            .document(generatedKey)
                            .collection("items")
                            .document(schedule.id)
                            .delete()
                            .addOnSuccessListener {
                                isDeleting = false
                                showDeleteDialog = null
                                Toast.makeText(context, "✅ 일정이 삭제되었습니다", Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener { exception ->
                                isDeleting = false
                                Toast.makeText(context, "❌ 삭제 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
                            }
                    },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        Text("삭제")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = null },
                    enabled = !isDeleting
                ) {
                    Text("취소")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("📥 받은 일정 목록", style = MaterialTheme.typography.titleLarge)
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "내 암호코드",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        generatedKey.ifEmpty { "(생성 안됨)" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // 알림 권한 상태 표시
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

        item { HorizontalDivider() }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "전체 ${schedules.size}개",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        if (schedules.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "받은 일정이 없습니다",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(schedules) { schedule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            schedule.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "📅 ${schedule.date}${if (schedule.time.isNotEmpty()) " ${schedule.time}" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    IconButton(
                        onClick = { showDeleteDialog = schedule }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "삭제",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// 알림 표시 함수
fun showNotification(context: Context, title: String, message: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))

    try {
        NotificationManagerCompat.from(context).notify(
            System.currentTimeMillis().toInt(),
            builder.build()
        )
    } catch (e: SecurityException) {
        e.printStackTrace()
    }
}