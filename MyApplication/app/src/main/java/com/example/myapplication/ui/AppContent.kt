package com.example.myapplication.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.example.myapplication.features.CameraScreen
import com.example.myapplication.features.LocationSharing.LocationSharingWithCodeScreen
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    // ✅ 세 개의 탭 구성
    val pages: List<Pair<String, @Composable () -> Unit>> = listOf(
        "위치 공유/암호" to { LocationSharingWithCodeScreen() },
        "화폐/바코드 인식" to { CameraScreen() },
        "웹페이지 보기" to { WebViewScreen("http://www.hsb.or.kr/") }
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
            // ✅ 탭 표시줄
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

            // ✅ 페이지 전환
            HorizontalPager(
                count = pages.size,
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                if (pagerState.currentPage == pageIndex) {
                    pages[pageIndex].second()
                }
            }
        }
    }
}
