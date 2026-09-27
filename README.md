# XRCH Companion

ARCH 위치 기반 AR 앱을 위한 iPhone 동반 앱 프로토타입입니다. iPhone의 위치와 나침반 방향을 Quest 앱으로 보내고, 가상 GPS와 진단 화면으로 연동을 확인할 수 있습니다.

## 기능

- 실제 GPS 또는 지도에서 선택한 가상 GPS 좌표 전송
- 가상 GPS 연속 전송: 실행 중 1초마다 위치 패킷 전송
- Quest와 Bluetooth LE 자동 검색 및 연결
- 위치와 헤딩 데이터를 UDP 또는 BLE로 전달
- 현재 헤딩으로 Quest 방향 보정, 실시간 헤딩 보정 모드
- 개발자 진단: 연결/권한/네트워크 상태, 좌표, 정확도, 최근 패킷, 이벤트 로그

탐색·저장·프로필 화면은 현재 UI 프로토타입입니다. POI와 AI 추천은 앱 안의 목업 데이터로 동작하며 외부 검색/API에 연결되어 있지 않습니다.

## 요구 사항

- Xcode 26.3 이상 권장
- iOS 17 이상
- iPhone 실기기: 실제 위치, 나침반, BLE 연동 테스트에 필요
- Quest 측 Unity 앱: `arch.location.v1` 위치 패킷과 아래 UDP/BLE 연결 규약을 지원해야 합니다.

## 실행

1. `XRCH.xcodeproj`를 Xcode에서 엽니다.
2. `XRCH` scheme을 선택하고 iOS Simulator 또는 iPhone을 대상으로 실행합니다.
3. iPhone 실기기에서 실행할 때는 Signing & Capabilities에서 본인 Apple 개발 팀을 선택합니다.
4. 앱에서 **기기 → Quest 검색 및 연결**을 눌러 BLE 연결을 시작합니다.
5. **GPS 모드**에서 실제 GPS 또는 가상 GPS를 고릅니다. 가상 모드에서는 지도를 이동하고 **이 위치를 가상 GPS로 적용**을 누릅니다.
6. **헤딩 보정** 탭에서 **실시간 보정 시작**을 누르면 iPhone 나침반 방향 변경을 Quest로 보냅니다. 한 번만 보정하려면 기기 화면의 **현재 방향으로 Quest 보정**을 사용합니다.
7. **기기 → 개발자 진단**에서 전송 횟수, 최근 패킷, 센서 값과 이벤트 로그를 확인합니다.

가상 GPS의 기본 좌표는 한양대학교 ERICA 인근입니다. 가상 GPS는 연속 전송을 시작한 뒤에도 적용한 좌표를 1초마다 다시 보냅니다. 지도 좌표를 바꾸면 실행 중인 연결에 새 좌표를 즉시 한 번 보내고 이후 1초 주기를 이어갑니다.

시뮬레이터는 화면과 가상 GPS 전송 흐름을 확인하는 용도로 사용할 수 있습니다. 실제 나침반과 BLE 기기 연결 동작은 iPhone과 Quest 실기기에서 확인하세요.

## 연결 방식

### Bluetooth LE

앱은 아래 서비스를 광고하는 Quest를 검색하고, 위치 characteristic에 JSON 바이트를 씁니다.

| 항목 | UUID |
|---|---|
| Service | `A6C40001-7D2A-4B2E-9E31-8A6F310A1201` |
| Location characteristic | `A6C40002-7D2A-4B2E-9E31-8A6F310A1201` |

BLE 패킷은 줄바꿈(`\n`)으로 끝납니다. characteristic 최대 쓰기 길이에 따라 여러 조각으로 나뉠 수 있으므로 Quest 수신부는 조각을 모아 줄바꿈 단위로 JSON을 복원해야 합니다.

### UDP

UDP 포트는 `47777`입니다. Quest 수신 IP를 입력해 전송을 시작하며, iPhone과 Quest가 서로 도달 가능한 네트워크에 있어야 합니다. iOS 앱에서 로컬 네트워크 권한 요청을 허용하세요.

```text
iPhone ── UDP :47777 ──▶ Quest Unity receiver
       └─ Bluetooth LE ─▶ Quest Unity receiver
```

현재 기본 기기 화면은 BLE 연결 흐름을 제공합니다. UDP sender는 `QuestLocationBridge`에 구현되어 있으며, 사용 시 수신 호스트 주소를 전달해야 합니다.

## 위치 패킷

UDP는 JSON 객체 하나를 datagram으로 보내며, BLE는 같은 JSON 뒤에 줄바꿈을 붙입니다. 일반 위치 패킷 예시:

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

헤딩 보정 패킷에는 다음 필드가 추가됩니다.

```json
{
  "trueHeading": 135.0,
  "headingAccuracy": 4.0,
  "headingCalibration": true,
  "headingSource": "iphone_compass_live"
}
```

`headingSource`는 UDP 실시간 보정에서 `iphone_compass_live`, BLE 보정에서 `iphone_compass`입니다. 일반 위치 패킷에는 헤딩 보정 필드가 포함되지 않을 수 있습니다. `timestamp`는 Unix 초 단위이고 `sequence`는 전송마다 증가합니다.

## 권한과 개인정보

앱은 iPhone 위치를 앱 사용 중에 읽고, Bluetooth로 Quest를 검색·연결하며, UDP 사용 시 로컬 네트워크에 접근합니다. 현재 저장소에는 위치/헤딩 데이터를 중계하는 외부 서버가 없습니다.

## 프로젝트 구성

- `XRCH/MyApp.swift`: 앱 진입점
- `XRCH/CompanionAppViews.swift`: 탐색, 지도, 기기, 헤딩 보정 화면
- `XRCH/ContentView.swift`: 개발자 진단 패널
- `XRCH/QuestLocationBridge.swift`: Core Location, Core Bluetooth, UDP, 패킷 직렬화
