package com.example.hjp.ui.theme

import androidx.compose.ui.graphics.Color

// 화면 목업(SCR-01~09)의 팔레트. Tailwind blue/slate 계열을 그대로 옮겼다 —
// 목업이 디자인 기준이라 값이 흔들리면 화면끼리 톤이 어긋난다.
val BluePrimary = Color(0xFF2563EB)      // blue-600 — 주요 버튼·활성 탭
val BluePrimaryDark = Color(0xFF1D4ED8)  // blue-700 — 그라데이션 끝
val BlueSoft = Color(0xFFEFF6FF)         // blue-50  — 활성 탭 배경, 칩
val BlueOnSoft = Color(0xFF1E40AF)       // blue-800 — 연한 배경 위 텍스트

val Slate900 = Color(0xFF0F172A)         // 제목
val Slate700 = Color(0xFF334155)         // 본문
val Slate500 = Color(0xFF64748B)         // 보조 설명
val Slate400 = Color(0xFF94A3B8)         // 비활성·플레이스홀더
val Slate200 = Color(0xFFE2E8F0)         // 구분선
val Slate100 = Color(0xFFF1F5F9)         // 화면 배경
val Slate50 = Color(0xFFF8FAFC)          // 카드 안쪽 옅은 면

val VioletSoft = Color(0xFFF5F3FF)       // Agent 빠른 액션
val VioletOnSoft = Color(0xFF7C3AED)
val EmeraldSoft = Color(0xFFECFDF5)      // 신뢰도 배지
val EmeraldOnSoft = Color(0xFF059669)
val AmberStar = Color(0xFFFBBF24)        // 즐겨찾기 별
val RedSoft = Color(0xFFFEF2F2)          // 삭제 액션
val RedOnSoft = Color(0xFFEF4444)

// 다크 모드 — 목업은 라이트 기준이라 대비만 뒤집고 포인트 색은 유지한다.
val BlueOnDark = Color(0xFF93C5FD)       // blue-300
val SurfaceDark = Color(0xFF1E293B)      // slate-800
val BackgroundDark = Color(0xFF0F172A)   // slate-900
