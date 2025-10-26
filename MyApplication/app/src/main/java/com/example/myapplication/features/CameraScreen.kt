package com.example.myapplication.features

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 상태
    var moneyResult by remember { mutableStateOf("화폐 인식 중...") }
    var barcodeResult by remember { mutableStateOf<String?>(null) }
    var productInfo by remember { mutableStateOf("스캔된 제품 정보를 기다리는 중...") }
    var isMoneyScanning by remember { mutableStateOf(true) }
    var isBarcodeScanning by remember { mutableStateOf(false) }
    var moneyRecognized by remember { mutableStateOf(false) }
    var barcodeRecognized by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var yoloInitError by remember { mutableStateOf<String?>(null) }

    // ✅ 추가: 화면 활성화 상태 추적
    var isScreenActive by remember { mutableStateOf(true) }
    val isProcessing = remember { AtomicBoolean(false) }

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

    // ✅ 생명주기 관찰자로 화면 전환 감지
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isScreenActive = true
                    Log.d("CameraScreen", "화면 활성화")
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isScreenActive = false
                    isProcessing.set(false)
                    Log.d("CameraScreen", "화면 비활성화")
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // TTS 초기화
    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            isScreenActive = false
            isProcessing.set(false)
            tts?.shutdown()
            try {
                yolov8?.close()
                Log.d("CameraScreen", "YOLO 리소스 해제 완료")
            } catch (e: Exception) {
                Log.e("CameraScreen", "YOLO 종료 오류: ${e.message}")
            }
            cameraExecutor.shutdown()
        }
    }

    fun speakText(text: String) {
        if (!isScreenActive) return
        tts?.let {
            if (it.isSpeaking) it.stop()
            it.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun vibrateOnce(context: Context) {
        if (!isScreenActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
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
            BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        } catch (e: Exception) {
            Log.e("CameraScreen", "Bitmap 변환 오류: ${e.message}", e)
            null
        }
    }

    fun preprocessBitmapForYolo(bitmap: Bitmap): Bitmap? {
        return try {
            val targetSize = 640
            val scale = min(targetSize.toFloat() / bitmap.width, targetSize.toFloat() / bitmap.height)
            val scaledWidth = (bitmap.width * scale).toInt()
            val scaledHeight = (bitmap.height * scale).toInt()

            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            val outputBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(scaledBitmap, (targetSize - scaledWidth)/2f, (targetSize - scaledHeight)/2f, null)
            scaledBitmap.recycle()
            outputBitmap
        } catch (e: Exception) {
            Log.e("CameraScreen", "비트맵 전처리 오류: ${e.message}", e)
            null
        }
    }

    // CameraX 바인딩
    DisposableEffect(Unit) {
        val hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) return@DisposableEffect onDispose { }

        var cameraProviderInstance: ProcessCameraProvider? = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProviderInstance = cameraProvider
                cameraProvider.unbindAll()

                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysisUseCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val ANALYSIS_INTERVAL_MS = 1500L
                var lastAnalysisTime = 0L
                val barcodeScanner = BarcodeScanning.getClient()
                var lastDetectedBarcode: String? = null
                var detectionCount = 0
                val detectionThreshold = 3
                val apiKey = "7798fd698f1f456a9988"

                analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        // ✅ 화면이 비활성 상태면 즉시 종료
                        if (!isScreenActive) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val currentTime = System.currentTimeMillis()

                        // 화폐 인식
                        if (isMoneyScanning && !moneyRecognized &&
                            currentTime - lastAnalysisTime >= ANALYSIS_INTERVAL_MS &&
                            yolov8 != null && isScreenActive) {

                            // ✅ AtomicBoolean로 동시 실행 방지
                            if (!isProcessing.compareAndSet(false, true)) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            var originalBitmap: Bitmap? = null
                            var processedBitmap: Bitmap? = null
                            try {
                                // ✅ 각 단계마다 화면 활성 상태 확인
                                if (!isScreenActive) return@setAnalyzer

                                originalBitmap = imageProxyToBitmap(imageProxy)
                                if (originalBitmap == null || !isScreenActive) return@setAnalyzer

                                processedBitmap = preprocessBitmapForYolo(originalBitmap)
                                if (processedBitmap == null || !isScreenActive) return@setAnalyzer

                                val detections = yolov8.detect(processedBitmap)

                                // ✅ 추론 완료 후에도 화면 상태 확인
                                if (!isScreenActive) return@setAnalyzer

                                if (detections.isNotEmpty()) {
                                    val top = detections.maxByOrNull { it.confidence }
                                    top?.takeIf { it.confidence > 0.5f }?.let { detection ->
                                        moneyResult = "${detection.className} (${(detection.confidence*100).toInt()}%)"
                                        speakText("${detection.className} 입니다")
                                        vibrateOnce(context)
                                        moneyRecognized = true
                                    }
                                } else {
                                    moneyResult = "화폐를 인식할 수 없습니다"
                                }
                                lastAnalysisTime = currentTime
                            } catch (e: Exception) {
                                Log.e("CameraScreen", "화폐 인식 오류: ${e.message}", e)
                            } finally {
                                try { originalBitmap?.recycle() } catch (_: Exception) {}
                                try { processedBitmap?.recycle() } catch (_: Exception) {}
                                isProcessing.set(false)
                            }
                        }

                        // 바코드 인식
                        if (isBarcodeScanning && !barcodeRecognized && isScreenActive) {
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                barcodeScanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        if (!isScreenActive) return@addOnSuccessListener

                                        barcodes.firstOrNull()?.rawValue?.let { rawValue ->
                                            if (lastDetectedBarcode == rawValue) {
                                                detectionCount++
                                                if (detectionCount >= detectionThreshold && barcodeResult != rawValue) {
                                                    barcodeResult = rawValue
                                                    vibrateOnce(context)
                                                    speakText("바코드를 인식했습니다.")
                                                    barcodeRecognized = true

                                                    // API 호출
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
                                                            if (!isScreenActive) return
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

                                                        override fun onFailure(call: Call<C005Response>, t: Throwable) {
                                                            if (!isScreenActive) return
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
                                    .addOnFailureListener { Log.e("CameraScreen", "바코드 인식 실패", it) }
                                    .addOnCompleteListener { imageProxy.close() }
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
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysisUseCase)
                Log.d("CameraScreen", "카메라 바인딩 완료")
            } catch (e: Exception) {
                Log.e("CameraScreen", "카메라 초기화 실패: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            isScreenActive = false
            isProcessing.set(false)
            cameraProviderInstance?.unbindAll()
            Log.d("CameraScreen", "카메라 리소스 해제")
        }
    }

    // UI
    var totalDrag by remember { mutableStateOf(0f) }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount -> totalDrag += dragAmount },
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

        if (yoloInitError != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = yoloInitError!!,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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