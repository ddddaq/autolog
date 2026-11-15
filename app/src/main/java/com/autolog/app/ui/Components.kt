package com.autolog.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autolog.app.data.Purpose

/** 용무의 화면 이름. 사용자가 설정에서 바꾼 버튼 이름이 있으면 그것을 돌려준다. */
internal val LocalPurposeLabel = compositionLocalOf<(Purpose) -> String> { { it.label } }

/** 화면에 놓인 글자 입력 칸들의 자리. 누른 곳이 입력 칸 바깥인지 가리는 데 쓴다. */
internal class InputFields {
    val bounds = HashMap<Any, Rect>()
}

internal val LocalInputFields = staticCompositionLocalOf { InputFields() }

/** 글자 입력 칸에 붙인다. 칸의 자리를 알려 두고, 화면에서 사라지면 지운다. */
internal fun Modifier.inputField(): Modifier = composed {
    val fields = LocalInputFields.current
    val key = remember { Any() }
    DisposableEffect(key) { onDispose { fields.bounds.remove(key) } }
    onGloballyPositioned { fields.bounds[key] = it.boundsInRoot() }
}

/**
 * 화면 뼈대에 붙인다. 입력 칸 바깥을 눌렀다 떼면 포커스를 풀어 키보드를 내린다.
 * 누른 것을 가로채지 않으므로 그 자리의 버튼은 그대로 눌린다. 버튼이 먼저 눌린 뒤(손을 뗀 뒤)에 포커스를 푼다.
 */
internal fun Modifier.dismissKeyboardOnOutsideTap(): Modifier = composed {
    val focus = LocalFocusManager.current
    val fields = LocalInputFields.current
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val outside = fields.bounds.values.none { it.contains(down.position) }
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
            if (outside) focus.clearFocus()
        }
    }
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(36.dp)) { content() }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text = text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 덜 중요한 동작에 쓰는 테두리 버튼. 글자만 있는 버튼은 차 안에서 누르기에 작아서, 누를 자리를 높이 72dp로 잡는다.
 */
@Composable
internal fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        shape = shape,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.clip(shape).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 72.dp).padding(horizontal = 26.dp)) {
            Text(text = text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 알약 모양 선택 버튼. 탭과 운전자 선택에 쓴다. */
@Composable
internal fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = shape,
        modifier = Modifier.clip(shape).clickable(onClick = onClick),
    ) {
        Text(text = text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp))
    }
}
