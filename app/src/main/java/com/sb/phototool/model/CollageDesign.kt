package com.sb.phototool.model

import com.sb.phototool.media.NormalizedCell

enum class PhotoShape(val label: String) {
    RECTANGLE("사각형"), ROUNDED("둥근 사각형"), CIRCLE("원"), OVAL("타원"), HEART("하트"),
    STAR("별"), TRIANGLE("삼각형"), DIAMOND("마름모"), HEXAGON("육각형"), ARCH("아치"),
    SLANT("사선"), WAVE("물결"), TORN("찢어진 종이"),
}

enum class BackgroundKind(val label: String) { SOLID("단색"), GRADIENT("그라데이션"), PATTERN("패턴"), PHOTO("사진"), BLUR("흐림") }
enum class BackgroundPattern(val label: String) { PAPER("종이"), LINEN("린넨"), DOTS("도트"), GRID("격자"), CHECK("체크"), STRIPES("줄무늬") }
enum class CollageFont(val label: String, val asset: String) {
    SANS("고딕", "fonts/nanum_gothic.ttf"), SERIF("명조", "fonts/nanum_myeongjo.ttf"),
    HAND("손글씨", "fonts/nanum_pen.ttf"), ROUND("둥근 글씨", "fonts/jua.ttf"),
}
enum class TextAlignment(val label: String) { LEFT("왼쪽"), CENTER("가운데"), RIGHT("오른쪽") }
enum class DecorationKind(val label: String) {
    TAPE("테이프"), PAPER("종이"), LINE("선"), STAR("별 장식"), HEART("하트 장식"),
    LEAF("나뭇잎"), FLOWER("꽃"), BUTTON("단추"), FILM("필름"), CLIP("클립"),
}

data class CollageBackground(
    val kind: BackgroundKind = BackgroundKind.SOLID,
    val secondaryColor: Int = 0xFFE4D7C4.toInt(),
    val angle: Float = 90f,
    val pattern: BackgroundPattern = BackgroundPattern.PAPER,
    val uri: String = "",
    val blur: Float = .5f,
)

data class CollageText(
    val id: String,
    val text: String = "소중한 순간",
    val frame: NormalizedCell = NormalizedCell(.12f, .4f, .88f, .6f),
    val rotation: Float = 0f,
    val layer: Int = 100,
    val color: Int = 0xFF262626.toInt(),
    val font: CollageFont = CollageFont.SANS,
    val size: Float = .055f,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val outline: Float = 0f,
    val outlineColor: Int = -1,
    val shadow: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeThrough: Boolean = false,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1f,
)

data class CollageDecoration(
    val id: String,
    val kind: DecorationKind,
    val frame: NormalizedCell,
    val rotation: Float = 0f,
    val layer: Int = 50,
    val color: Int = 0xFFD5BB91.toInt(),
    val opacity: Float = 1f,
)
