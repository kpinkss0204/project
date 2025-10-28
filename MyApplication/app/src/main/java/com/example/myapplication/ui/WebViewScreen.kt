package com.example.myapplication.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    url: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                // 터치 이벤트 충돌 방지: 상위 Compose가 스크롤 이벤트 가로채지 않음
                setOnTouchListener { v, event ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    false
                }

                loadUrl(url)
            }
        },
        modifier = modifier
    )
}
