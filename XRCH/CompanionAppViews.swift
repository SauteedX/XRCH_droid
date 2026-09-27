import Combine
import MapKit
import SwiftUI

struct CompanionPOI: Identifiable, Hashable {
    let id: String
    let name: String
    let category: String
    let address: String
    let summary: String
    let latitude: Double
    let longitude: Double
    let distanceMeters: Int
    let rating: Double
}

@MainActor
final class CompanionAppModel: ObservableObject {
    @Published var query = ""
    @Published var selectedCategory = "전체"
    @Published var selectedPOI: CompanionPOI?
    @Published var favoriteIDs: Set<String> = []
    @Published var recentSearches = ["조용한 카페", "점심 맛집", "약국"]
    @Published var aiPrompt = ""
    @Published var aiAnswer = "원하는 장소의 분위기나 목적을 입력하면 주변 후보를 정리해 드려요."

    let categories = ["전체", "카페", "음식점", "쇼핑", "생활", "의료"]
    let pois: [CompanionPOI] = [
        .init(id: "cafe-1", name: "모노 커피", category: "카페", address: "안산시 상록구 광덕1로", summary: "조용한 좌석과 넓은 창이 있는 로스터리", latitude: 37.30191, longitude: 126.83812, distanceMeters: 86, rating: 4.7),
        .init(id: "food-1", name: "담소 키친", category: "음식점", address: "안산시 상록구 한양대학로", summary: "가볍게 먹기 좋은 한식과 계절 메뉴", latitude: 37.30142, longitude: 126.83901, distanceMeters: 142, rating: 4.5),
        .init(id: "shop-1", name: "아카이브 문구", category: "쇼핑", address: "안산시 상록구 성안길", summary: "디자인 문구와 작은 로컬 굿즈 숍", latitude: 37.30218, longitude: 126.83744, distanceMeters: 205, rating: 4.6),
        .init(id: "life-1", name: "24시 편의점", category: "생활", address: "안산시 상록구 석호로", summary: "간단한 식품과 생활용품", latitude: 37.30098, longitude: 126.83874, distanceMeters: 248, rating: 4.2),
        .init(id: "medical-1", name: "중앙 약국", category: "의료", address: "안산시 상록구 광덕대로", summary: "처방 조제와 일반 의약품 상담", latitude: 37.30242, longitude: 126.83922, distanceMeters: 331, rating: 4.4)
    ]

    var filteredPOIs: [CompanionPOI] {
        pois.filter { poi in
            let categoryMatches = selectedCategory == "전체" || poi.category == selectedCategory
            let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
            let queryMatches = normalized.isEmpty || [poi.name, poi.category, poi.address, poi.summary]
                .contains { $0.localizedCaseInsensitiveContains(normalized) }
            return categoryMatches && queryMatches
        }
    }

    var favorites: [CompanionPOI] { pois.filter { favoriteIDs.contains($0.id) } }

    func submitSearch() {
        let value = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        recentSearches.removeAll { $0 == value }
        recentSearches.insert(value, at: 0)
        recentSearches = Array(recentSearches.prefix(6))
    }

    func toggleFavorite(_ poi: CompanionPOI) {
        if favoriteIDs.contains(poi.id) { favoriteIDs.remove(poi.id) }
        else { favoriteIDs.insert(poi.id) }
    }

    func askAI() {
        let prompt = aiPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty else { return }
        let candidates = filteredPOIs.prefix(3).map(\.name).joined(separator: ", ")
        aiAnswer = candidates.isEmpty
            ? "조건에 맞는 후보가 아직 없습니다. 검색 범위를 넓혀보세요."
            : "현재 위치와 조건을 기준으로 \(candidates)을 먼저 살펴보는 것을 추천해요."
        aiPrompt = ""
    }
}

struct ContentView: View {
    @StateObject private var bridge = QuestLocationBridge()
    @StateObject private var model = CompanionAppModel()

    var body: some View {
        TabView {
            NavigationStack { DiscoverView() }
                .tabItem { Label("탐색", systemImage: "sparkle.magnifyingglass") }
            NavigationStack { CompanionMapView() }
                .tabItem { Label("지도", systemImage: "map.fill") }
            NavigationStack { SavedPlacesView() }
                .tabItem { Label("저장", systemImage: "heart.fill") }
            NavigationStack { ProfileView() }
                .tabItem { Label("프로필", systemImage: "person.crop.circle") }
            NavigationStack { HeadingCalibrationView(bridge: bridge) }
                .tabItem { Label("헤딩 보정", systemImage: "safari") }
            NavigationStack { DeviceHubView(bridge: bridge) }
                .tabItem { Label("기기", systemImage: "vision.pro") }
        }
        .environmentObject(model)
        .tint(.indigo)
    }
}

private struct HeadingCalibrationView: View {
    @ObservedObject var bridge: QuestLocationBridge

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Image(systemName: bridge.headingCalibrationModeActive ? "safari.fill" : "safari")
                    .font(.system(size: 64, weight: .light))
                    .foregroundStyle(bridge.headingCalibrationModeActive ? .green : .indigo)
                    .padding(.top, 44)

                VStack(spacing: 8) {
                    Text(bridge.headingText)
                        .font(.system(size: 42, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                    Text("정확도  \(bridge.headingAccuracyText)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Text(bridge.headingCalibrationModeActive
                     ? "휴대폰을 천천히 좌우로 돌리면 POI 방향이 Quest에서 실시간으로 따라갑니다."
                     : "Quest GPS 연결을 시작한 뒤 보정 모드를 켜고, 휴대폰을 천천히 좌우로 돌려주세요.")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 28)

                Button {
                    bridge.headingCalibrationModeActive
                        ? bridge.stopHeadingCalibrationMode()
                        : bridge.startHeadingCalibrationMode()
                } label: {
                    Label(
                        bridge.headingCalibrationModeActive ? "실시간 보정 중지" : "실시간 보정 시작",
                        systemImage: bridge.headingCalibrationModeActive ? "stop.fill" : "location.north.line.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!bridge.isRunning && !bridge.headingCalibrationModeActive)
                .padding(.horizontal, 24)

                Label(bridge.isRunning ? "Quest 연결됨" : "Quest 연결 필요",
                      systemImage: bridge.isRunning ? "checkmark.circle.fill" : "exclamationmark.circle")
                    .font(.caption)
                    .foregroundStyle(bridge.isRunning ? .green : .secondary)

                Spacer()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("헤딩 보정")
            .navigationBarTitleDisplayMode(.inline)
        }
        .tint(.indigo)
    }
}

private struct DiscoverView: View {
    @EnvironmentObject private var model: CompanionAppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("어디로 가볼까요?").font(.largeTitle.bold())
                    Text("iPhone에서 찾고, 글라스에서 바로 확인하세요.").foregroundStyle(.secondary)
                }

                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("장소, 카테고리, 분위기 검색", text: $model.query)
                        .submitLabel(.search).onSubmit { model.submitSearch() }
                    if !model.query.isEmpty {
                        Button { model.query = "" } label: { Image(systemName: "xmark.circle.fill") }
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(14).background(.background, in: RoundedRectangle(cornerRadius: 16))
                .shadow(color: .black.opacity(0.06), radius: 12, y: 4)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(model.categories, id: \.self) { category in
                            Button(category) { model.selectedCategory = category }
                                .buttonStyle(CategoryButtonStyle(selected: model.selectedCategory == category))
                        }
                    }
                }

                SectionTitle("주변 추천", trailing: "\(model.filteredPOIs.count)곳")
                LazyVStack(spacing: 12) {
                    ForEach(model.filteredPOIs) { poi in
                        POIRow(poi: poi)
                    }
                }

                VStack(alignment: .leading, spacing: 12) {
                    Label("AI에게 조건으로 찾기", systemImage: "sparkles").font(.headline)
                    Text(model.aiAnswer).font(.subheadline).foregroundStyle(.secondary)
                    HStack {
                        TextField("예: 조용하고 콘센트 있는 카페", text: $model.aiPrompt)
                            .textFieldStyle(.roundedBorder).submitLabel(.send).onSubmit { model.askAI() }
                        Button { model.askAI() } label: { Image(systemName: "arrow.up.circle.fill").font(.title2) }
                    }
                }
                .padding(16).background(.indigo.opacity(0.08), in: RoundedRectangle(cornerRadius: 18))
            }
            .padding(16)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("ARCH")
    }
}

private struct POIRow: View {
    @EnvironmentObject private var model: CompanionAppModel
    let poi: CompanionPOI

    var body: some View {
        Button { model.selectedPOI = poi } label: {
            HStack(spacing: 14) {
                RoundedRectangle(cornerRadius: 14).fill(.indigo.opacity(0.12)).frame(width: 58, height: 58)
                    .overlay(Image(systemName: icon).font(.title2).foregroundStyle(.indigo))
                VStack(alignment: .leading, spacing: 4) {
                    HStack { Text(poi.name).font(.headline); Text("★ \(poi.rating, specifier: "%.1f")").font(.caption).foregroundStyle(.orange) }
                    Text(poi.summary).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    Text("\(poi.distanceMeters)m · \(poi.category)").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button { model.toggleFavorite(poi) } label: {
                    Image(systemName: model.favoriteIDs.contains(poi.id) ? "heart.fill" : "heart")
                }
                .buttonStyle(.plain).foregroundStyle(.pink)
            }
            .padding(14).background(.background, in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .sheet(item: $model.selectedPOI) { POIDetailView(poi: $0) }
    }

    private var icon: String {
        switch poi.category { case "카페": "cup.and.saucer.fill"; case "음식점": "fork.knife"; case "의료": "cross.case.fill"; case "쇼핑": "bag.fill"; default: "mappin.and.ellipse" }
    }
}

private struct POIDetailView: View {
    @EnvironmentObject private var model: CompanionAppModel
    @Environment(\.dismiss) private var dismiss
    let poi: CompanionPOI

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 18) {
                RoundedRectangle(cornerRadius: 24).fill(.indigo.gradient).frame(height: 180)
                    .overlay(Image(systemName: "building.2.crop.circle.fill").font(.system(size: 64)).foregroundStyle(.white))
                Text(poi.name).font(.largeTitle.bold())
                Text(poi.summary).foregroundStyle(.secondary)
                Label(poi.address, systemImage: "mappin")
                Label("\(poi.distanceMeters)m 거리", systemImage: "figure.walk")
                Spacer()
                Button {
                    model.selectedPOI = poi
                    dismiss()
                } label: {
                    Label("글라스에서 안내 시작", systemImage: "vision.pro").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).controlSize(.large)
            }
            .padding().navigationTitle("장소 정보").navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct CompanionMapView: View {
    @EnvironmentObject private var model: CompanionAppModel
    @State private var position: MapCameraPosition = .region(MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 37.3017361843555, longitude: 126.838560758712),
        span: MKCoordinateSpan(latitudeDelta: 0.008, longitudeDelta: 0.008)))

    var body: some View {
        Map(position: $position) {
            UserAnnotation()
            ForEach(model.filteredPOIs) { poi in
                Annotation(poi.name, coordinate: .init(latitude: poi.latitude, longitude: poi.longitude)) {
                    Button { model.selectedPOI = poi } label: {
                        Image(systemName: "mappin.circle.fill").font(.title).foregroundStyle(.indigo)
                            .background(.white, in: Circle())
                    }
                }
            }
        }
        .mapControls { MapCompass(); MapUserLocationButton(); MapScaleView() }
        .safeAreaInset(edge: .bottom) {
            if let poi = model.selectedPOI {
                HStack { VStack(alignment: .leading) { Text(poi.name).font(.headline); Text("\(poi.distanceMeters)m · \(poi.category)").font(.caption).foregroundStyle(.secondary) }; Spacer(); Image(systemName: "vision.pro") }
                    .padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18)).padding()
            }
        }
        .navigationTitle("주변 지도").navigationBarTitleDisplayMode(.inline)
    }
}

private struct SavedPlacesView: View {
    @EnvironmentObject private var model: CompanionAppModel
    var body: some View {
        Group {
            if model.favorites.isEmpty {
                ContentUnavailableView("저장한 장소가 없습니다", systemImage: "heart", description: Text("탐색 탭에서 마음에 드는 장소를 저장하세요."))
            } else {
                List(model.favorites) { POIRow(poi: $0) }
            }
        }
        .navigationTitle("저장한 장소")
    }
}

private struct ProfileView: View {
    @AppStorage("companionDisplayName") private var displayName = "ARCH 사용자"
    @AppStorage("companionSearchRadius") private var searchRadius = 500.0
    @AppStorage("companionLanguage") private var language = "한국어"

    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) {
                    Image(systemName: "person.crop.circle.fill").font(.system(size: 54)).foregroundStyle(.indigo)
                    VStack(alignment: .leading) { Text(displayName).font(.headline); Text("Companion 계정").foregroundStyle(.secondary) }
                }
            }
            Section("프로필") { TextField("표시 이름", text: $displayName); LabeledContent("로그인 상태", value: "프로토타입") }
            Section("검색 설정") {
                Picker("언어", selection: $language) { Text("한국어").tag("한국어"); Text("English").tag("English") }
                VStack(alignment: .leading) { Text("기본 반경 \(Int(searchRadius))m"); Slider(value: $searchRadius, in: 100...1000, step: 100) }
            }
            Section("권한") { Label("위치: 앱 사용 중", systemImage: "location.fill"); Label("Bluetooth: 기기 연결", systemImage: "wave.3.right") }
        }
        .navigationTitle("프로필 및 설정")
    }
}

private struct DeviceHubView: View {
    @ObservedObject var bridge: QuestLocationBridge
    @EnvironmentObject private var model: CompanionAppModel

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    Image(systemName: bridge.connectionSymbol).font(.title).foregroundStyle(bridge.connectionColor)
                    VStack(alignment: .leading) { Text(bridge.connectionStateText).font(.headline); Text(bridge.statusText).font(.caption).foregroundStyle(.secondary) }
                }
                Button(bridge.isRunning ? "연결 및 전송 중지" : "Quest 검색 및 연결") {
                    bridge.isRunning ? bridge.stop() : bridge.startBLE()
                }
            } header: { Text("Quest BLE") }

            Section("GPS 모드") {
                Picker("위치 소스", selection: $bridge.locationMode) {
                    ForEach(CompanionLocationMode.allCases) { mode in Text(mode.rawValue).tag(mode) }
                }
                .pickerStyle(.segmented)
                if bridge.locationMode == .virtual {
                    VirtualGPSLocationPicker(bridge: bridge)
                }
                LabeledContent("전송 소스", value: bridge.locationSourceText)
            }

            Section("현재 안내") {
                if let poi = model.selectedPOI {
                    LabeledContent("목적지", value: poi.name)
                    LabeledContent("거리", value: "\(poi.distanceMeters)m")
                } else {
                    Text("탐색 또는 지도에서 안내할 장소를 선택하세요.").foregroundStyle(.secondary)
                }
                LabeledContent("GPS 전송", value: "\(bridge.sentCount)회")
            }

            Section("Quest 초기 방향 보정") {
                Text("Quest 정면과 iPhone 상단을 같은 방향으로 맞춘 뒤 1회 전송하세요.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                LabeledContent("현재 방향", value: bridge.headingText)
                LabeledContent("방향 정확도", value: bridge.headingAccuracyText)
                Button {
                    bridge.calibrateQuestHeading()
                } label: {
                    Label("현재 방향으로 Quest 보정", systemImage: "location.north.line.fill")
                }
                .disabled(!bridge.isRunning)
            }

            Section("개발") {
                NavigationLink {
                    DeviceDiagnosticsView(bridge: bridge)
                } label: {
                    Label("개발자 진단", systemImage: "wrench.and.screwdriver")
                }
            }
        }
        .navigationTitle("연결 기기")
    }
}

private struct VirtualGPSLocationPicker: View {
    @ObservedObject var bridge: QuestLocationBridge
    @State private var mapPosition: MapCameraPosition = .region(MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 37.3017361843555, longitude: 126.838560758712),
        span: MKCoordinateSpan(latitudeDelta: 0.008, longitudeDelta: 0.008)))
    @State private var mapCenter = CLLocationCoordinate2D(latitude: 37.3017361843555, longitude: 126.838560758712)

    private var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(
            latitude: Double(bridge.virtualLatitude) ?? 37.3017361843555,
            longitude: Double(bridge.virtualLongitude) ?? 126.838560758712)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("파란 핀은 현재 위치예요. 지도를 움직여 빨간 핀을 새 위치에 맞추세요.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Map(position: $mapPosition) {
                Marker("현재 가상 GPS", coordinate: coordinate)
                    .tint(.blue)
            }
            .mapControls { MapCompass(); MapScaleView() }
            .onMapCameraChange(frequency: .continuous) { context in
                mapCenter = context.region.center
            }
            .frame(height: 250)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay {
                Image(systemName: "mappin")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(.red)
                    .shadow(color: .white.opacity(0.9), radius: 2)
                    .offset(y: -17)
                    .allowsHitTesting(false)
            }

            HStack {
                Label("핀 아래 위치", systemImage: "mappin.and.ellipse")
                    .foregroundStyle(.secondary)
                Spacer()
                Text(String(format: "%.6f, %.6f", mapCenter.latitude, mapCenter.longitude))
                    .font(.caption.monospacedDigit())
            }

            Button {
                bridge.setVirtualLocation(mapCenter)
            } label: {
                Label("이 위치를 가상 GPS로 적용", systemImage: "checkmark.circle.fill")
            }
            .buttonStyle(.borderedProminent)
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack {
                Button("선택된 위치로 이동") {
                    moveMap(to: coordinate)
                }
                Spacer()
                Button("ERICA 기준 위치 적용") {
                    let campus = CLLocationCoordinate2D(latitude: 37.3017361843555, longitude: 126.838560758712)
                    bridge.setVirtualLocation(campus)
                    moveMap(to: campus)
                }
            }
            .font(.caption)
        }
        .padding(.vertical, 4)
        .onAppear {
            moveMap(to: coordinate)
        }
    }

    private func moveMap(to coordinate: CLLocationCoordinate2D) {
        mapCenter = coordinate
        mapPosition = .region(MKCoordinateRegion(center: coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.008, longitudeDelta: 0.008)))
    }
}

private struct SectionTitle: View {
    let title: String
    let trailing: String
    init(_ title: String, trailing: String) { self.title = title; self.trailing = trailing }
    var body: some View { HStack { Text(title).font(.title3.bold()); Spacer(); Text(trailing).font(.caption).foregroundStyle(.secondary) } }
}

private struct CategoryButtonStyle: ButtonStyle {
    let selected: Bool
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.subheadline.weight(.semibold)).padding(.horizontal, 14).padding(.vertical, 9)
            .foregroundStyle(selected ? .white : .primary)
            .background(selected ? Color.indigo : Color(uiColor: .secondarySystemBackground), in: Capsule())
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

#Preview { ContentView() }
