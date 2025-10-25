package com.example.myapplication.features

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.core.content.ContextCompat
import com.example.myapplication.model.C005Response
import com.example.myapplication.network.RetrofitClient
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.Executors

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ------------------- 상태 -------------------
    var moneyResult by remember { mutableStateOf("화폐 인식 중...") }
    var barcodeResult by remember { mutableStateOf<String?>(null) }
    var productInfo by remember { mutableStateOf("스캔된 제품 정보를 기다리는 중...") }

    var isMoneyScanning by remember { mutableStateOf(true) }
    var isBarcodeScanning by remember { mutableStateOf(false) }

    var moneyRecognized by remember { mutableStateOf(false) }
    var barcodeRecognized by remember { mutableStateOf(false) }

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var yoloInitError by remember { mutableStateOf<String?>(null) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    val yolov8 = remember {
        try {
            Yolov8Onnx(context, "best.onnx").also {
                Log.d("CameraScreen", "YOLO 모델 로드 성공")
            }
        } catch (e: Exception) {
            Log.e("CameraScreen", "YOLO 모델 로드 실패: ${e.message}", e)
            yoloInitError = "모델 로드 실패: ${e.message}"
            null
        }
    }

    // ------------------- TTS -------------------
    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tts?.shutdown()
            try {
                yolov8?.close()
            } catch (e: Exception) {
                Log.e("CameraScreen", "YOLO 종료 오류: ${e.message}")
            }
            cameraExecutor.shutdown()
        }
    }

    fun speakText(text: String) {
        tts?.let {
            if (it.isSpeaking) it.stop()
            it.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun vibrateOnce(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(300)
            }
        }
    }

    // 개선된 비트맵 변환 함수
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
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)
            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.e("CameraScreen", "Bitmap 변환 오류: ${e.message}", e)
            null
        }
    }

    // YOLO 입력용 비트맵 전처리 (640x640으로 리사이즈)
    fun preprocessBitmapForYolo(bitmap: Bitmap): Bitmap? {
        return try {
            val targetSize = 640
            // 비율 유지하며 리사이즈
            val scale = minOf(
                targetSize.toFloat() / bitmap.width,
                targetSize.toFloat() / bitmap.height
            )
            val scaledWidth = (bitmap.width * scale).toInt()
            val scaledHeight = (bitmap.height * scale).toInt()

            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

            // 정사각형 비트맵 생성 (패딩 추가)
            val outputBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(outputBitmap)
            canvas.drawColor(android.graphics.Color.BLACK)

            val left = (targetSize - scaledWidth) / 2f
            val top = (targetSize - scaledHeight) / 2f
            canvas.drawBitmap(scaledBitmap, left, top, null)

            scaledBitmap.recycle()
            outputBitmap
        } catch (e: Exception) {
            Log.e("CameraScreen", "비트맵 전처리 오류: ${e.message}", e)
            null
        }
    }

    // ------------------- CameraX -------------------
    DisposableEffect(Unit) {
        val hasCameraPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            return@DisposableEffect onDispose { }
        }

        var cameraProviderInstance: ProcessCameraProvider? = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProviderInstance = cameraProvider
                cameraProvider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysisUseCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val ANALYSIS_INTERVAL_MS = 1500L // 간격 증가
                var lastAnalysisTime = 0L
                val barcodeScanner = BarcodeScanning.getClient()
                var lastDetectedBarcode: String? = null
                var detectionCount = 0
                val detectionThreshold = 3
                val apiKey = "7798fd698f1f456a9988"
                var isProcessing = false // 동시 처리 방지

                analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        val currentTime = System.currentTimeMillis()

                        // ------------------- 화폐 인식 -------------------
                        if (isMoneyScanning && !moneyRecognized && !isProcessing &&
                            currentTime - lastAnalysisTime >= ANALYSIS_INTERVAL_MS &&
                            yolov8 != null) {

                            isProcessing = true
                            var originalBitmap: Bitmap? = null
                            var processedBitmap: Bitmap? = null

                            try {
                                originalBitmap = imageProxyToBitmap(imageProxy)
                                if (originalBitmap == null) {
                                    Log.e("CameraScreen", "비트맵 변환 실패")
                                    return@setAnalyzer
                                }

                                processedBitmap = preprocessBitmapForYolo(originalBitmap)
                                if (processedBitmap == null) {
                                    Log.e("CameraScreen", "비트맵 전처리 실패")
                                    return@setAnalyzer
                                }

                                Log.d("CameraScreen", "YOLO 추론 시작 (${processedBitmap.width}x${processedBitmap.height})")

                                val detections = yolov8.detect(processedBitmap)

                                Log.d("CameraScreen", "YOLO 추론 완료: ${detections.size}개 감지")

                                if (detections.isNotEmpty()) {
                                    val top = detections.maxByOrNull { it.confidence }
                                    top?.let { detection ->
                                        if (detection.confidence > 0.5f) {
                                            val result = "${detection.className} (${(detection.confidence * 100).toInt()}%)"
                                            moneyResult = result
                                            speakText("${detection.className} 입니다")
                                            vibrateOnce(context)
                                            moneyRecognized = true
                                        }
                                    }
                                } else {
                                    moneyResult = "화폐를 인식할 수 없습니다"
                                }

                                lastAnalysisTime = currentTime

                            } catch (e: OutOfMemoryError) {
                                Log.e("CameraScreen", "메모리 부족: ${e.message}", e)
                                moneyResult = "메모리 부족으로 인식에 실패했습니다"
                            } catch (e: Exception) {
                                Log.e("CameraScreen", "YOLO 추론 오류: ${e.message}", e)
                                moneyResult = "인식 오류: ${e.message}"
                            } finally {
                                // 메모리 해제
                                try {
                                    processedBitmap?.recycle()
                                    originalBitmap?.recycle()
                                } catch (e: Exception) {
                                    Log.e("CameraScreen", "비트맵 해제 오류: ${e.message}")
                                }
                                isProcessing = false
                            }
                        }

                        // ------------------- 바코드 인식 -------------------
                        if (isBarcodeScanning && !barcodeRecognized) {
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                barcodeScanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        barcodes.firstOrNull()?.rawValue?.let { rawValue ->
                                            if (lastDetectedBarcode == rawValue) {
                                                detectionCount++
                                                if (detectionCount >= detectionThreshold &&
                                                    barcodeResult != rawValue) {
                                                    barcodeResult = rawValue
                                                    vibrateOnce(context)
                                                    speakText("바코드를 인식했습니다.")
                                                    barcodeRecognized = true

                                                    // Retrofit API 호출
                                                    RetrofitClient.instance.getProductByBarcode(
                                                        keyId = apiKey,
                                                        serviceId = "C005",
                                                        dataType = "xml",
                                                        startIdx = 1,
                                                        endIdx = 1,
                                                        barcode = rawValue
                                                    ).enqueue(object : Callback<C005Response> {
                                                        override fun onResponse(
                                                            call: Call<C005Response>,
                                                            response: Response<C005Response>
                                                        ) {
                                                            val item = response.body()?.rows?.firstOrNull()
                                                            if (item != null) {
                                                                productInfo = """
                                                                    바코드: $rawValue
                                                                    상품명: ${item.productName ?: "정보 없음"}
                                                                    유통기한: ${item.expiration ?: "정보 없음"}
                                                                    분류: ${item.productCategory ?: "정보 없음"}
                                                                    제조사: ${item.manufacturer ?: "정보 없음"}
                                                                """.trimIndent()
                                                                speakText("제품 정보를 찾았습니다. 상품명은 ${item.productName} 입니다.")
                                                            } else {
                                                                productInfo = "해당 바코드의 제품 정보를 찾을 수 없습니다."
                                                                speakText(productInfo)
                                                            }
                                                        }

                                                        override fun onFailure(
                                                            call: Call<C005Response>,
                                                            t: Throwable
                                                        ) {
                                                            productInfo = "API 호출 오류: ${t.localizedMessage}"
                                                            speakText(productInfo)
                                                        }
                                                    })
                                                }
                                            } else {
                                                lastDetectedBarcode = rawValue
                                                detectionCount = 1
                                            }
                                        }
                                    }
                                    .addOnFailureListener {
                                        Log.e("CameraScreen", "바코드 인식 실패", it)
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                                return@setAnalyzer
                            }
                        }

                    } catch (e: Exception) {
                        Log.e("CameraScreen", "Analyzer 오류: ${e.message}", e)
                    } finally {
                        if (!isBarcodeScanning) imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    analysisUseCase
                )
                Log.d("CameraScreen", "카메라 바인딩 완료")
            } catch (e: Exception) {
                Log.e("CameraScreen", "카메라 초기화 실패: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProviderInstance?.unbindAll()
            Log.d("CameraScreen", "카메라 리소스 해제")
        }
    }

    // ------------------- UI -------------------
    var totalDrag by remember { mutableStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                        },
                        onDragEnd = {
                            if (totalDrag > 150f) {
                                if (isMoneyScanning) {
                                    isMoneyScanning = false
                                    isBarcodeScanning = true
                                    speakText("바코드 스캔 모드로 전환합니다.")
                                } else {
                                    isMoneyScanning = true
                                    isBarcodeScanning = false
                                    speakText("화폐 인식 모드로 전환합니다.")
                                }
                            } else if (totalDrag < -150f) {
                                if (isMoneyScanning) moneyRecognized = false
                                if (isBarcodeScanning) barcodeRecognized = false
                                speakText("재인식을 시작합니다.")
                            }
                            totalDrag = 0f
                        },
                        onDragCancel = { totalDrag = 0f }
                    )
                },
            factory = { previewView }
        )

        // 에러 메시지 표시
        if (yoloInitError != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = yoloInitError!!,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // 결과 카드
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (isMoneyScanning) {
                    Text(moneyResult, style = MaterialTheme.typography.bodyLarge)
                } else {
                    productInfo.split("\n").forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}