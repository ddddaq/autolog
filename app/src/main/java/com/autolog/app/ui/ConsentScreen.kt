package com.autolog.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 첫 실행 안내. 무엇을 기록하고 어디에 쓰며 어디에 두는지 알리고 동의를 받는다.
 * '동의하고 시작'을 눌렀을 때만 동의로 본다. 그 전에는 차량 신호도 위치도 읽지 않는다.
 */
@Composable
fun ConsentContent(wide: Boolean, onAgree: () -> Unit) {
    var declined by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(text = "오토로그를 시작하기 전에", fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "차량이 보내는 신호로 업무용 승용차 운행기록부를 대신 씁니다. 아래 내용을 읽고 동의하면 기록을 시작합니다.",
            fontSize = 30.sp,
            lineHeight = 42.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Panel { NoticeItems(wide) }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onAgree, shape = RoundedCornerShape(24.dp), modifier = Modifier.height(104.dp)) {
                Text(text = "동의하고 시작", fontSize = 36.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 28.dp))
            }
            FilledTonalButton(onClick = { declined = true }, shape = RoundedCornerShape(24.dp), modifier = Modifier.height(104.dp)) {
                Text(text = "동의하지 않음", fontSize = 36.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 28.dp))
            }
        }
        if (declined) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "동의하지 않았습니다. 운행은 기록되지 않습니다. 생각이 바뀌면 '동의하고 시작'을 눌러 주세요.",
                fontSize = 28.sp,
                lineHeight = 40.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 기록 안내의 항목들. 첫 실행 안내와 설정의 '기록 안내'가 같은 내용을 보여 준다. */
@Composable
internal fun NoticeItems(wide: Boolean) {
    Item(wide, "기록하는 항목", "운행을 시작하고 마친 시각, 계기판 누적 거리, 출발지와 도착지(주소와 좌표 또는 내비 목적지 이름), 운전자 성명과 부서, 용무와 메모, 이름을 붙인 장소")
    Item(wide, "쓰는 곳", "운행기록부를 만들고 파일로 내보내는 데에만 씁니다")
    Item(wide, "두는 곳과 기간", "이 차량의 앱 저장소에 두고, 지우기 전까지 보관합니다. 앱이 기록을 외부 서버로 보내거나 다른 곳에 제공하지 않습니다. 내보낸 파일은 '폰으로 받기'를 연 동안에만 같은 네트워크의 폰이 받아 갈 수 있습니다. 설정의 '데이터 전체 삭제'로 언제든 모두 지울 수 있습니다")
    Item(wide, "음성", "'음성 메모'를 누른 뒤 한 마디가 끝날 때까지만 마이크를 씁니다. 들은 말은 글자로 바꿔 비고에 적고, 음성은 저장하지 않습니다")
    Item(wide, "동의하지 않아도 됩니다", "동의하지 않으면 운행을 기록하지 않습니다. 다른 불이익은 없습니다. 동의한 뒤에도 '데이터 전체 삭제'로 거둘 수 있습니다", last = true)
}

@Composable
private fun Item(wide: Boolean, title: String, body: String, last: Boolean = false) {
    if (wide) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(360.dp))
            Text(text = body, fontSize = 30.sp, lineHeight = 42.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(text = body, fontSize = 28.sp, lineHeight = 40.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (!last) Spacer(Modifier.height(20.dp))
}
