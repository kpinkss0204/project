package com.example.myapplication.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.myapplication.features.CameraScreen
import com.example.myapplication.features.LocationSharing.LocationSharingWithCodeScreen
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    val pages: List<Pair<String, @Composable () -> Unit>> = listOf(
        "화폐/바코드 인식" to { CameraScreen() },
        "위치 공유/암호" to { LocationSharingWithCodeScreen() }
    )

    val pagerState = rememberPagerState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(pages[pagerState.currentPage].first) }) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // 탭 UI (클릭 시 페이지 이동)
            TabRow(selectedTabIndex = pagerState.currentPage) {
                pages.forEachIndexed { index, page ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = { Text(page.first) }
                    )
                }
            }

            // HorizontalPager (스와이프 가능, 현재 페이지만 렌더링)
            HorizontalPager(
                count = pages.size,
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                // 현재 페이지만 렌더링 (백그라운드 실행 방지)
                if (pagerState.currentPage == pageIndex) {
                    pages[pageIndex].second()
                }
            }
        }
    }
}