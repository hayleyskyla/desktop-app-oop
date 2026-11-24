package ph.agritourism.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ph.agritourism.model.AvailabilityStatus

object Palette {
    val Forest = Color(0xFF1E562F)
    val Deep = Color(0xFF144824)
    val Ink = Color(0xFF1E3A24)
    val Mint = Color(0xFFE8F3EB)
    val Cream = Color(0xFFF8F6F0)
    val Border = Color(0xFFDDE7DF)
    val Muted = Color(0xFF64748B)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFDC2626)
    val Teal = Color(0xFF0D9488)
    val Glass = Color(0xF2FFFFFF)
}

val AppColors = lightColorScheme(
    primary = Palette.Forest, onPrimary = Color.White, secondary = Palette.Deep,
    background = Palette.Cream, surface = Color.White, onSurface = Color(0xFF1E1E1E),
    primaryContainer = Palette.Mint, onPrimaryContainer = Palette.Deep, error = Palette.Red,
)

@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 20.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = onClick != null && hovered
    val shape = RoundedCornerShape(18.dp)
    val springSpec = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
    val elevation by animateDpAsState(if (active) 18.dp else 4.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "card-elev")
    val lift by animateFloatAsState(if (active && !pressed) -4f else 0f, springSpec, label = "card-lift")
    val scale by animateFloatAsState(when { pressed && onClick != null -> 0.985f; active -> 1.01f; else -> 1f }, springSpec, label = "card-scale")
    val border by animateColorAsState(if (active) Palette.Forest.copy(alpha = 0.30f) else Color(0xFFE6ECE7), tween(220), label = "card-border")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift * density }
            .hoverable(interaction)
            .shadow(elevation, shape, ambientColor = Color(0x1A0F2A17), spotColor = Color(0x330F2A17))
            .clip(shape)
            .background(Palette.Glass)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.handCursor().clickable(interaction, LocalIndication.current, onClick = onClick) else Modifier)
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            .padding(padding),
        content = content,
    )
}

/** Fades and slides content in once when it first enters composition; [index] staggers siblings. */
@Composable
fun Modifier.entrance(index: Int = 0, offset: Dp = 18.dp): Modifier {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val delay = (index.coerceAtMost(10)) * 45
    val alpha by animateFloatAsState(if (started) 1f else 0f, tween(380, delay, FastOutSlowInEasing), label = "enter-a")
    val dy by animateFloatAsState(if (started) 0f else 1f, tween(460, delay, FastOutSlowInEasing), label = "enter-y")
    return graphicsLayer { this.alpha = alpha; translationY = dy * offset.toPx() }
}

/** Subtle hover/press scale feedback for buttons and chips. */
@Composable
private fun pressScale(interaction: MutableInteractionSource): Float {
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val v by animateFloatAsState(when { pressed -> 0.96f; hovered -> 1.03f; else -> 1f },
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    return v
}

/**
 * Compact single-line search box. Built on BasicTextField so the placeholder is vertically
 * centred at any height (Material's OutlinedTextField reserves 56dp and pushes text down).
 */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, height: Dp = 40.dp) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(when { focused -> Palette.Forest; hovered -> Palette.Forest.copy(alpha = 0.45f); else -> Palette.Border }, tween(180), label = "search-border")
    val ring by animateDpAsState(if (focused) 4.dp else 0.dp, spring(stiffness = Spring.StiffnessMedium), label = "search-ring")
    val iconTint by animateColorAsState(if (focused) Palette.Forest else Palette.Muted, tween(180), label = "search-icon")
    val shape = RoundedCornerShape(50)
    BasicTextField(
        value, onChange,
        modifier
            .height(height)
            .hoverable(interaction)
            .focusRing(ring, Palette.Forest.copy(alpha = 0.14f), height / 2)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, border, shape),
        singleLine = true,
        interactionSource = interaction,
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Color(0xFF1E1E1E)),
        cursorBrush = SolidColor(Palette.Forest),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(17.dp), tint = iconTint)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = Palette.Muted.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                AnimatedVisibility(value.isNotEmpty(), enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.6f), exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.6f)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).handCursor().clickable { onChange("") }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, "Clear search", Modifier.size(14.dp), tint = Palette.Muted)
                    }
                }
            }
        },
    )
}

@Composable
fun H1(text: String) = Text(text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
@Composable
fun H2(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Deep)
@Composable
fun Sub(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1) =
    Text(text, fontSize = 13.sp, color = Palette.Muted, modifier = modifier, maxLines = maxLines, minLines = minLines, overflow = TextOverflow.Ellipsis)
@Composable
fun Label(text: String, color: Color = Palette.Muted) = Text(text.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = color, letterSpacing = 0.6.sp)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, color: Color = Palette.Forest, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Button(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }.handCursor(enabled), enabled = enabled, shape = RoundedCornerShape(12.dp),
        interactionSource = interaction,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp, hoveredElevation = 6.dp, pressedElevation = 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = Palette.Forest, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) color.copy(alpha = 0.07f) else Color.Transparent, tween(160), label = "sec-bg")
    OutlinedButton(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }.handCursor(enabled), enabled = enabled, shape = RoundedCornerShape(12.dp),
        interactionSource = interaction,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = color, containerColor = bg),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

/** Minimal text-only action used for Back / Close / Exit so no boxed control is rendered. */
@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = Palette.Forest, onClick: () -> Unit) {
    Text(
        text,
        modifier = modifier.handCursor().clickable(onClick = onClick).padding(vertical = 4.dp),
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
    )
}

/** Set while a native image chooser is open so focus loss cannot dismiss the editor behind it. */
object ModalDismissGuard {
    var suppressed: Boolean = false
}

/** Back action with an arrow icon; keeps the label for clarity and accessibility. */
@Composable
fun BackAction(modifier: Modifier = Modifier, color: Color = Palette.Forest, onClick: () -> Unit) {
    Row(modifier.handCursor().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(17.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text("Back", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** X close action. [showLabel] keeps the word Close in wide footers; compact headers use the icon only. */
@Composable
fun CloseAction(modifier: Modifier = Modifier, color: Color = Palette.Forest, showLabel: Boolean = true, onClick: () -> Unit) {
    Row(modifier.handCursor().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Close, "Close", Modifier.size(17.dp), tint = color)
        if (showLabel) {
            Spacer(Modifier.width(6.dp))
            Text("Close", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
fun Pill(text: String, bg: Color = Palette.Mint, fg: Color = Palette.Deep, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
}

/** Compact status badge used on tour/orchard cards, booking forms and admin tables. */
@Composable
fun AvailabilityPill(status: AvailabilityStatus, remaining: Int? = null, capacity: Int? = null, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        AvailabilityStatus.AVAILABLE -> Color(0xFFDCFCE7) to Color(0xFF166534)
        AvailabilityStatus.LIMITED -> Color(0xFFFEF3C7) to Color(0xFF92400E)
        AvailabilityStatus.FULL -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
        AvailabilityStatus.UNAVAILABLE -> Color(0xFFE2E8F0) to Color(0xFF475569)
    }
    val label = if (remaining != null && capacity != null && status != AvailabilityStatus.UNAVAILABLE)
        "${status.label} · $remaining/$capacity pax" else status.label
    Pill(label, bg, fg, modifier)
}

@Composable
fun FilterChipRow(options: List<String>, selected: String, label: (String) -> String = { it }, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val sel = o == selected
            val bg by animateColorAsState(if (sel) Palette.Forest else Color.White, label = "chip-bg")
            val fg by animateColorAsState(if (sel) Color.White else Palette.Ink, label = "chip-fg")
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val border by animateColorAsState(if (sel || hovered) Palette.Forest else Palette.Border, label = "chip-border")
            val scale = pressScale(interaction)
            Text(label(o), Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(50))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(50))
                .hoverable(interaction).handCursor().clickable(interaction, LocalIndication.current) { onSelect(o) }.padding(horizontal = 14.dp, vertical = 7.dp),
                color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

fun statusColors(status: String): Pair<Color, Color> = when (status) {
    "Confirmed", "Completed" -> Color(0xFFDCFCE7) to Color(0xFF166534)
    "Pending" -> Color(0xFFFEF3C7) to Color(0xFF92400E)
    "Cancelled" -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
    else -> Palette.Mint to Palette.Deep
}

@Composable
fun StatusPill(status: String) { val (bg, fg) = statusColors(status); Pill(status, bg, fg) }

/** Remembers the last non-null value so exit animations keep showing their content. */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST") return holder[0] as T?
}

/** Soft focus halo drawn outside the bounds so it never shifts layout. */
fun Modifier.focusRing(width: Dp, color: Color, corner: Dp): Modifier = drawBehind {
    val r = width.toPx()
    if (r > 0.1f) drawRoundRect(color, Offset(-r, -r), Size(size.width + 2 * r, size.height + 2 * r), CornerRadius(corner.toPx() + r))
}

fun Modifier.handCursor(enabled: Boolean = true): Modifier = if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

/** Horizontal shake played whenever [trigger] changes to a non-null value. */
@Composable
private fun shakeOffset(trigger: Any?): Float {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger != null) for (x in listOf(-9f, 8f, -6f, 4f, -2f, 0f)) shake.animateTo(x, tween(45))
    }
    return shake.value
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
          singleLine: Boolean = true, minLines: Int = 1, enabled: Boolean = true, password: Boolean = false, number: Boolean = false,
          icon: ImageVector? = null, error: String? = null, helper: String? = null, maxLength: Int? = null, onSubmit: (() -> Unit)? = null,
          testTag: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    var reveal by remember { mutableStateOf(false) }
    val isError = error != null
    val accent = if (isError) Palette.Red else Palette.Forest
    val border by animateColorAsState(when {
        !enabled -> Palette.Border; isError -> Palette.Red; focused -> Palette.Forest
        hovered -> Palette.Forest.copy(alpha = 0.45f); else -> Palette.Border }, tween(180), label = "field-border")
    val ring by animateDpAsState(if (focused && enabled) 4.dp else if (isError) 3.dp else 0.dp, spring(stiffness = Spring.StiffnessMedium), label = "field-ring")
    val ringColor by animateColorAsState(accent.copy(alpha = if (focused) 0.15f else 0.08f), tween(180), label = "field-ring-c")
    val tint by animateColorAsState(when { isError -> Palette.Red; focused -> Palette.Forest; else -> Palette.Muted }, tween(180), label = "field-tint")
    val bg by animateColorAsState(if (enabled) Color.White else Color(0xFFF3F5F4), tween(180), label = "field-bg")
    val dx = shakeOffset(error)
    val shape = RoundedCornerShape(12.dp)
    Column(modifier) {
        if (label.isNotEmpty()) { Label(label, tint.takeIf { focused || isError } ?: Palette.Muted); Spacer(Modifier.height(5.dp)) }
        BasicTextField(
            value, { v -> onChange(if (maxLength != null) v.take(maxLength) else v) },
            Modifier.fillMaxWidth()
                .graphicsLayer { translationX = dx * density }
                .focusRing(ring, ringColor, 12.dp)
                .clip(shape).background(bg).border(1.dp, border, shape)
                .hoverable(interaction)
                .onPreviewKeyEvent {
                    if (onSubmit != null && singleLine && it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { onSubmit(); true } else false
                }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            enabled = enabled, singleLine = singleLine, minLines = minLines, maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            interactionSource = interaction,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = if (enabled) Color(0xFF1E1E1E) else Palette.Muted),
            cursorBrush = SolidColor(accent),
            visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = when { number -> KeyboardOptions(keyboardType = KeyboardType.Number); password -> KeyboardOptions(keyboardType = KeyboardType.Password); else -> KeyboardOptions.Default },
            decorationBox = { inner ->
                Row(Modifier.then(if (singleLine) Modifier.heightIn(min = 44.dp) else Modifier).padding(start = 12.dp, end = 6.dp, top = if (singleLine) 0.dp else 11.dp, bottom = if (singleLine) 0.dp else 11.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
                    if (icon != null) { Icon(icon, null, Modifier.size(17.dp), tint = tint); Spacer(Modifier.width(9.dp)) }
                    Box(Modifier.weight(1f).padding(end = 6.dp), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                        if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = Palette.Muted.copy(alpha = 0.7f), maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                    if (password) {
                        Box(Modifier.size(30.dp).clip(CircleShape).handCursor().clickable { reveal = !reveal }, contentAlignment = Alignment.Center) {
                            Crossfade(reveal, animationSpec = tween(160), label = "pw-eye") { r ->
                                Icon(if (r) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (r) "Hide password" else "Show password", Modifier.size(17.dp), tint = tint)
                            }
                        }
                    } else if (singleLine && enabled) {
                        AnimatedVisibility(value.isNotEmpty() && (focused || hovered), enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.6f), exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.6f)) {
                            Box(Modifier.size(26.dp).clip(CircleShape).handCursor().clickable { onChange("") }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, "Clear", Modifier.size(14.dp), tint = Palette.Muted)
                            }
                        }
                    }
                }
            },
        )
        val supporting = error ?: helper
        val shown = rememberLast(supporting)
        AnimatedVisibility(supporting != null || maxLength != null, enter = fadeIn(tween(180)) + expandVertically(spring(stiffness = Spring.StiffnessMedium)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160))) {
            Row(Modifier.fillMaxWidth().padding(top = 5.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (shown != null && supporting != null) {
                    if (isError) { Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(13.dp), tint = Palette.Red); Spacer(Modifier.width(4.dp)) }
                    Text(shown, Modifier.weight(1f), fontSize = 11.sp, color = if (isError) Palette.Red else Palette.Muted)
                } else Spacer(Modifier.weight(1f))
                if (maxLength != null) Text("${value.length}/$maxLength", fontSize = 10.sp, color = if (value.length >= maxLength) Palette.Amber else Palette.Muted)
            }
        }
    }
}

/** Animated inline error banner; slides in when [message] is set and out when cleared. */
@Composable
fun ErrorBanner(message: String?, modifier: Modifier = Modifier) {
    val shown = rememberLast(message)
    val dx = shakeOffset(message)
    AnimatedVisibility(message != null, modifier, enter = fadeIn(tween(180)) + expandVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)),
        exit = fadeOut(tween(120)) + shrinkVertically(tween(160))) {
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().graphicsLayer { translationX = dx * density }.clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFEF2F2)).border(1.dp, Color(0xFFFECACA), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = Palette.Red)
            Spacer(Modifier.width(8.dp))
            Text(shown.orEmpty(), fontSize = 12.sp, color = Color(0xFF991B1B), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T, text: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(when { open -> Palette.Forest; hovered -> Palette.Forest.copy(alpha = 0.45f); else -> Palette.Border }, tween(180), label = "dd-border")
    val ring by animateDpAsState(if (open) 4.dp else 0.dp, spring(stiffness = Spring.StiffnessMedium), label = "dd-ring")
    val rot by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), label = "dd-rot")
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val shape = RoundedCornerShape(12.dp)
    Column(modifier) {
        if (label.isNotEmpty()) { Label(label, if (open) Palette.Forest else Palette.Muted); Spacer(Modifier.height(5.dp)) }
        Box {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).onSizeChanged { anchorWidth = it.width }
                .focusRing(ring, Palette.Forest.copy(alpha = 0.15f), 12.dp)
                .clip(shape).background(Color.White).border(1.dp, border, shape)
                .hoverable(interaction).handCursor().clickable(interaction, LocalIndication.current) { open = !open }.padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(text(selected), Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF1E1E1E))
                Icon(Icons.Filled.ExpandMore, null, Modifier.graphicsLayer { rotationZ = rot }, tint = if (open) Palette.Forest else Palette.Muted)
            }
            DropdownMenu(open, { open = false }, Modifier.width(with(density) { anchorWidth.toDp() }).background(Color.White)) {
                options.forEach { o ->
                    val sel = o == selected
                    DropdownMenuItem(
                        { Text(text(o), fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, color = if (sel) Palette.Forest else Color(0xFF1E1E1E)) },
                        { onSelect(o); open = false },
                        Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp)).background(if (sel) Palette.Mint else Color.Transparent).handCursor(),
                        trailingIcon = if (sel) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Palette.Forest) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, desc: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, tween(160), label = "step-a")
    Box(Modifier.size(34.dp).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }.clip(CircleShape)
        .background(if (filled) Palette.Forest else Palette.Mint)
        .hoverable(interaction).handCursor(enabled).clickable(interaction, LocalIndication.current, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(18.dp), tint = if (filled) Color.White else Palette.Deep)
    }
}

@Composable
fun Stepper(value: Int, suffix: String, min: Int = 1, max: Int = 999, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(Icons.Filled.Remove, "Decrease", false, value > min) { onChange(maxOf(min, value - 1)) }
        Row(Modifier.padding(horizontal = 14.dp).widthIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(value, transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) { if (up) it else -it } + fadeIn(tween(150))) togetherWith
                    (slideOutVertically(tween(150)) { if (up) -it else it } + fadeOut(tween(120))) using SizeTransform(clip = false)
            }, label = "stepper") { v -> Text("$v", fontWeight = FontWeight.ExtraBold, color = Palette.Deep, fontSize = 15.sp) }
            if (suffix.isNotEmpty()) Text(" $suffix", fontWeight = FontWeight.ExtraBold, color = Palette.Deep, fontSize = 15.sp)
        }
        StepButton(Icons.Filled.Add, "Increase", true, value < max) { onChange(minOf(max, value + 1)) }
    }
}

@Composable
fun ChoiceRow(options: List<String>, selected: String, onSelect: (String) -> Unit) = FilterChipRow(options, selected, onSelect = onSelect)

@Composable
fun Modal(onDismiss: () -> Unit, width: Dp = 640.dp, content: @Composable ColumnScope.() -> Unit) {
    Dialog({ if (!ModalDismissGuard.suppressed) onDismiss() }, DialogProperties(usePlatformDefaultWidth = false)) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val a by animateFloatAsState(if (shown) 1f else 0f, tween(220, easing = FastOutSlowInEasing), label = "modal-a")
        val sc by animateFloatAsState(if (shown) 1f else 0.94f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "modal-s")
        Column(Modifier.graphicsLayer { alpha = a; scaleX = sc; scaleY = sc; translationY = (1f - a) * 24f * density }
            .shadow(32.dp, RoundedCornerShape(24.dp)).widthIn(max = width).fillMaxWidth(0.92f).heightIn(max = 760.dp).clip(RoundedCornerShape(24.dp))
            .background(Color.White).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
fun ModalHeader(title: String, subtitle: String? = null, icon: ImageVector? = null, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Palette.Deep, Palette.Forest))).padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = Color(0xFF86EFAC)); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle.uppercase(), color = Color(0xFF86EFAC), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        }
        CloseAction(color = Color.White, showLabel = false, onClick = onClose)
    }
}

@Composable
fun InfoTile(label: String, value: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFFF8FAFC)).border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Label(label)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(14.dp), tint = Palette.Forest); Spacer(Modifier.width(6.dp)) }
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
        }
    }
}

@Composable
fun Avatar(src: String, size: Dp) {
    if (src.isBlank()) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Color(0xFFE2E8F0)).border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Person, "Default profile picture", Modifier.size(size * 0.58f), tint = Color(0xFF64748B))
        }
    } else {
        AsyncImage(src, Modifier.size(size).clip(CircleShape).border(2.dp, Color.White, CircleShape))
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { H2(title); if (subtitle != null) Sub(subtitle) }
        action?.invoke()
    }
}

/** Simple responsive grid for use inside a vertically scrolling column. */
@Composable
fun <T> Grid(items: List<T>, columns: Int, spacing: Dp = 16.dp, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.chunked(columns).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                row.forEachIndexed { c, item -> Box(Modifier.weight(1f).entrance(r * columns + c)) { cell(item) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun RatingText(rating: Double, count: Int) = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Filled.Star, null, Modifier.size(15.dp), tint = Palette.Amber)
    Spacer(Modifier.width(4.dp))
    Text("$rating ($count)", fontSize = 12.sp, color = Palette.Muted, fontWeight = FontWeight.SemiBold)
}

@Composable
fun PhotoCard(image: String, title: String, locationTag: String, rating: Double, reviews: Int, price: Int, favorited: Boolean,
              onFavorite: () -> Unit, onClick: () -> Unit, priceLabel: String? = null, availability: AvailabilityStatus? = null) {
    GlassCard(padding = 0.dp, onClick = onClick) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            AsyncImage(image, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x55000000))))
            Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(locationTag, Color.White.copy(alpha = 0.92f), Palette.Deep)
                if (availability != null) AvailabilityPill(availability)
            }
            val heart by animateFloatAsState(if (favorited) 1f else 0.85f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium), label = "heart")
            IconButton(onFavorite, Modifier.handCursor().align(Alignment.TopEnd).padding(8.dp).size(36.dp).graphicsLayer { scaleX = heart + 0.15f; scaleY = heart + 0.15f }.clip(CircleShape).background(Color.White.copy(alpha = 0.9f))) {
                Icon(if (favorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Save to favorites", tint = Color(0xFFEF4444))
            }
        }
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Palette.Deep, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (reviews > 0) RatingText(rating, reviews) else Text("New listing", fontSize = 11.sp, color = Palette.Muted)
                Spacer(Modifier.weight(1f))
                Text(priceLabel ?: "$PESO$price", fontWeight = FontWeight.ExtraBold, color = if (priceLabel == null) Palette.Forest else Color(0xFF92400E))
                if (priceLabel == null) Text(" /person", fontSize = 11.sp, color = Palette.Muted)
            }
        }
    }
}

/** Tourist reviews with stars, author and comment. */
@Composable
fun ReviewList(reviews: List<ph.agritourism.model.CustomerReview>, empty: String = "No reviews yet.") {
    if (reviews.isEmpty()) { Sub(empty); return }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reviews.forEach { r ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF8FAFC)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(5) { i -> Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = if (i < r.rating) Palette.Amber else Color(0xFFE2E8F0)) }
                    Spacer(Modifier.width(8.dp)); Text(r.author, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(" · ${r.roleTag} · ${r.date}", fontSize = 11.sp, color = Palette.Muted)
                }
                Text(r.farmOrCrop, fontSize = 11.sp, color = Palette.Forest, fontWeight = FontWeight.SemiBold)
                Text(r.comment, fontSize = 13.sp, color = Color(0xFF334155))
            }
        }
    }
}

/** Avatar with a camera button that lets the user pick a new photo. */
@Composable
fun EditableAvatar(src: String, size: Dp, onEdit: () -> Unit) {
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(Modifier.handCursor().clickable(onClick = onEdit)) { Avatar(src, size) }
        IconButton(onEdit, Modifier.size(size / 3).clip(CircleShape).background(Palette.Forest).border(2.dp, Color.White, CircleShape).handCursor()) {
            Icon(Icons.Outlined.PhotoCamera, "Change photo", Modifier.size(size / 6), tint = Color.White)
        }
    }
}
