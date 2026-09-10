package com.example.hjp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SCR-01 로그인.
 *
 * **인증 백엔드가 없다.** 계정 서버도 OAuth 도 붙어 있지 않아서, 입력값은 검증되지도 저장되지도
 * 않고 기기 밖으로 나가지도 않는다. 진짜 로그인처럼 보이게 두면 어디까지 동작하는지 알 수
 * 없게 되므로 화면에 그대로 적어 둔다 — 지금은 목업의 배치를 확인하기 위한 껍데기다.
 */
@Composable
fun LoginScreen(
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        Box(
            Modifier
                .size(76.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "HJP",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
            )
        }

        Text(
            "명함을 더 쉽게 관리하세요",
            fontSize = 25.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            "OCR로 명함 정보를 자동 추출하고, AI Agent로 필요한 연락처를 바로 찾을 수 있습니다.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 12.dp),
        )

        Spacer(Modifier.height(28.dp))

        SectionCard {
            Text(
                "이메일",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                placeholder = { Text("user@example.com", fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )

            Text(
                "비밀번호",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                placeholder = { Text("••••••••", fontSize = 14.sp) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(16.dp),
            )

            Spacer(Modifier.height(20.dp))
            PrimaryButton(text = "시작하기", icon = HjpIcons.LOCK, onClick = onEnter)
            Spacer(Modifier.height(10.dp))
            SecondaryButton(text = "회원가입", icon = HjpIcons.USER, onClick = onEnter)
        }

        Spacer(Modifier.height(20.dp))
        SectionCard {
            Text(
                "아직 계정 기능이 없습니다",
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "인증 서버와 Gmail 연동이 붙기 전이라 입력값은 검증되지 않고 기기 밖으로 나가지도 " +
                    "않습니다. 어느 버튼을 눌러도 그대로 앱으로 들어갑니다.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
