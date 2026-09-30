# XRCH Companion (Android & iOS)

ARCH 위치 기반 AR 앱을 위한 스마트폰 동반(Companion) 앱입니다. 스마트폰의 GPS 위치와 나침반(Heading) 방향을 Meta Quest 앱으로 실시간 전송하고, 가상 GPS 및 실시간 진단 대시보드로 연동 상태를 모니터링할 수 있습니다.

본 저장소(`XRCH_droid`)는 **Android (Kotlin / Jetpack Compose)** 및 **iOS (Swift / SwiftUI)** 구현체를 모두 지원합니다.

---

## 주요 기능

- **실제 GPS 및 가상 GPS 전송**:
  - 기기 실제 GPS 센서 좌표 전송
  - 지도/미니 피커에서 선택한 가상 GPS 좌표 전송 (기본값: 한양대학교 ERICA 인근)
- **가상 GPS 1초 주기 연속 전송**:
  - 가상 모드 활성화 시 1Hz 주기로 패킷 연속 브로드캐스팅
- **Meta Quest Bluetooth LE 자동 탐색 및 연결**:
  - ARCH Quest BLE 서비스 자동 스캔 및 연결
  - MTU 협상(512 B) 및 패킷 청킹(Chunking, `\n` 개행 구분자) 지원
- **UDP 소켓 통신 지원**:
  - 포트 `47777`을 통한 저지연 로컬 네트워크 데이터그램 송신
- **헤딩 보정 (Heading Calibration)**:
  - 1회 초기 방위 보정 ("현재 방향으로 Quest 보정")
  - 실시간 연속 헤딩 동기화 모드 (스마트폰 회전 각도를 실시간 스트리밍)
- **개발자 진단 (Diagnostics)**:
  - 무선/BLE 연결 상태, 패킷 전송 수, 최근 패킷 크기, 경과 시간
  - 원시 위도/경도/고도/정확도 및 방위각
  - 최근 송신 JSON 패킷 뷰어
  - 최근 12개 실시간 이벤트 로그

---

## 플랫폼별 실행 방법

### 1. Android (Kotlin / Jetpack Compose)

#### 요구 사항
- Android Studio Ladybug / Meerkat 이상 권장
- JDK 21
- Android SDK 35 (minSdk 26 - Android 8.0 이상)

#### 실행 방법
1. **Android Studio에서 열기**:
   - `XRCH_droid` 폴더를 프로젝트로 엽니다. Gradle Sync가 자동으로 수행됩니다.
2. **명령줄에서 빌드**:
   ```powershell
   # Debug APK 빌드
   .\gradlew.bat assembleDebug
   ```
   - 빌드 결과물: `app/build/outputs/apk/debug/app-debug.apk`
3. **단말에 설치 및 실행**:
   ```powershell
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. 앱 실행 시 위치 권한 및 Bluetooth 권한을 허용합니다.

---

### 2. iOS (Swift / SwiftUI)

#### 요구 사항
- Xcode 26.3 이상 권장, iOS 17 이상
- iPhone 실기기 (실제 나침반 및 BLE 연동 시 필요)

#### 실행 방법
1. `XRCH.xcodeproj`를 Xcode에서 엽니다.
2. `XRCH` scheme을 선택하고 대상 기기를 지정하여 실행합니다.

---

## 연결 방식 및 프로토콜 규약

```text
스마트폰 (Android / iOS) ── UDP :47777 ──▶ Quest Unity Receiver
                         └─ Bluetooth LE ─▶ Quest Unity Receiver
```

### Bluetooth LE
- **Service UUID**: `A6C40001-7D2A-4B2E-9E31-8A6F310A1201`
- **Location Characteristic UUID**: `A6C40002-7D2A-4B2E-9E31-8A6F310A1201`
- BLE 패킷 끝에는 줄바꿈(`\n`, `0x0A`)이 포함되며, MTU 단위로 분할 청킹 전송됩니다.

### UDP
- **포트**: `47777`
- JSON 단일 데이터그램 패킷 송신

### 패킷 포맷 (`arch.location.v1`)

```json
{
  "type": "arch.location.v1",
  "source": "virtual",
  "sequence": 12,
  "timestamp": 1790000000.0,
  "latitude": 37.3017362,
  "longitude": 126.8385608,
  "altitude": 0,
  "horizontalAccuracy": 1
}
```

헤딩 보정 패킷 추가 필드:
```json
{
  "trueHeading": 135.0,
  "headingAccuracy": 4.0,
  "headingCalibration": true,
  "headingSource": "android_compass_live"
}
```

---

## 프로젝트 구조

```text
XRCH_droid/
├── app/                                       # [Android] Kotlin / Jetpack Compose 앱
│   ├── src/main/AndroidManifest.xml           # 권한 및 액티비티 선언
│   └── src/main/java/com/xrch/companion/
│       ├── MainActivity.kt                    # 앱 엔트리포인트 및 런타임 권한 제어
│       ├── data/Models.kt                     # 패킷 모델, 상태 및 POI 데이터
│       ├── bridge/QuestLocationBridge.kt      # BLE/UDP/GPS/나침반 센서 통신 엔진
│       └── ui/
│           ├── theme/                         # Material 3 테마 및 색상
│           ├── screens/
│           │   ├── MainScreen.kt              # 6개 탭 네비게이션
│           │   ├── DiscoverScreen.kt          # 장소 탐색 및 AI 추천
│           │   ├── MapScreen.kt               # 인터랙티브 주변 지도/레이더 뷰
│           │   ├── SavedPlacesScreen.kt       # 저장한 장소
│           │   ├── ProfileScreen.kt           # 프로필 및 검색 반경 설정
│           │   ├── HeadingCalibrationScreen.kt# 실시간 회전 나침반 및 보정
│           │   ├── DeviceHubScreen.kt         # Quest 연결, 가상 GPS 피커
│           │   └── DiagnosticsScreen.kt       # 개발자 진단 대시보드
│           └── viewmodel/CompanionViewModel.kt# POI 및 앱 상태 관리
├── XRCH/                                      # [iOS] Swift / SwiftUI 앱
│   ├── MyApp.swift
│   ├── QuestLocationBridge.swift
│   ├── CompanionAppViews.swift
│   └── ContentView.swift
├── XRCH.xcodeproj/                            # Xcode 프로젝트 설정
├── build.gradle.kts                           # 루트 Gradle 빌드 스크립트
├── settings.gradle.kts                        # Gradle 모듈 설정
└── README.md
```
