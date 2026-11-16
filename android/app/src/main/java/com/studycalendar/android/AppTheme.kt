package com.studycalendar.android

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

@Composable
fun StudyCalendarTheme(content: @Composable () -> Unit) {
  val type = Typography()
  MaterialTheme(
    colorScheme = lightColorScheme(
      primary = Color(0xFF7056ED), onPrimary = Color.White,
      primaryContainer = Color(0xFFF0EDFF), onPrimaryContainer = Color(0xFF543BCB),
      secondaryContainer = Color(0xFFF0EDFF), onSecondaryContainer = Color(0xFF543BCB),
      background = Color(0xFFF7F8FC), onBackground = Color(0xFF252D3D),
      surface = Color.White, onSurface = Color(0xFF252D3D),
      surfaceVariant = Color(0xFFF3F4F9), onSurfaceVariant = Color(0xFF596273),
      outline = Color(0xFF737B8C), outlineVariant = Color(0xFFE3E5EB)
    ),
    typography = type.copy(headlineLarge = type.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)),
    content = content
  )
}
