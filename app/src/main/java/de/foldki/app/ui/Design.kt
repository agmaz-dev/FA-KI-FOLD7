package de.foldki.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Rounded icon tile (settings rows, sheets, cards). [tone] = background to symbol colour. */
@Composable
fun Tile(icon: ImageVector, tone: Pair<Color, Color>, size: Dp = 30.dp, radius: Dp = 7.dp, iconSize: Dp = 18.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(tone.first), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tone.second, modifier = Modifier.size(iconSize))
    }
}

/** Grey upper-case section title above a group. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, top: Dp = 18.dp) {
    Text(
        text.uppercase(), fontSize = 13.sp, color = FK.Muted, letterSpacing = 0.3.sp,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = top, bottom = 7.dp),
    )
}

/** Rounded block of rows with thin lines between them. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FK.Surface), content = content)
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 60.dp), thickness = 0.5.dp, color = FK.Line)

/** One settings row: tile, title, grey line, something on the right. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = { Chevron() },
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FK.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 13.sp, color = FK.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
fun Chevron() = Icon(
    Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = FK.Dim,
    modifier = Modifier.padding(start = 4.dp).size(20.dp),
)

/** Grey value on the right of a row ("leer", "GPU", "75 %"). */
@Composable
fun RowValue(text: String) = Text(text, fontSize = 12.sp, color = FK.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 104.dp))

/** Small status label, green when [on] ("bereit"). */
@Composable
fun Tag(text: String, on: Boolean = false) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (on) FK.Ok else FK.Muted, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) FK.OkSoft else FK.Surface2)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Switch in the iOS look (51 × 31, green when on). [enabled] false = shown but locked. */
@Composable
fun FkToggle(on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.size(51.dp, 31.dp).clip(RoundedCornerShape(16.dp)).background(if (on) FK.Ok else FK.Surface3)
            .clickable(enabled = enabled) { onChange(!on) },
    ) {
        Box(Modifier.offset(x = if (on) 22.dp else 2.dp, y = 2.dp).size(27.dp).clip(CircleShape).background(Color.White))
    }
}

/** Coloured capsule label ("GPU", "Denken", "GGUF"). */
@Composable
fun Pill(text: String, color: Color = FK.Accent) {
    // Grey pill = grey background with grey text (like ".p-gray" of the mock-ups); others = tinted background.
    val gray = color == FK.Muted
    val bg = if (gray) FK.Surface2 else color.copy(alpha = if (color == FK.Ok || color == FK.Warn) 0.16f else 0.18f)
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Grey text under the large title (".sub" of the mock-ups). */
@Composable
fun Sub(text: String) {
    Text(text, fontSize = 15.sp, color = FK.Muted, lineHeight = 20.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
}

/** Small capsule button (".cta"): blue, or grey with blue text when [ghost]. */
@Composable
fun Cta(text: String, ghost: Boolean = false, enabled: Boolean = true, color: Color = FK.Accent, onClick: () -> Unit) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (!enabled) FK.Dim else if (ghost) color else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (ghost || !enabled) FK.Surface2 else color)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

/** Round 36-dp button of the top bar (menu, back, close, new chat). */
@Composable
fun NavBtn(icon: ImageVector, label: String, bg: Color = FK.Surface, tint: Color = FK.Text, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Input row of a group: label left, value right (grey hint when empty), like ".field" of the mock-ups. */
@Composable
fun FieldRow(label: String, value: String, onChange: (String) -> Unit, hint: String = "", password: Boolean = false, number: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, color = FK.Text)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty()) Text(hint, fontSize = 16.sp, color = FK.Dim, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 16.sp, color = FK.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(FK.Accent),
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = if (number) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    else androidx.compose.foundation.text.KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Divider between [FieldRow]s (full width, like ".field+.field"). */
@Composable
fun FieldDivider() = HorizontalDivider(thickness = 0.5.dp, color = FK.Line)

/** iOS-like slider: blue track, white round thumb. [steps] = number of stops between the ends. */
@Composable
fun FkSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, onDone: () -> Unit = {}) {
    androidx.compose.material3.Slider(
        value = value, onValueChange = onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone,
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = Color.White, activeTrackColor = FK.Accent, inactiveTrackColor = FK.Surface3,
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
        ),
    )
}

/** Large page title (iOS "large title"). */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = FK.Text, letterSpacing = (-0.5).sp, maxLines = 2, lineHeight = 40.sp,
        modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 10.dp))
}

/** Segmented control (two to four options). */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(FK.Surface).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(if (i == selected) FK.Surface3 else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (i == selected) FK.Text else FK.Muted, maxLines = 1) }
        }
    }
}

/** Grey search field with a magnifier (like iOS). */
@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FK.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = FK.Dim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = FK.Dim, fontSize = 16.sp, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 16.sp, color = FK.Text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(FK.Accent), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) Icon(androidx.compose.material.icons.Icons.Default.Close, "Leeren", tint = FK.Dim,
            modifier = Modifier.size(18.dp).clickable { onChange("") })
    }
}

/** Letter logo of a model maker (ab 4.9: calm grey tile like all other symbols). */
@Composable
fun ModelLogo(vendor: String, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(FK.Surface3),
        contentAlignment = Alignment.Center,
    ) { Text(vendor.take(1).uppercase(), color = FK.Text, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp) }
}

/** Card with rounded corners (models, memory). [border] e.g. green for the active model. */
@Composable
fun Card(modifier: Modifier = Modifier, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(FK.Surface)
            .then(if (border != null) Modifier.border(1.5.dp, border, shape) else Modifier).padding(16.dp),
        content = content,
    )
}

/** Status dot (green = ready). */
@Composable
fun StatusDot(ok: Boolean) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) FK.Ok else FK.Dim))
}

/** Four-pointed star of the start screen. */
@Composable
fun Spark(size: Dp = 34.dp, color: Color = FK.Accent) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val c = Offset(w / 2, h / 2); val k = 0.16f
        val p = Path().apply {
            moveTo(c.x, 0f)
            quadraticTo(c.x + w * k * 0.35f, c.y - h * k * 0.35f, w, c.y)
            quadraticTo(c.x + w * k * 0.35f, c.y + h * k * 0.35f, c.x, h)
            quadraticTo(c.x - w * k * 0.35f, c.y + h * k * 0.35f, 0f, c.y)
            quadraticTo(c.x - w * k * 0.35f, c.y - h * k * 0.35f, c.x, 0f)
            close()
        }
        drawPath(p, color)
    }
}

/** Round outlined 40-dp button (+, mic). */
@Composable
fun RoundOutline(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(FK.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Filled 34-dp capsule chip (filters, suggestions): white when chosen, like the mock-up. */
@Composable
fun FkChip(text: String, icon: ImageVector? = null, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
            .background(if (active) Color.White else FK.Surface)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (active) Color.Black else FK.Muted, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 14.sp, color = if (active) Color.Black else FK.Text, maxLines = 1)
    }
}

/** Initials in a round orange-tinted badge. */
@Composable
fun Avatar(initials: String, size: Dp = 34.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(FK.AccentSoft), contentAlignment = Alignment.Center) {
        Text(initials.ifBlank { "?" }, color = FK.Accent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp)
    }
}

/** Grey explanatory text. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, color = FK.Muted, lineHeight = 18.sp, modifier = modifier)
}

/** Full-width orange (or outlined) 48-dp button. */
@Composable
fun FkButton(text: String, modifier: Modifier = Modifier, ghost: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
            .background(if (ghost) FK.Surface2 else if (enabled) FK.Accent else FK.Surface2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = if (ghost) FK.Accent else if (enabled) FK.OnAccent else FK.Dim) }
}
