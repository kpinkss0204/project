// 파일 경로: app/src/main/java/com/example/myapplication/MainActivity.kt
package com.example.myapplication

import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.myapplication.features.CameraScreen
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val CAMERA_PERMISSION_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        } else {
            setContent {
                MyApplicationTheme { AppContent() }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            setContent {
                MyApplicationTheme { AppContent() }
            }
        } else {
            Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    var p1Location by remember { mutableStateOf<Pair<Double, Double>?>(Pair(37.5665, 126.9780)) }
    var inputKey by remember { mutableStateOf("") }
    var p2ViewLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var generatedKey by remember { mutableStateOf("") }

    val pages: List<Pair<String, @Composable () -> Unit>> = listOf(
        "텍스트 읽기" to { TextReaderScreen() },
        "화폐/바코드 인식" to { CameraScreen() },
        "위치 공유/암호" to {
            LocationSharingWithCodeScreen(
                locationState = p1Location,
                inputKeyState = inputKey,
                onInputChange = { inputKey = it },
                p2LocationState = p2ViewLocation,
                onLocationConfirmed = { p2ViewLocation = it },
                generatedKeyState = generatedKey,
                onGenerateKey = { generatedKey = it }
            )
        }
    )

    val pagerState = rememberPagerState()
    var selectedPage by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(pages[pagerState.currentPage].first) })
        }
    ) { paddingValues ->
        HorizontalPager(
            count = pages.size,
            state = pagerState,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -30f) selectedPage = pagerState.currentPage
                    }
                }
        ) { pageIndex ->
            pages[pageIndex].second()
        }

        selectedPage?.let { index ->
            LaunchedEffect(index) {
                println("선택된 기능: ${pages[index].first}")
                selectedPage = null
            }
        }
    }
}

@Composable
fun TextReaderScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("텍스트 읽기 기능 구현 중...")
    }
}

// =====================================
// 위치 공유 + 난수 암호 생성 화면
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
    val location = locationState ?: Pair(37.5665, 126.9780)
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("위치 공유 및 암호코드 생성", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            val key = (1..10).map { Random.nextInt(0, 10) }.joinToString("")
            onGenerateKey(key)
        }) {
            Text("난수 암호 생성")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("생성된 암호코드: $generatedKeyState")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            if (generatedKeyState.isNotEmpty()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("암호코드", generatedKeyState)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "암호코드가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }) {
            Text("클립보드에 복사")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = inputKey,
            onValueChange = { inputKey = it; onInputChange(it) },
            label = { Text("P2 암호 입력") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            if (inputKey == generatedKeyState) {
                onLocationConfirmed(location)
            } else {
                Toast.makeText(context, "암호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
            }
        }) {
            Text("위치 확인")
        }
        Spacer(modifier = Modifier.height(16.dp))
        p2LocationState?.let { loc ->
            Text("P1 위치 확인: Lat ${loc.first}, Lon ${loc.second}")
        }
    }
}
