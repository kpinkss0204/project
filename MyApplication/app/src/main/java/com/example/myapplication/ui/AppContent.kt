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
    val pages: List<Pair<String, @Composable () -> Unit>> = listOf(
        "화폐/바코드 인식" to { CameraScreen() },
        "위치 공유/암호" to { LocationSharingWithCodeScreen() }
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
                .padding(paddingValues)
                .fillMaxSize()
        ) { pageIndex ->
            pages[pageIndex].second()
        }
    }
}