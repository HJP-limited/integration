package com.example.hjp.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BlueOnDark,
    onPrimary = Slate900,
    primaryContainer = BluePrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = BlueOnDark,
    onSecondary = Slate900,
    background = BackgroundDark,
    onBackground = Color.White,
    surface = SurfaceDark,
    onSurface = Color.White,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = Slate400,
    outlineVariant = Slate700,
)

private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = Color.White,
    primaryContainer = BlueSoft,
    onPrimaryContainer = BlueOnSoft,
    secondary = BlueAccent,
    onSecondary = Color.White,
    secondaryContainer = SkySoft,
    onSecondaryContainer = SkyOnSoft,
    tertiary = EmeraldOnSoft,
    tertiaryContainer = EmeraldSoft,
    onTertiaryContainer = EmeraldOnSoft,
    error = RedOnSoft,
    errorContainer = RedSoft,
    onErrorContainer = RedOnSoft,
    // 목업은 흰 카드가 옅은 회색 바탕 위에 뜬다. background 와 surface 를 다르게 둬야
    // 카드가 면으로 읽힌다 — 같게 두면 그림자만 남아 밋밋해진다.
    background = Slate100,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate50,
    onSurfaceVariant = Slate500,
    outline = Slate400,
    outlineVariant = Slate200,
    surfaceTint = BluePrimary,
    inverseSurface = Slate900,
    inverseOnSurface = Slate100,
    inversePrimary = BlueOnDark,
)

@Composable
fun HJPTheme(
    // 앱 로고와 시작 화면이 밝은 브랜드 시스템으로 설계되어 기본값은 시스템
    // 다크모드를 따르지 않는다. 명시적인 미리보기·테스트에서는 true 를 줄 수 있다.
    darkTheme: Boolean = false,
    // 기기 배경화면 색을 따라가면 목업 팔레트가 묻히므로 끈다
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
