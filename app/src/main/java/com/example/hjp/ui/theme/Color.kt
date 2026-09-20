package com.example.hjp.ui.theme

import androidx.compose.ui.graphics.Color

// HJP 로고의 코발트 블루와 밝은 아이스 블루를 기준으로 한 앱 팔레트.
// 화면 전체는 로고의 흰 여백처럼 밝게 유지하고, 상호작용 요소에만 선명한 파랑을 쓴다.
val BluePrimary = Color(0xFF1F5EFF)      // 로고 코발트 — 주요 버튼·활성 탭
val BluePrimaryDark = Color(0xFF1746D1)  // 로고 그라데이션의 짙은 끝
val BlueAccent = Color(0xFF2D96F3)       // 로고 스카이 블루 — 보조 액션
val BlueSoft = Color(0xFFEAF2FF)         // 활성 탭 배경, 칩
val BlueOnSoft = Color(0xFF1746A2)       // 연한 배경 위 텍스트
val SkySoft = Color(0xFFEEF7FF)          // AI 액션 배경
val SkyOnSoft = Color(0xFF176DB5)        // AI 액션 텍스트·아이콘

val Slate900 = Color(0xFF17233D)         // 제목 — 블루 톤의 잉크색
val Slate700 = Color(0xFF42526D)         // 본문
val Slate500 = Color(0xFF6C7A94)         // 보조 설명
val Slate400 = Color(0xFF93A2BA)         // 비활성·플레이스홀더
val Slate200 = Color(0xFFDCE5F2)         // 구분선
val Slate100 = Color(0xFFF7F9FE)         // 화면 배경
val Slate50 = Color(0xFFF0F5FF)          // 카드 안쪽 옅은 면

val VioletSoft = Color(0xFFF5F3FF)       // Agent 빠른 액션
val VioletOnSoft = Color(0xFF7C3AED)
val EmeraldSoft = Color(0xFFECFDF5)      // 신뢰도 배지
val EmeraldOnSoft = Color(0xFF059669)
val AmberStar = Color(0xFFFBBF24)        // 즐겨찾기 별
val RedSoft = Color(0xFFFEF2F2)          // 삭제 액션
val RedOnSoft = Color(0xFFEF4444)

// 명시적으로 다크 테마를 요청하는 미리보기·테스트용 색상.
val BlueOnDark = Color(0xFF93C5FD)       // blue-300
val SurfaceDark = Color(0xFF1E293B)      // slate-800
val BackgroundDark = Color(0xFF0F172A)   // slate-900
