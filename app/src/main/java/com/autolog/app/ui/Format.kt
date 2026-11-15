package com.autolog.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayTime = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREAN)
private val dayOnly = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN)

fun at(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

/** 12340.0 → "12,340", 18.5 → "18.5" */
fun km(value: Float): String {
    val rounded = Math.round(value * 10f) / 10f
    return if (rounded % 1f == 0f) String.format(Locale.KOREAN, "%,d", rounded.toInt())
    else String.format(Locale.KOREAN, "%,.1f", rounded)
}

/** 음성 안내용 거리. 32.0 → "32", 18.5 → "18.5" */
fun speechKm(value: Float): String {
    val rounded = Math.round(value * 10f) / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

/** 낱말 뒤에 붙는 조사 '로/으로'. 받침이 없거나 ㄹ 받침이면 '로', 그 밖의 받침이면 '으로'. */
fun directionParticle(word: String): String {
    val last = word.lastOrNull() ?: return "로"
    if (last !in '가'..'힣') return "로"
    val final = (last.code - 0xAC00) % 28
    return if (final == 0 || final == 8) "로" else "으로"
}

/** "자택 → 본사". 한쪽만 알면 그쪽만, 둘이 같으면 하나만. 모르면 빈 문자열. */
fun placeLine(startPlace: String, endPlace: String): String = when {
    startPlace.isBlank() && endPlace.isBlank() -> ""
    startPlace.isBlank() -> endPlace
    endPlace.isBlank() || startPlace == endPlace -> startPlace
    else -> "$startPlace → $endPlace"
}

fun tripWhen(startMs: Long, endMs: Long): String {
    val start = at(startMs)
    val end = at(endMs)
    val endText = if (start.toLocalDate() == end.toLocalDate()) timeOnly.format(end) else "${end.monthValue}/${end.dayOfMonth} ${timeOnly.format(end)}"
    return "${dayTime.format(start)} ~ $endText"
}

fun tripDay(startMs: Long): String = dayOnly.format(at(startMs))

/** "08:40 ~ 09:20". 끝난 날이 다르면 끝 시각 앞에 날짜를 붙인다: "23:50 ~ 11/12 00:20" */
fun tripClock(startMs: Long, endMs: Long): String {
    val start = at(startMs)
    val end = at(endMs)
    val endText = if (start.toLocalDate() == end.toLocalDate()) timeOnly.format(end) else "${end.monthValue}/${end.dayOfMonth} ${timeOnly.format(end)}"
    return "${timeOnly.format(start)} ~ $endText"
}

fun percent(part: Float, whole: Float): String =
    if (whole <= 0f) "-" else String.format(Locale.KOREAN, "%.1f%%", part / whole * 100f)
