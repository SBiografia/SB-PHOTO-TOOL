# 콜라주 비율·레이아웃 기억 — 2026-11-26 KST

콜라주를 새로 시작할 때, 지난번에 쓰던 비율·레이아웃·여백 같은 캔버스 설정을 기본값으로 되살린다.
요청: "콜라주 할 때 비율, 레이아웃 같은 거 마지막에 했던 방식을 유지해 줘. 예를 들면 비율을 9:16으로 했으면 그다음 번 콜라주 할 때도 항상 9:16이 되도록."

## 기억하는 값

| 값 | 저장 시점 | 복원 위치 |
|---|---|---|
| 캔버스 비율(`CanvasRatio`) | 비율 시트에서 선택할 때 | 새 콜라주 초안의 `ratio` |
| 레이아웃 id + 손으로 옮긴 칸 경계(`customCells`) | 레이아웃 시트 선택·경계 드래그 | 같은 사진 장수의 초안에만 적용 |
| 바깥 여백·사진 사이 간격·모서리·테두리 두께·테두리 색 | 여백·테두리 시트 | 새 콜라주 초안의 같은 필드 |
| 배경색(`backgroundColor`), 배경 종류(단색·그라데이션·패턴)와 그 두 번째 색·각도·무늬 | 배경 시트 | 새 콜라주 초안의 `backgroundColor`·`background` |

기억하지 않는 값: 사진별 확대·이동·회전·반전·자르기·사진틀 모양·모서리 여백·필터/보정, 글자·장식, 배경 사진·배경 흐림, 저장 해상도. 이 값들은 그 콜라주에 넣은 사진에 딸린 것이라 다음 사진에 그대로 옮기면 맞지 않는다. 배경 사진으로 바꿔도 그 전에 쓰던 단색·그라데이션·패턴 배경은 지우지 않고 그대로 둔다.

## 사진 장수가 다를 때

레이아웃은 사진 장수마다 다른 카탈로그(`collageLayouts(count)`)를 쓴다. 그래서 기억값을 `사진 장수 → (layoutId, customCells)` 표로 보관하고, 새 초안을 만들 때 그 장수의 항목만 꺼낸다.

- 저장된 장수와 같고 id가 그 장수 카탈로그에 있으면 그 레이아웃으로 시작한다.
- id가 없거나 그 장수에 없는 id면 `auto`(기본 배치)로 시작한다.
- 손으로 옮긴 칸 경계는 `validCollageCells(cells, count)`를 통과할 때만 쓴다. 통과하지 못하면 버리고 레이아웃 id로 돌아간다.
- 저장된 장수와 다른 장수로 시작하면 그 장수의 마지막 레이아웃이 없을 때 `auto`로 시작한다. 앱에 없는 id·깨진 JSON·범위를 벗어난 값은 모두 기본값으로 떨어진다(크래시 없음).

## 템플릿과 자유형

- 템플릿은 자체 비율·배경·간격·여백을 가진 디자인이므로 템플릿을 편집해도 기억값을 바꾸지 않는다. 템플릿을 몇 번 열어 보아도 마지막 기본 콜라주에서 고른 비율·레이아웃·배경이 그대로 남는다(사용자 템플릿 `templateId`도 같다).
- 자유형으로 바꾼 작업은 사진틀 좌표가 그 사진들에만 맞으므로 레이아웃을 덮어쓰지 않는다. 마지막 그리드 레이아웃은 그대로 남는다.

## 저장 방식

- `data/CollageSettingsStore.kt`: 앱 전용 `SharedPreferences`(`collage_settings`). 키는 `ratio`, `gap`, `corner`, `background_color`, `background_kind`·`background_secondary_color`·`background_angle`·`background_pattern`, `outer_margin`, `border_width`, `border_color`, 장수별 `layout_<count>`·`cells_<count>`(JSON 배열).
- 사진 URI·비트맵·썸네일은 저장하지 않는다. 인터넷 권한도 쓰지 않는다. 앱 데이터를 지우면 함께 사라진다.
- 저장 시점: `ui/collage/CollageEditorScreen.kt`의 `RememberCollageSettings`가 문서에서 기억 대상 값만 뽑아 비교하고, 값이 실제로 달라졌을 때 바로 쓴다. 사진을 확대·이동하는 동안에는 값이 그대로이므로 파일을 쓰지 않는다. 콜라주를 저장하거나 화면에서 나가도 마지막 값이 그대로 남는다.
- 앱 시작·기기 회전·프로세스 종료 후 복원은 기존 초안 저장(`rememberSaveable`)이 담당하고, 기억값은 그다음 새 콜라주를 시작할 때만 쓰인다.

## 변경 파일

- 추가: `app/src/main/java/com/sb/phototool/model/CollageSettings.kt`, `app/src/main/java/com/sb/phototool/data/CollageSettingsStore.kt`, `app/src/test/java/com/sb/phototool/model/CollageSettingsTest.kt`.
- 수정: `app/src/main/java/com/sb/phototool/ui/collage/CollageEditorState.kt`(`create`가 기억값을 받아 초안을 만든다), `app/src/main/java/com/sb/phototool/ui/collage/CollageEditorScreen.kt`(기억값 로드·저장), `README.md`.

## 검증 범위

이 작업 환경에는 JVM·Kotlin 컴파일러·Android SDK가 없어 빌드·단위 테스트·lint를 실행하지 못했다. 대신 다음을 정적으로 확인했다.

- 호출부 정합성: `CollageEditorState.create`의 새 인자는 기본값이 있어 기존 호출(`CollageEditorTest`, `CollageEnhancementsTest`, `CollageEditorStateTest`)이 그대로 컴파일된다.
- import·타입: `CollageSettings`가 쓰는 `NormalizedCell`·`collageLayouts`·`validCollageCells`는 `media` 패키지 공개 함수이고, `JobSpec.kt`도 이미 `media`를 참조하므로 패키지 순환 문제가 없다.
- `strings.xml`을 쓰지 않는 화면이라 새 문자열 리소스가 필요 없다(기존 화면도 한글 문자열을 코드에 둔다).
- edge case: 사진 0장(빈 초안), 저장된 장수에 없는 id, 장수가 다른 초안, 깨진 JSON, 범위를 벗어난 float, 템플릿·자유형 작업을 각각 단위 테스트로 덮었다(`CollageSettingsTest`).

실기기 확인이 필요한 항목:

1. 9:16 + 특정 레이아웃으로 저장한 뒤 홈 → 콜라주 → 사진 선택 시 같은 비율·레이아웃으로 시작하는지.
2. 사진 장수를 4장 → 6장으로 바꿔 열었을 때 크래시 없이 `auto` 또는 그 장수의 마지막 레이아웃으로 시작하는지.
3. 템플릿을 한 번 열었다 닫은 뒤 기본 콜라주의 비율·배경·간격이 템플릿 값으로 바뀌지 않았는지.
4. 여백 슬라이더를 드래그한 뒤 앱을 강제 종료하고 다시 열어도 마지막 여백이 남는지.
