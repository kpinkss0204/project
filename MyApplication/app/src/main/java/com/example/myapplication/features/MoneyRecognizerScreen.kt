package com.example.myapplication.features

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.Executors

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun MoneyRecognizerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var resultText by remember { mutableStateOf("화폐 인식 중...") }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var lastAnalysisTime by remember { mutableStateOf(0L) }
    var isSurfaceReady by remember { mutableStateOf(false) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }

    val yolov8 = remember {
        try {
            Yolov8Onnx(context, "best.onnx").also {
                Log.d("MoneyRecognizer", "YOLO 모델 로드 성공")
            }
        } catch (e: Exception) {
            Log.e("MoneyRecognizer", "YOLO 모델 로드 실패: ${e.message}")
            null
        }
    }

    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                Log.d("TTS", "TTS 초기화 성공")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tts?.shutdown()
            yolov8?.close()
            cameraExecutor.shutdown()
        }
    }

    fun speakText(text: String) {
        tts?.let { ttsEngine ->
            if (ttsEngine.isSpeaking) ttsEngine.stop()
            ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val image = imageProxy.image ?: return null
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
            val imageBytes = out.toByteArray()

            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.e("MoneyRecognizer", "Bitmap 변환 오류: ${e.message}")
            null
        }
    }

    // Surface가 준비된 후에만 카메라 바인딩
    LaunchedEffect(isSurfaceReady) {
        if (!isSurfaceReady) return@LaunchedEffect

        val previewView = previewViewRef.value ?: return@LaunchedEffect

        // Surface 준비를 확실히 하기 위한 딜레이
        kotlinx.coroutines.delay(100)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        try {
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val ANALYSIS_INTERVAL_MS = 1000L

            analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastAnalysisTime >= ANALYSIS_INTERVAL_MS && yolov8 != null) {
                    try {
                        val bitmap = imageProxyToBitmap(imageProxy)

                        if (bitmap != null) {
                            val detections = yolov8.detect(bitmap)

                            if (detections.isNotEmpty()) {
                                val top = detections.maxByOrNull { it.confidence }
                                if (top != null && top.confidence > 0.5f) {
                                    resultText = "${top.className} (${(top.confidence * 100).toInt()}%)"
                                    speakText("${top.className} 입니다")
                                    Log.d("MoneyRecognizer", "인식 성공: ${top.className}")
                                }
                            } else {
                                resultText = "화폐를 인식할 수 없습니다"
                            }

                            bitmap.recycle()
                        }

                        lastAnalysisTime = currentTime
                    } catch (e: Exception) {
                        Log.e("MoneyRecognizer", "분석 오류: ${e.message}", e)
                    }
                }

                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                analysisUseCase
            )

            Log.d("MoneyRecognizer", "카메라 바인딩 완료")
        } catch (e: Exception) {
            Log.e("MoneyRecognizer", "카메라 초기화 실패: ${e.message}", e)
            resultText = "카메라 오류: ${e.message}"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 100f) {
                            resultText = "다시 인식 시작..."
                            speakText("다시 인식 시작합니다.")
                        }
                    }
                },
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // SurfaceView 사용 (더 안정적)
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE

                    // Surface 준비 콜백 등록
                    post {
                        // View가 완전히 레이아웃된 후
                        postDelayed({
                            previewViewRef.value = this
                            isSurfaceReady = true
                            Log.d("MoneyRecognizer", "Surface 준비 완료")
                        }, 200) // Surface 생성을 위한 충분한 시간
                    }

                    Log.d("MoneyRecognizer", "PreviewView 생성 완료")
                }
            }
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = resultText, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}