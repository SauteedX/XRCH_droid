import CoreLocation
import CoreBluetooth
import Combine
import Foundation
@preconcurrency import Network
import SwiftUI
import UIKit

private struct LocationPacket: Codable {
    let type: String
    let source: String
    let sequence: UInt64
    let timestamp: TimeInterval
    let latitude: Double
    let longitude: Double
    let altitude: Double
    let horizontalAccuracy: Double
    let trueHeading: Double?
    let headingAccuracy: Double?
    let headingCalibration: Bool?
    let headingSource: String?
}

enum CompanionLocationMode: String, CaseIterable, Identifiable {
    case real = "실제 GPS"
    case virtual = "가상 GPS"
    var id: String { rawValue }
}

@MainActor
final class QuestLocationBridge: NSObject, ObservableObject, CLLocationManagerDelegate, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let port: UInt16 = 47_777
    static let bleServiceUUID = CBUUID(string: "A6C40001-7D2A-4B2E-9E31-8A6F310A1201")
    static let bleLocationUUID = CBUUID(string: "A6C40002-7D2A-4B2E-9E31-8A6F310A1201")

    @Published private(set) var statusText = "대기 중"
    @Published private(set) var connectionStateText = "연결 안 됨"
    @Published private(set) var isRunning = false
    @Published private(set) var latitudeText = "-"
    @Published private(set) var longitudeText = "-"
    @Published private(set) var altitudeText = "-"
    @Published private(set) var accuracyText = "-"
    @Published private(set) var headingText = "-"
    @Published private(set) var headingAccuracyText = "-"
    @Published private(set) var headingCalibrationModeActive = false
    @Published private(set) var locationSourceText = "위치 없음"
    @Published private(set) var locationTimestampText = "-"
    @Published private(set) var locationAgeText = "-"
    @Published private(set) var authorizationText = "확인 중"
    @Published private(set) var networkPathText = "확인 중"
    @Published private(set) var endpointText = "-"
    @Published private(set) var bleDeviceText = "검색 전"
    @Published private(set) var bleRSSIText = "-"
    @Published private(set) var sentCount: UInt64 = 0
    @Published private(set) var lastSentAgoText = "-"
    @Published private(set) var lastPacketSizeText = "-"
    @Published private(set) var lastPacketText = "아직 송신한 패킷이 없습니다."
    @Published private(set) var events: [String] = []
    @Published var locationMode: CompanionLocationMode = .real {
        didSet { applyLocationMode() }
    }
    @Published var virtualLatitude = "37.3017361843555"
    @Published var virtualLongitude = "126.838560758712"

    private let locationManager = CLLocationManager()
    private let pathMonitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "arch.companion.path-monitor")
    private var connection: NWConnection?
    private var latestLocation: CLLocation?
    private var sequence: UInt64 = 0
    private var lastSentAt: Date?
    private var refreshTimer: Timer?
    private var centralManager: CBCentralManager!
    private var questPeripheral: CBPeripheral?
    private var locationCharacteristic: CBCharacteristic?
    private var shouldScanBLE = false
    private var usesBLE = false
    private var latestHeadingDegrees: CLLocationDirection = -1
    private var latestHeadingAccuracy: CLLocationDirection = -1

    var connectionColor: Color {
        switch connectionStateText {
        case "준비됨": return .green
        case "연결 중", "대기 중": return .orange
        case "실패": return .red
        default: return .secondary
        }
    }

    var connectionSymbol: String {
        switch connectionStateText {
        case "준비됨": return "checkmark.icloud.fill"
        case "연결 중", "대기 중": return "arrow.triangle.2.circlepath.icloud.fill"
        case "실패": return "exclamationmark.icloud.fill"
        default: return "icloud.slash.fill"
        }
    }

    var appVersionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "-"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "-"
        return "\(version) (\(build))"
    }
    var deviceText: String { "\(UIDevice.current.model) · iOS \(UIDevice.current.systemVersion)" }

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 1
        locationManager.headingFilter = 1
        locationManager.headingOrientation = .portrait
        centralManager = CBCentralManager(delegate: self, queue: nil,
                                          options: [CBCentralManagerOptionShowPowerAlertKey: true])
        updateAuthorizationText(locationManager.authorizationStatus)
        startNetworkMonitoring()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.timerTick() }
        }
        addEvent("진단 화면 시작")
    }

    deinit { pathMonitor.cancel(); refreshTimer?.invalidate() }

    func start(questHost: String) {
        usesBLE = false
        guard configureConnection(questHost: questHost) else { return }
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()
        startUpdatingHeadingIfAvailable()
        isRunning = true
        addEvent("연속 GPS 전송 시작")
    }

    func stop() {
        headingCalibrationModeActive = false
        locationManager.stopUpdatingLocation()
        locationManager.stopUpdatingHeading()
        connection?.cancel()
        connection = nil
        shouldScanBLE = false
        centralManager.stopScan()
        if let questPeripheral { centralManager.cancelPeripheralConnection(questPeripheral) }
        questPeripheral = nil
        locationCharacteristic = nil
        isRunning = false
        connectionStateText = "연결 안 됨"
        statusText = "전송 중지됨"
        addEvent("GPS 전송 중지")
    }

    func sendCurrentOrTestLocation(questHost: String) {
        if connection == nil, !configureConnection(questHost: questHost) { return }
        let testLocation = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 37.3017361843555, longitude: 126.838560758712),
                                      altitude: 0, horizontalAccuracy: -1, verticalAccuracy: -1, timestamp: Date())
        let location = latestLocation ?? testLocation
        updateLocationDisplay(location, source: latestLocation == nil ? "안산 테스트 좌표" : "CoreLocation")
        send(location)
    }

    func startBLE() {
        usesBLE = true
        shouldScanBLE = true
        applyLocationMode()
        isRunning = true
        startBLEScanIfReady()
        startUpdatingHeadingIfAvailable()
        addEvent("BLE 위치 전송 시작")
    }

    func sendCurrentOrTestLocationBLE() {
        guard let location = selectedLocation() else { return }
        updateLocationDisplay(location, source: packetSourceLabel)
        sendBLE(location)
    }

    func setVirtualLocation(_ coordinate: CLLocationCoordinate2D) {
        virtualLatitude = String(format: "%.7f", locale: Locale(identifier: "en_US_POSIX"), coordinate.latitude)
        virtualLongitude = String(format: "%.7f", locale: Locale(identifier: "en_US_POSIX"), coordinate.longitude)

        guard isRunning, locationMode == .virtual, let location = selectedLocation() else { return }
        updateLocationDisplay(location, source: packetSourceLabel)
        usesBLE ? sendBLE(location) : send(location)
    }

    func calibrateQuestHeading() {
        guard isRunning else {
            statusText = "먼저 Quest GPS 연결을 시작하세요"
            addEvent("방향 보정 실패 · Quest 연결 없음")
            return
        }
        guard latestHeadingDegrees >= 0 else {
            statusText = "iPhone 방향을 아직 읽지 못했습니다"
            addEvent("방향 보정 실패 · heading 없음")
            return
        }
        guard let location = selectedLocation() else {
            statusText = "보정에 사용할 위치가 없습니다"
            addEvent("방향 보정 실패 · 위치 없음")
            return
        }
        sendHeadingPacket(location)
    }

    func startHeadingCalibrationMode() {
        guard isRunning else {
            statusText = "먼저 Quest GPS 연결을 시작하세요"
            return
        }
        guard latestHeadingDegrees >= 0 else {
            statusText = "iPhone 방향을 아직 읽지 못했습니다"
            return
        }

        headingCalibrationModeActive = true
        addEvent("실시간 헤딩 보정 시작")
        sendHeadingUpdate()
    }

    func stopHeadingCalibrationMode() {
        guard headingCalibrationModeActive else { return }
        headingCalibrationModeActive = false
        addEvent("실시간 헤딩 보정 중지")
    }

    private func sendHeadingUpdate() {
        guard headingCalibrationModeActive,
              isRunning,
              latestHeadingDegrees >= 0,
              let location = selectedLocation() else { return }
        sendHeadingPacket(location)
    }

    private func sendHeadingPacket(_ location: CLLocation) {
        if usesBLE {
            sendBLE(location, calibration: true)
        } else {
            send(location, headingCalibration: true)
        }
    }

    func clearEvents() { events.removeAll(); addEvent("로그 초기화") }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        latestLocation = location
        guard locationMode == .real else { return }
        updateLocationDisplay(location, source: packetSourceLabel)
        if isRunning { usesBLE ? sendBLE(location) : send(location) }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        guard heading >= 0 else { return }
        latestHeadingDegrees = heading
        latestHeadingAccuracy = newHeading.headingAccuracy
        headingText = String(format: "%.1f°", heading)
        headingAccuracyText = newHeading.headingAccuracy >= 0
            ? String(format: "±%.1f°", newHeading.headingAccuracy)
            : "알 수 없음"
        sendHeadingUpdate()
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        statusText = "위치 오류: \(error.localizedDescription)"
        addEvent("위치 오류 · \(error.localizedDescription)")
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        updateAuthorizationText(manager.authorizationStatus)
        if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted {
            statusText = "위치 권한이 필요합니다"
            addEvent("위치 권한 거부됨")
        }
    }

    private func configureConnection(questHost: String) -> Bool {
        let host = questHost.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty else {
            statusText = "Quest IP를 입력하세요"
            addEvent("연결 실패 · Quest IP 없음")
            return false
        }
        connection?.cancel()
        let newConnection = NWConnection(host: NWEndpoint.Host(host),
                                         port: NWEndpoint.Port(rawValue: Self.port)!, using: .udp)
        newConnection.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in self?.handleConnectionState(state, host: host) }
        }
        newConnection.start(queue: .global(qos: .userInitiated))
        connection = newConnection
        endpointText = "\(host):\(Self.port) / UDP"
        connectionStateText = "연결 중"
        statusText = "연결 중: \(host)"
        addEvent("UDP endpoint 생성 · \(host):\(Self.port)")
        return true
    }

    private func handleConnectionState(_ state: NWConnection.State, host: String) {
        switch state {
        case .setup: connectionStateText = "설정 중"
        case .waiting(let error):
            connectionStateText = "대기 중"; statusText = "네트워크 대기: \(error.localizedDescription)"
            addEvent("UDP 대기 · \(error.localizedDescription)")
        case .preparing: connectionStateText = "연결 중"
        case .ready:
            connectionStateText = "준비됨"; statusText = "전송 준비됨: \(host)"; addEvent("UDP 준비 완료 · \(host)")
        case .failed(let error):
            connectionStateText = "실패"; statusText = "네트워크 오류: \(error.localizedDescription)"
            addEvent("UDP 실패 · \(error.localizedDescription)")
        case .cancelled: connectionStateText = "연결 안 됨"
        @unknown default: connectionStateText = "알 수 없음"
        }
    }

    private func send(_ location: CLLocation, headingCalibration: Bool = false) {
        guard let connection else { statusText = "UDP 연결이 없습니다"; return }
        sequence += 1
        let packet = LocationPacket(type: "arch.location.v1", source: packetSource,
                                    sequence: sequence,
                                    timestamp: (headingCalibration ? Date() : location.timestamp).timeIntervalSince1970,
                                    latitude: location.coordinate.latitude, longitude: location.coordinate.longitude,
                                    altitude: location.altitude, horizontalAccuracy: location.horizontalAccuracy,
                                    trueHeading: headingCalibration ? latestHeadingDegrees : nil,
                                    headingAccuracy: headingCalibration ? latestHeadingAccuracy : nil,
                                    headingCalibration: headingCalibration ? true : nil,
                                    headingSource: headingCalibration ? "iphone_compass_live" : nil)
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
            lastPacketText = String(decoding: try encoder.encode(packet), as: UTF8.self)
            let wireData = try JSONEncoder().encode(packet)
            lastPacketSizeText = "\(wireData.count) B"
            connection.send(content: wireData, completion: .contentProcessed { [weak self] error in
                Task { @MainActor in self?.handleSendResult(error, sequence: packet.sequence) }
            })
        } catch {
            statusText = "JSON 오류: \(error.localizedDescription)"
            addEvent("JSON 인코딩 실패 · \(error.localizedDescription)")
        }
    }

    private func sendBLE(_ location: CLLocation, calibration: Bool = false) {
        guard let peripheral = questPeripheral, let characteristic = locationCharacteristic else {
            statusText = "Quest BLE 연결을 기다리는 중"
            addEvent("BLE 전송 보류 · Quest 미연결")
            return
        }
        sequence += 1
        let packet = LocationPacket(type: "arch.location.v1", source: packetSource,
                                    sequence: sequence,
                                    timestamp: (calibration ? Date() : location.timestamp).timeIntervalSince1970,
                                    latitude: location.coordinate.latitude, longitude: location.coordinate.longitude,
                                    altitude: location.altitude, horizontalAccuracy: location.horizontalAccuracy,
                                    trueHeading: calibration ? latestHeadingDegrees : nil,
                                    headingAccuracy: calibration ? latestHeadingAccuracy : nil,
                                    headingCalibration: calibration ? true : nil,
                                    headingSource: calibration ? "iphone_compass" : nil)
        do {
            let displayEncoder = JSONEncoder()
            displayEncoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
            lastPacketText = String(decoding: try displayEncoder.encode(packet), as: UTF8.self)
            var wireData = try JSONEncoder().encode(packet)
            wireData.append(0x0A)
            lastPacketSizeText = "\(wireData.count) B"

            let maximum = max(20, peripheral.maximumWriteValueLength(for: .withoutResponse))
            var offset = 0
            while offset < wireData.count {
                let end = min(offset + maximum, wireData.count)
                peripheral.writeValue(wireData.subdata(in: offset..<end), for: characteristic, type: .withoutResponse)
                offset = end
            }
            sentCount += 1
            lastSentAt = Date()
            lastSentAgoText = "방금"
            statusText = calibration
                ? "#\(packet.sequence) 초기 방향 보정 전송 완료"
                : "#\(packet.sequence) BLE 전송 완료"
            addEvent(calibration
                ? "#\(packet.sequence) 초기 방향 보정 · \(String(format: "%.1f°", latestHeadingDegrees))"
                : "#\(packet.sequence) BLE 전송 · \(wireData.count) B")
        } catch {
            statusText = "BLE JSON 오류: \(error.localizedDescription)"
            addEvent("BLE 인코딩 실패 · \(error.localizedDescription)")
        }
    }

    private func startUpdatingHeadingIfAvailable() {
        guard CLLocationManager.headingAvailable() else {
            headingText = "사용 불가"
            headingAccuracyText = "-"
            addEvent("나침반 사용 불가")
            return
        }
        locationManager.startUpdatingHeading()
    }

    private func startBLEScanIfReady() {
        guard shouldScanBLE, centralManager.state == .poweredOn else {
            connectionStateText = centralManager.state == .poweredOff ? "Bluetooth 꺼짐" : "Bluetooth 준비 중"
            return
        }
        connectionStateText = "Quest 검색 중"
        statusText = "ARCH Quest BLE 검색 중"
        bleDeviceText = "주변 Quest 검색 중"
        centralManager.scanForPeripherals(withServices: [Self.bleServiceUUID],
                                          options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        addEvent("BLE 스캔 시작")
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let stateText: String
        switch central.state {
        case .poweredOn: stateText = "켜짐"
        case .poweredOff: stateText = "꺼짐"
        case .unauthorized: stateText = "권한 없음"
        case .unsupported: stateText = "지원 안 함"
        case .resetting: stateText = "재설정 중"
        default: stateText = "확인 중"
        }
        networkPathText = "Bluetooth \(stateText) · \(networkPathText.replacingOccurrences(of: "Bluetooth 켜짐 · ", with: ""))"
        if central.state == .poweredOn { startBLEScanIfReady() }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        central.stopScan()
        questPeripheral = peripheral
        peripheral.delegate = self
        bleDeviceText = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? "ARCH Quest")
        bleRSSIText = "\(RSSI) dBm"
        connectionStateText = "연결 중"
        statusText = "\(bleDeviceText)에 연결 중"
        addEvent("BLE 발견 · \(bleDeviceText) · \(RSSI) dBm")
        central.connect(peripheral, options: [CBConnectPeripheralOptionNotifyOnDisconnectionKey: true])
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectionStateText = "서비스 확인 중"
        statusText = "Quest BLE 서비스 검색 중"
        addEvent("BLE 연결됨 · 서비스 검색")
        peripheral.discoverServices([Self.bleServiceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectionStateText = "실패"
        statusText = "BLE 연결 실패: \(error?.localizedDescription ?? "알 수 없는 오류")"
        addEvent(statusText)
        questPeripheral = nil
        if shouldScanBLE { startBLEScanIfReady() }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) {
        locationCharacteristic = nil
        questPeripheral = nil
        connectionStateText = "연결 끊김"
        statusText = "Quest BLE 연결 끊김"
        addEvent("BLE 연결 끊김" + (error.map { " · \($0.localizedDescription)" } ?? ""))
        if shouldScanBLE { startBLEScanIfReady() }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { statusText = "서비스 검색 실패: \(error.localizedDescription)"; return }
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.bleServiceUUID }) else {
            statusText = "ARCH BLE 서비스를 찾지 못함"; return
        }
        peripheral.discoverCharacteristics([Self.bleLocationUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { statusText = "Characteristic 검색 실패: \(error.localizedDescription)"; return }
        guard let characteristic = service.characteristics?.first(where: { $0.uuid == Self.bleLocationUUID }) else {
            statusText = "위치 Characteristic을 찾지 못함"; return
        }
        locationCharacteristic = characteristic
        connectionStateText = "준비됨"
        endpointText = "BLE · \(Self.bleServiceUUID.uuidString)"
        statusText = "Quest BLE 전송 준비됨"
        addEvent("BLE 위치 채널 준비 완료")
    }

    private func handleSendResult(_ error: NWError?, sequence: UInt64) {
        if let error {
            statusText = "전송 실패: \(error.localizedDescription)"
            addEvent("#\(sequence) 전송 실패 · \(error.localizedDescription)")
        } else {
            sentCount += 1; lastSentAt = Date(); lastSentAgoText = "방금"; statusText = "#\(sequence) 전송 완료"
            addEvent("#\(sequence) UDP 전송 완료")
        }
    }

    private func updateLocationDisplay(_ location: CLLocation, source: String) {
        latitudeText = String(format: "%.7f", location.coordinate.latitude)
        longitudeText = String(format: "%.7f", location.coordinate.longitude)
        altitudeText = String(format: "%.1f m", location.altitude)
        accuracyText = location.horizontalAccuracy >= 0 ? String(format: "%.1f m", location.horizontalAccuracy) : "테스트 값"
        locationSourceText = source
        locationTimestampText = Self.timeFormatter.string(from: location.timestamp)
        refreshRelativeTimes()
    }

    private func updateAuthorizationText(_ status: CLAuthorizationStatus) {
        switch status {
        case .notDetermined: authorizationText = "요청 전"
        case .restricted: authorizationText = "제한됨"
        case .denied: authorizationText = "거부됨"
        case .authorizedAlways: authorizationText = "항상 허용"
        case .authorizedWhenInUse: authorizationText = "사용 중 허용"
        @unknown default: authorizationText = "알 수 없음"
        }
    }

    private func startNetworkMonitoring() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let status: String
            if path.status != .satisfied { status = "오프라인" }
            else if path.usesInterfaceType(.wifi) { status = "Wi-Fi 연결됨" }
            else if path.usesInterfaceType(.cellular) { status = "셀룰러 연결됨" }
            else if path.usesInterfaceType(.wiredEthernet) { status = "유선 연결됨" }
            else { status = "네트워크 연결됨" }
            Task { @MainActor in self?.networkPathText = status }
        }
        pathMonitor.start(queue: monitorQueue)
    }

    private func refreshRelativeTimes() {
        if let location = latestLocation { locationAgeText = Self.relativeAge(from: location.timestamp) }
        else if locationSourceText != "위치 없음" { locationAgeText = "방금" }
        if let lastSentAt { lastSentAgoText = Self.relativeAge(from: lastSentAt) }
    }

    private var packetSource: String { locationMode == .real ? "real" : "virtual" }
    private var packetSourceLabel: String { locationMode == .real ? "CoreLocation" : "가상 GPS" }

    private func applyLocationMode() {
        guard isRunning else { return }
        if locationMode == .real {
            locationManager.requestWhenInUseAuthorization()
            locationManager.startUpdatingLocation()
            addEvent("실제 GPS 모드")
        } else {
            locationManager.stopUpdatingLocation()
            if let location = selectedLocation() {
                updateLocationDisplay(location, source: packetSourceLabel)
                usesBLE ? sendBLE(location) : send(location)
            }
            addEvent("가상 GPS 모드")
        }
    }

    private func selectedLocation() -> CLLocation? {
        if locationMode == .real {
            guard let latestLocation else {
                statusText = "실제 GPS 수신을 기다리는 중"
                return nil
            }
            return latestLocation
        }
        guard let latitude = Double(virtualLatitude), let longitude = Double(virtualLongitude),
              (-90...90).contains(latitude), (-180...180).contains(longitude) else {
            statusText = "가상 GPS 좌표를 확인하세요"
            return nil
        }
        return CLLocation(coordinate: .init(latitude: latitude, longitude: longitude),
                          altitude: 0, horizontalAccuracy: 1, verticalAccuracy: 1, timestamp: Date())
    }

    private func timerTick() {
        refreshRelativeTimes()
        guard isRunning, locationMode == .virtual, let location = selectedLocation() else { return }
        updateLocationDisplay(location, source: packetSourceLabel)
        usesBLE ? sendBLE(location) : send(location)
    }

    private func addEvent(_ message: String) {
        events.insert("[\(Self.timeFormatter.string(from: Date()))] \(message)", at: 0)
        if events.count > 12 { events.removeLast(events.count - 12) }
    }

    private static func relativeAge(from date: Date) -> String {
        let seconds = max(0, Int(Date().timeIntervalSince(date)))
        if seconds < 2 { return "방금" }
        if seconds < 60 { return "\(seconds)초 전" }
        return "\(seconds / 60)분 전"
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter(); formatter.dateFormat = "HH:mm:ss"; return formatter
    }()
}
