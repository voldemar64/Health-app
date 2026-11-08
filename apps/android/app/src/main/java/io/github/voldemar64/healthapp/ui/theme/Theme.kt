package io.github.voldemar64.healthapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF252525),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FC),
    onPrimaryContainer = Color(0xFF283C70),
    secondary = Color(0xFF465E9F),
    secondaryContainer = Color(0xFFDDE5FC),
    onSecondaryContainer = Color(0xFF253B70),
    background = Color(0xFFEDECF2),
    onBackground = Color(0xFF252525),
    surface = Color.White,
    onSurface = Color(0xFF252525),
    onSurfaceVariant = Color(0xFF60616B),
    surfaceContainer = Color(0xFFEDECF2),
    surfaceContainerHighest = Color.White,
)

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun HealthAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = AppTypography,
        shapes = Shapes(medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(32.dp)), content = content)
}
