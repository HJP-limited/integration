package com.example.hjp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.hjp.tool.contact.BusinessCardRecord
import com.example.hjp.DebugImage
import com.example.hjp.ocr.AndroidOcr
import com.example.hjp.ocr.CardParser
import com.example.hjp.ocr.OcrPipeline
import com.example.hjp.ocr.OcrCardMapper
import com.example.hjp.ui.theme.EmeraldOnSoft
import com.example.hjp.ui.theme.EmeraldSoft
import com.example.hjp.ui.theme.Slate400
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** 인식은 끝났고 아직 저장 전인 명함. SCR-03 → SCR-04 로 넘어가는 값. */
data class OcrDraft(
    /** 검출 영역을 그려 넣은 사본. 사람이 "무엇을 읽었는지" 볼 수 있어야 한다. */
    val image: Bitmap,
    val fields: List<CardParser.Field>,
    val regionCount: Int,
    val elapsedMs: Long,
    val classifier: String,
)

/**
 * SCR-03 명함 촬영.
 *
 * 목업은 실시간 뷰파인더지만 여기서는 **카메라 앱 호출**과 **갤러리 선택**으로 받는다 —
 * 미리보기 프레임을 직접 다루려면 CameraX 가 필요한데, 인식 품질은 최종 정지 이미지로
 * 결정되므로 지금 단계에서 얻을 게 없다.
 */
@Composable
fun CaptureScreen(
    onRecognized: (OcrDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 모델 로드가 수 초 걸려서 화면에 들어올 때 미리 띄운다.
    var ocr by remember { mutableStateOf<AndroidOcr?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { AndroidOcr.createOrNull(context) }
        ocr = loaded
        loadFailed = loaded == null
    }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }

    fun recognize(bitmap: Bitmap) {
        val engine = ocr ?: return
        busy = true
        error = null
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.read(bitmap) }
            }
            busy = false
            result.onSuccess {
                onRecognized(
                    OcrDraft(
                        image = drawDetections(bitmap, it.regions),
                        fields = it.fields,
                        regionCount = it.regions.size,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        classifier = engine.fieldClassifier,
                    )
                )
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    // 디버그 인텐트로 들어온 이미지를 태운다. 갤러리로 고른 것과 **같은 경로**다 —
    // decodeBitmap 이 EXIF 를 보고 세운 뒤 인식으로 넘어간다.
    // 키에 pending 을 넣는다 — 이게 없으면 이미 떠 있는 화면에 새 인텐트가 들어와도
    // 효과가 다시 돌지 않아 조용히 무시된다(에뮬레이터에서 실제로 그랬다).
    LaunchedEffect(DebugImage.pending, ocr, busy) {
        val path = DebugImage.pending
        if (path != null && ocr != null && !busy) {
            DebugImage.consume()
            val bitmap = decodeBitmap(context, Uri.fromFile(File(path)))
            if (bitmap != null) {
                android.util.Log.i(
                    "HJP",
                    "debug image=$path decoded=${bitmap.width}x${bitmap.height}",
                )
                recognize(bitmap)
            } else {
                error = "이미지를 열지 못했어요: $path"
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { decodeBitmap(context, it)?.let(::recognize) ?: run { error = "이미지를 열지 못했어요." } }
    }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = pendingCapture
        pendingCapture = null
        if (ok && uri != null) {
            decodeBitmap(context, uri)?.let(::recognize) ?: run { error = "촬영 결과를 열지 못했어요." }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("명함 촬영", "카메라 또는 갤러리에서 명함을 가져옵니다")

        // 목업의 가이드 프레임. 실제 뷰파인더 대신 촬영 안내 역할을 한다.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.1f)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.onSurface),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1.6f)
                    .border(2.dp, Slate400, RoundedCornerShape(20.dp))
            )
            Text(
                "명함을 프레임에 맞춰 촬영하세요",
                color = Slate400,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp),
            )
            when {
                busy -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        "인식 중…",
                        color = Slate400,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                ocr == null && !loadFailed -> Text("모델 준비 중…", color = Slate400, fontSize = 13.sp)
            }
        }

        val ready = ocr != null && !busy
        PrimaryButton(
            text = "카메라로 촬영",
            icon = HjpIcons.CAMERA,
            enabled = ready,
        ) {
            val uri = newCaptureUri(context)
            pendingCapture = uri
            takePicture.launch(uri)
        }
        SecondaryButton(
            text = "갤러리에서 선택",
            icon = HjpIcons.GALLERY,
            enabled = ready,
        ) {
            pickImage.launch("image/*")
        }

        if (loadFailed) {
            SectionCard {
                SectionTitle("OCR 모델을 불러오지 못했어요")
                Text(
                    "assets/ocr 의 det.onnx · rec.onnx · korean_dict.txt 가 있는지 확인해 주세요.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        error?.let {
            SectionCard {
                SectionTitle("인식에 실패했어요")
                Text(
                    it,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        SectionCard {
            SectionTitle("촬영 팁")
            Text(
                "밝은 곳에서 명함 전체가 보이도록 촬영하면 인식률이 높아집니다.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * SCR-04 OCR 결과 확인. 저장을 눌러야 검색 DB 에 들어간다 — 인식이 틀릴 수 있으므로
 * 사람이 한 번 보고 넘기는 단계를 둔다.
 */
@Composable
fun OcrResultScreen(
    draft: OcrDraft,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("OCR 결과 확인", "추출된 필드를 확인하고 저장합니다", onBack = onCancel)

        // Crop 이 아니라 Fit 이다. 이 이미지에는 검출 박스가 그려져 있는데, 잘라서 보여 주면
        // 가장자리 글줄의 박스가 화면 밖으로 나가 "못 찾은 것"과 구별되지 않는다.
        Image(
            bitmap = draft.image.asImageBitmap(),
            contentDescription = "인식된 명함과 검출 영역",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(24.dp)),
        )

        SectionCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("자동 추출 필드")
                Badge(draft.classifier, EmeraldSoft, EmeraldOnSoft)
            }
            Text(
                "${draft.regionCount}개 영역 · ${draft.elapsedMs}ms",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (draft.fields.isEmpty()) {
                Text(
                    "글자를 찾지 못했어요. 더 밝은 곳에서 다시 촬영해 주세요.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                draft.fields.forEach { field ->
                    InfoRow("${field.icon} ${field.label}", field.value)
                }
            }
        }

        PrimaryButton(
            text = if (saving) "저장 중…" else "저장하기",
            icon = HjpIcons.CHECK,
            enabled = draft.fields.isNotEmpty() && !saving,
            onClick = onSave,
        )
        SecondaryButton(text = "다시 촬영", enabled = !saving, onClick = onCancel)
    }
}

private fun newCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "card_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * 이미지를 읽어 **똑바로 세워서** 돌려준다.
 *
 * 카메라는 폰을 어떻게 들고 찍었든 센서 방향 그대로 저장하고, "보여 줄 때 이만큼 돌려라"를
 * EXIF Orientation 태그로만 남긴다. `BitmapFactory` 는 그 태그를 보지 않으므로, 갤러리에서는
 * 똑바로 보이는 사진이 여기서는 90도 누운 채로 들어온다.
 *
 * 이게 화면만의 문제가 아닌 이유: 이 비트맵이 그대로 OCR 로 들어간다. 누운 한글을 인식시키면
 * 검출은 되는데 글자가 엉킨다. 읽는 자리에서 바로 세우는 게 맞다 — 인식과 미리보기가 같은
 * 비트맵을 쓰므로 한 번만 세우면 둘 다 맞는다.
 */
private fun decodeBitmap(context: Context, uri: Uri): Bitmap? =
    try {
        // 먼저 크기만 읽어 몇 분의 1 로 줄여 받을지 정한다. 요즘 폰 카메라는 5000만 화소라
        // 원본 그대로 펼치면 한 장에 200MB 가까이 쓴다 — 앱이 OutOfMemory 로 죽는다.
        // 검출기가 어차피 긴 변 960 으로 줄이므로 2000 이면 인식 품질에 손해가 없다.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODED_SIDE) sample *= 2

        val decoded = context.contentResolver.openInputStream(uri).use { input ->
            // ARGB_8888 로 강제한다 — OpenCV 의 bitmapToMat 이 하드웨어 비트맵을 못 읽는다.
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
        decoded?.let { uprightByExif(context, uri, it) }
    } catch (_: Throwable) {
        null
    }

/**
 * 검출된 글줄을 원본 위에 그려 준다.
 *
 * 인식이 틀렸을 때 **어디서 틀렸는지** 보이게 하려는 것이다. 필드 목록만 보면 글자를 잘못
 * 읽은 것인지 아예 못 찾은 것인지 구별할 수 없는데, 박스를 보면 바로 안다 — 박스가 없으면
 * 검출 실패, 박스는 맞는데 값이 이상하면 인식 실패다.
 *
 * 흰 테두리를 깔고 그 위에 검은 선을 얹는다. 명함 바탕색이 무엇이든 읽히게 하려는 것으로,
 * 원본 App 트랙과 같은 방식이다.
 */
private fun drawDetections(src: Bitmap, regions: List<OcrPipeline.Region>): Bitmap {
    if (regions.isEmpty()) return src
    val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
    val canvas = Canvas(out)
    // 선 굵기를 이미지 크기에 맞춘다 — 고정 픽셀이면 큰 사진에서 실처럼 가늘어진다.
    val width = max(2.2f, out.width / 400f)
    val halo = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = width * 2.6f
        isAntiAlias = true
    }
    val stroke = Paint().apply {
        color = Color.rgb(10, 10, 10)
        style = Paint.Style.STROKE
        strokeWidth = width
        isAntiAlias = true
    }
    regions.forEach { region ->
        val path = Path()
        region.poly.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        canvas.drawPath(path, halo)
        canvas.drawPath(path, stroke)
    }
    return out
}

/** 이보다 긴 변은 반씩 줄여 받는다. 원본 App 트랙과 같은 값이다. */
private const val MAX_DECODED_SIDE = 2000

/**
 * EXIF 태그가 시키는 대로 회전·반전한다. 태그가 없거나 읽지 못하면 원본을 그대로 돌려준다 —
 * 방향을 짐작해서 돌리면 멀쩡한 사진을 눕히게 된다.
 */
private fun uprightByExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = try {
        context.contentResolver.openInputStream(uri).use { input ->
            input?.let { ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }
    } catch (_: Throwable) {
        null
    } ?: return bitmap

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        // 전치(transpose)/역전치(transverse): 대각선 반사. 회전 + 좌우반전으로 같아진다.
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return bitmap
    }
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    } catch (_: OutOfMemoryError) {
        // 큰 사진이면 회전 사본을 못 만들 수 있다. 누운 사진이라도 없는 것보다 낫다.
        bitmap
    }
}
