package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
  primary = CyanLight,
  onPrimary = Slate950,
  primaryContainer = CyanContainer,
  onPrimaryContainer = Color.White,
  secondary = EmeraldShield,
  onSecondary = Slate950,
  secondaryContainer = EmeraldContainer,
  onSecondaryContainer = Color.White,
  tertiary = IndigoAccent,
  background = Slate950,
  onBackground = Slate100,
  surface = Slate900,
  onSurface = Slate100,
  surfaceVariant = CardDarkSurface,
  onSurfaceVariant = Slate200,
  outline = SurfaceBorderDark,
  error = RoseLock,
  errorContainer = RoseContainer,
  onError = Color.White
)

private val LightColorScheme = lightColorScheme(
  primary = CyanDark,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFE0F2FE),
  onPrimaryContainer = Color(0xFF0369A1),
  secondary = EmeraldShieldDark,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFD1FAE5),
  onSecondaryContainer = Color(0xFF065F46),
  tertiary = IndigoAccent,
  background = Color(0xFFF8FAFC),
  onBackground = Color(0xFF0F172A),
  surface = Color.White,
  onSurface = Color(0xFF0F172A),
  surfaceVariant = Color(0xFFF1F5F9),
  onSurfaceVariant = Color(0xFF334155),
  outline = Color(0xFFCBD5E1),
  error = Color(0xFFE11D48),
  errorContainer = Color(0xFFFFE4E6),
  onError = Color.White
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Default to sleek guardian dark theme
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
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
