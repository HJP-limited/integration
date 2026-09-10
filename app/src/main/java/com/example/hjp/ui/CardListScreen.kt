package com.example.hjp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.search.CardSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SortOrder(val label: String) {
    Recent("최신순"),
    Name("이름순"),
    Company("회사순"),
}

/** SCR-05 명함 목록 — 전체 리스트와 검색바. */
@Composable
fun CardListScreen(
    searchService: CardSearchService,
    onCardClick: (BusinessCardEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOrder.Recent) }
    var all by remember { mutableStateOf<List<BusinessCardEntity>>(emptyList()) }
    var hits by remember { mutableStateOf<List<BusinessCardEntity>?>(null) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { searchService.recentCards(Int.MAX_VALUE) }
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) {
            hits = null
            return
        }
        searching = true
        scope.launch {
            // 목록 검색은 키워드 경로만 쓴다 — 임베딩까지 태우면 타이핑마다 수백 ms 가 붙는다.
            // 자연어 질문은 Agent 탭이 담당한다.
            val found = withContext(Dispatchers.IO) {
                runCatching { searchService.searchKeywordOnly(q, 20).results.map { it.card } }
                    .getOrDefault(emptyList())
            }
            hits = found
            searching = false
        }
    }

    val shown = (hits ?: all).let { list ->
        when (sort) {
            SortOrder.Recent -> list
            SortOrder.Name -> list.sortedBy { it.name }
            SortOrder.Company -> list.sortedBy { it.company }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(vertical = 16.dp)) {
            ScreenHeader("명함 목록", "이름·회사·직급으로 찾습니다")
        }

        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            TextField(
                value = query,
                onValueChange = { query = it; if (it.isBlank()) hits = null },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("이름, 회사, 직급 검색", fontSize = 14.sp) },
                leadingIcon = { Text(HjpIcons.SEARCH, fontSize = 18.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortOrder.entries.forEach { option ->
                SortChip(option.label, option == sort) { sort = option }
            }
        }

        val header = when {
            searching -> "검색 중…"
            hits != null -> "검색 결과 ${shown.size}건"
            else -> "전체 ${shown.size}장"
        }
        Text(
            header,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { card ->
                SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 6.dp
                )) {
                    ContactRow(card = card, onClick = { onCardClick(card) })
                }
            }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
