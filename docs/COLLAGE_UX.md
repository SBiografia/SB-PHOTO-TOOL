# 콜라주 UX 개편 — 2026-09-29 KST

이 문서는 당시 구현·검증 기록이다. 2026-10-28 자유형 편집과 확장 템플릿은 [자유형·레이아웃 확장](COLLAGE_FREEFORM.md)을, 현재 미리보기 품질·홈 복귀 초기화 정책은 [README](../README.md)를 따른다.

## 기준과 범위

사용자가 제공한 47초 GridArt 조작 영상에서 큰 고정 미리보기, 두 줄 레이아웃 선택, 사진 터치 후 문맥 도구, 직접 이동·확대, 경계선 조절, 위치 교환·교체 흐름을 확인했다. 로고·유료 템플릿·이미지 자산을 복제하지 않고 해당 조작 흐름을 기존 네이티브 Compose 앱에 구현한다. 압축·분할·GIF 화면은 재설계 범위가 아니다.

## 변경

- 스크롤 문서 형태였던 콜라주 화면을 상단 저장/실행 취소 + 고정 미리보기 + 하단 도구 패널로 분리.
- 사진 수 1~9장에 맞는 실제 레이아웃 썸네일, 비율 프리셋, 바깥 여백·사진 간격·모서리, 배경색·테두리색·두께.
- 사진을 터치하면 교체·위치 교환·회전·좌우 반전·초기화·삭제. 위치 교환을 누른 뒤 다른 사진을 선택한다.
- 사진 위 드래그/핀치로 구도 조절. 사진 내부 경계 손잡이로 칸 크기 조절. 1회 연속 제스처는 실행 취소 1단계이며 최대 30단계.
- 원본을 변경하지 않고 새 JPEG 저장. 편집 내용은 홈 왕복·화면 재생성에 복원하되 비트맵은 저장 상태에 넣지 않는다. 완전히 종료한 앱을 위한 영구 프로젝트 저장은 포함하지 않는다.
- 미리보기와 출력은 `CollageGeometry`/`CollageRenderer`를 공유한다. 오래된 작업 JSON에는 새 설정의 기본값을 적용한다.
- 768px 미리보기만 순차 로딩. 재배치 시 이미 읽은 사진을 재사용하고, Compose가 표시 중인 비트맵을 수동 해제하지 않는다. 실패한 사진은 재시도/교체 가능하며 저장은 비활성화한다.
- 저장 요청 후 홈의 기존 진행/완료 카드로 이동하며, 콜라주를 다시 열면 편집 배치를 복원한다.

## 검증 중 발견한 환경 문제

2026-09-29 KST: 설치된 API 37 에뮬레이터에서 Espresso 3.6.1은 테스트 시작 전 `InputManager.getInstance` 반사 호출 오류로 종료됐다. 앱 오류와 분리하고, 해당 수정이 포함된 테스트 전용 Espresso 3.7.0 / AndroidX JUnit 1.3.0으로 갱신했다. 배포 앱에 테스트 의존성을 추가하지 않는다. 근거: [AndroidX Test 릴리스 노트](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0).

## 검증 기록

2026-09-29 KST 최종 확인:

- `:app:testDebugUnitTest`: 17개, 실패 0. 1~9장 모든 템플릿의 면적/겹침, 경계 이동 한계, 확대 기준점, 회전 후 영역 채움, 출력과 미리보기 비례, 실행 취소, 신규/이전 JSON 복원을 검사했다.
- `:app:lintDebug`: 오류 0, 경고 32. 경고는 의존성 최신 버전·KTX 권고·SharedPreferences 기록 방식 관련이다.
- `:app:assembleDebug :app:assembleDebugAndroidTest`: 성공.
- API 37 x86_64 16KB 에뮬레이터 `SBPhotoTool_UX_Test`에서 최종 7개 계측 테스트 통과(33.675초).
- 갤러리 `content://` 사진으로 5장/9장 로딩, 레이아웃·비율 변경, 터치·드래그·핀치, 사진 맞바꾸기와 실행 취소/다시 실행, 칸 크기 변경, 여백/색상/테두리, 모두 삭제 후 복구를 검사했다. 없는 파일은 저장을 비활성화하고 재시도/교체 경로를 제공했다.
- 실제 MainActivity에서 사진 선택 결과 수신, 최대 9장 요청, 홈 왕복, Activity 재생성, Worker를 통한 갤러리 저장 완료, 편집 내용 복원까지 통과했다. 선택기 외부 UI는 테스트 결과 주입으로 대체했다.
- 출력 JPEG는 공유 렌더러의 예상 이미지와 픽셀 비교로 확인(채널 평균 오차 7 미만, JPEG 손실 허용). 회전/반전과 인접 기능인 3분할 출력도 통과했다.
- `build/collage-ux-reference/collage-qa/`의 화면 캡처로 레이아웃·사진 선택 도구·테두리 패널을 확인했다. 테스트 이미지들은 합성 도형이며 사용자 사진을 사용하지 않았다.
- 연결된 실제 기기는 ADB `unauthorized`여서 실기기/삼성 갤러리 선택 화면/Android 10~16별 호환성은 미검증이다. 이번 개편에서 압축·GIF 전체 동작은 다시 검증하지 않았다.

사용자 원본 사진·영상은 APK나 업로드에 포함하지 않는다. APK ZIP 경로 검사에서 참고 사진·영상·키 저장소가 없음을 확인했다.

## 변경 파일과 전달 APK

- 화면/상태: `ui/collage/CollageEditorScreen.kt`, `CollageCanvas.kt`, `CollageEditorState.kt`, `MainActivity.kt`.
- 배치/출력: `media/CollageLayout.kt`, `CollageGeometry.kt`, `CollageRenderer.kt`, `MediaProcessor.kt`, `model/JobSpec.kt`.
- 저장 작업: `worker/ToolboxWorker.kt`. 버전 및 테스트 의존성: `app/build.gradle.kts`.
- 단위/기기 테스트: `app/src/test/`, `app/src/androidTest/`. 기능 설명: `README.md`.
- 전달 파일: `build/distributions/SB-Photo-Tool-1.1.0-collage.apk`, 58,181,194 bytes. 최종 검증된 `app-debug.apk`와 SHA-256이 같은 복사본이다.
- 버전 `1.1.0 (2)`, minSdk 29 / targetSdk 36, arm64-v8a·armeabi-v7a·x86·x86_64 범용 테스트 APK. APK v2 서명 검증 통과. Android Debug 인증서이며 Play 스토어 배포 서명이 아니다.
- APK SHA-256: `3a17ec646127544ccb5c89a661fdfef56e7066db7bfaa74fda0c648e2a0f3f40`.
- 인증서 SHA-256: `c7c96bc6616b41866223f7ad5e9bdf4d733965504bfaa7e3069e6db857179cae`.
- GoFile 전달: https://gofile.io/d/STZgyIv1 (게스트 임시 저장). 업로드는 APK 한 파일만 1회 수행했다.

## 저장 연결 검사에서 추가 발견한 오류

2026-09-29 KST: 실제 MainActivity에서 사진 선택→홈 왕복→Activity 재생성→저장까지 실행할 때 `InvalidForegroundServiceTypeException: Starting FGS with type none`으로 종료됨을 재현했다. Manifest에는 `dataSync`가 있었지만 Worker의 `ForegroundInfo` 생성 시 유형을 넘기지 않았던 것이 원인이다. `FOREGROUND_SERVICE_TYPE_DATA_SYNC`를 명시하고, Android 10~11의 expedited worker를 위한 `getForegroundInfo()`도 제공한다. 공통 저장 Worker의 수정이므로 콜라주 외 기능의 저장에도 적용된다. [WorkManager 공식 지침](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).
