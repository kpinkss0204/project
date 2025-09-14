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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import com.example.myapplication.network.RetrofitClient
import com.example.myapplication.model.C005Response
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.Executors
import java.util.Locale

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun BarcodeScannerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner

    var barcodeResult by remember { mutableStateOf<String?>(null) }
    var productInfo by remember { mutableStateOf("스캔된 제품 정보를 기다리는 중...") }
    var isScanning by remember { mutableStateOf(true) } // 스캔 상태
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    val apiKey = "7798fd698f1f456a9988"

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // TTS 초기화
    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                Log.d("TTS", "TTS 초기화 성공")
            } else {
                Log.e("TTS", "TTS 초기화 실패")
            }
        }
    }

    // 컴포즈가 종료될 때 TTS 리소스 해제
    DisposableEffect(Unit) {
        onDispose {
            tts?.shutdown()
        }
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
                vibrator.vibrate(
                    VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                vibrator.vibrate(300)
            }
        }
    }

    // TTS로 텍스트 읽기
    fun speakText(text: String) {
        tts?.let { textToSpeech ->
            if (textToSpeech.isSpeaking) {
                textToSpeech.stop()
            }
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    // 바코드 인식 후 API 호출
    LaunchedEffect(barcodeResult) {
        barcodeResult?.let { code ->
            productInfo = "정보 불러오는 중..."
            isScanning = false // 인식 멈춤
            vibrateOnce(context)  // 진동 울림
            speakText("바코드를 인식했습니다. 제품 정보를 불러오는 중입니다.")

            RetrofitClient.instance.getProductByBarcode(
                keyId = apiKey,
                serviceId = "C005",
                dataType = "xml",
                startIdx = 1,
                endIdx = 1,
                barcode = code
            ).enqueue(object : Callback<C005Response> {
                override fun onResponse(call: Call<C005Response>, response: Response<C005Response>) {
                    if (response.isSuccessful) {
                        val body = response.body()
                        Log.d("API_RESPONSE", "응답 전체: $body")

                        val rows = body?.rows
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

                            // TTS로 제품 정보 읽기
                            val ttsText = "제품 정보를 찾았습니다. 상품명은 $productName 입니다. " +
                                    "유통기한은 $expiration 이고, " +
                                    "분류는 $category 입니다. " +
                                    "제조사는 $manufacturer 입니다."
                            speakText(ttsText)
                        } else {
                            productInfo = "해당 바코드의 제품 정보를 찾을 수 없습니다."
                            speakText("해당 바코드의 제품 정보를 찾을 수 없습니다.")
                        }
                    } else {
                        productInfo = "API 호출 실패: ${response.code()}"
                        speakText("제품 정보를 불러오는데 실패했습니다.")
                    }
                }

                override fun onFailure(call: Call<C005Response>, t: Throwable) {
                    productInfo = "API 호출 오류: ${t.localizedMessage}"
                    speakText("네트워크 오류가 발생했습니다.")
                }
            })
        }
    }

    // 카메라 Preview
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    // 위로 드래그하면 다시 스캔 가능
                    if (dragAmount > -30f) {
                        isScanning = true
                        barcodeResult = null
                        productInfo = "스캔된 제품 정보를 기다리는 중..."
                        speakText("다시 스캔을 시작합니다.")
                    }
                }
            },
        factory = { ctx ->
            val previewView = androidx.camera.view.PreviewView(ctx)
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val barcodeScannerOptions = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()

            val scanner = BarcodeScanning.getClient(barcodeScannerOptions)

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
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                barcode.rawValue?.let { rawValue ->
                                    if (barcodeResult != rawValue) {
                                        barcodeResult = rawValue
                                        Log.d("BarcodeScanner", "바코드 인식: $rawValue")
                                    }
                                }
                            }
                        }
                        .addOnFailureListener { Log.e("BarcodeScanner", "바코드 인식 실패", it) }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysisUseCase)

            previewView
        }
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = productInfo, style = MaterialTheme.typography.titleMedium)
    }
}