package com.example.myapplication.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.KakaoMapViewCompose
import kotlin.random.Random

@Composable
fun LocationSharingWithCodeScreen(
    locationState: Pair<Double, Double>?,
    inputKeyState: String,
    onInputChange: (String) -> Unit,
    p2LocationState: Pair<Double, Double>?,
    onLocationConfirmed: (Pair<Double, Double>) -> Unit,
    generatedKeyState: String,
    onGenerateKey: (String) -> Unit
) {
    var inputKey by remember { mutableStateOf(inputKeyState) }
    val location = locationState ?: 37.5665 to 126.9780
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("위치 공유 및 암호코드 생성", style = MaterialTheme.typography.titleMedium)
        }

        item {
            Button(onClick = { onGenerateKey((1..10).map { Random.nextInt(0, 10) }.joinToString("")) }) {
                Text("난수 암호 생성")
            }
        }

        item { Text("생성된 암호코드: $generatedKeyState") }

        item {
            Button(onClick = {
                if (generatedKeyState.isNotEmpty()) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("암호코드", generatedKeyState))
                    Toast.makeText(context, "암호코드가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }) { Text("클립보드에 복사") }
        }

        item {
            OutlinedTextField(
                value = inputKey,
                onValueChange = { inputKey = it; onInputChange(it) },
                label = { Text("P2 암호 입력") }
            )
        }

        item {
            Button(onClick = {
                if (inputKey == generatedKeyState) {
                    onLocationConfirmed(location)
                } else {
                    Toast.makeText(context, "암호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                }
            }) { Text("위치 확인") }
        }

        item { Text("현재 위치 (지도 표시)") }

        item {
            KakaoMapViewCompose(
                lat = location.first,
                lon = location.second,
                zoom = 15,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )
        }

        p2LocationState?.let { loc ->
            item { Text("P1 위치 확인: Lat ${loc.first}, Lon ${loc.second}") }
        }
    }
}
