package com.moting.linkgo.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.addTextChangedListener

/**
 * 字符串版本的兼容封装
 */

/**
 * 字符串版本的兼容封装
 */
@Composable
fun NativeOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    cornerRadius: Dp = 12.dp,
    shape: Shape = RoundedCornerShape(cornerRadius),
    onFocusChange: (Boolean) -> Unit = {},
    trailingIcon: @Composable (() -> Unit)? = null,
    inputType: Int = android.text.InputType.TYPE_CLASS_TEXT
            or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            or android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
) {
    // 将 String 包装成 TextFieldValue 进行处理，并维护内部的光标状态
    var textFieldValue by remember { 
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length))) 
    }
    
    // 如果外部的 value (String) 改变了，同步更新内部的 textFieldValue
    // 选区位置交给底层的 NativeOutlinedTextField 逻辑识别（仅在文本剧变时修正）
    if (value != textFieldValue.text) {
        textFieldValue = textFieldValue.copy(text = value)
    }
    
    NativeOutlinedTextField(
        value = textFieldValue,
        onValueChange = { 
            textFieldValue = it
            if (it.text != value) {
                onValueChange(it.text)
            }
        },
        modifier = modifier,
        hint = hint,
        singleLine = singleLine,
        maxLines = maxLines,
        enabled = enabled,
        cornerRadius = cornerRadius,
        shape = shape,
        onFocusChange = onFocusChange,
        trailingIcon = trailingIcon,
        inputType = inputType
    )
}

/**
 * 完整的 TextFieldValue 版本，支持光标同步
 */
@Composable
fun NativeOutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    cornerRadius: Dp = 12.dp,
    shape: Shape = RoundedCornerShape(cornerRadius),
    onFocusChange: (Boolean) -> Unit = {},
    trailingIcon: @Composable (() -> Unit)? = null,
    inputType: Int = android.text.InputType.TYPE_CLASS_TEXT
            or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            or android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
) {
    var isFocused by remember { mutableStateOf(false) }

    val focusedColor = MaterialTheme.colorScheme.primary
    val unfocusedColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) focusedColor else unfocusedColor,
        animationSpec = tween(150),
        label = "borderColor"
    )
    val borderWidth = if (isFocused) 2.dp else 1.dp

    val textColorArgb = textColor.toArgb()
    val density = LocalDensity.current
    val textSizeSp = MaterialTheme.typography.bodyLarge.fontSize.value
    val paddingVPx = with(density) { 12.dp.toPx() }.toInt()
    val paddingHPx = with(density) { 16.dp.toPx() }.toInt()

    // --- 同步逻辑重构 ---
    class UpdateInterceptor {
        var isUpdating = false
        inline fun <T> withNoUpdate(block: () -> T): T {
            isUpdating = true
            try { return block() } finally { isUpdating = false }
        }
    }
    val interceptor = remember { UpdateInterceptor() }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentValue by rememberUpdatedState(value)

    // --- Floating Label Logic (Optimized for Draw Phase) ---
    // 使用 lambda 形式读取状态，使动画变化不触发 Recomposition
    val floatAnimState = animateFloatAsState(
        targetValue = if (isFocused || value.text.isNotEmpty()) 1f else 0f,
        animationSpec = tween(150),
        label = "floatAnim"
    )

    val textMeasurer = rememberTextMeasurer()
    val labelTextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = textSizeSp.sp)
    
    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp, minWidth = 80.dp)
            .drawWithContent {
                drawContent()
                val strokeWidth = borderWidth.toPx()
                val corner = cornerRadius.toPx()
                val floatFactor = floatAnimState.value // 触发绘制层读取

                if (hint != null && floatFactor > 0f) {
                    // 核心修复：使用 TextMeasurer 进行精确测量替代估算
                    val measuredLabel = textMeasurer.measure(
                        text = hint,
                        style = labelTextStyle
                    )
                    val gapStart = 12.dp.toPx()
                    // 标签缩放比例为 1f -> 0.75f，所以切口也要对应缩放
                    val labelScale = 1f - (0.25f * floatFactor)
                    val gapWidth = (measuredLabel.size.width * labelScale + 8.dp.toPx()) * floatFactor
                    
                    clipRect(
                        left = gapStart,
                        top = -strokeWidth * 2,
                        right = gapStart + gapWidth,
                        bottom = strokeWidth * 2,
                        clipOp = ClipOp.Difference
                    ) {
                        drawRoundRect(
                            color = borderColor,
                            size = size,
                            cornerRadius = CornerRadius(corner, corner),
                            style = Stroke(strokeWidth)
                        )
                    }
                } else {
                    drawRoundRect(
                        color = borderColor,
                        size = size,
                        cornerRadius = CornerRadius(corner, corner),
                        style = Stroke(strokeWidth)
                    )
                }
            }
    ) {
        AndroidView<SelectionAwareEditText>(
            factory = { ctx ->
                SelectionAwareEditText(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    background = null
                    setPadding(paddingHPx, paddingVPx, paddingHPx, paddingVPx)
                    setTextColor(textColorArgb)
                    textSize = textSizeSp
                    isEnabled = enabled
                    this.maxLines = maxLines
                    isSingleLine = singleLine
                    this.inputType = if (singleLine) {
                        android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                    } else inputType

                    gravity = if (singleLine) {
                        android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                    } else {
                        android.view.Gravity.TOP or android.view.Gravity.START
                    }

                    setOnFocusChangeListener { _, focused ->
                        if (isFocused != focused) {
                            isFocused = focused
                            onFocusChange(focused)
                        }
                    }

                    onSelectionUpdate = { start, end ->
                        if (!interceptor.isUpdating) {
                            val cv = currentValue
                            val currentNativeText = text?.toString() ?: ""
                            val newRange = TextRange(start, end)
                            
                            // 关键优化：如果选区发生变化，或者是 native 文本已经领先于 Compose 状态，
                            // 我们必须回传最新的文本和选区。这样即使 selection 事件先于 text 事件到达 Compose，
                            // 也不会因为回传了旧 text 而导致 Compose 在重组时把 native 的更改回滚掉。
                            if (newRange != cv.selection || currentNativeText != cv.text) {
                                currentOnValueChange(TextFieldValue(
                                    text = currentNativeText,
                                    selection = newRange
                                ))
                            }
                        }
                    }

                    addTextChangedListener { editable ->
                        if (!interceptor.isUpdating) {
                            val newText = editable?.toString() ?: ""
                            val cv = currentValue
                            // 如果文本已变，同步回传最新的文本和当前 native 选区
                            if (newText != cv.text) {
                                currentOnValueChange(TextFieldValue(
                                    text = newText,
                                    selection = TextRange(selectionStart, selectionEnd)
                                ))
                            }
                        }
                    }

                }
            },
            update = { editText ->
                // 仅在数据确实发生变化时才同步
                interceptor.withNoUpdate {
                    val newValueText = value.text
                    val isTextModifiedExternally = editText.text?.toString() != newValueText
                    
                    if (isTextModifiedExternally) {
                        editText.setText(newValueText)
                        
                        // 只有当文本内容确实发生改变（外部逻辑修改）时，才强制同步光标
                        // 否则，光标位置应由原生 EditText 内部由于用户输入而自动维持
                        val safeStart = value.selection.start.coerceIn(0, newValueText.length)
                        val safeEnd = value.selection.end.coerceIn(0, newValueText.length)
                        if (editText.selectionStart != safeStart || editText.selectionEnd != safeEnd) {
                            editText.setSelection(safeStart, safeEnd)
                        }
                    }
                }
                
                // 视觉属性同步保持
                if (editText.isEnabled != enabled) {
                    editText.isEnabled = enabled
                }
                val targetedTextColor = textColor.toArgb()
                if (editText.currentTextColor != targetedTextColor) {
                    editText.setTextColor(targetedTextColor)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (trailingIcon != null) 36.dp else 0.dp)
        )

        if (trailingIcon != null) {
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.CenterEnd)
                    .padding(end = 4.dp)
            ) {
                trailingIcon()
            }
        }

        if (hint != null) {
            val typography = MaterialTheme.typography
            Text(
                text = hint,
                color = if (isFocused) focusedColor else hintColor,
                style = typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .graphicsLayer {
                        // 核心优化：在 graphicsLayer 中通过读取 floatAnimState.value 改变视觉属性
                        // 这不会触发父容器的 Recomposition
                        val floatFactor = floatAnimState.value
                        transformOrigin = TransformOrigin(0f, 0f)
                        val scale = 1f - (0.25f * floatFactor)
                        scaleX = scale
                        scaleY = scale
                        val startY = 12.dp.toPx()
                        val targetY = -(size.height * scale / 2f)
                        translationY = startY + (targetY - startY) * floatFactor
                        translationX = 16.dp.toPx()
                    }
            )
        }
    }
}

/**
 * 带有光标位置变动监听的 EditText
 */
internal class SelectionAwareEditText(context: Context) : EditText(context) {
    var onSelectionUpdate: ((Int, Int) -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionUpdate?.invoke(selStart, selEnd)
    }

    /**
     * 实现焦点搜索隔离。
     * 解决 MIUI 等系统在建立输入连接期间可能触发的递归焦点搜索死循环 (ANR)。
     */
    override fun focusSearch(direction: Int): View? {
        // 如果是从 TextView 系统内部触发（如 hasEditorInFocusSearchDirection）
        // 我们强制返回自身，不让搜索请求穿透到 Compose 容器
        return this
    }

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        // 在建立输入连接的关键时刻，确保不进行任何可能引起级联重组的焦点搜索
        return try {
            super.onCreateInputConnection(outAttrs)
        } catch (e: Exception) {
            null
        }
    }
}
