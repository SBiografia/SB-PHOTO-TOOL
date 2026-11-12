# 압축 원본 휴지통 이동 — 2026-11-03 KST

> 과거 구현 기록이다. 2026-11-04 합의에 따라 현재 소스는 [원본 별도 보관 폴더 이동](COMPRESSION_ORIGINAL_ARCHIVE.md) 방식으로 변경했다. 아래 테스트·APK·GoFile 근거는 이전 휴지통 방식에 한한다.

## 합의한 흐름

- 압축본은 기존 `Pictures/SB PHOTO TOOL/Compress`에 저장한다. 원본·결과 파일을 1:1로 연결하는 대응표는 만들지 않는다.
- 압축이 끝나면 홈의 최근 작업 카드에 `원본 N장 휴지통으로 이동`을 표시한다. N은 이번 작업에서 압축에 성공했고 현재 접근 가능한 원본 수다.
- 사용자가 버튼을 누르면 Android 시스템 확인창을 연다. 승인했을 때만 `MediaStore.createTrashRequest(..., true)`로 휴지통에 이동한다. 영구 삭제 API로 대체하지 않는다.
- 압축 실패·작업 취소·시스템 확인 취소 시 해당 원본을 이동하지 않는다. 실패한 사진은 성공 목록에 포함하지 않는다. 압축본과 선택하지 않은 다른 원본은 대상에 포함하지 않는다.
- Android 10은 시스템 휴지통 API가 없어 원본을 그대로 보존하고 안내한다. SAF 폴더 사진은 MediaStore 항목으로 변환 가능한 경우만 지원하고 나머지는 제외 안내한다.

## 구현과 영향 범위

- `MediaProcessor.kt`: 결과 파일 저장을 마친 사진의 원본 URI만 성공 목록에 추가한다. 다른 도구의 처리 결과는 빈 목록을 사용한다.
- `ToolboxWorker.kt`: 압축 성공 원본 목록을 기록한 뒤 작은 작업 ID만 WorkManager 결과에 전달한다. 대량 URI 목록을 WorkManager Data나 저장 상태 Bundle에 넣지 않는다. 목록 보관 실패 시 압축 결과는 유지하고 원본 정리 기능을 제공하지 못함을 안내한다.
- `data/CompressionTrashStore.kt`: 새 압축을 시작할 때 작업 ID를 등록하고 성공한 원본 URI만 AtomicFile로 보관한다. 중복을 제거하고 현재 작업 ID가 일치할 때만 완료 목록 저장·이동 확인된 원본 제거를 허용한다. 이전 작업이 늦게 완료되거나 확인 응답이 늦게 도착해도 새 작업의 목록을 덮어쓰거나 지우지 않는다.
- `media/CompressionTrash.kt`: 접근 가능한 MediaStore 항목만 요청 대상으로 만든다. 이미 휴지통에 있는 원본과 접근 불가·미지원 항목을 구분한다. 승인 응답 후 실제 `IS_TRASHED` 상태를 다시 조회해 확인된 원본만 완료 처리한다.
- `ui/compress/CompressionTrashAction.kt`, `MainActivity.kt`: 완료 카드의 버튼·로딩·미지원 안내와 취소·결과 메시지를 연결한다. 시스템 요청에 대한 작업 ID와 결과 수신기는 루트에 두어 화면 재생성 중에도 같은 작업으로 응답을 받는다.
- 기존 압축 인코딩·출력 이름·저장 위치·콜라주·분할·GIF 처리 방식은 유지한다. 추가 앱 권한이나 인터넷 권한은 없다.

## 확인 범위

- `:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug` 성공. Debug 및 AndroidTest APK 빌드 성공.
- 최종 Debug·Release 단위 테스트 각각 66개 통과, 실패·오류 0. Debug·AndroidTest·R8 릴리스 빌드 성공. Lint 오류 0, 경고 50. 새 경고는 기존 코드에서도 사용하는 `Uri.parse`에 대한 KTX 권고다.
- API 37 x86_64 / 4GiB 에뮬레이터, 720×1280px / 320dpi(360×640dp)에서 최종 계측 8개 통과(`OK (8 tests)`, 54.953초). 신규 휴지통 4개와 기존 압축/분할/GIF 실저장 4개다. 최종 로그는 `instrumentation-verified.txt`에 있다.
- 부분 실패 2장 중 성공한 원본 1장만 대상으로 삼고, 시스템 취소 후 원본 유지·완료 화면 재생성 후 재요청·시스템 승인 후 이동·실패/미선택 원본 유지·압축본 유지를 검사했다. 이동한 원본은 복구 후 바이트가 일치했다.
- 100장을 실제 압축해 압축본 100장 저장을 확인하고, 원본 100장 일괄 휴지통 이동 후 압축본과 다른 원본이 그대로 남는지 검사했다. 원본 100장을 복구한 뒤 각각의 바이트 일치를 확인했다.
- 셸에서 별도로 생성해 앱 소유가 아닌 MediaStore 사진임을 확인한 뒤 앱에서 압축·시스템 승인·원본 휴지통 이동을 검사했다. 시스템의 복구 요청으로 되돌린 뒤 원본 바이트와 압축본 유지도 확인했다.
- 저장소 계측은 중복 제거, 다른 작업 ID 격리, 이전 작업의 늦은 완료·이전 승인 응답이 새 목록을 바꾸지 못하는 조건, 이미 정리한 작업의 재기록 차단, 미지원 URI 제외를 확인했다.
- 최종 화면은 `verified-screens/{01-partial-success,02-original-trashed,03-hundred-originals-trashed,04-foreign-original-restored}.png`에 보관했다. 작은 화면에서 최근 작업의 이동 버튼과 완료 메시지가 표시됨을 확인했다. 화면 크기·밀도는 원래대로 복원했고 이번 작업의 전용 에뮬레이터만 종료했다.
- 이 기능은 현재 완료 작업의 카드에서 사용한다. 별도의 과거 작업 검색·장기 사진 이력·휴지통 비우기 기능은 추가하지 않았다. 실기기·Android 10·실제 외부 폴더 공급자는 별도 확인 대상이다.

## 근거

- [Android MediaStore API](https://developer.android.com/reference/android/provider/MediaStore): `createTrashRequest`는 API 30부터 제공하며 승인 시 `IS_TRASHED`를 설정한다. `getMediaUri`는 ExternalStorageProvider·MediaDocumentsProvider URI를 지원하며 새 읽기 권한을 부여하지 않는다.
- 검증 로그와 화면은 `build/compression-trash-qa`에 보관한다.

## 검증 중 기록

- 최초 새 계측은 제품의 버튼 문구가 정상 표시된 상태에서 테스트의 부분 문자열 비교 설정 누락으로 실패했다. 버튼 전체 문구를 비교하도록 고쳤다. 로그는 `instrumentation-partial-first-assertion.txt`에 보관한다.
- 작은 화면에서 홈의 새 하단 버튼이 시스템 내비게이션 영역과 겹치지 않도록 홈 스크롤 영역에 안전 여백을 적용했다.
- 100장 계측의 첫 시도는 갤러리 확인 직후 아직 갱신되지 않은 저장 버튼을 눌러 압축이 시작되지 않았다. 테스트가 활성화된 `100장 압축` 버튼을 기다리고 홈 전환도 확인하도록 보완한 뒤 100장 압축·휴지통 이동·복구가 통과했다. 최초 로그는 `instrumentation-bulk-first-timeout.txt`에 보관한다.
- 앱 외부 소유 사진을 만드는 계측 보조 명령은 `UiAutomation.executeShellCommand`가 명령 인자를 직접 분리하므로 경로의 셸 따옴표가 그대로 전달되어 초기 생성에 실패했다. 이 테스트에서 생성한 공백 없는 경로만 인자로 전달하도록 고쳤다.
- 동시 실행 소스 검토에서 이전 압축이 나중에 끝날 경우 최신 작업의 정리 목록을 덮어쓸 수 있어, 새 압축 시작 시 작업 ID를 먼저 등록하고 일치하는 작업만 목록을 저장하도록 보완했다. 이전 완료/승인 응답이 새 목록에 영향을 주지 않는 계측을 추가했다.
- 시스템 확인창이 닫히고 앱으로 돌아오는 중 Compose 루트가 일시적으로 없는 구간을 계측이 즉시 실패로 처리하는 경우가 있어, 폴링 대기에서는 루트가 없는 상태를 허용하고 실제 결과 화면이 나타날 때까지 기다리도록 했다. 실제 이동 결과·원본 복구·압축본 보존 검사는 그대로 유지했다.

## 최종 APK·GoFile — 2026-11-03 KST

- 최종 파일: `build/distributions/SB-Photo-Tool-1.2.1-compress-trash-20261103-r2.apk`, 7,039,347바이트.
- SHA-256: `cce054ebc8e14623b5a11fdd4242bff1d653557c6a7560f51b4e8011a018f08b`.
- 패키지 `com.sb.phototool`, 버전 `1.2.1 (4)`, minSdk 29, targetSdk 36, ABI `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`. 이전 배포본과 같은 테스트용 인증서로 v2/v3 서명 검증, 16KiB ZIP 정렬, ZIP CRC 검사를 통과했다. INTERNET 권한·debuggable 플래그·키 파일·테스트 사진/영상이 없음을 확인했다.
- 동시 작업 보호를 반영한 최종 r2 APK를 기존 앱 위에 업데이트 설치하고 일반 콜드 시작과 홈 표시를 확인했다. 상세 휴지통 동작 계측은 Debug APK에서 수행했으며, R8 APK의 휴지통 전체 흐름과 실기기는 별도 실행하지 않았다.
- 정확히 이 r2 파일의 검사 이후 SHA-256 일치를 다시 확인하고 GoFile 업로드 1회 성공. https://gofile.io/d/9H596c3A — 2026-11-03 22:17:37 KST 공유 페이지 GET HTTP 200 확인. 게스트 임시 저장이며 관리 토큰은 출력·보관하지 않았다.
- 검사·설치·업로드 근거: `inspection-final.json`, `signature-final.txt`, `signature-v2-final.txt`, `alignment-final.txt`, `badging-final.txt`, `manifest-final.txt`, `release-launch-final.txt`, `release-home-final.xml`, `gofile-upload.json`, `gofile-page-check.json`.

## 실사용 보고 조사 — 2026-11-03 22:51 KST

- 사용자 보고: 앱에는 휴지통 이동 완료가 표시되지만 확인한 휴지통에는 사진이 없다. 확인한 앱, 원본 앨범의 잔존 여부, 기기/OS는 아직 확인되지 않았다. 조사 시 ADB 연결 기기는 없었다.
- 현재 소스를 다시 확인했다. 시스템 승인 후 `IS_TRASHED == 1`을 조회한 원본에 대해서만 완료 수를 표시하며, 실제 원본을 영구 삭제하는 API는 사용하지 않는다. 다만 이 조회는 특정 갤러리 앱의 휴지통 목록 표시나 그 앱을 통한 복구 가능성을 검증하지 않는다. 이전 에뮬레이터의 복구 검증 역시 MediaStore 복구 요청을 통한 것으로, 삼성 갤러리 휴지통 UI 검증과 구분해야 한다.
- 확인된 제품의 공백: `confirmMoved`는 확인된 원본 URI를 정리 목록에서 제거하며, 현재 앱에는 이동한 원본을 조회/복구하는 UI가 없다. 제조사 갤러리에 표시되지 않을 때 사용자가 되돌릴 경로를 앱이 제공하지 못한다. 이 사실만으로 현재 사용자의 사진이 영구 삭제되었다거나 특정 제조사 문제가 원인이라고 단정하지 않는다.
- Android 공식 `createTrashRequest` 설명과 삼성 공식 One UI 6 통합 휴지통 안내를 확인했다. 삼성 문서는 내 파일·갤러리·음성 녹음의 통합을 설명하지만 이 앱의 MediaStore 요청 결과가 반드시 해당 UI에 나타난다고 보장하지 않는다. 추가 삭제나 설정 초기화를 수행하지 않았고, 앱 코드 변경·새 APK 배포 없이 실제 증상 확인을 기다린다.
- 참고: https://developer.android.com/reference/android/provider/MediaStore#createTrashRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E,%20boolean), https://www.samsung.com/us/apps/one-ui/features/
