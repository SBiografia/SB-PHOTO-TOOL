# 고화질 미리보기와 자체 갤러리 — 2026-09-30 KST

## 원인 및 결정

- 기존 콜라주는 `ContentResolver.loadThumbnail`이 제공하는 작은 파생 이미지를 확대했고, 그리드는 긴 변 768px로 제한했다. 편집 미리보기는 갤러리 썸네일과 분리하여 원본을 `ImageDecoder`로 읽도록 변경했다. EXIF 회전과 원래 비율을 보존한다.
- 최대 긴 변 8192px, 전체 메모리 예산 `min(heap / 4, 128MiB)`, 개별 64MiB 이하를 적용한다. 콜라주는 서로 다른 원본 수로 나누고 순차 디코딩한다. 원본이 작으면 확대 디코딩하지 않는다. 무제한 원본 디코딩은 여러 고화소 사진에서 OOM 또는 Canvas 크기 제한을 일으킬 수 있어 채택하지 않았다.
- 메모리 부족 시 해상도를 낮춰 한 번 더 시도하며 실패하면 재시도 화면을 유지하고 진단 로그에 기록한다. Compose가 그리는 Bitmap을 직접 recycle하지 않는다. 실제 저장은 기존 원본 기반 경로를 유지한다.

## 자체 갤러리

SEMO의 로컬 `lib/features/gallery_picker/presentation/semo_gallery_picker_screen.dart`를 읽어 앨범 드롭다운, 날짜별 3열 그리드, 선택 순번, 상단 확인 흐름을 참고했다. 다른 앱의 코드를 복사하거나 라이브러리를 추가하지 않았다.

- 콜라주 전체 선택/재선택/추가/교체, 그리드, 압축, GIF/MP4가 같은 `ui/gallery/PhotoGalleryPicker.kt`를 사용한다. 압축 폴더 선택은 SAF를 유지한다.
- 사진·즐겨찾기 필터, 앨범 목록 및 개수, 선택 순번과 하단 선택 사진 모음. 길게 눌러 범위 선택/해제하고 위·아래 끝에서 자동 스크롤한다. 일반 드래그는 목록 스크롤이다. 선택 표시를 즉시 갱신하고 불필요한 전환 애니메이션을 추가하지 않았다.
- 콜라주 최대 9장, 그리드 1장(다른 사진을 누르면 교체), GIF/MP4 최대 100장. 압축 선택에는 100장 제한을 적용하지 않는다. 초과 사진을 완료 후 몰래 잘라내지 않는다.
- 선택 목록은 순서대로 ViewModel에 저장하고 같은 갤러리 세션의 화면 재생성 때 유지한다. 취소는 기존 편집 사진을 바꾸지 않는다. 새 선택 세션은 빈 상태로 시작한다. 홈 복귀 초기화는 유지한다.
- 사진 목록은 저장 날짜와 ID 내림차순 키 기반으로 120장씩 읽는다. 앨범 변경 시 이전 요청은 취소한다. 목록용 384px 썸네일은 최대 2개 동시 로딩과 최대 16MiB 메모리 캐시를 사용하며 편집 미리보기에 재사용하지 않는다.
- 권한 없음/거부, 제한 접근, 빈 앨범, 로딩 실패와 재시도를 처리한다. 확인 직전 읽기 가능 여부를 확인하고 삭제되었거나 접근 불가한 사진은 안내 후 사용자가 다시 확인하도록 한다.
- Android 10~12 사진 읽기, 13 이미지 읽기, 14 이상 제한 사진 권한을 처리한다. 앱 복귀 시 권한과 목록을 다시 확인한다. [Android 공식 권한 변경 문서](https://developer.android.com/about/versions/14/behavior-changes-14)를 참고했다. 인터넷 권한은 추가하지 않았다.

## 검증 상태

- JVM 단위 테스트 **35개 통과**, 실패 0. 새 해상도·메모리 경계 8개와 선택 정책 6개를 포함한다.
- Debug APK 및 AndroidTest APK 빌드 성공. Android Lint **오류 0, 경고 38**(의존성 업데이트·KTX 권고 등). 검증용 빌드이며 이번 작업에서 배포용 APK 또는 GoFile 업로드를 만들지는 않았다.
- Android API 37 전용 에뮬레이터(1080×2400, 420dpi) 전체 **21개 통과**, `OK (21 tests)`. 최종 선택 목록 스냅샷 및 창 크기 수정 후 재실행한 결과다.
- 작은 화면(720×1280, 320dpi / 360×640dp)에서도 네 기능의 선택·복귀 회귀 테스트 **4개 통과**, `OK (4 tests)`. `build/gallery-qa/compact/gallery-qa`의 캡처에서 상단 확인과 하단 선택 목록이 온전히 표시됨을 확인했고 테스트 후 AVD 화면 크기·밀도를 원래대로 복원했다.
- 콜라주 9장 제한, 선택 순번·드래그, 사진 추가/교체, 재선택 취소, 편집기 및 선택 화면 재생성, 홈 복귀 초기화, 압축/GIF 선택 경로를 검사했다. 4096×2048 원본이 두 편집기에서 그대로 디코딩되고 EXIF 회전 후 2048×4096으로 읽히는지 검사했다.
- 123장 앨범을 120장+3장으로 누락·중복 없이 읽기, 즐겨찾기 필터, 접근할 수 없게 된 선택 사진 감지를 검사했다. 인접 기능의 콜라주/분할/압축/GIF 실제 저장 테스트도 통과했다. MP4 인코딩은 이번 변경 범위가 아니며 이번 실행에서 별도 실저장 검증은 하지 않았다.
- 에뮬레이터에서 제한 사진 권한 배너·허용 사진 추가 버튼, 최초 권한 요청 거부 후 안내와 앱 설정 버튼을 직접 확인했다. 실제 외부 앱 소유 사진의 제한 접근 조합은 실기기 추가 검증 대상이다.
- 화면 캡처는 `build/gallery-qa/gallery-qa`와 `build/gallery-qa/gallery-limited.png`, `gallery-denied.png`에 있다. 최종 갤러리 캡처에서 하단 선택 사진 전체가 표시됨을 확인했다.
- 연결된 실제 기기는 ADB 미승인 상태라 실기기 테스트는 하지 않았다. Android 10~14 각각의 실기기, 초고화소 HEIC/Ultra HDR 및 수만 장 앨범의 장기 성능은 별도 검증이 필요하다.

### 검증 중 발견한 문제와 처리 — 2026-09-30 KST

- 갤러리 선택을 비우고 닫는 시점에 LazyRow가 이전 항목 수로 변경 가능한 목록을 읽어 `IndexOutOfBoundsException`이 재현됐다. 선택 사진 목록은 각 구성 시점의 불변 스냅샷을 LazyRow에 전달하도록 수정했다. 닫기·재열기·사진 추가/교체 회귀 테스트가 이 경로를 검사한다.
- API 37에서 갤러리 하단 선택 사진 목록이 시스템 내비게이션 영역 밖으로 잘렸다. 로컬 Compose UI 1.7.6의 `DialogLayout.internalOnMeasure`가 full-width 모드에서 부모 측정값 대신 `screenHeightDp`를 사용함을 확인했다. Dialog 속성만 바꾸는 것으로는 해결되지 않아 호스트 창의 실제 visible frame으로 크기를 제한하고 창 크기 변경도 반영한다.
- Android 테스트 프로세스 안에서 최초 사진 권한을 shell로 변경하면 프로세스가 종료될 수 있어, 권한은 테스트 실행 전에 부여하고 테스트 안에서는 사전 조건만 확인하도록 바꿨다.
- 갤러리 목록 테스트의 삭제 항목을 정리 단계에서 다시 삭제하면서 SecurityException이 발생했다. 테스트 소유 목록에서도 해당 URI를 제거하여 중복 정리를 없앴다. 앱의 파일 삭제 기능을 추가한 것이 아니다.

### 재현 명령

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.sb.phototool android.permission.READ_MEDIA_IMAGES
adb -s emulator-5554 shell pm grant com.sb.phototool android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell am instrument -w -r com.sb.phototool.test/androidx.test.runner.AndroidJUnitRunner
```

`emulator-5554`는 이번 전용 테스트 AVD의 연결 ID다. 다른 기기에 그대로 실행하지 않는다. API 32 이하는 사진 권한을 `READ_EXTERNAL_STORAGE`로 바꾼다. 테스트는 생성한 사진만 자체 URI 목록을 통해 정리한다.

## 경계

- 기기에 내려받지 않은 클라우드 전용 사진은 자체 MediaStore 갤러리에서 조회하지 않는다.
- 갤러리 선택 중 프로세스 자체가 종료되면 선택 목록은 초기화한다. 대량 사진 URI를 Bundle에 담아 복원하지 않는다.
- 기존 `COLLAGE_UX.md`, `TOOLS_UX.md`의 당시 검증 기록은 역사적 기록이며 현재 사진 선택 방식, GIF/MP4 입력·출력, 홈 복귀 동작은 이 문서와 README를 따른다.

### 대량 앨범 페이지 로딩 보완 — 2026-10-01 KST

- 페이지 끝 자동 불러오기는 현재 목록 크기와 마지막 가시 항목을 기준으로 판단하고, 한 번의 페이지 요청이 진행 중일 때 중복 요청하지 않는다.
- 앨범 조회 수와 로드된 수가 다른데 MediaStore 페이지가 끝났다고 응답하면 다음 페이지를 계속 확인한다. 자동 로드가 멈춰 보일 때를 위해 하단에 “사진 더 불러오기”도 제공한다.
- 대량 앨범 계측 테스트와 실기기 검증은 아직 별도로 하지 않았다.
