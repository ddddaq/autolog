package com.autolog.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autolog.app.data.Driver
import com.autolog.app.data.Profile
import com.autolog.app.data.Purpose
import com.autolog.app.place.SavedPlace

/**
 * 설정 탭. 운행기록부 머리에 들어가는 차량 정보, '사용자(부서·성명)' 칸에 들어가는 운전자,
 * 운행을 닫는 기준, 음성 안내, 데이터 전체 삭제를 다룬다.
 * 주행 중에는 이 화면이 보이지 않으므로 글자 입력은 정차 중에만 일어난다.
 */
@Composable
fun SettingsContent(
    profile: Profile,
    places: List<SavedPlace>,
    wide: Boolean,
    onChange: ((Profile) -> Profile) -> Unit,
    onRemovePlace: (SavedPlace) -> Unit,
    onWipe: () -> Unit,
) {
    if (wide) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                VehiclePanel(profile, onChange)
                RulesPanel(profile, onChange)
                LabelsPanel(profile, onChange)
                VoicePanel(profile, onChange)
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                DriversPanel(profile, onChange)
                PlacesPanel(places, onRemovePlace)
                NoticePanel()
                WipePanel(onWipe)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            VehiclePanel(profile, onChange)
            DriversPanel(profile, onChange)
            RulesPanel(profile, onChange)
            LabelsPanel(profile, onChange)
            VoicePanel(profile, onChange)
            PlacesPanel(places, onRemovePlace)
            NoticePanel()
            WipePanel(onWipe)
        }
    }
}

@Composable
private fun VehiclePanel(profile: Profile, onChange: ((Profile) -> Profile) -> Unit) {
    var model by remember { mutableStateOf(profile.vehicleModel) }
    var plate by remember { mutableStateOf(profile.plateNo) }
    Panel {
        Heading("차량", "운행기록부 머리에 들어갑니다")
        Spacer(Modifier.height(24.dp))
        Field(label = "차종", value = model) { value ->
            model = value
            onChange { it.copy(vehicleModel = value.trim()) }
        }
        Spacer(Modifier.height(20.dp))
        Field(label = "자동차등록번호", value = plate) { value ->
            plate = value
            onChange { it.copy(plateNo = value.trim()) }
        }
    }
}

/** 운행을 닫는 기준. 차를 쓰는 방식에 따라 바꿀 수 있다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesPanel(profile: Profile, onChange: ((Profile) -> Profile) -> Unit) {
    Panel {
        Heading("운행을 마치는 기준", "기어를 P에 두고 운전석 문을 열면 운행을 마칩니다. 문을 열지 않으면 아래 시간이 지난 뒤에 마칩니다")
        Spacer(Modifier.height(20.dp))
        Text(text = "문을 열지 않았을 때 기다리는 시간", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Profile.parkTimeoutChoices.forEach { minutes ->
                Pill(text = "${minutes}분", selected = profile.parkTimeoutMin == minutes, onClick = { onChange { it.copy(parkTimeoutMin = minutes) } })
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(text = "이보다 짧은 이동은 기록만 하고 용무를 묻지 않음", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Profile.minTripChoices.forEach { distance ->
                Pill(text = "${distance}km", selected = profile.minTripKm == distance, onClick = { onChange { it.copy(minTripKm = distance) } })
            }
        }
    }
}

/** 용무 버튼 이름. 업무 버튼 두 개의 이름을 하는 일에 맞게 바꾼다. */
@Composable
private fun LabelsPanel(profile: Profile, onChange: ((Profile) -> Profile) -> Unit) {
    Panel {
        Heading("용무 버튼 이름", "업무 버튼 두 개의 이름을 하는 일에 맞게 바꿉니다. 운행기록부에는 둘 다 일반 업무용 거리로 들어갑니다. 비워 두면 원래 이름을 씁니다")
        Purpose.renamable.forEach { purpose ->
            var text by remember(purpose) { mutableStateOf(profile.purposeLabels[purpose.name].orEmpty()) }
            Spacer(Modifier.height(20.dp))
            Field(label = "'${purpose.label}' 버튼", value = text) { value ->
                text = value.take(LABEL_MAX)
                onChange { it.copy(purposeLabels = it.purposeLabels + (purpose.name to text.trim())) }
            }
        }
    }
}

@Composable
private fun VoicePanel(profile: Profile, onChange: ((Profile) -> Profile) -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Heading("운행 완료 음성 안내", "운행이 기록되면 한 문장으로 알립니다")
            }
            Switch(checked = profile.voiceGuide, onCheckedChange = { on -> onChange { it.copy(voiceGuide = on) } })
        }
    }
}

@Composable
private fun DriversPanel(profile: Profile, onChange: ((Profile) -> Profile) -> Unit) {
    var newName by remember { mutableStateOf("") }
    var newDept by remember { mutableStateOf("") }
    Panel {
        Heading("운전자", "고른 운전자가 새 운행의 사용자로 기록됩니다. 운행마다 카드에서 바꿀 수 있습니다")
        Spacer(Modifier.height(20.dp))
        if (profile.drivers.isEmpty()) {
            Text(text = "등록된 운전자가 없습니다", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        profile.drivers.forEachIndexed { index, driver ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Pill(text = driver.name, selected = index == profile.currentDriver, onClick = { onChange { it.copy(currentDriver = index) } })
                Text(
                    text = driver.dept,
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                )
                QuietButton(text = "삭제", onClick = {
                    onChange { p ->
                        val remaining = p.drivers.filterIndexed { i, _ -> i != index }
                        val current = when {
                            index < p.currentDriver -> p.currentDriver - 1
                            else -> p.currentDriver
                        }
                        p.copy(drivers = remaining, currentDriver = current)
                    }
                })
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(label = "성명", value = newName, modifier = Modifier.weight(1f)) { newName = it }
            Field(label = "부서", value = newDept, modifier = Modifier.weight(1f)) { newDept = it }
            Button(
                onClick = {
                    val driver = Driver(newName.trim(), newDept.trim())
                    onChange { it.copy(drivers = it.drivers + driver) }
                    newName = ""
                    newDept = ""
                },
                enabled = newName.isNotBlank(),
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(84.dp),
            ) {
                Text(text = "추가", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

/** 이름을 붙인 장소. 이름은 운행 카드의 '장소 이름'에서 붙인다. */
@Composable
private fun PlacesPanel(places: List<SavedPlace>, onRemove: (SavedPlace) -> Unit) {
    Panel {
        Heading("장소", "이름을 붙인 곳에서 출발하거나 정차하면 주소 대신 그 이름으로 적습니다. 운행 카드의 '장소 이름'에서 붙입니다")
        Spacer(Modifier.height(16.dp))
        if (places.isEmpty()) {
            Text(text = "이름을 붙인 장소가 없습니다", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        places.forEach { place ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(text = place.name, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = place.rawNames.joinToString(", "),
                    fontSize = 26.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                )
                QuietButton(text = "삭제", onClick = { onRemove(place) })
            }
        }
    }
}

/** 기록 안내 다시 보기. 첫 실행 때 동의한 내용을 언제든 확인할 수 있다. 패널 폭이 좁아 항목을 위아래로 쌓는다. */
@Composable
private fun NoticePanel() {
    var open by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Heading("기록 안내", "무엇을 기록하고 어디에 쓰며 어디에 두는지")
            }
            QuietButton(text = if (open) "접기" else "보기", onClick = { open = !open })
        }
        if (open) {
            Spacer(Modifier.height(20.dp))
            NoticeItems(wide = false)
        }
    }
}

/** 데이터 전체 삭제. 차를 넘기거나 담당자가 바뀔 때 쓴다. 한 번 더 눌러야 지워진다. */
@Composable
private fun WipePanel(onWipe: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Panel {
        Heading(
            "데이터 전체 삭제",
            if (confirm) "운행 기록, 내보낸 파일, 차량·운전자 정보, 이름 붙인 장소, 설정이 모두 지워지고 되돌릴 수 없습니다. 지운 뒤에는 첫 실행 안내부터 다시 시작합니다"
            else "차를 넘기거나 담당자가 바뀔 때 이 차량에 남은 기록을 모두 지웁니다",
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (confirm) {
                Button(
                    onClick = {
                        confirm = false
                        onWipe()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(84.dp),
                ) {
                    Text(text = "모두 지우기", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
                }
                FilledTonalButton(onClick = { confirm = false }, shape = RoundedCornerShape(50), modifier = Modifier.height(84.dp)) {
                    Text(text = "취소", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
                }
            } else {
                FilledTonalButton(onClick = { confirm = true }, shape = RoundedCornerShape(50), modifier = Modifier.height(84.dp)) {
                    Text(text = "데이터 전체 삭제", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, description: String) {
    Text(text = title, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(text = description, fontSize = 26.sp, lineHeight = 36.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label, fontSize = 24.sp) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 32.sp),
        modifier = modifier.fillMaxWidth().inputField(),
    )
}

/** 버튼 이름 길이 제한. 버튼 한 줄에 들어가야 한다. */
private const val LABEL_MAX = 8
