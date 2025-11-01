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
import com.example.myapplication.features.ScheduleSharing.ScheduleSendScreen
import com.example.myapplication.features.ScheduleSharing.ScheduleListScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    var currentScreen by remember { mutableStateOf<Screen?>(Screen.EmptyPage) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var dragOffsetX by remember { mutableStateOf(0f) }

    val gesturesEnabled = currentScreen != Screen.WebView

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
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
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentScreen) {
                    if (currentScreen != Screen.WebView) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            dragOffsetX += dragAmount

                            // 빈 페이지에서 왼쪽 스와이프 → 화폐/바코드 인식
                            if (dragOffsetX < -150f && currentScreen == Screen.EmptyPage) {
                                currentScreen = null
                                dragOffsetX = 0f
                            }

                            // 화폐/바코드 인식에서 오른쪽 스와이프 → 빈 페이지
                            if (dragOffsetX > 150f && currentScreen == null) {
                                currentScreen = Screen.EmptyPage
                                dragOffsetX = 0f
                            }

                            // 화폐/바코드 인식에서 왼쪽 스와이프 → 일정 목록
                            if (dragOffsetX < -150f && currentScreen == null) {
                                currentScreen = Screen.ScheduleList
                                dragOffsetX = 0f
                            }

                            // 일정 목록에서 오른쪽 스와이프 → 화폐/바코드 인식
                            if (dragOffsetX > 150f && currentScreen == Screen.ScheduleList) {
                                currentScreen = null
                                dragOffsetX = 0f
                            }

                            change.consume()
                        }
                    }
                },
            topBar = {
                TopAppBar(
                    title = { Text(currentScreen?.title ?: "화폐/바코드 인식") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "메뉴"
                            )
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
                    Screen.WebView -> WebViewScreen("http://www.hsb.or.kr/", modifier = Modifier.fillMaxSize())
                    Screen.EmptyPage -> EmptyPageScreen()
                    Screen.ScheduleSend -> ScheduleSendScreen()
                    Screen.ScheduleList -> ScheduleListScreen()  // 스와이프로 접근
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

    HorizontalDivider()

    NavigationDrawerItem(
        icon = { Text("📄") },
        label = { Text("빈 페이지") },
        selected = currentScreen == Screen.EmptyPage,
        onClick = { onBackToMain() },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )

    // 메뉴에 표시할 화면만 필터링 (ScheduleList 제외)
    Screen.values().forEach { screen ->
        if (screen != Screen.EmptyPage && screen != Screen.ScheduleList) {
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
    EmptyPage("빈 페이지", "📄"),
    ScheduleSend("일정 보내기", "📤"),
    ScheduleList("일정 목록", "📥")  // 메뉴에는 표시 안 됨, 스와이프로만 접근
}