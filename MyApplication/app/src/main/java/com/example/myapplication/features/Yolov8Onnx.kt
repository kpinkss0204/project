package com.example.myapplication.features

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val inputWidth = 640
    private val inputHeight = 640
    private var originalWidth = 0
    private var originalHeight = 0

    private val classNames = listOf("1000원", "5000원", "10000원", "50000원")

    init {
        val modelBytes = context.assets.open(modelPath).readBytes()
        session = env.createSession(modelBytes)
    }

    /** Bitmap → OnnxTensor (NCHW, float32) - 다른 접근 방식 */
    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        originalWidth = bitmap.width
        originalHeight = bitmap.height

        // 이미지 리사이즈
        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        // 3차원 배열로 생성 [channels][height][width]
        val tensorData = Array(3) { Array(inputHeight) { FloatArray(inputWidth) } }

        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val pixel = resized.getPixel(x, y)
                tensorData[0][y][x] = ((pixel shr 16) and 0xFF) / 255f  // R
                tensorData[1][y][x] = ((pixel shr 8) and 0xFF) / 255f   // G
                tensorData[2][y][x] = (pixel and 0xFF) / 255f           // B
            }
        }

        // 텐서 생성 (3차원 배열을 4차원으로 래핑)
        val batchedData = arrayOf(tensorData)
        return OnnxTensor.createTensor(env, batchedData)
    }

    /** YOLO (x,y,w,h) → (x1,y1,x2,y2) */
    private fun xywh2xyxy(box: FloatArray): RectF {
        val x1 = box[0] - box[2] / 2f
        val y1 = box[1] - box[3] / 2f
        val x2 = box[0] + box[2] / 2f
        val y2 = box[1] + box[3] / 2f
        return RectF(x1, y1, x2, y2)
    }

    /** 원본 크기로 복원 */
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

    /** 추론 - 두 번째 코드와 동일한 후처리 방식 */
    fun detect(bitmap: Bitmap): List<Detection> {
        val inputTensor = preprocess(bitmap)
        val inputName = session.inputNames.iterator().next()
        val results = session.run(mapOf(inputName to inputTensor))

        // 출력 처리: [1, 85, N] -> [N, 85] 형태로 변환
        val outputArray = results[0].value as Array<Array<FloatArray>>
        val predictions = outputArray[0]  // [85, N]

        val boxes = mutableListOf<RectF>()
        val scores = mutableListOf<Float>()
        val clsIds = mutableListOf<Int>()

        // predictions를 transpose하여 [N, 85] 형태로 처리
        val numDetections = predictions[0].size
        for (i in 0 until numDetections) {
            // 각 detection의 정보 추출
            val x = predictions[0][i]
            val y = predictions[1][i]
            val w = predictions[2][i]
            val h = predictions[3][i]

            // 클래스별 점수 (4개 클래스)
            val classScores = FloatArray(4)
            for (j in 0 until 4) {
                classScores[j] = predictions[4 + j][i]
            }

            // 최고 점수의 클래스 찾기
            val maxIdx = classScores.indices.maxByOrNull { classScores[it] } ?: -1
            val confidence = classScores[maxIdx]

            if (confidence > confThreshold) {
                val box = floatArrayOf(x, y, w, h)
                boxes.add(scaleBox(xywh2xyxy(box)))
                scores.add(confidence)
                clsIds.add(maxIdx)
            }
        }

        val keep = nms(boxes, scores)
        val detections = mutableListOf<Detection>()
        for (i in keep) {
            detections.add(
                Detection(
                    className = classNames[clsIds[i]],
                    confidence = scores[i],
                    boundingBox = boxes[i]
                )
            )
        }
        return detections
    }

    fun close() {
        session.close()
        env.close()
    }
}