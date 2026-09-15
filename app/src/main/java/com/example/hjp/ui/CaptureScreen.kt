package com.example.hjp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.hjp.tool.contact.BusinessCardRecord
import com.example.hjp.DebugImage
import com.example.hjp.ocr.AndroidOcr
import com.example.hjp.ocr.OcrImageLoader
import com.example.hjp.ocr.CardParser
import com.example.hjp.ocr.OcrPipeline
import com.example.hjp.ocr.OcrCardMapper
import com.example.hjp.ui.theme.EmeraldOnSoft
import com.example.hjp.ui.theme.EmeraldSoft
import com.example.hjp.ui.theme.Slate400
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

// Disposal must outlive composition cancellation and never wait for native inference on main.
private val ocrDisposalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        var unowned: AndroidOcr? = null
        try {
            // Assign inside IO: prompt cancellation during dispatcher return must not lose the
            // only reference to an already-created native engine.
            withContext(Dispatchers.IO) { unowned = AndroidOcr.createOrNull(context) }
            currentCoroutineContext().ensureActive()
            ocr = unowned
            loadFailed = unowned == null
            unowned = null
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { unowned?.close() }
        }
    }
    DisposableEffect(ocr) {
        val owned = ocr
        onDispose { ocrDisposalScope.launch { owned?.close() } }
    }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedImage by rememberSaveable { mutableStateOf<String?>(null) }
    var quarterTurns by rememberSaveable { mutableStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var decoding by remember { mutableStateOf(false) }

    LaunchedEffect(selectedImage, quarterTurns) {
        preview = null
        val address = selectedImage ?: return@LaunchedEffect
        decoding = true
        error = null
        try {
            preview = withContext(Dispatchers.IO) {
                val original = OcrImageLoader.load(context, Uri.parse(address))
                OcrImageLoader.rotateClockwise(original, quarterTurns).also {
                    if (it !== original) original.recycle()
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "사진을 읽을 수 없습니다." }
        catch (failure: OutOfMemoryError) { error = "사진 처리 메모리가 부족합니다. 다른 앱을 닫고 다시 시도해 주세요." }
        finally { decoding = false }
    }

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
    // OcrImageLoader가 EXIF를 처리한다. 수동 회전 없는 기본 입력을 검증하는 경로다.
    // 키에 pending 을 넣는다 — 이게 없으면 이미 떠 있는 화면에 새 인텐트가 들어와도
    // 효과가 다시 돌지 않아 조용히 무시된다(에뮬레이터에서 실제로 그랬다).
    LaunchedEffect(DebugImage.pending, ocr, busy) {
        val path = DebugImage.pending
        if (path != null && ocr != null && !busy) {
            DebugImage.consume()
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { OcrImageLoader.load(context, Uri.fromFile(File(path))) }.getOrNull()
            }
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
        if (uri != null) { quarterTurns = 0; selectedImage = uri.toString() }
    }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = pendingCapture
        pendingCapture = null
        if (ok && uri != null) {
            quarterTurns = 0
            selectedImage = uri
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

        if (selectedImage != null) {
            preview?.let { bitmap ->
                Image(bitmap.asImageBitmap(), "인식 전 사진 방향 확인", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(260.dp))
            }
            Text("글자가 똑바로 보이는지 확인하세요. 이 사진 그대로 인식하고 결과에도 표시합니다.")
            SecondaryButton(text = "오른쪽으로 90° 회전", enabled = !busy && !decoding,
                onClick = { quarterTurns = (quarterTurns + 1) % 4 })
            PrimaryButton(text = if (busy) "인식 중…" else "이 방향으로 인식", icon = HjpIcons.CHECK,
                enabled = preview != null && !busy && !decoding && ocr != null,
                onClick = { preview?.let(::recognize) })
        }

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
            pendingCapture = uri.toString()
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
                    "OCR 검출·인식·글줄 방향 모델, KIE 분류기·토크나이저·라벨 및 문자 사전이 모두 필요해요. " +
                        "assets/ocr 자산과 모델 로드 상태를 확인해 주세요. 모델 없이 인식을 진행하지 않습니다.",
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
    saveError: String? = null,
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

        saveError?.let { message ->
            SectionCard {
                SectionTitle("명함 임베딩 저장 실패")
                Text(
                    message,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
