package com.example.myapplication

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
import androidx.core.content.ContextCompat
import com.example.myapplication.features.BarcodeScannerScreen
import com.example.myapplication.features.MoneyRecognizerScreen
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val CAMERA_PERMISSION_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        } else {
            setContent {
                MyApplicationTheme {
                    AppContent()
                }
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
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setContent {
                MyApplicationTheme {
                    AppContent()
                }
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
    val pages: List<Pair<String, @Composable () -> Unit>> = listOf(
        "텍스트 읽기" to { TextReaderScreen() },
        "화폐 인식" to { MoneyRecognizerScreen() }, // 이제 로컬 함수 사용
        "바코드 인식" to { BarcodeScannerScreen() }
    )

    val pagerState = rememberPagerState()
    var selectedPage by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

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
                        if (dragAmount < -30f) {
                            selectedPage = pagerState.currentPage
                        }
                    }
                }
        ) { page ->
            pages[page].second()
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