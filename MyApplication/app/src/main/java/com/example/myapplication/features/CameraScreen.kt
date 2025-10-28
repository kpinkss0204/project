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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    val lastDetectedBarcode = remember { AtomicReference<String?>(null) }
    val detectionCount = remember { AtomicInteger(0) }

    // 카메라 on/off 상태
    var isCameraOn by remember { mutableStateOf(false) }
    var tapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val yolov8 = remember {
        try {
            Yolov8Onnx(context, "best.onnx").also { Log.d("CameraScreen", "YOLO 모델 로드 성공") }
        } catch (e: Exception) {
            Log.e("CameraScreen", "YOLO 모델 로드 실패: ${e.message}")
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
            yolov8?.close()
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
            Log.e("CameraScreen", "Bitmap 변환 오류: ${e.message}")
            null
        }
    }

    // ------------------- CameraX -------------------
    LaunchedEffect(cameraProviderFuture, isCameraOn) {
        val hasCameraPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) return@LaunchedEffect

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                if (!isCameraOn) {
                    // 카메라 끄기
                    cameraProvider.unbindAll()
                    Log.d("CameraScreen", "카메라 언바인딩 완료")
                    return@addListener
                }

                cameraProvider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysisUseCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val ANALYSIS_INTERVAL_MS = 1000L
                var lastAnalysisTime = 0L
                val barcodeScanner = BarcodeScanning.getClient()
                val detectionThreshold = 3
                val apiKey = "7798fd698f1f456a9988"

                analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        val currentTime = System.currentTimeMillis()

                        // ------------------- 화폐 인식 -------------------
                        if (isMoneyScanning && !moneyRecognized && currentTime - lastAnalysisTime >= ANALYSIS_INTERVAL_MS && yolov8 != null) {
                            val bitmap = imageProxyToBitmap(imageProxy)
                            bitmap?.let {
                                val detections = yolov8.detect(it)
                                if (detections.isNotEmpty()) {
                                    val top = detections.maxByOrNull { it.confidence }
                                    top?.let { detection ->
                                        if (detection.confidence > 0.5f) {
                                            moneyResult = "${detection.className} (${(detection.confidence * 100).toInt()}%)"
                                            speakText("${detection.className} 입니다")
                                            vibrateOnce(context)
                                            moneyRecognized = true
                                        }
                                    }
                                } else {
                                    moneyResult = "화폐를 인식할 수 없습니다"
                                }
                                it.recycle()
                            }
                            lastAnalysisTime = currentTime
                        }

                        // ------------------- 바코드 인식 -------------------
                        if (isBarcodeScanning) {
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                barcodeScanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        barcodes.firstOrNull()?.rawValue?.let { rawValue ->
                                            if (lastDetectedBarcode.get() == rawValue) {
                                                detectionCount.incrementAndGet()
                                            } else {
                                                lastDetectedBarcode.set(rawValue)
                                                detectionCount.set(1)
                                            }

                                            if (detectionCount.get() >= detectionThreshold && barcodeResult != rawValue) {
                                                barcodeResult = rawValue
                                                barcodeRecognized = true
                                                vibrateOnce(context)
                                                speakText("바코드를 인식했습니다.")

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
                                                        val item = response.body()?.rows?.firstOrNull()
                                                        if (item != null) {
                                                            productInfo = """
                                                                바코드: $rawValue
                                                                상품명: ${item.productName ?: "정보 없음"}
                                                                유통기한: ${item.expiration ?: "정보 없음"}
                                                                분류: ${item.productCategory ?: "정보 없음"}
                                                                제조사: ${item.manufacturer ?: "정보 없음"}
                                                            """.trimIndent()
                                                            speakText("상품명은 ${item.productName} 입니다.")
                                                        } else {
                                                            productInfo = "해당 바코드의 제품 정보를 찾을 수 없습니다."
                                                            speakText(productInfo)
                                                        }
                                                    }

                                                    override fun onFailure(call: Call<C005Response>, t: Throwable) {
                                                        productInfo = "API 호출 오류: ${t.localizedMessage}"
                                                        speakText(productInfo)
                                                    }
                                                })
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
                        imageProxy.close()
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
                            if (!isCameraOn) {
                                // 카메라 꺼진 상태에서는 스와이프 무시
                                totalDrag = 0f
                                return@detectVerticalDragGestures
                            }

                            if (totalDrag > 150f) {
                                // 아래로 스와이프 -> 모드 전환
                                isMoneyScanning = !isMoneyScanning
                                isBarcodeScanning = !isBarcodeScanning

                                speakText(
                                    if (isMoneyScanning) "화폐 인식 모드로 전환합니다."
                                    else "바코드 스캔 모드로 전환합니다."
                                )

                                // 상태 초기화
                                moneyRecognized = false
                                barcodeRecognized = false
                                barcodeResult = null
                                lastDetectedBarcode.set(null)
                                detectionCount.set(0)
                                productInfo = "스캔된 제품 정보를 기다리는 중..."
                            } else if (totalDrag < -150f) {
                                // 위로 스와이프 -> 재인식
                                if (isMoneyScanning) moneyRecognized = false
                                if (isBarcodeScanning) {
                                    barcodeRecognized = false
                                    barcodeResult = null
                                    lastDetectedBarcode.set(null)
                                    detectionCount.set(0)
                                    productInfo = "스캔된 제품 정보를 기다리는 중..."
                                }
                                speakText("재인식을 시작합니다.")
                            }
                            totalDrag = 0f
                        },
                        onDragCancel = { totalDrag = 0f }
                    )
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            // DOWN 이벤트만 감지 (터치 시작)
                            event.changes.forEach { change ->
                                if (change.pressed && change.previousPressed.not()) {
                                    val currentTime = System.currentTimeMillis()

                                    // 1초 이내의 탭만 카운트
                                    if (currentTime - lastTapTime <= 1000) {
                                        tapCount++
                                    } else {
                                        tapCount = 1
                                    }
                                    lastTapTime = currentTime

                                    // 2번 탭: 카메라 켜기
                                    if (tapCount == 2 && !isCameraOn) {
                                        isCameraOn = true
                                        speakText("카메라를 켭니다")
                                        tapCount = 0
                                    }
                                    // 3번 탭: 카메라 끄기
                                    else if (tapCount == 3 && isCameraOn) {
                                        isCameraOn = false
                                        speakText("카메라를 끕니다")
                                        tapCount = 0
                                        // 상태 초기화
                                        moneyRecognized = false
                                        barcodeRecognized = false
                                        barcodeResult = null
                                        lastDetectedBarcode.set(null)
                                        detectionCount.set(0)
                                        productInfo = "스캔된 제품 정보를 기다리는 중..."
                                        moneyResult = "화폐 인식 중..."
                                    }

                                    change.consume()
                                }
                            }
                        }
                    }
                },
            factory = { previewView }
        )

        // 카드 UI
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (!isCameraOn) {
                    Text("카메라가 꺼져 있습니다. 2번 탭하여 켜주세요.", style = MaterialTheme.typography.bodyLarge)
                } else if (isMoneyScanning) {
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