package com.example.myapplication.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.myapplication.features.CameraScreen
import com.example.myapplication.features.LocationSharing.LocationSharingWithCodeScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    // 초기 화면을 EmptyPageScreen으로 설정
    var currentScreen by remember { mutableStateOf<Screen?>(Screen.EmptyPage) }

    // 드로어 상태
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 스와이프용 임시 상태
    var dragOffsetX by remember { mutableStateOf(0f) }

    // 메뉴는 빈 페이지에서만 보이도록
    val gesturesEnabled = currentScreen == Screen.EmptyPage

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            if (gesturesEnabled) {
                ModalDrawerSheet {
                    NavigationDrawerContent(
                        currentScreen = currentScreen,
                        onScreenSelected = { screen ->
                            currentScreen = screen
                            scope.launch { drawerState.close() }
                        },
                        onBackToMain = {
                            currentScreen = Screen.EmptyPage
                            scope.launch { drawerState.close() }
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentScreen) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        dragOffsetX += dragAmount

                        // 빈 페이지에서 왼쪽 스와이프 → CameraScreen
                        if (dragOffsetX < -150f && currentScreen == Screen.EmptyPage) {
                            currentScreen = null
                            dragOffsetX = 0f
                        }

                        // CameraScreen에서 오른쪽 스와이프 → EmptyPage
                        if (dragOffsetX > 150f && currentScreen == null) {
                            currentScreen = Screen.EmptyPage
                            dragOffsetX = 0f
                        }
                    }
                },
            topBar = {
                TopAppBar(
                    title = { Text(currentScreen?.title ?: "화폐/바코드 인식") },
                    navigationIcon = {
                        if (gesturesEnabled) {
                            IconButton(onClick = {
                                scope.launch { drawerState.open() }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "메뉴"
                                )
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            ) {
                when (currentScreen) {
                    null -> CameraScreen()
                    Screen.LocationSharing -> LocationSharingWithCodeScreen()
                    Screen.WebView -> WebViewScreen("http://www.hsb.or.kr/")
                    Screen.EmptyPage -> EmptyPageScreen()
                }
            }
        }
    }
}

@Composable
private fun NavigationDrawerContent(
    currentScreen: Screen?,
    onScreenSelected: (Screen) -> Unit,
    onBackToMain: () -> Unit
) {
    Text(
        text = "기능 선택",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(16.dp)
    )

    Divider()

    // 메인 화면으로 돌아가기
    NavigationDrawerItem(
        icon = { Text("📄") },
        label = { Text("빈 페이지") },
        selected = currentScreen == Screen.EmptyPage,
        onClick = { onBackToMain() },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )

    // 메뉴 항목들
    Screen.values().forEach { screen ->
        if (screen != Screen.EmptyPage) {
            NavigationDrawerItem(
                icon = { Text(screen.icon) },
                label = { Text(screen.title) },
                selected = currentScreen == screen,
                onClick = { onScreenSelected(screen) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

// 빈 페이지 화면
@Composable
fun EmptyPageScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "준비중 화면입니다.", style = MaterialTheme.typography.headlineMedium)
    }
}

enum class Screen(val title: String, val icon: String) {
    LocationSharing("위치 공유", "📍"),
    WebView("웹뷰", "🌐"),
    EmptyPage("빈 페이지", "📄")
}
