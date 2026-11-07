package com.topsprintspeed.finish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Top Sprint Speed colours: maroon, black and a light Vegas gold. */
object Palette {
    val Maroon = Color(0xFF8C1D2C)
    val MaroonDark = Color(0xFF3A1218)
    val Black = Color(0xFF0B0A0B)
    val Nav = Color(0xFF120D0E)
    val Surface1 = Color(0xFF1C1416)
    val Surface2 = Color(0xFF2B1F22)
    val Border = Color(0xFF4A3A3D)
    val Gold = Color(0xFFC5B358)
    val GoldLight = Color(0xFFE0D08A)
    val LineGold = Color(0xFFF0D86A)
    val Ink = Color(0xFFF5F1E8)
    val Ink2 = Color(0xFFD6CDBF)
    val Muted = Color(0xFFADA39A)
}

val Mono = FontFamily.Monospace

@Composable
fun SprintTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Gold, onPrimary = Palette.Black,
            secondary = Palette.Maroon, onSecondary = Palette.Ink,
            background = Palette.Black, onBackground = Palette.Ink,
            surface = Palette.Surface1, onSurface = Palette.Ink,
            surfaceVariant = Palette.Surface2, onSurfaceVariant = Palette.Muted,
            error = Palette.Maroon, onError = Palette.Ink,
            outline = Palette.Border,
        ),
        content = content,
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, warn: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (warn) Palette.MaroonDark else Palette.Surface1)
            .border(1.dp, if (warn) Palette.Maroon else Palette.Border, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun Label(text: String) = Text(text, color = Palette.Muted, fontSize = 13.sp)

@Composable
fun Title(text: String) = Text(text, color = Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Segmented(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface1)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Button(
                onClick = { onSelect(i) },
                enabled = enabled,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (on) Palette.Gold else Color.Transparent,
                    contentColor = if (on) Palette.Black else Palette.Ink2,
                    disabledContainerColor = if (on) Palette.Gold.copy(alpha = 0.5f) else Color.Transparent,
                    disabledContentColor = Palette.Muted,
                ),
            ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface1)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
        SmallButton("−", onMinus)
        Text(value, fontFamily = Mono, color = Palette.Ink, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.width(84.dp))
        SmallButton("+", onPlus)
    }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Surface2, contentColor = Palette.Ink),
    ) { Text(text, fontSize = 20.sp) }
}

@Composable
fun GoldButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Gold, contentColor = Palette.Black),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun MaroonButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Maroon, contentColor = Palette.Ink),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun LineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Ink),
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
}
