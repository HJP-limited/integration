package com.example.hjp.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PP-OCRv5 mobile det + korean rec ONNX 파이프라인.
 *
 * `HJP/App/ref_pipeline.py` 의 이식본이다(PaddleOCR 대비 정합 검증 완료: 500장 GT
 * coverage 0.9480 vs 0.9494). **상수와 단계는 참조 구현과 1:1 이어야 한다** —
 * 바꿀 때는 ref_pipeline.py 를 먼저 고치고 정합을 다시 확인한 뒤 여기로 옮긴다.
 *
 * 입력을 안드로이드 Bitmap 이 아니라 OpenCV BGR [Mat] 으로 받는다. 이미지 타입만
 * 플랫폼이 변환해 주면 검출·인식 경로 전체가 폰과 노트북에서 같은 코드로 돈다.
 */
class OcrPipeline(assets: OcrAssets) {

    /** 검출 박스 꼭짓점. 플랫폼 이미지 타입에 묶이지 않도록 자체 정의한다. */
    data class Pt(val x: Float, val y: Float)

    data class Region(val poly: List<Pt>, val text: String, val score: Float)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val det: OrtSession
    private val rec: OrtSession
    private val charset: List<String>

    /**
     * 글줄 위아래를 바로잡는 단계. 모델이 없으면 null 이고 그대로 인식한다.
     *
     * 참조 구현의 [5] textline_orientation 에 해당한다 — 원근 보정 **뒤**, 인식 **앞**.
     * 이 자리인 이유: 크롭이 이미 가로로 누운 뒤라야 분류기가 학습된 모양(160x80)과 맞고,
     * 인식기에 넣기 전이라야 뒤집힘을 고칠 수 있다.
     */
    private val textLineOrientation: TextLineOrientation?

    /** 진단용. 이 단계가 실제로 켜져 있는지 화면·로그가 물어볼 수 있어야 한다. */
    val textLineOrientationEnabled: Boolean get() = textLineOrientation != null

    init {
        val opts = OrtSession.SessionOptions()
        det = env.createSession(assets.require("det.onnx"), opts)
        rec = env.createSession(assets.require("rec.onnx"), opts)
        // 없으면 null 이고 그 단계만 빠진다 — 모델 파일이 배치되지 않아도 촬영은 돌아야 한다.
        textLineOrientation = TextLineOrientation.createOrNull(env, assets)
        charset = buildList {
            add("<blank>")
            val dict = assets.text("korean_dict.txt")
                ?: throw IllegalStateException("OCR asset not found: korean_dict.txt")
            // **끝의 빈 줄을 버린다.** 사전 파일이 개행으로 끝나는데 lineSequence() 는 그 뒤의
            // 빈 문자열도 한 항목으로 내놓는다(파이썬 splitlines() 는 안 그런다). 그 한 칸이
            // 공백 문자의 번호를 밀어내서, 인식된 공백이 전부 빈 문자열이 됐다 —
            // "경기도 성남시 분당구" 가 "경기도성남시분당구" 로 저장됐고, FTS 는 공백에서
            // 자르므로 주소가 통째로 낱말 하나가 됐다.
            dict.lineSequence().forEach { line -> if (line.isNotEmpty()) add(line) }
            // 마지막 항목이 공백이다. 인식기의 charset 은 blank + 사전 + 공백 순서다.
            add(" ")
        }
    }

    companion object {
        private const val DET_LIMIT_SIDE = 960.0
        private const val DET_THRESH = 0.3
        private const val DET_BOX_THRESH = 0.6
        private const val DET_UNCLIP_RATIO = 1.5
        private const val DET_MIN_SIZE_PRE = 3.0
        private const val DET_MIN_SIZE_POST = 5.0
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val REC_H = 48
        private const val REC_W = 320
        private const val REC_MAX_W = 3200
        private const val MAX_CANDIDATES = 1000
    }

    /** [bgr] 은 BGR 3채널 Mat. 호출부가 소유하며 여기서 해제하지 않는다. */
    fun run(bgr: Mat): List<Region> {
        val origH = bgr.rows()
        val origW = bgr.cols()

        // ---- det preprocess: limit-max resize, /32 round ----
        val maxSide = max(origH, origW).toDouble()
        val ratio = if (maxSide > DET_LIMIT_SIDE) DET_LIMIT_SIDE / maxSide else 1.0
        val rh = max(((origH * ratio).toInt() / 32.0).roundToInt() * 32, 32)
        val rw = max(((origW * ratio).toInt() / 32.0).roundToInt() * 32, 32)
        val resized = Mat()
        Imgproc.resize(bgr, resized, Size(rw.toDouble(), rh.toDouble()))

        val chw = FloatArray(3 * rh * rw)
        run {
            val f = Mat()
            resized.convertTo(f, CvType.CV_32FC3, 1.0 / 255.0)
            val hwc = FloatArray(rh * rw * 3)
            f.get(0, 0, hwc)
            val plane = rh * rw
            for (i in 0 until plane) {
                val b = hwc[i * 3]; val g = hwc[i * 3 + 1]; val r = hwc[i * 3 + 2]
                chw[i] = (b - MEAN[0]) / STD[0]
                chw[plane + i] = (g - MEAN[1]) / STD[1]
                chw[2 * plane + i] = (r - MEAN[2]) / STD[2]
            }
            f.release()
        }

        // ---- det inference ----
        val prob: FloatArray
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(chw),
            longArrayOf(1, 3, rh.toLong(), rw.toLong())
        ).use { input ->
            det.run(mapOf("x" to input)).use { out ->
                val t = out[0] as OnnxTensor
                prob = FloatArray(rh * rw)
                t.floatBuffer.get(prob)
            }
        }

        // ---- DB postprocess ----
        val probMat = Mat(rh, rw, CvType.CV_32FC1)
        probMat.put(0, 0, prob)
        val binary = Mat(rh, rw, CvType.CV_8UC1)
        run {
            val bytes = ByteArray(rh * rw)
            for (i in prob.indices) if (prob[i] > DET_THRESH) bytes[i] = 1
            binary.put(0, 0, bytes)
        }
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            binary, contours, Mat(), Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        val ratioH = rh.toDouble() / origH
        val ratioW = rw.toDouble() / origW
        val results = ArrayList<Pair<Array<Point>, Double>>()
        for (contour in contours.take(MAX_CANDIDATES)) {
            val pts2f = MatOfPoint2f(*contour.toArray())
            val rect = Imgproc.minAreaRect(pts2f)
            if (min(rect.size.width, rect.size.height) < DET_MIN_SIZE_PRE) continue
            val box = orderedBoxPoints(rect)
            val score = boxScore(probMat, box)
            if (score < DET_BOX_THRESH) continue
            // unclip: offset the rect by area*ratio/perimeter (== shapely
            // buffer + minAreaRect on a rectangle)
            val area = rect.size.width * rect.size.height
            val perimeter = 2 * (rect.size.width + rect.size.height)
            if (perimeter <= 0) continue
            val d = area * DET_UNCLIP_RATIO / perimeter
            val expanded = RotatedRect(
                rect.center,
                Size(rect.size.width + 2 * d, rect.size.height + 2 * d), rect.angle
            )
            if (min(expanded.size.width, expanded.size.height) < DET_MIN_SIZE_POST) continue
            val ebox = orderedBoxPoints(expanded)
            for (p in ebox) {
                p.x = (p.x / ratioW).coerceIn(0.0, (origW - 1).toDouble())
                p.y = (p.y / ratioH).coerceIn(0.0, (origH - 1).toDouble())
            }
            results.add(ebox to score)
        }

        // ---- rec per box ----
        val regions = ArrayList<Region>()
        for ((box, detScore) in results) {
            val crop = cropQuad(bgr, box) ?: continue
            // 뒤집힌 글줄을 바로 세운다. 검출기는 사각형만 주고 위아래는 알려주지 않는다.
            textLineOrientation?.upright(crop)
            val (text, recScore) = recognize(crop)
            crop.release()
            if (text.isEmpty()) continue
            val combined = sqrt(detScore * recScore).toFloat()
            regions.add(
                Region(
                    box.map { Pt(it.x.toFloat(), it.y.toFloat()) },
                    Postprocess.apply(text), combined
                )
            )
        }

        probMat.release(); binary.release(); resized.release()
        return regions
    }

    /** minAreaRect points ordered [tl, tr, br, bl] (DBPostProcess order). */
    private fun orderedBoxPoints(rect: RotatedRect): Array<Point> {
        val pts = arrayOfNulls<Point>(4)
        rect.points(pts)
        val sorted = pts.filterNotNull().sortedBy { it.x }
        val (i1, i4) = if (sorted[0].y <= sorted[1].y) 0 to 1 else 1 to 0
        val (i2, i3) = if (sorted[2].y <= sorted[3].y) 2 to 3 else 3 to 2
        return arrayOf(sorted[i1], sorted[i2], sorted[i3], sorted[i4])
    }

    /** Mean probability inside the quad (box_score_fast). */
    private fun boxScore(probMat: Mat, box: Array<Point>): Double {
        val w = probMat.cols(); val h = probMat.rows()
        val xmin = box.minOf { it.x }.toInt().coerceIn(0, w - 1)
        val xmax = ceil(box.maxOf { it.x }).toInt().coerceIn(0, w - 1)
        val ymin = box.minOf { it.y }.toInt().coerceIn(0, h - 1)
        val ymax = ceil(box.maxOf { it.y }).toInt().coerceIn(0, h - 1)
        if (xmax <= xmin || ymax <= ymin) return 0.0
        val mask = Mat.zeros(ymax - ymin + 1, xmax - xmin + 1, CvType.CV_8UC1)
        val shifted = MatOfPoint(*box.map {
            Point(it.x - xmin, it.y - ymin)
        }.toTypedArray())
        Imgproc.fillPoly(mask, listOf(shifted), Scalar(1.0))
        val region = probMat.submat(Rect(xmin, ymin, xmax - xmin + 1, ymax - ymin + 1))
        val mean = Core.mean(region, mask).`val`[0]
        mask.release()
        return mean
    }

    /** Perspective-rectify quad; rotate 90 if tall (ocr.py _crop_quad). */
    private fun cropQuad(img: Mat, box: Array<Point>): Mat? {
        val w = max(
            hypot(box[0].x - box[1].x, box[0].y - box[1].y),
            hypot(box[2].x - box[3].x, box[2].y - box[3].y)
        ).toInt()
        val h = max(
            hypot(box[0].x - box[3].x, box[0].y - box[3].y),
            hypot(box[1].x - box[2].x, box[1].y - box[2].y)
        ).toInt()
        if (w <= 1 || h <= 1) return null
        val src = MatOfPoint2f(*box)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(w.toDouble(), 0.0),
            Point(w.toDouble(), h.toDouble()), Point(0.0, h.toDouble())
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val crop = Mat()
        // PaddleX 와 같은 플래그다. 기본값(BORDER_CONSTANT/INTER_LINEAR)이면 글자가 잘린
        // 가장자리에 검은 테두리가 생기고 확대가 거칠어져, 작은 글씨에서 인식이 흔들린다.
        Imgproc.warpPerspective(
            img, crop, m, Size(w.toDouble(), h.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE,
        )
        m.release()
        if (h > w * 1.5) {
            // **반시계** 방향이다. 원본(ocr.py `_crop_quad`)이 PaddleX 의 `np.rot90` 과 맞추려고
            // 그렇게 했는데, 이식본은 시계 방향으로 돌고 있었다 — 세로로 쓴 글줄이 180도 뒤집힌
            // 채로 인식기에 들어간다.
            //
            // 원본에서는 이 선택이 덜 중요했다. 뒤에 텍스트라인 방향 분류기가 0/180 을 바로잡기
            // 때문이다. 우리는 그 분류기가 없으므로(assets 에 모델이 없다) 여기서 고른 방향이
            // 곧 결과다. 맞추는 편이 낫다.
            Core.rotate(crop, crop, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
        return crop
    }

    /** Rec: dynamic-width resize + CTC decode + NFC. Returns text, score. */
    private fun recognize(crop: Mat): Pair<String, Double> {
        val h = crop.rows(); val w = crop.cols()
        val maxWhRatio = max(REC_W.toDouble() / REC_H, w.toDouble() / h)
        val imgW = min((REC_H * maxWhRatio).toInt(), REC_MAX_W)
        val resizedW = min(imgW, ceil(REC_H.toDouble() * w / h).toInt())
        val resized = Mat()
        Imgproc.resize(crop, resized, Size(resizedW.toDouble(), REC_H.toDouble()))
        val f = Mat()
        resized.convertTo(f, CvType.CV_32FC3, 1.0 / 255.0)
        val hwc = FloatArray(REC_H * resizedW * 3)
        f.get(0, 0, hwc)
        f.release(); resized.release()

        val plane = REC_H * imgW
        val chw = FloatArray(3 * plane) // zero-padded on the right
        for (y in 0 until REC_H) {
            for (x in 0 until resizedW) {
                val src = (y * resizedW + x) * 3
                val dst = y * imgW + x
                chw[dst] = (hwc[src] - 0.5f) / 0.5f
                chw[plane + dst] = (hwc[src + 1] - 0.5f) / 0.5f
                chw[2 * plane + dst] = (hwc[src + 2] - 0.5f) / 0.5f
            }
        }

        val outShape: LongArray
        val logits: FloatArray
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(chw),
            longArrayOf(1, 3, REC_H.toLong(), imgW.toLong())
        ).use { input ->
            rec.run(mapOf("x" to input)).use { out ->
                val t = out[0] as OnnxTensor
                outShape = t.info.shape // [1, T, C]
                logits = FloatArray((outShape[1] * outShape[2]).toInt())
                t.floatBuffer.get(logits)
            }
        }
        return ctcDecode(logits, outShape[1].toInt(), outShape[2].toInt())
    }

    private fun ctcDecode(logits: FloatArray, T: Int, C: Int): Pair<String, Double> {
        val sb = StringBuilder()
        var confSum = 0.0
        var kept = 0
        var prevIdx = -1
        for (t in 0 until T) {
            var best = 0
            var bestV = logits[t * C]
            for (c in 1 until C) {
                val v = logits[t * C + c]
                if (v > bestV) { bestV = v; best = c }
            }
            if (best != 0 && best != prevIdx) {
                sb.append(charset[best])
                confSum += bestV
                kept++
            }
            prevIdx = best
        }
        if (kept == 0) return "" to 0.0
        val text = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
        return text to confSum / kept
    }
}
