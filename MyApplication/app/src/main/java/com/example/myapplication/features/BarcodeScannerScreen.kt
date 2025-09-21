package com.example.myapplication.features

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.example.myapplication.model.C005Response
import com.example.myapplication.network.RetrofitClient
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.*
import java.util.concurrent.Executors

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun BarcodeScannerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner

    var barcodeResult by remember { mutableStateOf<String?>(null) }
    var productInfo by remember { mutableStateOf("스캔된 제품 정보를 기다리는 중...") }
    var isScanning by remember { mutableStateOf(true) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    // 🔹 바코드 2차 검증용 상태
    var lastDetectedBarcode by remember { mutableStateOf<String?>(null) }
    var detectionCount by remember { mutableStateOf(0) }
    val detectionThreshold = 3   // 같은 값이 3번 이상 반복되면 확정

    val apiKey = "7798fd698f1f456a9988"

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // TTS 초기화
    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                Log.d("TTS", "TTS 초기화 성공")
            } else Log.e("TTS", "TTS 초기화 실패")
        }
    }

    DisposableEffect(Unit) {
        onDispose { tts?.shutdown() }
    }

    // 진동 함수
    fun vibrateOnce(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            } else vibrator.vibrate(300)
        }
    }

    fun speakText(text: String) {
        tts?.let { textToSpeech ->
            if (textToSpeech.isSpeaking) textToSpeech.stop()
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    // 바코드 인식 후 API 호출
    LaunchedEffect(barcodeResult) {
        barcodeResult?.let { code ->
            productInfo = "정보 불러오는 중..."
            isScanning = false
            vibrateOnce(context)
            speakText("바코드를 인식했습니다. 제품 정보를 불러오는 중입니다.")

            RetrofitClient.instance.getProductByBarcode(
                keyId = apiKey,
                serviceId = "C005",
                dataType = "xml",
                startIdx = 1,
                endIdx = 1,
                barcode = code
            ).enqueue(object : Callback<C005Response> {
                override fun onResponse(
                    call: Call<C005Response>,
                    response: Response<C005Response>
                ) {
                    if (response.isSuccessful) {
                        val rows = response.body()?.rows
                        if (!rows.isNullOrEmpty()) {
                            val item = rows[0]
                            val productName = item.productName ?: "정보 없음"
                            val expiration = item.expiration ?: "정보 없음"
                            val category = item.productCategory ?: "정보 없음"
                            val manufacturer = item.manufacturer ?: "정보 없음"

                            productInfo = """
                                바코드: $barcodeResult
                                상품명: $productName
                                유통기한: $expiration
                                분류: $category
                                제조사: $manufacturer
                            """.trimIndent()

                            val ttsText = "제품 정보를 찾았습니다. 상품명은 $productName 입니다. " +
                                    "유통기한은 $expiration 이고, 분류는 $category 입니다. " +
                                    "제조사는 $manufacturer 입니다. " +
                                    "다시 스캔하려면 화면을 아래로 스와이프하세요."
                            speakText(ttsText)
                        } else {
                            productInfo =
                                "해당 바코드의 제품 정보를 찾을 수 없습니다. 다시 스캔하려면 화면을 아래로 스와이프하세요."
                            speakText(productInfo)
                        }
                    } else {
                        productInfo =
                            "API 호출 실패: ${response.code()}. 다시 스캔하려면 화면을 아래로 스와이프하세요."
                        speakText("제품 정보를 불러오는데 실패했습니다. 다시 스캔하려면 화면을 아래로 스와이프하세요.")
                    }
                }

                override fun onFailure(call: Call<C005Response>, t: Throwable) {
                    productInfo =
                        "API 호출 오류: ${t.localizedMessage}. 다시 스캔하려면 화면을 아래로 스와이프하세요."
                    speakText("네트워크 오류가 발생했습니다. 다시 스캔하려면 화면을 아래로 스와이프하세요.")
                }
            })
        }
    }

    // 카메라 Preview + 바코드 인식
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount > 100f) {
                        isScanning = true
                        barcodeResult = null
                        productInfo = "스캔된 제품 정보를 기다리는 중..."
                        lastDetectedBarcode = null
                        detectionCount = 0
                        speakText("다시 스캔을 시작합니다.")
                    }
                }
            },
        factory = { ctx ->
            val previewView = androidx.camera.view.PreviewView(ctx)
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                    .build()
            )

            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                if (!isScanning) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            barcodes.firstOrNull()?.rawValue?.let { rawValue ->
                                if (lastDetectedBarcode == rawValue) {
                                    detectionCount++
                                    if (detectionCount >= detectionThreshold && barcodeResult != rawValue) {
                                        barcodeResult = rawValue
                                        Log.d("BarcodeScanner", "✅ 최종 확정 바코드: $rawValue")
                                    }
                                } else {
                                    lastDetectedBarcode = rawValue
                                    detectionCount = 1
                                }
                            }
                        }
                        .addOnFailureListener {
                            Log.e("BarcodeScanner", "바코드 인식 실패", it)
                        }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                analysisUseCase
            )

            previewView
        }
    )

    // 제품 정보 카드 UI
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                productInfo.split("\n").forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
