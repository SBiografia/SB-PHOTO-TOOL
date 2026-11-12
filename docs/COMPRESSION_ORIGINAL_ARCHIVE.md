# 압축 원본 별도 보관 폴더 이동

2026-11-04 KST

## 합의한 동작

- 홈의 최근 압축 작업에 `원본 N장 별도 보관 폴더로 이동`을 표시한다. N은 압축 저장에 성공했고 현재 이동 가능한 원본 수다.
- 사용자가 버튼을 누르고 시스템의 파일 수정 요청을 승인하면, 원본과 같은 저장장치의 `Pictures/SB PHOTO TOOL/compress_original/`로 원본을 옮긴다. 압축본의 저장 위치는 `Pictures/SB PHOTO TOOL/Compress/`다.
- 사용자가 보관 폴더의 원본을 삭제하거나 별도로 백업한다. 앱에서 자동 이동·휴지통 처리·영구 삭제를 수행하는 흐름은 없다.
- Android 11 이상에서 지원한다. Android 10에서는 지원 범위를 안내한다. MediaStore 항목으로 변환·접근할 수 없는 공급자, 휴지통·저장 대기 상태의 항목은 제외 안내한다.

## 구현

- `MainActivity.kt`는 `MediaStore.createWriteRequest`로 수정 승인을 받은 후 이동을 실행한다. 승인 결과 수신기와 작업 ID는 앱 루트에 있어 화면 재생성 후에도 같은 작업에 연결된다.
- `CompressionOriginalArchive.kt`는 성공 원본 목록을 실제 사진 URI로 정규화·중복 제거한다. 원래 저장장치를 유지하며 `ContentResolver.update`로 `RELATIVE_PATH`만 변경한다. 사진 재인코딩이나 원본 스트림 쓰기는 수행하지 않는다.
- 파일명 충돌은 MediaProvider의 이동 시 고유 파일명 생성에 맡긴다. Android 공개 소스에서 이동 전에 `ensureUniqueFileColumns`를 호출하는 것을 확인했다.
- 이동 전후 실제 원본 파일을 읽기 전용으로 열고 크기를 비교하며, 현재 경로와 일반 사진 상태를 다시 확인한다. 확인된 원본만 정리 목록에서 제거하고 실패·접근 불가 개수를 구분한다. 중단된 작업을 다시 누르면 이미 보관 폴더에 있는 사진을 재이동하지 않는다.
- `CompressionOriginalStore.kt`는 이전 APK의 `compression-trash.json` 파일명과 JSON 구조를 유지해 아직 처리하지 않은 완료 작업을 읽을 수 있다. 새 압축 시작 시 작업 ID 등록, 늦은 이전 작업의 완료·승인 응답 격리는 유지한다. 이미 이전 버전에서 휴지통 처리된 사진은 이동 대상에서 제외한다.
- `CompressionOriginalAction.kt`의 버튼은 전체 폭에서 줄바꿈할 수 있다. 처리·승인 대기 중에는 중복 요청을 막는다. 압축 실패 사진과 선택하지 않은 사진은 성공 원본 목록에 포함되지 않는다.

## 검증 범위

- APK 빌드는 사용자의 요청에 따라 수행하지 않는다. 실제 폴더 이동·삼성 갤러리 표시는 이번 작업에서 기기로 검증하지 않는다.
- 기존 휴지통 계측을 폴더 이동 계측으로 갱신했다. 부분 압축 실패, 승인 취소, 화면 재생성 후 승인, 100장 이동과 바이트 보존, 외부 앱 소유 원본의 승인 전 부분 실패와 승인 후 재시도, 이름 중복과 기존 파일 보존, 이전 JSON 기록 호환, 이동 직후 중단 상태의 재시도를 검사하도록 구성했다. 이번 작업에서는 계측 소스 컴파일까지 확인하며 실행 결과로 보고하지 않는다.
- 컴파일·단위 테스트·Lint 결과는 `build/compression-archive-qa/verification.txt`, `verification-final.txt`, `verification-completed.txt`에 기록한다.
- 최종 `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:lintDebug` 성공(`verification-completed.txt`, 37초). 단위 테스트 66개, 실패·오류 0. Lint 오류 0, 경고 50이며 신규 이동 파일의 경고는 기존 코드에서도 사용하는 `Uri.parse`의 KTX 권고 1건이다. 계측 5개는 소스 컴파일 성공으로 한정한다.
- `git diff --check` 통과. `app/build/outputs/apk`의 기존 Debug·AndroidTest·Release APK 수정 시각이 모두 2026-11-03으로 유지됨을 확인했다. 이번 변경의 APK 생성·설치·기기 실행은 수행하지 않았다.

## 근거

- [Android 공유 미디어 수정 및 이동](https://developer.android.com/training/data-storage/shared/media#update-item)
- [Android MediaProvider 이동 구현](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/src/com/android/providers/media/MediaProvider.java)
