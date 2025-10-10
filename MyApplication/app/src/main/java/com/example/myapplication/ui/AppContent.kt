package com.example.myapplication.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.myapplication.features.CameraScreen
import com.example.myapplication.ui.screens.LocationSharingWithCodeScreen
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    var p1Location by remember { mutableStateOf(37.5665 to 126.9780) }
    var inputKey by remember { mutableStateOf("") }
    var p2ViewLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var generatedKey by remember { mutableStateOf("") }

    val pages: List<Pair<String, @Composable () -> Unit>> = listOf(
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(pages[pagerState.currentPage].first) }) }
    ) { paddingValues ->
        HorizontalPager(
            count = pages.size,
            state = pagerState,
            modifier = Modifier
                .padding(paddingValues)   // ✅ Scaffold가 준 padding 적용
                .fillMaxSize()
        ) { pageIndex ->
            pages[pageIndex].second()
        }
    }
}
