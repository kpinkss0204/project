package com.example.myapplication.features

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import ai.onnxruntime.*
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.text.Regex
import kotlin.text.RegexOption

@Composable
fun PillRecognizerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isRealtimeMode by remember { mutableStateOf(false) }
    var detectedPill by remember { mutableStateOf<String?>(null) }
    var drugInfo by remember { mutableStateOf<String?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val pillRecognizer = remember { PillRecognizer(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val inferExecutor = remember { Executors.newSingleThreadExecutor() }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isLoading = true
            val bitmap = uriToBitmap(context, it)
            inferExecutor.execute {
                pillRecognizer.recognizePill(bitmap) { pill, info, image ->
                    detectedPill = pill
                    drugInfo = info
                    resultBitmap = image
                    isLoading = false
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            inferExecutor.shutdown()
            pillRecognizer.cleanup()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 버튼들
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = {
                    isRealtimeMode = !isRealtimeMode
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isRealtimeMode) "실시간 모드 중단" else "실시간 모드 시작")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    galleryLauncher.launch("image/*")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("갤러리에서 선택")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 카메라 프리뷰 또는 이미지 결과
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        ) {
            if (isRealtimeMode) {
                CameraPreview(
                    onImageAnalysis = { bitmap ->
                        inferExecutor.execute {
                            pillRecognizer.recognizePill(bitmap) { pill, info, image ->
                                detectedPill = pill
                                drugInfo = info
                                resultBitmap = image
                            }
                        }
                    }
                )
            } else {
                resultBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "인식 결과",
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else {
                        Text("이미지를 선택하거나 실시간 모드를 시작하세요")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 인식 결과
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "인식 결과",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    detectedPill ?: "알약이 감지되지 않았습니다.",
                    fontSize = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 약품 정보
        drugInfo?.let { info ->
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "약품 정보",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        info,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CameraPreview(
    onImageAnalysis: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val bitmap = imageProxyToBitmap(imageProxy)
                    onImageAnalysis(bitmap)
                    imageProxy.close()
                }

                try {
                    cameraProvider.unbindAll()
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (ex: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", ex)
                }

            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

class PillRecognizer(private val context: Context) {
    private lateinit var ortEnv: OrtEnvironment
    private lateinit var ortSession: OrtSession
    private lateinit var labels: List<String>

    private val inputSize = 640
    private val VIBRATE_COOLDOWN_MS = 1000L
    @Volatile
    private var lastVibrateTime = 0L

    init {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open("best.onnx").readBytes()
            ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
            labels = context.assets.open("labels.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            Log.e("PillRecognizer", "초기화 실패: ${e.message}", e)
        }
    }

    fun recognizePill(
        bitmap: Bitmap,
        callback: (pillName: String?, drugInfo: String?, resultImage: Bitmap?) -> Unit
    ) {
        try {
            val inputTensor = preprocess(bitmap)
            val inputName = ortSession.inputNames.iterator().next()
            val outputs = ortSession.run(mapOf(inputName to inputTensor))

            @Suppress("UNCHECKED_CAST")
            val rawOutput = outputs[0].value as Array<Array<FloatArray>>

            val boxes = postprocess(rawOutput[0])
            val resultBitmap = drawBoxes(bitmap.copy(Bitmap.Config.ARGB_8888, true), boxes)

            val firstPrediction = boxes.maxByOrNull { it.score }
            if (firstPrediction != null) {
                val labelIndex = firstPrediction.labelIndex
                val pillName = labels.getOrNull(labelIndex)?.trim() ?: "알약명 불명"

                val pillDisplayName = "인식된 알약: $pillName (score=${"%.2f".format(firstPrediction.score)})"

                val now = System.currentTimeMillis()
                if (now - lastVibrateTime > VIBRATE_COOLDOWN_MS) {
                    lastVibrateTime = now
                    vibratePhone()
                }

                // API 호출로 약품 정보 가져오기
                fetchDrugInfo(pillName) { drugInfo ->
                    callback(pillDisplayName, drugInfo, resultBitmap)
                }
            } else {
                callback("알약이 감지되지 않았습니다.", null, resultBitmap)
            }
        } catch (e: Exception) {
            Log.e("PillRecognizer", "인식 실패: ${e.message}", e)
            callback("인식 오류: ${e.message}", null, null)
        }
    }

    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val inputArray = Array(1) {
            Array(3) {
                Array(inputSize) {
                    FloatArray(inputSize)
                }
            }
        }

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = pixels[y * inputSize + x]
                val r = Color.red(pixel) / 255.0f
                val g = Color.green(pixel) / 255.0f
                val b = Color.blue(pixel) / 255.0f

                inputArray[0][0][y][x] = r
                inputArray[0][1][y][x] = g
                inputArray[0][2][y][x] = b
            }
        }

        return OnnxTensor.createTensor(ortEnv, inputArray)
    }

    private fun postprocess(output: Array<FloatArray>, threshold: Float = 0.4f): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        for (det in output) {
            val score = det[4]
            if (score > threshold) {
                val labelIdx = det[5].toInt()
                predictions.add(Prediction(det[0], det[1], det[2], det[3], score, labelIdx))
            }
        }
        return predictions
    }

    private fun drawBoxes(bitmap: Bitmap, boxes: List<Prediction>): Bitmap {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        for (box in boxes) {
            canvas.drawRect(box.x1, box.y1, box.x2, box.y2, paint)
        }
        return bitmap
    }

    private fun vibratePhone() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(350)
        }
    }

    private fun fetchDrugInfo(pillName: String, callback: (String?) -> Unit) {
        val convertedName = pillName.replace("mg", "밀리그람")
        val encodedName = URLEncoder.encode(convertedName, "UTF-8")

        val serviceKey = "of3IseSecTDkEFHUYiIPNZJi%2Byy43kSzbSfbiJgFVsvi4U%2BTgz1GCB2bwXc3kP5qETN0PJqN%2FiGqgTJNS%2FB8NA%3D%3D"
        val apiUrl = "https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService06/getDrugPrdtPrmsnDtlInq05" +
                "?serviceKey=$serviceKey" +
                "&item_name=$encodedName" +
                "&type=json"

        val client = OkHttpClient()
        val request = Request.Builder().url(apiUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("API 호출 실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { jsonStr ->
                    try {
                        val drugInfo = parseDrugInfo(jsonStr)
                        callback(drugInfo)
                    } catch (e: Exception) {
                        // 실패 시 간소화된 이름으로 재시도
                        val simplifiedName = simplifyPillName(pillName)
                        if (simplifiedName != pillName) {
                            fetchDrugInfoSimple(simplifiedName, callback)
                        } else {
                            callback("약품 정보 파싱 오류: ${e.message}")
                        }
                    }
                } ?: callback("API 응답이 없습니다.")
            }
        })
    }

    private fun fetchDrugInfoSimple(simpleName: String, callback: (String?) -> Unit) {
        val convertedName = simpleName.replace("mg", "밀리그람")
        val encodedName = URLEncoder.encode(convertedName, "UTF-8")

        val serviceKey = "of3IseSecTDkEFHUYiIPNZJi%2Byy43kSzbSfbiJgFVsvi4U%2BTgz1GCB2bwXc3kP5qETN0PJqN%2FiGqgTJNS%2FB8NA%3D%3D"
        val apiUrl = "https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService06/getDrugPrdtPrmsnDtlInq05" +
                "?serviceKey=$serviceKey" +
                "&item_name=$encodedName" +
                "&type=json"

        val client = OkHttpClient()
        val request = Request.Builder().url(apiUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("API 호출 실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { jsonStr ->
                    try {
                        val drugInfo = parseDrugInfo(jsonStr)
                        callback(drugInfo)
                    } catch (e: Exception) {
                        callback("약품 정보가 없습니다.")
                    }
                } ?: callback("API 응답이 없습니다.")
            }
        })
    }

    private fun parseDrugInfo(jsonStr: String): String {
        val jsonObj = JSONObject(jsonStr)
        val body = when {
            jsonObj.has("response") -> jsonObj.getJSONObject("response").getJSONObject("body")
            jsonObj.has("body") -> jsonObj.getJSONObject("body")
            else -> throw Exception("response 또는 body 객체가 없습니다")
        }

        if (!body.has("items") || body.isNull("items")) {
            throw Exception("약품 정보가 없습니다")
        }

        val itemsObj = body.get("items")
        val item = when {
            itemsObj is JSONObject -> itemsObj
            itemsObj is org.json.JSONArray && itemsObj.length() > 0 -> itemsObj.getJSONObject(0)
            else -> throw Exception("약품 정보가 없습니다")
        }

        return formatDrugInfo(item)
    }

    private fun formatDrugInfo(item: JSONObject): String {
        val name = item.optString("ITEM_NAME", "정보 없음").trim()
        val manufacturer = item.optString("ENTP_NAME", "정보 없음").trim()
        val efficacy = extractTextFromDoc(item.optString("EE_DOC_DATA", "정보 없음"))
        val usage = parseXmlParagraphs(item.optString("UD_DOC_DATA", "정보 없음"))
        val cautionRaw = parseXmlParagraphs(item.optString("NB_DOC_DATA", "정보 없음"))
        val caution = summarizeCaution(cautionRaw)
        val storage = item.optString("STORAGE_METHOD", "정보 없음")
        val validTerm = item.optString("VALID_TERM", "정보 없음")

        return """
            1. 약 이름
            약품명: $name

            2. 제조사
            제조사: $manufacturer

            3. 용도(효능·효과)
            $efficacy

            4. 복용법 (용법·용량)
            $usage

            5. 주의사항 (요약)
            $caution

            6. 보관 방법
            $storage

            7. 유효 기간
            $validTerm
        """.trimIndent()
    }

    private fun extractTextFromDoc(docData: String): String {
        val cdataStart = docData.indexOf("CDATA[")
        val cdataEnd = docData.indexOf("]]>", cdataStart)
        if (cdataStart != -1 && cdataEnd != -1) {
            val cdataText = docData.substring(cdataStart + 6, cdataEnd).trim()
            return cdataText
        }
        return if (docData.isNotBlank()) docData else "정보 없음"
    }

    private fun parseXmlParagraphs(xml: String): String {
        val regex = Regex("<PARAGRAPH[^>]*>(.*?)</PARAGRAPH>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val matches = regex.findAll(xml)
        val paragraphs = matches.map {
            val content = it.groupValues[1]
            val cdataRegex = Regex("<!\\[CDATA\\[(.*?)]]>", setOf(RegexOption.DOT_MATCHES_ALL))
            cdataRegex.find(content)?.groups?.get(1)?.value?.trim() ?: content.trim()
        }.toList()
        return if (paragraphs.isEmpty()) "정보 없음" else paragraphs.joinToString("\n\n")
    }

    private val importantKeywords = listOf("금기", "주의", "복용 금지", "부작용", "경고", "임신", "알레르기", "과민반응")

    private fun summarizeCaution(cautionText: String, maxSentences: Int = 5): String {
        if (cautionText == "정보 없음") return cautionText
        val sentences = cautionText.split(Regex("(?<=[.!?])\\s+"))
        val filtered = sentences.filter { s -> importantKeywords.any { s.contains(it) } }
        val selected = if (filtered.isNotEmpty()) filtered else sentences.take(maxSentences)
        val result = selected.joinToString("\n\n")
        return if (result.length > 300) result.take(300) + "\n..." else result
    }

    private fun simplifyPillName(name: String): String {
        val regex = Regex("""[\d\s\w/]+$""")
        return name.replace(regex, "").trim()
    }

    fun cleanup() {
        try {
            ortSession.close()
            ortEnv.close()
        } catch (e: Exception) {
            Log.e("PillRecognizer", "정리 중 오류: ${e.message}", e)
        }
    }
}

data class Prediction(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    val labelIndex: Int
)

private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
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

    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 90, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

private fun uriToBitmap(context: Context, uri: Uri): Bitmap {
    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
    return BitmapFactory.decodeStream(inputStream)!!
}