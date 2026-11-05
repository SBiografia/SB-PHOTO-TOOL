# 압축 메타데이터 보존

기준일: 2026-11-05 KST. 기존 압축본 복구나 원본 변경은 수행하지 않는다.

## 동작

- 압축은 입력 파일의 실제 형식을 판별해 JPEG→JPEG, PNG→PNG, WebP→WebP로 저장한다. 기존 Job JSON의 outputFormat 필드는 호환성을 위해 남기지만 압축 형식 변환에는 사용하지 않는다.
- 이름과 확장자를 그대로 사용한다. `Pictures/SB PHOTO TOOL/Compress/`에서 충돌하면 고유 하위 폴더에 같은 이름으로 새 파일을 만든다. 원본 URI에 쓰지 않는다.
- 촬영 정보 보존은 기본 켜짐이다. MediaStore 원본은 ACCESS_MEDIA_LOCATION 권한 및 setRequireOriginal로 연다. 원본 접근을 확인할 수 없는 문서 공급자는 보존을 보장했다고 처리하지 않는다.
- 입력을 앱 캐시에 고정한 후 메타데이터를 읽는다. 실제 원본 FD의 stat 수정일과 MediaStore 촬영일을 별도로 확보한다. DB 수정일을 원본의 실제 수정일로 대체하지 않는다.
- JPEG APP0~APP15/COM, PNG 지원 부가 청크, WebP EXIF/XMP/ICCP/알 수 없는 청크를 바이트로 옮긴다. EXIF는 문자열로 재생성하지 않아 GPS 유리수의 정밀도, 시간대, 소수초, MakerNote 및 라이브러리에 등록되지 않은 태그도 원본 블록에 남긴다.
- 압축 픽셀의 원래 저장 방향과 EXIF Orientation을 함께 유지한다. IFD0 ImageWidth/ImageLength와 EXIF PixelXDimension/PixelYDimension이 있으면 실제 출력 치수로만 패치한다. 바이트 순서·오프셋·다른 값은 유지한다. 알려진 XMP 치수도 출력에 맞춘다.
- 원본 MediaStore DATE_TAKEN을 우선 사용한다. 없으면 원본 EXIF 촬영일+명시된 시간대를 사용한다. 시간대 없는 촬영일에 시스템 시간대를 넣지 않는다. 이 경우 DB 날짜를 확정하지 못했다는 안내를 남긴다.
- 최종 파일 기록 후 원본 실제 수정일을 설정하고 FD stat으로 다시 읽는다. 공개 및 미디어 스캔 뒤 파일 해시·수정일·DB 날짜·실제 이름을 검증한다. 파일시스템 수정일과 DB DATE_MODIFIED를 따로 확인한다.
- 메타데이터 오류는 성공으로 숨기지 않는다. 파일 저장 후 날짜/수정일 확인에 실패하면 완료 메시지에 안내한다. 안내가 있는 원본은 별도 보관 이동 대상에서 제외한다.
- 메타데이터 때문에 목표 용량을 넘으면 정보를 버리지 않고 용량 초과를 알린다.
- SDR 본체와 JPEG 게인맵 1장으로 구성된 Ultra HDR은 본체만 압축한다. 보조 JPEG 전체와 HDR 수치·ICC·XMP는 보존하며 MPF의 본체 길이와 보조 이미지 위치만 새 파일에 맞게 갱신한다. Android 14 이상에서는 본체 인코딩 전에 메모리 비트맵의 게인맵 연결을 분리해 보조 이미지가 중복 생성되거나 재압축되는 것을 막는다.
- 후단 데이터 유무만으로 모션사진이라고 표시하지 않는다. 지원하지 않는 구조는 실제 판정 사유를 완료 메시지와 `compress/file_failed` 진단 로그에 남긴다.
- 삼성 SEF가 있으면 파일 끝의 SEFH/SEFT 디렉터리를 검사해 HDR JPEG와 분리한다. 날짜·색상·알 수 없는 항목·인물사진 추가 이미지·편집 데이터·모션 MP4를 포함한 SEF 전체 바이트는 그대로 이어 붙인다. 디렉터리 상대 위치가 유지되므로 SEF 내부 참조를 재작성하지 않는다. MPF의 본체 길이/보조 위치만 새 본체에 맞춘다.
- HDR+삼성 모션사진은 XMP의 Primary/GainMap/MotionPhoto 항목, 영상 위치·길이와 SEF의 실제 MP4 범위를 대조한다. 영상·음성은 재인코딩하지 않는다. 인물사진은 보존 데이터와 별도로 삼성 갤러리 재편집 동작이 미검증이라는 안내를 내보내며, 그 원본을 선택적 보관 이동 대상에서 제외한다.

## 제한

- 해상도가 달라지면 치수 값은 달라진다. 모든 EXIF 바이트가 완전히 동일하다는 뜻은 아니다.
- 제조사 비공개 블록은 바이트 보존을 확인하며, 모든 제조사 내부 의미/파일 외부 참조의 유효성까지 보장하지 않는다.
- HEIC 등 지원하지 않는 원본 형식은 확장자만 바꿔 저장하지 않는다.
- 지원 범위를 벗어난 다중 이미지·HDR 구조, 확인할 수 없는 후단 데이터, 확장 XMP, 움직이는 PNG/WebP, 지원하지 않는 PNG 색상/부가 구조는 재압축을 거부하고 사유를 알린다. 지원 형식의 무재압축 복사는 원본 바이트를 유지한다. 지원하는 삼성 모션 형식 외의 추가 항목·패딩, 손상된 MPF/SEF 인덱스를 임의로 단순화하지 않는다. 후단 데이터는 최대 32MiB이며 초과하면 사유를 알린다.
- 쓰기 가능한 실제 파일/FD가 없는 공급자나 파일시스템 정밀도 제약에서는 실제 수정일을 그대로 설정하지 못할 수 있다. 원본 촬영일로 대체하지 않는다.
- 미디어 DB는 Android가 관리한다. 기록 요청 후 스캔 결과를 확인하며, 다른 기기에서의 재색인까지 무조건 유지한다고 보장하지 않는다.

## 검증 기록

- 2026-11-04 KST: 수정 전 파일은 `build/metadata-qa/baseline`에 별도 보관했다. 기존 작업폴더의 다른 수정은 유지했다.
- 2026-11-04 KST: 단위 검증에서 WebP의 ICCP 우선 배치와 메타데이터 비교 순서 차이를 발견했다. 같은 종류의 블록 내 순서는 유지하며 WebP 종류 간 순서만 정규화해 검증한다.
- 2026-11-04 KST: MediaStore의 중복 이름 배정은 삽입 시뿐 아니라 IS_PENDING 해제 때도 발생한다. 임시 `.pending-숫자-` 접두사를 구분하고 실제 할당 이름, 같은 폴더의 기존 DB 행 및 최종 경로의 기존 파일을 확인한다. 충돌은 고유 하위 폴더에 새로 예약하며 공개 후 이름도 재검증한다.
- 2026-11-04 KST: 용량 우선 추가 축소 시 인코더가 입력 비트맵을 해제할 수 있음을 확인했다. 색공간은 인코딩 전에 확보해 해제된 비트맵을 읽지 않는다.
- 2026-11-04 KST: 최종 단위 테스트 84개 통과, 실패·오류·건너뜀 0개. 근거: `build/metadata-qa/verified-final-build.txt`, `unit-summary.json`.
- 2026-11-04 KST: API 37 x86_64 / 4GiB 전용 에뮬레이터에서 압축 메타데이터 11개, 기존 압축·분할·GIF 내보내기 4개, 별도 프로세스 재실행 1개 통과. 기존 원본 보관·100장 처리 및 선택 UI 7개도 통과했다(서로 다른 검증 23개). 근거: `verified-final-instrumentation.txt`, `verified-final-restart.txt`, `neighbor-instrumentation.txt`.
- 2026-11-04 KST: 실제 저장한 합성 JPEG를 앱 ContentResolver로 다시 읽어 ExifTool로 별도 비교했다. 메타데이터 67개 및 GPS 32개에 누락이 없으며, 출력 치수 4개만 240×400에서 120×200으로 변경됐다. 실제 수정일은 촬영일과 다른 `2026-11-01T04:05:06.123Z`를 유지했다. 근거: `metadata-external-comparison.json`, `lifecycle-external-check.json`.
- 2026-11-04 KST: run-as 셸의 외부 저장소 직접 읽기는 권한 거절로 실패했다. 오류 문자열을 이미지로 취급하지 않고 해시 불일치로 검출한 후, 앱이 검증한 바이트를 앱 캐시로 내보내 대조했다. 외부 저장소 직접 접근 성공으로 보고하지 않는다.
- 2026-11-04 KST: 최종 릴리스 APK를 전용 에뮬레이터에 설치·실행했다. v2/v3 서명·기존 인증서 일치·16KiB 정렬·ZIP CRC·디버그 불가·테스트/개인 사진/키 파일 미포함을 확인했다. INTERNET 권한은 없으며 ACCESS_MEDIA_LOCATION만 사진 메타데이터 접근에 추가했다.
- 실기기 사진과 삼성 갤러리·설치된 SEMO의 실제 표시는 검증하지 않았다. Android 10 실기기/에뮬레이터 검증도 수행하지 않았다. 기존 사용자 사진과 기존 압축본은 변경하지 않았다.

## 최종 배포

### 커밋 범위 검증 (2026-11-05 KST)

- 사용자 요청에 따라 압축 메타데이터 구현·권한·UI 안내·테스트·합성 TIFF 생성기·문서 21개 파일만 별도 작업 트리로 분리했다. `MediaProcessor.kt`의 콜라주 변경과 압축 화면의 별도 100장 선택 안내는 포함하지 않았다. 원래 작업폴더의 다른 편집과 개인 사진·빌드 산출물은 커밋 대상에서 제외한다.
- 분리한 상태에서 단위 테스트 72개 통과(실패·오류·건너뜀 0), debug APK와 androidTest APK 빌드 통과, diff 공백 검사 통과를 확인했다. 압축 처리 본체는 앞서 원본 4장 및 20개 계측 검증을 통과한 코드와 같다. 이 커밋 검증에서는 기기 테스트를 다시 실행하지 않았다.
- 앞서 배포한 APK와 102개 단위 테스트 기록은 다른 진행 중 작업을 포함한 당시 전체 작업폴더 기준이다. 이번에 분리한 커밋 전체가 그 APK의 전체 소스와 같다고 보고하지 않는다. 근거: `build/sef-metadata-qa/commit-isolated-build.txt`, `commit-unit-summary.json`, `commit-files.json`.

### 삼성 SEF·모션·인물사진 보완 (2026-11-05 KST)

- `SamsungSef.kt`를 추가해 후단 디렉터리의 범위, 항목 종류·이름·길이, 중첩/빈틈 여부와 모션 MP4 박스 경계를 검사한다. 전체 SEF를 불투명 바이트로 유지하고, HDR 범위를 별도로 검사한다. 삼성 HDR+모션 XMP가 가리키는 영상 위치·길이도 실제 SEF와 대조한다. 지원하지 않는 구조는 사유를 알리며 버리거나 추정해서 저장하지 않는다.
- 제공된 ZIP 원본 4장 모두 API 37 전용 에뮬레이터에서 50% 해상도 압축·저장에 성공했다. 실제 원본 이름·확장자, EXIF 촬영일/시간대/소수초·GPS·회전, MediaStore 촬영일/수정일, 별도로 설정한 실제 파일 수정일을 확인했다. 파일 수정일 검증에는 촬영일과 다른 `2026-11-01T04:05:06.123Z`를 사용했다. 이는 ZIP으로 휴대폰 실제 수정일을 알아냈다는 의미가 아니다.
- ExifTool 별도 대조는 각 118/125/175/119개, 총 537개 메타데이터 필드(사진당 GPS 6개 포함)에 누락이 없었다. 변경된 값은 출력 치수 4개와 HDR이 있는 사진의 MPF 길이/위치 2개뿐이다. 삼성 SEF 전체, HDR 보조 JPEG, 모션 영상·음성 바이트가 원본과 일치했다. 원본 파일 해시도 그대로였다. 근거: `build/sef-metadata-qa/independent-comparison.json`.

| 원본 | 원본 bytes | 압축본 bytes | 실제 저장 픽셀 |
| --- | ---: | ---: | --- |
| `20261031_153707.jpg` | 3,693,925 | 832,244 | 2000×1500 |
| `20261030_204122.jpg` | 8,466,641 | 5,624,561 | 2000×1500 |
| `20261104_191702.jpg` | 3,406,742 | 2,343,698 | 1126×1407 |
| `20261105_054743.jpg` | 1,647,108 | 396,368 | 2000×1500 |

- 모션·인물 편집 데이터가 목표 용량보다 크면 목표 2MiB를 넘더라도 이를 유지하고 안내했다. EXIF 회전 6인 마지막 사진은 실제 픽셀 2000×1500과 화면 표시 1500×2000을 구분해 검사했다. 저장 픽셀을 이중 회전하지 않았다.
- 단위 테스트 102개 통과(실패·오류·건너뜀 0). 실제 원본/별도 프로세스 재실행 2개와 기존 HDR·메타데이터·분할·GIF/재실행 18개, 총 20개 계측 검증 통과. 처음 작성한 계측 테스트의 Boolean 반환형과 EXIF 회전이 반영된 미리보기 기대값을 바로잡은 뒤 최종 통과했다. 제품 코드 오류와 테스트 기대값 오류를 혼동하지 않는다. 근거: `unit-summary.json`, `original-instrumentation-final.txt`, `original-restart.txt`, `regression.txt`, `regression-restart.txt`.
- 삼성 갤러리의 모션 재생·인물 재편집과 SM-S928N 실기기 동작은 미검증이다. 인물 편집 정보가 있는 출력은 그 제한을 완료/미리보기에서 알리며 원본 보관 이동 후보에서 제외한다. 기존 파일명 충돌, 원본 접근 권한, 날짜, 실제 수정일 검증 흐름은 유지했다. 다른 작업의 콜라주 등 수정은 되돌리지 않았다.
- 최종 서명 APK 설치·실행, 치명적 오류 없음, 기존 인증서와 동일한 v2/v3 서명, 16KiB 정렬, ZIP CRC, 4종 ABI, 디버그 불가, INTERNET 권한 없음, 개인 사진·테스트 자산·키 파일 미포함을 확인했다. 릴리스 DEX에 새 삼성 SEF 구현도 확인했다.
- APK: `build/distributions/SB-Photo-Tool-1.2.1-samsung-metadata-20261105.apk`, `com.sb.phototool` / `1.2.1 (4)`, 7,100,986 bytes.
- SHA-256: `ee100a185826dd840e115da20631a4371be00ea54b878e11c6069853c98a9d6c`.
- GoFile: https://gofile.io/d/DXIN52fD (임시 게스트 저장). 2026-11-05 12:27 KST에 페이지 HTTP 200 및 업로드 해시 일치를 확인했다. 근거: `build/sef-metadata-qa/apk-inspection.json`, `signature.txt`, `signature-v2.txt`, `alignment.txt`, `release-install.txt`, `release-launch.txt`, `release-crash-log.txt`, `gofile-upload.json`, `gofile-page-verification.json`.

### ZIP 원본 분석 — 원인 확인 (2026-11-05 KST)

사용자가 제공한 `1-이슈사진원본.zip`의 4장을 읽기 전용으로 분석했다. ZIP SHA-256은 `f3a1b448efaba5ab2e4435b17eb2b5c1df3711b6a750dc2488b1f36aa6068df7`이며 검사 전후 동일하다. 아래 결과는 앞선 1280픽셀 첨부본으로 확인하지 못했던 원본 구조를 직접 확인한 결과다.

| 원본 | 확인한 구조 | 현 코드의 실패 원인 |
| --- | --- | --- |
| `20261105_054743.jpg` | JPEG 1,627,342 + HDR JPEG 19,422 + 삼성 SEF 344 bytes | HDR 길이 19,422와 후단 전체 19,766을 비교하여 잘못 거절 |
| `20261031_153707.jpg` | JPEG 3,669,107 + HDR JPEG 24,539 + 삼성 SEF 279 bytes | HDR 길이 24,539와 후단 전체 24,818을 비교하여 잘못 거절 |
| `20261030_204122.jpg` | JPEG 3,791,921 + HDR JPEG 30,645 + 삼성 SEF 4,644,075 bytes | 같은 길이 검사에서 우선 거절. 실제 모션사진이어서 그 검사만 고쳐도 다음 XMP 항목 검사에서 다시 거절됨 |
| `20261104_191702.jpg` | JPEG 1,495,296 + 삼성 SEF 1,911,446 bytes, MPF/HDR 없음 | MPF 없는 모든 후단 데이터를 미지원으로 처리. 인물 배경 흐림·추가 이미지·재편집 정보가 포함됨 |

- HDR 3장의 MPF 본체 길이·보조 위치·보조 길이는 실제 JPEG 경계와 정확히 일치한다. 위치가 잘못된 원본이라는 근거가 아니라 앱이 HDR 뒤의 SEF까지 한 보조 이미지로 세는 것이 문제다. `ImageMetadata.kt:239`에서 파일 끝까지 읽고 `HdrJpeg.kt:129`에서 이 전체 길이와 MPF 길이를 비교한다.
- 삼성 SEF는 네 장 모두 `SEFH`/`SEFT`와 유효한 디렉터리를 갖는다. 디렉터리의 상대 위치·필드 크기·필드 이름 범위를 검사했다. HDR/본체 끝과 SEF 시작 사이의 설명되지 않는 간격은 0 bytes다. 날짜(`Image_UTC_Data`), 색상(`Color_Display_P3`), 촬영 모드 등 별도의 메타데이터이므로 버려서 해결하면 원본 정보 보존 요구를 위반한다.
- `20261030_204122.jpg`는 XMP `MotionPhoto=1`, `MotionPhoto_Data`, `MotionPhoto_AutoPlay`가 있으며, 실제 MP4 4,186,189 bytes를 포함한다. MP4의 `ftyp`/`mdat`/`moov`, 영상·음성 트랙, 컨테이너 길이 3.2208초를 확인했다. 모션 표시만 남은 일반 사진으로 판정하지 않는다. 재생·디코딩 검증은 하지 않았다.
- `20261104_191702.jpg`는 `Portrait_Bokeh_Edit_Info`, `DualShot_Extra_Info`, `DualShot_Core_Info`, `DualShot_5`, `PhotoEditor_Re_Edit_Data`, `Original_Path_Hash_Key` 등을 가진다. 실제 픽셀 2252×2814와 기존 IFD0 치수 4000×2252가 이미 다르다. 제조사 재편집 기능까지 보존되는지는 단순 바이트 복사만으로 보장할 수 없다.
- 네 장 모두 EXIF 촬영일·디지털화 날짜·수정 날짜, 명시된 `+09:00`, 소수초, GPS 위도·경도·방향 정보를 읽었다. 앞선 1280픽셀 전달본의 EXIF 누락을 원본 정보 없음으로 처리하지 않는다. 파일명 시각과 EXIF 시각이 1초 다른 사진도 있으므로 파일명에서 날짜를 추정하지 않는다. 휴대폰 MediaStore 값이나 실제 파일시스템 수정일은 ZIP/추출본으로 검증하지 않았다.
- 기존 빌드의 실제 메타데이터 파서로 네 장 모두 사용자 로그와 같은 실패를 재현했다. 일반 HDR 두 장은 메모리 안에서 MPF가 지정한 보조 JPEG 범위만 전달하면 HDR 검사가 통과했다. 원본 파일을 자르거나 다시 저장하지 않았다. 모션사진은 HDR 범위만 전달해도 추가 동영상 항목 때문에 여전히 거절된다.
- 추천 수정: JPEG 본체, MPF 지정 HDR 범위, SEF 블록/디렉터리를 구분하고 원본 블록을 보존한다. 재압축 후 MPF 위치·길이 등 실제로 달라지는 참조만 갱신하고 전체 SEF/보조 파일 해시를 대조한다. 모션사진은 동영상·자동재생 데이터와 XMP 참조까지 별도 지원해야 한다. 인물사진은 추가 이미지·편집 정보를 유지하되 삼성 갤러리 재편집 동작을 실기기에서 확인하기 전에는 동일 기능 보존을 보장하지 않는다. SEF 디렉터리는 상대 위치를 쓰지만 내부 비공개 필드의 외부 참조 유효성은 추가 검증 대상이다.
- 근거: `build/original-zip-audit/analysis.json`, `reproduction.txt`, `motion-container.json`; 개인 정보가 포함된 상세 EXIF와 바이트 동일 추출본은 Git 제외된 `build/original-zip-audit/private/`에만 보관한다. 참고 형식: [ExifTool Samsung SEF 처리](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/Samsung.pm), [Android Ultra HDR 명세](https://developer.android.com/media/platform/hdr-image-format).
- 이번 요청은 분석만 수행했다. 앱 코드 수정, APK 빌드·배포, 원본 사진 변경은 하지 않았다.

### 실기기 재실패 조사 (2026-11-05 08:26 KST 사용자 로그)

- 사용자 SM-S928N / SDK 36 로그는 9장 중 1장 저장, 8장 실패다. 7장은 MPF 보조 이미지 위치 또는 길이 검사, `20261104_191702.jpg` 1장은 미지원 JPEG 후단 데이터 검사에서 중단됐다. 두 검사 실패를 모션사진이나 원본 손상의 증거로 해석하지 않는다.
- 현재 `HdrJpeg.read()`는 첫 JPEG 끝부터 파일 끝까지를 보조 이미지 하나로 간주하고 그 전체 길이를 MPF 길이와 비교한다. 따라서 별도의 후단 블록이 붙는 정상 구조도 거절할 수 있다. 로그는 위치와 길이 중 어느 비교가 실패했는지 수치를 제공하지 않으므로 이 기기의 정확한 구조는 아직 확정하지 못했다.
- 이번 첨부 5개는 사진 4장과 화면 캡처 1장이다. 모두 긴 변 1280픽셀이고 촬영일·GPS가 없다. 사진 4장은 EXIF가 ExifVersion만 있으며, 본체 길이와 보조 JPEG 길이의 합이 파일 전체 크기와 정확히 같다. `4-1000257721.jpg`는 이전 첨부와 SHA-256도 동일하다. 전달본의 구조가 휴대폰 원본과 같다는 근거가 없다.
- 기존 빌드의 실제 메타데이터 파서를 JVM에서 실행한 결과 5개 모두 재압축 지원 검사에 통과했다. 이는 이번 실패 지점을 재현하지 못했다는 뜻이며, 새 압축·MediaStore·실기기 검증 성공으로 보고하지 않는다. 근거: `build/hdr-followup-qa/attachment-check.txt`. 연결된 테스트 기기 없음도 확인했다.
- 정확한 수정·검증에는 서로 다른 실패 유형의 원본 `20261105_054743.jpg`, `20261104_191702.jpg`가 필요하다. 파일 내용을 보존하는 ZIP 전달을 요청한다. 이번 조사에서는 앱 코드 수정, 새 APK 빌드·배포, 사용자 사진 변경을 수행하지 않았다.
- 참고: [ExifTool Samsung 처리 코드](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/Samsung.pm)는 SEF 후단 디렉터리를 별도로 처리한다. 이 구조가 존재할 수 있다는 근거이며, 현재 사용자 원본이 SEF라는 확정 근거는 아니다.

### HDR 보완 (2026-11-05 KST)

- 제공된 두 첨부 JPEG에서 HDR 게인맵과 MPF 인덱스를 확인했다. 모션 동영상이 아니라 기본 JPEG 뒤에 320×240 보조 JPEG가 붙은 구조이며, 각각 2,656 / 2,900 bytes다. 이전 구현은 첫 JPEG 뒤에 데이터가 있으면 MPF 판정 사유까지 모션사진 문구로 덮어썼다.
- `HdrJpeg.kt`에서 두 이미지의 MPF 위치·길이와 XMP 의미를 검사하고, 본체 압축 후 참조를 갱신한다. 보조 JPEG의 전체 바이트는 유지한다. EXIF 복사 정책·파일명·수정일·MediaStore 날짜 검증은 기존 흐름을 유지한다.
- API 37 전용 에뮬레이터에서 첨부 두 장의 70% 압축(1280×960 → 896×672), 보조 JPEG 바이트 일치, Android의 HDR 재인식, HDR 복원 수치·색공간·수정일·원본 바이트 유지와 미리보기/실저장 일치를 확인했다. 기존 메타데이터·분할·GIF 회귀를 포함한 17개 계측 테스트가 통과했다. 근거: `build/hdr-metadata-qa/instrumentation.txt`.
- 단위 테스트 90개, 별도 앱 프로세스 재실행 검증 1개도 통과했다. 첨부 HDR 테스트 2개는 목표 용량을 원본 절반으로 바꿔 다시 통과했다. 실제 파일은 115,410 → 60,859 bytes, 180,173 → 92,339 bytes였고, 메타데이터 추가분 때문에 목표를 초과했다는 안내를 확인했다. ExifTool 대조에서 각 54개 메타데이터 필드에 누락이 없었으며 MPF 본체 길이·보조 위치 2개만 바뀌었다. 보조 이미지 해시는 원본과 일치했다. 근거: `unit-summary.json`, `restart.txt`, `target-instrumentation.txt`, `real-photo-comparison.json`.
- 목표가 원본보다 큰 1MiB일 때 이 작은 첨부본은 최고 허용 품질로 재인코딩되어 크기가 늘어났다. 이는 기존 목표 용량 동작이며 HDR 보조 정보는 유지됐다. 해당 조건의 별도 결과는 `real-photo-comparison-1mb.json`에 남겼다. 목표 이하 크기나 원본보다 작은 크기를 무조건 보장하지 않는다.
- 첨부본은 1280×960이며 촬영일·GPS EXIF가 없는 전달본이다. 휴대폰의 전체 해상도 원본 메타데이터가 동일하게 전달됐다고 가정하지 않았다. 삼성 실기기에서의 전체 원본 동작은 아직 검증하지 않았다. 실제 사진은 로컬 테스트의 캐시에만 복사하며 테스트·배포 APK 자산에 포함하지 않는다.
- 콜라주 동시 편집 중 잠깐 발생한 컴파일 오류는 해당 코드를 수정하지 않고 별도 작업의 변경이 정리된 뒤 재빌드해 통과했다. 압축 코드 외의 수정은 유지했다.
- 형식 근거: [Android Ultra HDR 명세](https://developer.android.com/media/platform/hdr-image-format), [Ultra HDR 편집 안내](https://developer.android.com/media/grow/ultra-hdr/edit).
- APK: `build/distributions/SB-Photo-Tool-1.2.1-hdr-metadata-20261105.apk`, `com.sb.phototool` / `1.2.1 (4)`, 7,100,986 bytes.
- SHA-256: `7030585d3feaf7df2cc4e7522100017c354c1b87633f47cc44cc0624f7d17499`.
- 기존 인증서와 동일한 v2/v3 서명, 16KiB 정렬, ZIP CRC, 4종 ABI, 디버그 불가, INTERNET 권한 없음, 개인 사진·테스트 자산·키 파일 미포함을 확인했다. 최종 서명 APK를 전용 에뮬레이터에 설치·실행했으며 치명적 오류 로그가 없었다.
- GoFile: https://gofile.io/d/xJ38ivXV (임시 게스트 저장). 2026-11-05 05:23 KST에 다운로드 페이지 HTTP 200 및 업로드 SHA-256 일치를 확인했다. 근거: `build/hdr-metadata-qa/apk-inspection.json`, `signature.txt`, `signature-v2.txt`, `alignment.txt`, `release-install.txt`, `release-launch.txt`, `release-crash-log.txt`, `gofile-upload.json`, `gofile-page-verification.json`.

### 이전 배포 (2026-11-04 KST)

- APK: `build/distributions/SB-Photo-Tool-1.2.1-metadata-20261104.apk`
- 패키지/버전: `com.sb.phototool`, `1.2.1 (4)`, minSdk 29 / targetSdk 36, ABI 4종.
- 크기: 7,084,602 bytes.
- SHA-256: `be9f02f7874e109a6cc77cf56aafbece65e71b58de155a5472c0acd39cf4b207`.
- GoFile: https://gofile.io/d/gCPrDjCT (임시 게스트 저장).
- 서명·정렬·패키지·업로드 증거: `build/metadata-qa/apk-inspection.json`, `signature.txt`, `signature-v2.txt`, `alignment.txt`, `gofile-upload.json`, `gofile-page-verification.json`.

## 관련 파일

- `media/ImageMetadata.kt`: 컨테이너 메타데이터와 TIFF 치수 패치.
- `media/HdrJpeg.kt`: Ultra HDR의 MPF/XMP 검사와 보조 이미지 위치 갱신.
- `media/SamsungSef.kt`: 삼성 후단 디렉터리 검사와 원본 바이트 보존.
- `media/CompressionStorage.kt`: 원본 접근, 날짜, 동일 이름 저장, 최종 파일 검증.
- `media/MediaProcessor.kt`, `ui/compress/CompressEditorScreen.kt`, `AndroidManifest.xml`: 압축 연결, 권한, 실패 안내.
- `ImageMetadataTest.kt`, `CompressionMetadataTest.kt`, `HdrJpegTest.kt`, `HdrCompressionTest.kt`, `scripts/generate-metadata-fixtures.py`: 합성 원본과 HDR 검증. 개인 사진은 테스트 자산으로 포함하지 않는다.
