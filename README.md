# XRCH Companion (Android & iOS)

ARCH 위치 기반 AR 앱을 위한 스마트폰 동반(Companion) 앱입니다. 스마트폰의 GPS 위치와 나침반(Heading) 방향을 Meta Quest 앱으로 실시간 전송하고, 가상 GPS 및 실시간 진단 대시보드로 연동 상태를 모니터링할 수 있습니다. 또한 Supabase Auth 기반의 AI 추천 기능을 통해 추천 장소를 Meta Quest로 전송하여 증강현실 POI(보라색 Billboard)로 시각화합니다.

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
- **AI 추천 장소를 Quest에 전송 (`arch.ai_recommendations.v1`)**:
  - AI 추천 결과를 Quest Unity Receiver로 전송하여 보라색 3D POI Billboard로 표시
- **헤딩 보정 (Heading Calibration)**:
  - 1회 초기 방위 보정 ("현재 방향으로 Quest 보정")
  - 실시간 연속 헤딩 동기화 모드 (스마트폰 회전 각도를 실시간 스트리밍)
- **Supabase 게스트/로그인 인증 및 AI 추천 대화**:
  - 익명 세션(Guest) 자동 발급 및 저장, 일반 이메일 로그인/회원가입 지원
  - 실제 GPS 좌표와 함께 Supabase `chat` 함수 호출 및 GPU 추천 결과 획득
  - 일일 GPU 쿼터/사용량 실시간 확인
- **개발자 진단 (Diagnostics)**:
  - 무선/BLE 연결 상태, 패킷 전송 수, 최근 패킷 크기, 경과 시간
  - 원시 위도/경도/고도/정확도 및 방위각
  - 최근 송신 JSON 패킷 뷰어
  - 최근 실시간 이벤트 로그

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
- Xcode 16 이상 권장, iOS 17 이상
- iPhone 실기기 (실제 나침반 및 BLE 연동 시 필요)

#### 실행 방법
1. `XRCH.xcodeproj`를 Xcode에서 엽니다.
2. `XRCH` scheme을 선택하고 iOS Simulator 또는 iPhone을 대상으로 실행합니다.
3. iPhone 실기기에서 실행할 때는 Signing & Capabilities에서 본인 Apple 개발 팀을 선택합니다.
4. **기타 → 계정**에서 로그인하거나 회원가입합니다. **AI 추천** 탭에서 질문을 입력하면 현재 GPS와 로그인 토큰으로 Supabase `chat` 함수를 호출합니다. 답변에 포함된 장소 카드를 누르면 상세 정보가 열리고 탐색·지도 탭에도 같은 결과가 표시됩니다.
5. 앱에서 **기기 → Quest 검색 및 연결**을 눌러 BLE 연결을 시작합니다.
6. **GPS 모드**에서 실제 GPS 또는 가상 GPS를 고릅니다. 가상 모드에서는 지도를 이동하고 **이 위치를 가상 GPS로 적용**을 누릅니다.
7. **헤딩 보정** 탭에서 **실시간 보정 시작**을 누르면 iPhone 나침반 방향 변경을 Quest로 보냅니다. 한 번만 보정하려면 기기 화면의 **현재 방향으로 Quest 보정**을 사용합니다.
8. **기기 → 개발자 진단**에서 전송 횟수, 최근 패킷, 센서 값과 이벤트 로그를 확인합니다.

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

### 1. 위치 및 헤딩 패킷 (`arch.location.v1`)

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

### 2. AI 추천 위치 패킷 (`arch.ai_recommendations.v1`)

기기 연결 화면에서 **AI 추천 위치를 Quest에 표시**를 누르면 현재 AI 추천 결과를 같은 UDP/BLE 채널로 전송합니다. Unity는 기존 디버그 추천 표시를 끄고, 아래 패킷의 장소를 보라색 추천 POI로 표시합니다.

```json
{
  "type": "arch.ai_recommendations.v1",
  "source": "android",
  "sequence": 42,
  "timestamp": 1790000000.0,
  "clearDebugRecommendations": true,
  "recommendations": [
    {
      "poiId": "place-123",
      "name": "Example Cafe",
      "category": "카페",
      "address": "주소",
      "description": "추천 장소",
      "latitude": 37.3017,
      "longitude": 126.8386,
      "distanceMeters": 420,
      "score": 0.96,
      "reason": "조용히 작업하기 좋은 장소"
    }
  ]
}
```

---

## Supabase 게스트 인증 및 보안

- 앱 시작 시 Supabase Auth의 `/auth/v1/signup`에 빈 JSON을 보내 익명 세션을 생성합니다. 기존 세션이 있으면 안전한 스토리지(Android EncryptedSharedPreferences / iOS Keychain)에서 복원하고, 만료 전 `/auth/v1/token?grant_type=refresh_token`으로 갱신합니다.
- 동시 인증 요청은 한 작업을 공유하며, 네트워크 장애 시 기존 세션을 폐기하지 않습니다.
- 공개된 legacy anon 키는 클라이언트 설정이며 서비스 권한을 대신하지 않습니다. service-role 키나 GPU 비밀값은 클라이언트에 포함되지 않습니다.
- 익명 사용자는 서버 기준 일일 GPU 예산(한국 시간 자정 초기화)을 사용합니다. 일반 계정 로그아웃 시 기존 게스트 세션을 복원합니다.

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
│       ├── auth/                              # Supabase Auth 및 토큰 관리
│       └── ui/
│           ├── theme/                         # Material 3 테마 및 색상
│           ├── screens/
│           │   ├── MainScreen.kt              # 탭 네비게이션
│           │   ├── DiscoverScreen.kt          # 장소 탐색 및 AI 추천 대화
│           │   ├── MapScreen.kt               # 인터랙티브 주변 지도/레이더 뷰
│           │   ├── SavedPlacesScreen.kt       # 저장한 장소
│           │   ├── ProfileScreen.kt           # 프로필, 계정 및 일일 사용량
│           │   ├── HeadingCalibrationScreen.kt# 실시간 회전 나침반 및 보정
│           │   ├── DeviceHubScreen.kt         # Quest 연결, 가상 GPS 피커, AI 패킷 전송
│           │   └── DiagnosticsScreen.kt       # 개발자 진단 대시보드
│           └── viewmodel/CompanionViewModel.kt# POI, 인증, 추천 및 앱 상태 관리
├── XRCH/                                      # [iOS] Swift / SwiftUI 앱
│   ├── MyApp.swift                            # 진입점
│   ├── QuestLocationBridge.swift              # BLE/UDP/위치 통신
│   ├── CompanionAppViews.swift                # 탐색, 지도, 기기, 보정 뷰
│   ├── CompanionAuthSession.swift             # Supabase 게스트/로그인 인증 세션
│   ├── CompanionUsage.swift                   # GPU 사용량/쿼터 모델
│   ├── SupabaseConfiguration.swift            # Supabase URL 및 공개 Anon 키
│   └── ContentView.swift                      # 개발자 진단 패널
├── Tests/                                     # iOS 테스트 하네스
├── Config/                                    # iOS 빌드 및 시크릿 설정
├── XRCH.xcodeproj/                            # Xcode 프로젝트 설정
├── build.gradle.kts                           # 루트 Gradle 빌드 스크립트
├── settings.gradle.kts                        # Gradle 모듈 설정
└── README.md
```
