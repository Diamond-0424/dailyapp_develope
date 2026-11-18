package com.dailyapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyapp.R

/*
 * 視覺風格：沿用使用者 create-pdf 的個人預設 ——
 * 黑白極簡、白底黑字、灰階分隔線，以字重與留白建立層次，寬鬆版面。
 * 字體：Noto Serif TC（與思源宋體同一設計），可變字重 400 / 600 / 700。
 */

object Ink {
    val Black = Color(0xFF111111)
    val Body = Color(0xFF222222)
    val Secondary = Color(0xFF6B6B6B)
    val Rule = Color(0xFFE2E2E2)
    val Fill = Color(0xFFF5F5F5)
    val White = Color(0xFFFFFFFF)
}

private fun serif(weight: Int) = Font(
    R.font.noto_serif_tc,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val SerifTC = FontFamily(serif(400), serif(600), serif(700))

private val Regular = FontWeight(400)
private val SemiBold = FontWeight(600)
private val Bold = FontWeight(700)

private fun style(size: Int, weight: FontWeight, lineHeight: Double = 1.5, color: Color = Ink.Body) =
    TextStyle(
        fontFamily = SerifTC,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * lineHeight).sp,
        color = color,
    )

private val AppTypography = Typography(
    headlineMedium = style(28, Bold, 1.3, Ink.Black),   // 頁面標題
    titleLarge = style(20, Bold, 1.35, Ink.Black),
    titleMedium = style(17, SemiBold, 1.45, Ink.Black), // 項目標題
    titleSmall = style(14, SemiBold, 1.45, Ink.Black),  // 區段標籤
    bodyLarge = style(16, Regular),
    bodyMedium = style(15, Regular),
    bodySmall = style(13, Regular, 1.5, Ink.Secondary),
    labelLarge = style(15, SemiBold, 1.3, Ink.Black),   // 按鈕
    labelMedium = style(13, SemiBold, 1.3, Ink.Black),
    labelSmall = style(12, Regular, 1.3, Ink.Secondary),
)

private val AppColors = lightColorScheme(
    primary = Ink.Black, onPrimary = Ink.White,
    secondary = Ink.Secondary, onSecondary = Ink.White,
    background = Ink.White, onBackground = Ink.Body,
    surface = Ink.White, onSurface = Ink.Body,
    surfaceVariant = Ink.Fill, onSurfaceVariant = Ink.Secondary,
    surfaceContainer = Ink.White, surfaceContainerLow = Ink.White, surfaceContainerHigh = Ink.Fill,
    outline = Ink.Rule, outlineVariant = Ink.Rule,
    error = Ink.Black, onError = Ink.White,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = AppTypography) {
        Surface(color = Ink.White, content = content)
    }
}

/** 版面間距：寬鬆版面，左右 24dp，區段之間留白明顯。 */
object Space {
    val Page = 24.dp
    val Section = 32.dp
    val Item = 18.dp
}

@Composable
fun PageHeader(title: String, subtitle: String? = null) {
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.Page, end = Space.Page, top = 28.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(color = Ink.Secondary, letterSpacing = 1.sp),
        modifier = modifier.padding(horizontal = Space.Page).padding(top = Space.Section, bottom = 8.dp),
    )
}

@Composable
fun Rule(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier.padding(horizontal = Space.Page), thickness = 1.dp, color = Ink.Rule)

/** 灰框小標籤，取代彩色 chip 與 emoji。 */
@Composable
fun Tag(text: String) {
    Surface(shape = RoundedCornerShape(2.dp), border = BorderStroke(1.dp, Ink.Rule), color = Ink.White) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** 文字按鈕：黑字，無底色，左緣與上方文字對齊。 */
@Composable
fun PlainAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

/** 主要動作：細黑框。 */
@Composable
fun OutlineAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(1.dp, Ink.Black),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** 一列「名稱 ……… 狀態」，用於健康檢查等清單。 */
@Composable
fun KeyValue(key: String, value: String, emphasize: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.Page, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium.copy(color = Ink.Secondary),
        )
    }
}

/** 底部分頁：純文字，選中者粗體黑字並有上方黑線。 */
@Composable
fun BottomTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        HorizontalDivider(thickness = 1.dp, color = Ink.Rule)
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(28.dp).height(2.dp).background(if (on) Ink.Black else Color.Transparent))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (on) Bold else Regular,
                            color = if (on) Ink.Black else Ink.Secondary,
                        ),
                        modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
                    )
                }
            }
        }
    }
}
