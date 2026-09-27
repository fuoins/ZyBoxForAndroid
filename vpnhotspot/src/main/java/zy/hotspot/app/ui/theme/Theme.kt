package zy.hotspot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ZyHotspot：粉色主题（关闭动态取色，固定粉系主色）
private val PinkLight = lightColorScheme().copy(
    primary = Color(0xFFE91E63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E4),
    onPrimaryContainer = Color(0xFF3E001D),
    secondary = Color(0xFFD81B60),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E4),
    onSecondaryContainer = Color(0xFF3E001D),
    tertiary = Color(0xFFEC407A),
)

private val PinkDark = darkColorScheme().copy(
    primary = Color(0xFFFFB2C8),
    onPrimary = Color(0xFF3E001D),
    primaryContainer = Color(0xFF5C1132),
    onPrimaryContainer = Color(0xFFFFD9E4),
    secondary = Color(0xFFFFB2C8),
    onSecondary = Color(0xFF3E001D),
    secondaryContainer = Color(0xFF5C1132),
    onSecondaryContainer = Color(0xFFFFD9E4),
    tertiary = Color(0xFFF06292),
)

@Composable
fun VpnHotspotTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) PinkDark else PinkLight
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

@Composable
fun VpnHotspotPreviewSurface(content: @Composable () -> Unit) {
    VpnHotspotTheme(dynamicColor = false) {
        Surface(content = content)
    }
}
