# 사진 압축 선택·정렬 — 2026-11-03 KST

## 적용 동작

- 압축 사진 선택 화면에 `전체 선택`과 `최근 저장순 / 용량 큰 순` 메뉴를 추가했다. 전체 선택은 현재 앨범·즐겨찾기 필터의 정렬 순서대로 선택하며, 기존 선택을 유지하고 중복 없이 총 100장까지만 채운다. 100장보다 많은 앨범에서는 제한 안내를 표시한다. 전체 선택 바로 옆의 `전체 해제` 또는 기존 하단 `선택 해제`로 비울 수 있다.
- 수동·드래그 선택도 100장으로 제한한다. 폴더 불러오기는 101번째 사진을 발견하면 안내하고 작업에 반영하지 않는다. 임의의 100장을 몰래 잘라서 가져오지 않으며, 사진 선택 화면에서 나누어 선택하도록 안내한다.
- 용량순은 MediaStore의 파일 바이트 수 내림차순이며, 동률은 ID 내림차순이다. 현재 로드된 썸네일만 재배열하지 않고 앨범 전체를 정렬한 뒤 120장씩 읽는다. 전체 선택 조회는 최대 101개 메타데이터만 읽고 100장을 후보로 사용한다.
- 용량순에서는 날짜 구분을 숨기고 각 썸네일에 파일 용량을 표시한다. 정렬 변경은 선택한 사진이나 기존 선택 순번을 바꾸지 않는다. 새로 전체 선택할 때의 순서에 적용한다.
- 정렬과 앨범·필터는 화면 재생성 동안 보존하며 선택 목록은 기존 ViewModel을 사용한다. 선택 확정 직전의 접근 가능 여부 검사도 유지한다.
- 원본 파일, 압축 인코딩·해상도·EXIF 정책, 콜라주·분할·GIF 선택 방식은 유지한다. 의존성·인터넷 권한은 추가하지 않았다.

## 변경 위치

- `ui/compress/CompressionSelection.kt`: 압축 선택 한도 100장.
- `ui/gallery/GalleryRepository.kt`: 파일 용량 메타데이터, 정렬별 페이지 경계와 제한 조회.
- `ui/gallery/GallerySelection.kt`: 기존 선택·순번을 보존하는 일괄 선택.
- `ui/gallery/PhotoGalleryPicker.kt`: 압축 전용 전체 선택·정렬 메뉴와 용량 표시.
- `MainActivity.kt`, `ui/compress/CompressEditorScreen.kt`: 압축 갤러리·폴더 한도와 빈 편집 화면 안내.
- `GallerySelectionTest.kt`, `GalleryRepositoryTest.kt`, `CompressionGalleryTest.kt`: 선택 한도·동률 페이지·앨범 필터·화면 재생성 검증.

## 검증

- JVM 단위 테스트 66개 통과, 실패·오류 0. 101번째 수동 선택 차단, 최대 100장 일괄 선택, 기존 앨범 선택 보존, 중복·빈 목록·작은 목록을 포함한다.
- Debug 앱·AndroidTest APK 빌드 성공. `lintDebug` 오류 0, 경고 49.
- Android API 37 x86_64 / 4GiB 에뮬레이터 계측 8개 통과(`OK (8 tests)`). 압축 전체 선택·정렬 UI 2개, 저장순/용량순의 123장 페이지·즐겨찾기 조회 1개, 기존 압축/GIF 선택·분할·템플릿 갤러리 경로 3개, 압축 실저장·원본/EXIF 보존·무재압축 복사 2개다.
- `build/compression-qa/size-sort-100.png`에서 전체 선택·정렬 버튼, 용량 표시, 확인 100장과 하단 선택 목록이 보이는 것을 확인했다.
- 720×1280px / 320dpi(360×640dp)의 작은 화면에서도 100장 선택·회전·확정 테스트 1개를 재실행해 통과했다(`OK (1 test)`). `size-sort-100-compact.png`에서 새 버튼과 상단 확인·하단 선택 목록이 잘리지 않음을 확인했고, 화면 크기·밀도를 원래 1080×2400px / 420dpi로 복원했다.
- 정렬·선택용 사진은 생성한 32×32 JPEG 123장이며 실제 고화소 사진 100장의 장시간 압축 성능을 검증한 것은 아니다. 폴더의 101장 차단은 소스 확인 범위이며 실제 SAF 폴더 선택·실기기 검증은 수행하지 않았다.

## 개발 환경 기록

- 기존 두 AVD 등록 파일이 삭제된 예전 체크아웃 경로를 참조해 최초 부팅에 실패했다. 현재 `build/SBPhotoTool_UX_Test.avd`를 가리키는 작업 전용 `build/compression-qa/avd/SBPhotoCompression_QA.ini`로 실행했다. 사용자 전역 AVD 등록은 수정하지 않았다.
- 최초 2GiB 에뮬레이터 계측은 첫 테스트 시작 직후 `LOW_MEMORY`로 OS가 앱을 종료했다. 로그의 `min watermark is breached and swap is low`와 종료 이력을 보관하고 `-memory 4096`으로 재실행했다. 앱 예외나 사진 선택 단계의 실패로 단정하지 않는다. 증거는 `first-exit-info.txt`, `instrumentation-first-low-memory.txt`, `logcat.txt`에 있다.
- 빌드·계측 로그: `build/compression-qa/gradle-verification.txt`, `gradle-final.txt`, `instrumentation.txt`, `instrumentation-compact.txt`. 테스트용 에뮬레이터는 검증 후 종료했다.

## 릴리스 APK·GoFile 전달 — 2026-11-03 KST

- `:app:assembleRelease :app:testReleaseUnitTest --console=plain` 성공. 릴리스 단위 테스트 66개, 실패·오류 0.
- 파일: `build/distributions/SB-Photo-Tool-1.2.1-compress-selection-20261103.apk`, 7,022,963바이트. 패키지 `com.sb.phototool`, 버전 `1.2.1 (4)`, minSdk 29, targetSdk 36, ABI `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- SHA-256: `833407ebf453379af91c6c66d2046654f50d911b0ae7de27a1591152529cbef3`.
- 기존 배포 APK와 같은 테스트용 인증서로 서명했다(Play 배포용 서명과 별개). 인증서 SHA-256은 `c7c96bc6616b41866223f7ad5e9bdf4d733965504bfaa7e3069e6db857179cae`. v2/v3 서명 검증과 `zipalign -c -P 16 -v 4` 통과. 기본 minSdk 29 검증에서는 v3가 선택되므로 v2는 추가로 검증 범위 API 24~28을 지정해 확인했다.
- ZIP CRC 통과. 최종 DEX에서 전체 선택·용량 큰 순 문자열 확인. INTERNET 권한·debuggable 플래그·키 파일·별도 테스트 사진/영상이 없고, assets는 기존 글꼴·라이선스와 빌드 프로필만 포함한다. 검사 결과는 `build/compression-qa/release-inspection.json`, `release-signature*.txt`, `release-alignment.txt`, `release-badging.txt`, `release-manifest.txt`에 있다.
- 서명한 최종 APK가 기존 앱 위에 업데이트 설치되는 것을 확인했다. API 37 에뮬레이터에서 일반 콜드 시작 후 압축 갤러리 진입, 별도로 생성한 테스트 앨범의 9 KB/5 KB/665 B 사진을 용량순으로 정렬, 전체 3장 선택·확정까지 확인했다. 화면·UI 트리는 `release-gallery-selected.png`, `release-gallery-selected.xml`, `release-confirmed.xml`에 보관했다.
- Debug 전용 AndroidTest APK를 R8 릴리스 위에 실행한 시도는 테스트 러너의 `kotlin.jvm.internal.Intrinsics` 미발견으로 시작에 실패했다. 이를 릴리스 계측 통과로 계산하지 않았다. 앱 일반 실행에서는 같은 오류가 발생하지 않았으며 위 일반 UI 검증으로 확인했다. 실패 근거는 `release-instrumentation.txt`, `release-crash.txt`에 보관했다.
- 최종 파일의 검사 이후 SHA-256 일치를 확인하고 GoFile 업로드 1회 성공. https://gofile.io/d/P0tefNh4 — 2026-11-03 17:21:08 KST 공유 페이지 GET HTTP 200 확인. 게스트 임시 저장이며 관리 토큰은 출력·보관하지 않았다. 테스트용 에뮬레이터는 검증 후 종료했다.

## 전체 선택 옆 전체 해제 — 2026-11-03 KST

- 압축 갤러리 상단에 `전체 선택 / 전체 해제 / 정렬`을 나란히 배치했다. 100장 한도 안내는 바로 아래 드래그 선택 안내에 표시해 작은 화면에서도 버튼을 위한 공간을 확보했다.
- `전체 해제`는 선택이 0장일 때 비활성화하고, 수동·전체 선택 여부와 관계없이 1장 이상이면 활성화한다. 사진 조회·확정 중에는 다른 선택 조작과 함께 비활성화한다.
- 누르면 현재 필터에 보이지 않는 기존 선택까지 모두 해제하고 안내 메시지도 지운다. 확인 버튼과 하단 선택 목록은 기존 선택 상태를 따라 갱신된다. 정렬·앨범·즐겨찾기 필터는 유지한다.
- `PhotoGalleryPicker.kt`, `CompressionGalleryTest.kt`, README를 수정했다. 새 회귀 확인은 0→1→0 활성 상태, 숨겨진 선택을 포함한 전체 해제, 100장 일괄 해제와 한 장 재선택을 기존 압축 갤러리 계측에 포함했다.
- 릴리스 단위 테스트 66개 통과, 실패·오류 0. Debug·AndroidTest·R8 릴리스 빌드 성공. Lint 오류 0, 경고 49. 실행 로그는 `build/compression-clear-qa/gradle.txt`에 있다.
- API 37 x86_64 / 4GiB 에뮬레이터를 720×1280px / 320dpi(360×640dp)로 설정해 계측 4개 통과(`OK (4 tests)`). 새 압축 UI 2개와 기존 압축/GIF 선택·분할 선택 2개다. 1장 선택/직접 해제, 필터에 보이지 않는 사진을 포함한 전체 해제, 100장 일괄 해제, 재선택, 회전 후 상태를 검사했다. `clear-all-size-sort-100.png`, `clear-all-single.png`, `clear-all-empty.png`에서 버튼·확인·하단 목록이 잘리지 않고 0장일 때 전체 해제가 회색으로 표시되는 것을 확인했다. 화면 크기·밀도는 원래대로 복원했다.
- 새 배포 파일: `build/distributions/SB-Photo-Tool-1.2.1-compress-clear-all-20261103.apk`, 7,022,963바이트. SHA-256: `fa54d76160d98ba803bfb8ba55ee44450ffd61d8b39e9f88e7d6af2e787562f2`. 이전 APK는 그대로 보존했다.
- 패키지 `com.sb.phototool`, 버전 `1.2.1 (4)`, Android 10 이상, targetSdk 36, ABI 4종. 이전 배포본과 같은 테스트용 인증서로 v2/v3 서명 검증·16KiB ZIP 정렬·ZIP CRC 통과. DEX의 전체 해제 문자열, INTERNET 권한·debuggable 플래그·키 파일·별도 사진/영상 미포함을 확인했다. 상세 검사 결과는 `build/compression-clear-qa/inspection.json`, `signature*.txt`, `alignment.txt`, `badging.txt`, `manifest.txt`에 있다.
- 최종 서명 APK를 기존 앱 위에 업데이트 설치했다. 일반 실행에서 사진 0장→1장→전체 해제 후 0장으로 바뀔 때 실제 버튼이 비활성→활성→비활성으로 변하고, 확인 버튼과 하단 선택 목록도 함께 초기화되는 것을 확인했다. `release-smoke.txt`, `release-empty.xml`, `release-single.xml`, `release-cleared.xml`, `release-single.png`, `release-cleared.png`에 증거를 보관했다.
- 릴리스 UI 확인 초기에 에뮬레이터의 System UI·Pixel Launcher 응답 없음 대화상자가 앱을 가렸다. System UI의 기다리기와 런처 닫기를 처리한 뒤 앱 홈이 정상 표시됐다. 증거는 `release-systemui-anr.xml`, `release-launcher-anr.xml`, `release-smoke-first-systemui-anr.txt`에 있다. 보조 검사 도구가 자식 TextView의 enabled를 읽던 부분도 실제 클릭 가능한 부모 버튼의 enabled를 읽도록 보정했다. 제품 코드 추가 수정은 없었다.
- 최종 검사 후 해시 일치를 다시 확인하고 GoFile 업로드 1회 성공. https://gofile.io/d/27gDEjyZ — 2026-11-03 17:45:16 KST 공유 페이지 GET HTTP 200 확인. 게스트 임시 저장이다. 이번 작업의 테스트용 에뮬레이터만 종료했다. 실기기 검증은 수행하지 않았다.
