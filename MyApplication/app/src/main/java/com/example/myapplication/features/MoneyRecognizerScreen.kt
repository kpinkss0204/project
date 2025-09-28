package com.example.myapplication.features

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import java.io.InputStream

@Composable
fun MoneyRecognizerScreen() {
    val context = LocalContext.current
    var resultText by remember { mutableStateOf("결과 없음") }
    var previewImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = {
            // 샘플 이미지 (assets에 test.jpg 넣어둬야 함)
            val inputStream: InputStream = context.assets.open("test.jpg")
            val bitmap = BitmapFactory.decodeStream(inputStream)

            val yolov8 = Yolov8Onnx(context)
            val detections = yolov8.detect(bitmap)
            previewImage = bitmap
            resultText = if (detections.isNotEmpty()) {
                detections.joinToString("\n") {
                    "${it.className} (${String.format("%.2f", it.confidence)})"
                }
            } else {
                "인식된 화폐 없음"
            }
        }) {
            Text("화폐 인식 실행")
        }

        Spacer(modifier = Modifier.height(20.dp))

        previewImage?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = "preview", modifier = Modifier.size(300.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(resultText)
    }
}
