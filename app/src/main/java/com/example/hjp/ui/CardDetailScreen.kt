package com.example.hjp.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hjp.tool.contact.BusinessCardRecord
import com.example.hjp.ui.theme.Slate700
import com.example.hjp.ui.theme.Slate900

/** SCR-06 명함 상세 — 단일 명함의 전체 정보와 액션. */
@Composable
fun CardDetailScreen(
    card: BusinessCardRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // 처리할 앱이 없으면 조용히 넘어간다 — 여기서 죽을 이유가 없다.
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
        ScreenHeader("명함 상세", null, onBack = onBack)

        // 목업의 어두운 명함 카드
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(Slate900, Slate700)),
                    RoundedCornerShape(24.dp),
                )
                .padding(20.dp)
        ) {
            Column {
                Text(
                    card.company.ifBlank { "—" },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    card.name.ifBlank { "(이름 없음)" },
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 28.dp),
                )
                val role = listOf(card.department, card.title).filter { it.isNotBlank() }.joinToString(" · ")
                if (role.isNotBlank()) {
                    Text(
                        role,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column(Modifier.padding(top = 22.dp)) {
                    listOf(card.email, card.phone, card.location).filter { it.isNotBlank() }.forEach {
                        Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                }
            }
        }

        SectionCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(HjpIcons.PHONE, "전화", Modifier.weight(1f), card.phone.isNotBlank()) {
                    open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${card.phone}")))
                }
                ActionButton(HjpIcons.MAIL, "메일", Modifier.weight(1f), card.email.isNotBlank()) {
                    open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${card.email}")))
                }
                ActionButton(HjpIcons.MAP, "위치", Modifier.weight(1f), card.address.isNotBlank()) {
                    open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(card.address)}")))
                }
            }
        }

        SectionCard {
            SectionTitle("전체 정보")
            InfoRow("이메일", card.email)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoRow("전화", card.phone)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoRow("주소", card.address)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoRow("지역", card.location)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoRow("메모", card.memo)

            val tags = card.tags.map { it.trim() }.filter { it.isNotBlank() }
            if (tags.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.forEach { Tag(it) }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.35f
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}
