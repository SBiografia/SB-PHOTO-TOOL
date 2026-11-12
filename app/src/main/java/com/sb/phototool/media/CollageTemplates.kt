package com.sb.phototool.media

import com.sb.phototool.model.*

enum class TemplateCategory(val label: String) {
    MINIMAL("미니멀"), POLAROID("폴라로이드"), FILM("필름"), MAGAZINE("매거진"), SCRAPBOOK("스크랩북"),
    TRAVEL("여행"), FAMILY("가족·커플"), CELEBRATION("생일·기념일"), SEASON("계절"), MANY("다중 사진"),
}

data class CollageTemplate(val id: String, val name: String, val category: TemplateCategory, val document: CollageJob) {
    val photoCount get() = document.tiles.size
}

private fun rect(x: Float, y: Float, w: Float, h: Float) = NormalizedCell(x, y, x + w, y + h)
private fun col(hex: Long) = (0xFF000000 or hex).toInt()
private val cream = col(0xF2EBDD)
private val ink = col(0x292824)
private val brown = col(0x8C684B)

val collageTemplates: List<CollageTemplate> by lazy {
    TemplateCategory.entries.flatMap { category -> (0..5).map { variant -> createTemplate(category, variant) } }
}

private class TemplateBuilder(val id: String, val ratio: CanvasRatio) {
    val photos = mutableListOf<TileTransform>()
    val texts = mutableListOf<CollageText>()
    val decorations = mutableListOf<CollageDecoration>()
    var color: Int = cream
    var background = CollageBackground()
    fun photo(frame: NormalizedCell, rotation: Float = 0f, shape: PhotoShape = PhotoShape.RECTANGLE,
              matte: Float = 0f, bottom: Float = 0f, frameColor: Int = -1, layer: Int = 10) {
        photos += TileTransform("", freeform = frame, layer = layer, elementId = id + "-p" + photos.size,
            frameRotation = rotation, shape = shape, matte = matte, bottomMatte = bottom, frameColor = frameColor)
    }
    fun text(value: String, frame: NormalizedCell, size: Float = .055f, font: CollageFont = CollageFont.SERIF,
             tint: Int = ink, rotation: Float = 0f, layer: Int = 80) {
        texts += CollageText(id + "-t" + texts.size, value, frame, rotation, layer, tint, font, size)
    }
    fun deco(kind: DecorationKind, frame: NormalizedCell, rotation: Float = 0f, tint: Int = brown, layer: Int = 40, opacity: Float = 1f) {
        decorations += CollageDecoration(id + "-d" + decorations.size, kind, frame, rotation, layer, tint, opacity)
    }
    fun grid(count: Int, region: NormalizedCell, gap: Float = .015f, shape: PhotoShape = PhotoShape.RECTANGLE,
             tilted: Boolean = false, matte: Float = 0f, bottom: Float = 0f) {
        collageCells(count).forEachIndexed { i, c ->
            val w = region.right - region.left; val h = region.bottom - region.top
            photo(NormalizedCell(region.left + c.left * w + gap, region.top + c.top * h + gap,
                region.left + c.right * w - gap, region.top + c.bottom * h - gap),
                if (tilted) (if (i % 2 == 0) -4f else 4f) else 0f, shape, matte, bottom)
        }
    }
    fun build() = CollageJob("draft", photos, ratio, 0f, 0f, color, ExportSize(), mode = CollageMode.FREEFORM,
        freeformCanvasAspect = ratio.ratio, background = background, texts = texts, decorations = decorations,
        templateId = id, placementLocked = true)
}

private fun createTemplate(category: TemplateCategory, v: Int): CollageTemplate {
    val id = category.name.lowercase() + "-" + (v + 1)
    val ratio = listOf(CanvasRatio.SQUARE, CanvasRatio.PORTRAIT, CanvasRatio.STORY, CanvasRatio.SQUARE, CanvasRatio.PORTRAIT, CanvasRatio.STORY)[v]
    val b = TemplateBuilder(id, ratio)
    var title = ""
    with(b) {
        when (category) {
            TemplateCategory.MINIMAL -> {
                color = if (v % 2 == 0) col(0xFAF9F5) else col(0xE8E8E4)
                when (v) {
                    0 -> { title = "여백 속 한 장"; photo(rect(.09f, .08f, .82f, .72f)); text("a quiet moment", rect(.08f, .83f, .84f, .08f), .042f) }
                    1 -> { title = "두 장의 대화"; photo(rect(.07f, .09f, .55f, .57f)); photo(rect(.48f, .4f, .45f, .45f)); text("OUR DAYS", rect(.05f, .87f, .9f, .07f), .045f) }
                    2 -> { title = "세로의 리듬"; (0..2).forEach { photo(rect(.08f, .08f + it * .275f, .84f, .25f)) }; text("ONE DAY", rect(.1f, .915f, .8f, .04f), .04f) }
                    3 -> { title = "큰 순간과 작은 순간"; photo(rect(.06f, .06f, .58f, .72f)); photo(rect(.67f, .06f, .27f, .345f)); photo(rect(.67f, .435f, .27f, .345f)); text("기억하고 싶은 장면", rect(.06f, .82f, .88f, .09f), .045f) }
                    4 -> { title = "모던 아카이브"; grid(6, rect(.045f, .13f, .91f, .79f), .01f); text("THE ARCHIVE", rect(.05f, .035f, .9f, .065f), .05f) }
                    else -> { title = "아홉 개의 순간"; grid(9, rect(.05f, .11f, .9f, .78f), .008f); text("EVERY LITTLE THING", rect(.05f, .91f, .9f, .04f), .032f) }
                }
            }
            TemplateCategory.POLAROID -> {
                title = listOf("겹쳐 놓은 두 장", "벽 위의 세 장", "네 장의 추억", "일상의 보드", "여섯 번의 셔터", "포켓 앨범")[v]
                color = listOf(cream, col(0xC9D7D2), col(0xE2CBBA), col(0xD9D7E8), col(0xE4DCCB), col(0xB7C8C7))[v]
                background = CollageBackground(BackgroundKind.PATTERN, pattern = BackgroundPattern.LINEN)
                if (v == 0) {
                    photo(rect(.08f, .12f, .52f, .57f), -7f, matte = .045f, bottom = .12f)
                    photo(rect(.43f, .35f, .49f, .55f), 8f, matte = .045f, bottom = .12f, layer = 11)
                } else {
                    grid(listOf(2, 3, 4, 5, 6, 8)[v], rect(.075f, .13f, .85f, .74f), .018f, tilted = true, matte = .04f, bottom = .12f)
                }
                deco(DecorationKind.TAPE, rect(.34f, .07f, .28f, .045f), -5f, opacity = .7f)
                text("little memories", rect(.08f, .9f, .84f, .055f), .052f, CollageFont.HAND)
            }
            TemplateCategory.FILM -> {
                title = listOf("한 줄 필름", "가로 필름 두 줄", "비스듬한 필름", "필름과 큰 사진", "컨택트 시트", "시네마 다이어리")[v]
                color = if (v % 2 == 0) cream else col(0x22221F)
                val fg = if (v % 2 == 0) ink else cream
                when (v) {
                    0 -> (0..2).forEach { photo(rect(.18f, .07f + it * .275f, .64f, .255f), matte = .055f, frameColor = ink) }
                    1 -> { grid(6, rect(.05f, .18f, .9f, .63f), .008f, matte = .06f); text("35 MM", rect(.05f, .04f, .9f, .09f), .07f, tint = fg) }
                    2 -> { (0..3).forEach { photo(rect(.08f + it * .21f, .12f, .2f, .28f), -12f, matte = .06f, frameColor = ink) }; photo(rect(.08f, .5f, .84f, .39f)) }
                    3 -> { photo(rect(.07f, .08f, .86f, .5f)); (0..2).forEach { photo(rect(.07f + it * .29f, .65f, .27f, .23f), matte = .06f, frameColor = ink) } }
                    4 -> grid(9, rect(.06f, .1f, .88f, .77f), .012f, matte = .04f)
                    else -> { (0..3).forEach { photo(rect(.07f, .08f + it * .2f, .39f, .18f), -2f, matte = .055f, frameColor = ink); photo(rect(.54f, .12f + it * .2f, .39f, .18f), 2f, matte = .055f, frameColor = ink) } }
                }
                text("FRAME BY FRAME", rect(.06f, .92f, .88f, .04f), .035f, CollageFont.SANS, fg)
                deco(DecorationKind.FILM, rect(.025f, .06f, .025f, .83f), tint = fg, layer = 0)
            }
            TemplateCategory.MAGAZINE -> {
                title = listOf("커버 스토리", "에디토리얼", "위켄드 매거진", "모노 저널", "라이프스타일", "사진 에세이")[v]
                color = if (v == 3) col(0x252728) else col(0xF5F0E7)
                val fg = if (v == 3) cream else ink
                when (v) {
                    0 -> { photo(rect(.05f, .17f, .9f, .77f)); text("MOMENTS", rect(.02f, .015f, .96f, .13f), .115f, tint = fg) }
                    1 -> { photo(rect(.05f, .12f, .57f, .6f)); photo(rect(.66f, .29f, .29f, .42f)); text("A NEW\nPERSPECTIVE", rect(.03f, .76f, .94f, .17f), .07f, tint = fg) }
                    2 -> { photo(rect(.05f, .05f, .9f, .45f)); grid(3, rect(.04f, .61f, .92f, .3f), .012f); text("WEEKEND", rect(.05f, .515f, .9f, .07f), .08f, tint = fg) }
                    3 -> { grid(4, rect(.06f, .18f, .88f, .72f), .01f); text("THE JOURNAL", rect(.06f, .045f, .88f, .1f), .08f, tint = fg) }
                    4 -> { photo(rect(.06f, .05f, .6f, .54f)); photo(rect(.4f, .64f, .54f, .29f)); photo(rect(.7f, .14f, .24f, .37f)); text("LIFE\nIN FOCUS", rect(.04f, .66f, .34f, .22f), .065f, tint = fg) }
                    else -> { grid(5, rect(.05f, .2f, .9f, .69f), .012f); text("사진으로 쓰는 하루", rect(.05f, .04f, .9f, .1f), .065f, tint = fg) }
                }
                deco(DecorationKind.LINE, rect(.06f, .965f, .88f, .003f), tint = fg)
            }
            TemplateCategory.SCRAPBOOK -> {
                title = listOf("종이 조각", "커피 노트", "테이프 다이어리", "무드 보드", "오래된 편지", "수집한 장면")[v]
                color = listOf(col(0xD7C6AC), col(0x6B5040), col(0xE4DCCF), col(0xA2B0A0), col(0xE8D1C5), col(0xBEB5A7))[v]
                background = CollageBackground(BackgroundKind.PATTERN, secondaryColor = col(0xAD957E), pattern = if (v % 2 == 0) BackgroundPattern.PAPER else BackgroundPattern.CHECK)
                deco(DecorationKind.PAPER, rect(.07f, .09f, .84f, .81f), -3f, cream, 0)
                when (v) {
                    0 -> { photo(rect(.13f, .18f, .55f, .58f), -6f, PhotoShape.TORN); photo(rect(.53f, .5f, .35f, .32f), 7f, PhotoShape.CIRCLE) }
                    1 -> { photo(rect(.08f, .13f, .49f, .4f), -5f, matte = .03f); photo(rect(.5f, .45f, .4f, .42f), 5f, PhotoShape.ARCH); photo(rect(.12f, .59f, .3f, .22f), -4f, PhotoShape.TORN) }
                    2 -> grid(4, rect(.12f, .15f, .76f, .69f), .025f, tilted = true, matte = .025f)
                    3 -> { photo(rect(.11f, .12f, .5f, .54f), -5f); grid(4, rect(.59f, .23f, .31f, .62f), .009f, shape = PhotoShape.ROUNDED) }
                    4 -> { photo(rect(.16f, .16f, .68f, .53f), 4f, PhotoShape.TORN); photo(rect(.13f, .72f, .31f, .18f), -6f); photo(rect(.55f, .72f, .31f, .18f), 6f) }
                    else -> grid(6, rect(.11f, .15f, .78f, .7f), .02f, PhotoShape.TORN, tilted = true)
                }
                deco(DecorationKind.TAPE, rect(.3f, .1f, .35f, .055f), -8f, col(0xBEAA7E), opacity = .75f)
                deco(DecorationKind.CLIP, rect(.81f, .78f, .065f, .13f), 15f, col(0x8B745B))
                text("pieces of my day", rect(.08f, .89f, .84f, .065f), .06f, CollageFont.HAND)
            }
            TemplateCategory.TRAVEL -> {
                title = listOf("여행의 시작", "로드 트립", "도시의 조각", "산과 바다", "여행 엽서", "주말 탐험")[v]
                color = listOf(col(0xE6E1D0), col(0xDCE5E1), col(0xE8D2B5), col(0xD4E0E6), col(0xE6DED4), col(0xCCD8C4))[v]
                when (v) {
                    0 -> { photo(rect(.06f, .08f, .88f, .63f), shape = PhotoShape.WAVE); text("LET'S GO", rect(.06f, .74f, .88f, .14f), .1f) }
                    1 -> { photo(rect(.07f, .12f, .86f, .38f)); grid(3, rect(.06f, .55f, .88f, .31f), .014f, matte = .025f); text("ROAD TRIP", rect(.05f, .025f, .9f, .065f), .06f) }
                    2 -> { grid(5, rect(.06f, .12f, .88f, .72f), .014f, PhotoShape.SLANT); text("CITY NOTES", rect(.05f, .88f, .9f, .06f), .065f) }
                    3 -> { photo(rect(.06f, .09f, .54f, .73f), shape = PhotoShape.ARCH); photo(rect(.64f, .15f, .3f, .3f), shape = PhotoShape.CIRCLE); photo(rect(.64f, .5f, .3f, .3f), shape = PhotoShape.CIRCLE); text("OUTSIDE", rect(.05f, .87f, .9f, .06f), .05f) }
                    4 -> { photo(rect(.07f, .08f, .86f, .47f), matte = .035f); photo(rect(.08f, .62f, .37f, .25f), -4f); photo(rect(.54f, .62f, .37f, .25f), 4f); text("a postcard to myself", rect(.06f, .91f, .88f, .04f), .045f, CollageFont.HAND) }
                    else -> { grid(7, rect(.06f, .16f, .88f, .7f), .012f, PhotoShape.ROUNDED); text("우리의 주말 여행", rect(.05f, .04f, .9f, .075f), .065f) }
                }
                deco(DecorationKind.LINE, rect(.09f, .955f, .82f, .004f), tint = brown)
            }
            TemplateCategory.FAMILY -> {
                title = listOf("하트 속 우리", "동그란 행복", "함께한 날", "가족 앨범", "우리의 조각", "사랑의 모양")[v]
                color = listOf(col(0xF0D8D4), col(0xDFE8D8), col(0xEDE3CC), col(0xE4DEE9), col(0xF1DFD2), col(0xD6E4E8))[v]
                when (v) {
                    0 -> { photo(rect(.1f, .1f, .8f, .67f), shape = PhotoShape.HEART); text("you & me", rect(.1f, .8f, .8f, .11f), .085f, CollageFont.HAND) }
                    1 -> { photo(rect(.08f, .11f, .55f, .44f), shape = PhotoShape.OVAL); photo(rect(.38f, .49f, .54f, .43f), shape = PhotoShape.OVAL); deco(DecorationKind.HEART, rect(.67f, .22f, .15f, .12f), tint = col(0xC17F7F)) }
                    2 -> { (0..2).forEach { photo(rect(.12f, .07f + it * .285f, .76f, .255f), shape = PhotoShape.ARCH) }; text("TOGETHER", rect(.1f, .93f, .8f, .035f), .04f) }
                    3 -> { grid(4, rect(.08f, .14f, .84f, .71f), .02f, PhotoShape.ROUNDED); text("우리 가족", rect(.1f, .035f, .8f, .075f), .07f, CollageFont.ROUND) }
                    4 -> { photo(rect(.06f, .1f, .55f, .58f), shape = PhotoShape.HEXAGON); photo(rect(.56f, .12f, .38f, .32f), shape = PhotoShape.CIRCLE); photo(rect(.48f, .53f, .46f, .34f), shape = PhotoShape.HEART); text("좋아하는 모든 순간", rect(.05f, .91f, .9f, .04f), .04f) }
                    else -> { (0..5).forEach { i -> photo(rect(.1f + (i % 2) * .44f, .15f + (i / 2) * .25f, .36f, .21f), shape = PhotoShape.HEART) }; text("LOVE LIVES HERE", rect(.08f, .04f, .84f, .06f), .06f, CollageFont.ROUND) }
                }
            }
            TemplateCategory.CELEBRATION -> {
                title = listOf("생일 축하해", "기념일 카드", "빛나는 하루", "파티의 순간", "함께한 시간", "오늘의 주인공")[v]
                color = listOf(col(0xF4DEBD), col(0xE5CBD4), col(0xD5DFEF), col(0xE2DBEF), col(0xE8DEC6), col(0xD3E3D8))[v]
                text(listOf("HAPPY BIRTHDAY", "OUR ANNIVERSARY", "SHINE TODAY", "PARTY TIME", "THANK YOU", "오늘을 축하해")[v], rect(.06f, .04f, .88f, .12f), .068f, CollageFont.ROUND)
                when (v) {
                    0 -> photo(rect(.12f, .22f, .76f, .61f), shape = PhotoShape.ARCH)
                    1 -> { photo(rect(.08f, .26f, .46f, .46f), -5f, PhotoShape.HEART); photo(rect(.5f, .4f, .42f, .42f), 5f, PhotoShape.HEART) }
                    2 -> { photo(rect(.08f, .2f, .84f, .37f), shape = PhotoShape.STAR); grid(2, rect(.1f, .64f, .8f, .24f), .02f) }
                    3 -> grid(4, rect(.1f, .22f, .8f, .62f), .022f, PhotoShape.ROUNDED, tilted = true)
                    4 -> { photo(rect(.08f, .21f, .84f, .4f), matte = .03f); grid(3, rect(.07f, .67f, .86f, .2f), .014f) }
                    else -> grid(5, rect(.08f, .24f, .84f, .6f), .018f, PhotoShape.OVAL)
                }
                deco(DecorationKind.STAR, rect(.04f, .16f, .11f, .11f), -10f, col(0xC5A04E))
                deco(DecorationKind.STAR, rect(.85f, .83f, .1f, .1f), 12f, col(0xC5A04E))
                text("a day to remember", rect(.1f, .91f, .8f, .04f), .043f, CollageFont.HAND)
            }
            TemplateCategory.SEASON -> {
                title = listOf("봄의 꽃잎", "여름의 파도", "가을 일기", "겨울의 창", "햇살 가득", "따뜻한 연말")[v]
                color = listOf(col(0xF0DFE3), col(0xCBE2E8), col(0xD4BC91), col(0xD8DFE5), col(0xF1E5BC), col(0x486052))[v]
                background = CollageBackground(BackgroundKind.PATTERN, pattern = listOf(BackgroundPattern.DOTS, BackgroundPattern.STRIPES, BackgroundPattern.LINEN, BackgroundPattern.GRID, BackgroundPattern.PAPER, BackgroundPattern.CHECK)[v])
                when (v) {
                    0 -> { photo(rect(.12f, .18f, .76f, .62f), shape = PhotoShape.OVAL); text("hello spring", rect(.08f, .035f, .84f, .1f), .075f, CollageFont.HAND) }
                    1 -> { photo(rect(.07f, .11f, .86f, .36f), shape = PhotoShape.WAVE); photo(rect(.07f, .56f, .86f, .33f), shape = PhotoShape.WAVE); text("SUMMER DAYS", rect(.05f, .47f, .9f, .075f), .06f) }
                    2 -> { grid(3, rect(.09f, .13f, .82f, .73f), .02f, PhotoShape.TORN, tilted = true); text("가을의 기록", rect(.1f, .9f, .8f, .05f), .06f, CollageFont.HAND) }
                    3 -> { grid(4, rect(.13f, .17f, .74f, .65f), .025f, PhotoShape.ARCH); text("WINTER WINDOW", rect(.06f, .045f, .88f, .08f), .06f) }
                    4 -> { photo(rect(.09f, .13f, .54f, .67f), -3f, PhotoShape.ROUNDED); photo(rect(.59f, .34f, .32f, .34f), 6f, PhotoShape.CIRCLE); text("햇살처럼", rect(.08f, .85f, .84f, .08f), .07f, CollageFont.HAND) }
                    else -> { grid(6, rect(.09f, .16f, .82f, .7f), .014f, PhotoShape.ROUNDED); text("WARM WISHES", rect(.06f, .04f, .88f, .07f), .06f, tint = cream) }
                }
                deco(if (v == 0) DecorationKind.FLOWER else if (v in 1..2 || v == 4) DecorationKind.LEAF else DecorationKind.STAR,
                    rect(.79f, .82f, .13f, .13f), 15f, if (v == 5) cream else col(0xA87C58))
            }
            TemplateCategory.MANY -> {
                val count = 10 + v
                title = count.toString() + "장의 아카이브"
                color = listOf(col(0xF2EDE3), col(0xD9E1DE), col(0xE2D8D0), col(0x23292C), col(0xE6DDE7), col(0xE1DFD2))[v]
                val fg = if (v == 3) cream else ink
                grid(count, rect(.04f, .14f, .92f, .74f), .006f,
                    listOf(PhotoShape.RECTANGLE, PhotoShape.ROUNDED, PhotoShape.SLANT, PhotoShape.HEXAGON, PhotoShape.OVAL, PhotoShape.RECTANGLE)[v])
                text("COLLECTED MOMENTS", rect(.05f, .035f, .9f, .07f), .05f, CollageFont.SANS, fg)
                text("작은 순간들이 모여 우리의 이야기가 됩니다", rect(.05f, .92f, .9f, .035f), .026f, CollageFont.SANS, fg)
            }
        }
    }
    return CollageTemplate(id, title, category, b.build())
}
