package com.example.myapplication.features

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class Detection(
    val className: String,
    val confidence: Float,
    val boundingBox: RectF
)

class Yolov8Onnx(
    context: Context,
    modelPath: String = "best.onnx",
    private val confThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.45f
) {
    companion object {
        private const val TAG = "Yolov8Onnx"
    }

    // ONNX 환경/세션
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // 입력 크기 (모델이 기대하는 사이즈가 640x640이라 가정)
    private val inputWidth = 640
    private val inputHeight = 640
    private var originalWidth = 0
    private var originalHeight = 0

    private val classNames = listOf("1000원", "5000원", "10000원", "50000원")

    // 세션 동시 접근 방지용 락
    private val runLock = ReentrantLock()

    init {
        // 세션 옵션: 스레드 수 제한, 불필요한 provider는 사용하지 않음(기기별 이슈 완화)
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            // 필요시 graph 최적화 레벨 조정 가능
            // setGraphOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC)
            // GPU/NNAPI 등 자동 추가하지 않음 — 디바이스 호환성 문제 예방
        }

        // 모델 로드: 예외발생 시 호출자에게 전달(혹은 null 처리)
        try {
            val modelBytes = context.assets.open(modelPath).readBytes()
            session = env.createSession(modelBytes, sessionOptions)
            // 모델의 입력 정보 로그 (디버깅용)
            try {
                val inputInfo = session.inputInfo.values.firstOrNull()
                val shape = (inputInfo?.info as? TensorInfo)?.shape
                Log.i(TAG, "Model input shape: ${shape?.contentToString() ?: "unknown"}")
            } catch (e: Exception) {
                Log.w(TAG, "입력 shape 확인 중 예외: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ONNX 모델 로드 실패: ${e.message}", e)
            throw e
        }
    }

    /** Bitmap → FloatBuffer 기반 OnnxTensor (NCHW, float32) */
    private fun preprocessToFloatBuffer(bitmap: Bitmap): Pair<OnnxTensor, FloatArray> {
        originalWidth = bitmap.width
        originalHeight = bitmap.height

        // 리사이즈 (단순하게 640x640으로 강제 리사이즈; 필요시 letterbox/패딩 로직 사용)
        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        // NCHW shape
        val nchwSize = 1 * 3 * inputHeight * inputWidth
        val byteBuffer = ByteBuffer.allocateDirect(nchwSize * 4).order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        // 모델이 [1,3,640,640] (채널 순서: R,G,B, 정규화 0..1) 을 기대한다고 가정
        // Fill in NCHW order: channel 0 (R), channel1 (G), channel2 (B)
        for (c in 0 until 3) {
            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val pixel = resized.getPixel(x, y)
                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255.0f // R
                        1 -> ((pixel shr 8) and 0xFF) / 255.0f  // G
                        else -> (pixel and 0xFF) / 255.0f       // B
                    }
                    floatBuffer.put(value)
                }
            }
        }
        floatBuffer.rewind()
        // createTensor(env, FloatBuffer, longArrayOf(...))
        val shape = longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())

        // OnnxTensor 생성 — try/catch로 안전하게 만듦
        val tensor = try {
            OnnxTensor.createTensor(env, floatBuffer, shape)
        } catch (e: Exception) {
            // fallback: 자바 배열로 생성 (메모리/성능 저하 가능)
            Log.w(TAG, "FloatBuffer 방식 OnnxTensor 생성 실패, 배열 방식으로 시도: ${e.message}")
            // reconstruct as nested arrays (original 방식) - but prefer buffer method
            val arr = Array(1) {
                Array(3) {
                    Array(inputHeight) { FloatArray(inputWidth) }
                }
            }
            // rewind buffer to read values
            floatBuffer.rewind()
            for (n in 0 until 1) {
                for (c in 0 until 3) {
                    for (y in 0 until inputHeight) {
                        for (x in 0 until inputWidth) {
                            arr[n][c][y][x] = floatBuffer.get()
                        }
                    }
                }
            }
            OnnxTensor.createTensor(env, arr)
        }

        // resized는 필요없으면 recycle
        try { if (!resized.isRecycled) resized.recycle() } catch (_: Exception) {}

        return Pair(tensor, FloatArray(nchwSize)) // 두번째는 더미(호출부에서 필요 시 사용)
    }

    /** YOLO (x,y,w,h) → (x1,y1,x2,y2) */
    private fun xywh2xyxy(box: FloatArray): RectF {
        val x1 = box[0] - box[2] / 2f
        val y1 = box[1] - box[3] / 2f
        val x2 = box[0] + box[2] / 2f
        val y2 = box[1] + box[3] / 2f
        return RectF(x1, y1, x2, y2)
    }

    /** 원본 크기로 복원 (스케일) */
    private fun scaleBox(box: RectF): RectF {
        val scaleX = originalWidth / inputWidth.toFloat()
        val scaleY = originalHeight / inputHeight.toFloat()
        return RectF(
            max(0f, box.left * scaleX),
            max(0f, box.top * scaleY),
            min(originalWidth.toFloat(), box.right * scaleX),
            min(originalHeight.toFloat(), box.bottom * scaleY)
        )
    }

    /** IoU 계산 */
    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    /** NMS */
    private fun nms(boxes: List<RectF>, scores: List<Float>): List<Int> {
        val picked = mutableListOf<Int>()
        val sorted = scores.indices.sortedByDescending { scores[it] }
        for (i in sorted) {
            var keep = true
            for (j in picked) {
                if (iou(boxes[i], boxes[j]) > iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) picked.add(i)
        }
        return picked
    }

    /**
     * 추론
     * - thread-safe 하도록 runLock으로 보호
     * - 텐서/결과는 사용 후 반드시 close()
     * - 예외 발생 시 로그 남김
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        // 락으로 동시 실행 차단 (onnxruntime native crash 완화)
        runLock.withLock {
            val detections = mutableListOf<Detection>()
            var inputTensor: OnnxTensor? = null
            var results: OrtSession.Result? = null

            try {
                // 전처리 → OnnxTensor 생성
                val (tensor, _) = preprocessToFloatBuffer(bitmap)
                inputTensor = tensor

                // 입력 이름 확인 (모델의 첫 input 사용)
                val inputName = session.inputNames.iterator().nextOrNull()
                    ?: run {
                        Log.e(TAG, "세션 입력 이름을 찾을 수 없습니다.")
                        inputTensor.close()
                        return emptyList()
                    }

                // run — 결과는 AutoCloseable (OrtSession.Result)
                try {
                    results = session.run(mapOf(inputName to inputTensor))
                } catch (e: Exception) {
                    Log.e(TAG, "session.run() 예외: ${e.message}", e)
                    return emptyList()
                }

                // 결과 파싱
                // 예: output shape 가 [1, 85, N] 또는 [1, N, 85] 등 모델에 따라 다름
                // 여기서는 원래 구현( [1,85,N] => predictions = outputArray[0]) 을 따름
                val out0 = results[0].value
                if (out0 !is Array<*>) {
                    Log.e(TAG, "예상치 못한 출력 타입: ${out0?.javaClass}")
                    return emptyList()
                }

                // 안전한 캐스팅 시도
                @Suppress("UNCHECKED_CAST")
                val outputArray = out0 as? Array<Array<FloatArray>>
                if (outputArray == null || outputArray.isEmpty()) {
                    Log.e(TAG, "출력 배열 변환 실패 또는 비어있음")
                    return emptyList()
                }

                val predictions = outputArray[0] // [85, N] 형태 가정
                val numDetections = predictions.firstOrNull()?.size ?: 0
                if (numDetections <= 0) {
                    return emptyList()
                }

                val boxes = mutableListOf<RectF>()
                val scores = mutableListOf<Float>()
                val clsIds = mutableListOf<Int>()

                for (i in 0 until numDetections) {
                    val x = predictions[0][i]
                    val y = predictions[1][i]
                    val w = predictions[2][i]
                    val h = predictions[3][i]

                    // 클래스 점수 (여기서는 4개 클래스 가정)
                    val classScores = FloatArray(classNames.size)
                    for (j in classScores.indices) {
                        val idx = 4 + j
                        if (idx < predictions.size) {
                            classScores[j] = predictions[idx][i]
                        } else {
                            classScores[j] = 0f
                        }
                    }

                    val maxIdx = classScores.indices.maxByOrNull { classScores[it] } ?: -1
                    if (maxIdx < 0) continue
                    val confidence = classScores[maxIdx]

                    if (confidence > confThreshold) {
                        val box = floatArrayOf(x, y, w, h)
                        boxes.add(scaleBox(xywh2xyxy(box)))
                        scores.add(confidence)
                        clsIds.add(maxIdx)
                    }
                }

                val keep = nms(boxes, scores)
                for (i in keep) {
                    val clsId = clsIds[i]
                    val className = if (clsId in classNames.indices) classNames[clsId] else "unknown"
                    detections.add(
                        Detection(
                            className = className,
                            confidence = scores[i],
                            boundingBox = boxes[i]
                        )
                    )
                }

                return detections
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "메모리 부족 during detect(): ${oom.message}", oom)
                return emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "detect 예외: ${e.message}", e)
                return emptyList()
            } finally {
                try { results?.close() } catch (_: Exception) {}
                try { inputTensor?.close() } catch (_: Exception) {}
            }
        } // end lock
    }

    fun close() {
        try { session.close() } catch (_: Exception) {}
        try { env.close() } catch (_: Exception) {}
    }
}

// 작은 확장 함수: Iterator.nextOrNull() 유틸
private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null
