package com.example.hjp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.hjp.tool.contact.BusinessCardRecord
import com.example.hjp.ocr.AndroidOcr
import com.example.hjp.ocr.CardParser
import com.example.hjp.ocr.OcrCardMapper
import com.example.hjp.ui.theme.EmeraldOnSoft
import com.example.hjp.ui.theme.EmeraldSoft
import com.example.hjp.ui.theme.Slate400
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 인식은 끝났고 아직 저장 전인 명함. SCR-03 → SCR-04 로 넘어가는 값. */
data class OcrDraft(
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
                        image = bitmap,
                        fields = it.fields,
                        regionCount = it.regions.size,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        classifier = engine.fieldClassifier,
                    )
                )
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
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

        Image(
            bitmap = draft.image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
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

private fun decodeBitmap(context: Context, uri: Uri): Bitmap? =
    try {
        context.contentResolver.openInputStream(uri).use { input ->
            // ARGB_8888 로 강제한다 — OpenCV 의 bitmapToMat 이 하드웨어 비트맵을 못 읽는다.
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    } catch (_: Throwable) {
        null
    }
