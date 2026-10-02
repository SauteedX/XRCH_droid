import Combine
import CoreLocation
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

struct RecommendationMessage: Identifiable {
    enum Role { case user, assistant }
    let id = UUID()
    let role: Role
    let text: String
    let places: [CompanionPOI]
}

@MainActor
private final class RecommendationLocationProvider: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var completions: [(Result<CLLocation, Error>) -> Void] = []

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
    }

    func request(_ completion: @escaping (Result<CLLocation, Error>) -> Void) {
        completions.append(completion)
        guard completions.count == 1 else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse: manager.requestLocation()
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            finish(.failure(RecommendationError.locationUnavailable))
        @unknown default:
            finish(.failure(RecommendationError.locationUnavailable))
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard !completions.isEmpty else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse: manager.requestLocation()
        case .denied, .restricted: finish(.failure(RecommendationError.locationUnavailable))
        default: break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last(where: { $0.horizontalAccuracy >= 0 }) else { return }
        finish(.success(location))
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish(.failure(error))
    }

    private func finish(_ result: Result<CLLocation, Error>) {
        let callbacks = completions
        completions.removeAll()
        callbacks.forEach { $0(result) }
    }
}

private enum RecommendationError: LocalizedError {
    case locationUnavailable, invalidResponse, server(String)

    var errorDescription: String? {
        switch self {
        case .locationUnavailable: "위치 접근을 허용한 뒤 다시 시도해 주세요."
        case .invalidResponse: "추천 응답을 읽을 수 없습니다. 잠시 후 다시 시도해 주세요."
        case .server(let message): message
        }
    }
}

private enum RecommendationAPI {
    static let endpoint = URL(string: "https://pdvemspkknlfapdzkwhz.supabase.co/functions/v1/chat")!

    private static func clientSecret() throws -> String {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "ARCHClientSecret") as? String,
              !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw RecommendationError.server("로컬 서버 연결 키가 없습니다. Config/LocalSecrets.xcconfig를 설정해 주세요.")
        }
        return value
    }

    static func recommend(question: String, location: CLLocation, radius: Int, accessToken: String,
                          previousPlaces: [CompanionPOI]) async throws -> (String, [CompanionPOI], String, Bool) {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 45
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(try clientSecret(), forHTTPHeaderField: "X-ARCH-Client-Key")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "question": question,
            "lat": location.coordinate.latitude,
            "lng": location.coordinate.longitude,
            "searchRadiusMeters": radius,
            "userLanguageCode": "ko",
            "engine": "gpu",
            "places": previousPlaces.prefix(15).map { poi in
                ["id": poi.id, "name": poi.name, "category": poi.category,
                 "address": poi.address, "lat": poi.latitude, "lng": poi.longitude,
                 "distanceMeters": poi.distanceMeters] as [String: Any]
            },
            "recent_recommended_ids": previousPlaces.prefix(3).map(\.id)
        ])

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw RecommendationError.invalidResponse }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw RecommendationError.invalidResponse
        }
        guard (200...299).contains(http.statusCode), object["success"] as? Bool == true else {
            throw RecommendationError.server(object["message"] as? String ?? "추천 서버가 응답하지 않았습니다.")
        }

        let answer = (object["answer"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let primary = object["top_places"] as? [[String: Any]] ?? []
        let all = object["places"] as? [[String: Any]] ?? []
        var seen = Set<String>()
        let places = (primary + all).compactMap { raw -> CompanionPOI? in
            guard let poi = makePOI(raw, origin: location) else { return nil }
            return seen.insert(poi.id).inserted ? poi : nil
        }
        let model = object["model"] as? String ?? "알 수 없음"
        let gpuUsed = (object["gpuOrchestration"] as? [String: Any])?["used"] as? Bool ?? false
        return (answer.isEmpty ? "추천 결과를 확인해 주세요." : answer, places, model, gpuUsed)
    }

    static func probe(accessToken: String) async throws -> String {
        var request = URLRequest(url: endpoint.appendingPathComponent("plan"))
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(try clientSecret(), forHTTPHeaderField: "X-ARCH-Client-Key")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["question": "연결 확인"])
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw RecommendationError.invalidResponse }
        guard (200...299).contains(http.statusCode),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["success"] as? Bool == true else {
            throw RecommendationError.server("chat/plan HTTP \(http.statusCode)")
        }
        return "연결됨 · HTTP \(http.statusCode)"
    }

    private static func makePOI(_ raw: [String: Any], origin: CLLocation) -> CompanionPOI? {
        func text(_ keys: String...) -> String? {
            for key in keys {
                if let value = raw[key] as? String, !value.isEmpty { return value }
            }
            return nil
        }
        func number(_ keys: String...) -> Double? {
            for key in keys {
                if let value = raw[key] as? NSNumber { return value.doubleValue }
                if let value = raw[key] as? String, let parsed = Double(value) { return parsed }
            }
            return nil
        }
        guard let name = text("displayName", "name", "placeName", "place_name"),
              let latitude = number("lat", "latitude", "y"),
              let longitude = number("lng", "longitude", "x"),
              (-90...90).contains(latitude), (-180...180).contains(longitude),
              latitude != 0, longitude != 0 else { return nil }
        let id = text("id", "placeId", "place_id") ?? "\(name)-\(latitude)-\(longitude)"
        let rawCategory = text("category", "subCategory", "majorCategory") ?? "장소"
        let category: String
        switch rawCategory.lowercased() {
        case "cafe", "카페": category = "카페"
        case "restaurant", "food", "음식점", "식당": category = "음식점"
        case "medical", "약국", "병원": category = "의료"
        case "shopping", "shop", "쇼핑": category = "쇼핑"
        case "convenience", "편의점": category = "생활"
        default: category = rawCategory
        }
        let distance = Int(origin.distance(from: CLLocation(latitude: latitude, longitude: longitude)).rounded())
        return CompanionPOI(id: id, name: name, category: category,
                            address: text("address", "roadAddress", "road_address_name", "address_name") ?? "주소 정보 없음",
                            summary: text("oneLine", "summary", "description") ?? "추천 장소",
                            latitude: latitude, longitude: longitude,
                            distanceMeters: distance, rating: number("rating", "score") ?? 0)
    }
}

@MainActor
final class CompanionAppModel: ObservableObject {
    @Published var query = ""
    @Published var selectedCategory = "전체"
    @Published var selectedPOI: CompanionPOI?
    @Published var favoriteIDs: Set<String> = []
    @Published var recentSearches = ["조용한 카페", "점심 맛집", "약국"]
    @Published var aiPrompt = ""
    @Published var recommendationMessages: [RecommendationMessage] = [
        .init(role: .assistant, text: "안녕하세요! 지금 계신 곳 주변에서 어떤 장소를 찾으세요?", places: [])
    ]
    @Published var isRecommending = false
    @Published var recommendationLocation: CLLocationCoordinate2D?
    @Published var isLocatingOnMap = false
    @Published var mapLocationError: String?
    @Published var supabaseProbeStatus = "확인 전"
    @Published var isProbingSupabase = false
    @Published var lastRecommendationStatus = "요청 전"
    @Published var lastRecommendationModel = "-"
    @Published var lastRecommendationGPU = "-"
    @Published var lastRecommendationAt: Date?
    @Published var lastRecommendationAccuracy: Double?
    private let locationProvider = RecommendationLocationProvider()

    let categories = ["전체", "카페", "음식점", "쇼핑", "생활", "의료"]
    @Published var pois: [CompanionPOI] = []

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

    func locateOnMap() {
        guard !isLocatingOnMap else { return }
        isLocatingOnMap = true
        mapLocationError = nil
        locationProvider.request { [weak self] result in
            guard let self else { return }
            self.isLocatingOnMap = false
            switch result {
            case .success(let location):
                self.recommendationLocation = location.coordinate
            case .failure(let error):
                self.mapLocationError = error.localizedDescription
            }
        }
    }

    func askAI(auth: CompanionAuthSession) {
        let prompt = aiPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty, !isRecommending else { return }
        guard auth.isAuthenticated else {
            recommendationMessages.append(.init(role: .assistant,
                text: CompanionAuthError.loginRequired.localizedDescription, places: []))
            return
        }
        aiPrompt = ""
        recommendationMessages.append(.init(role: .user, text: prompt, places: []))
        isRecommending = true
        lastRecommendationStatus = "GPS 확인 중"
        locationProvider.request { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error):
                self.recommendationMessages.append(.init(role: .assistant, text: error.localizedDescription, places: []))
                self.isRecommending = false
                self.lastRecommendationStatus = "GPS 실패: \(error.localizedDescription)"
            case .success(let location):
                self.lastRecommendationAccuracy = location.horizontalAccuracy
                self.lastRecommendationStatus = "Supabase 요청 중"
                Task {
                    do {
                        let accessToken = try await auth.validAccessToken()
                        let isFollowUp = ["그중", "그 중", "각각", "방금", "위에서", "거기", "그곳", "비교"]
                            .contains { prompt.contains($0) } || self.pois.contains { prompt.contains($0.name) }
                        let (answer, places, serverModel, gpuUsed) = try await RecommendationAPI.recommend(
                            question: prompt, location: location,
                            radius: UserDefaults.standard.integer(forKey: "companionSearchRadius") == 0
                                ? 500 : UserDefaults.standard.integer(forKey: "companionSearchRadius"),
                            accessToken: accessToken,
                            previousPlaces: isFollowUp ? self.pois : [])
                        self.recommendationMessages.append(.init(role: .assistant, text: answer, places: Array(places.prefix(3))))
                        self.pois = places
                        self.recommendationLocation = location.coordinate
                        self.selectedCategory = "전체"
                        self.query = ""
                        self.lastRecommendationStatus = "성공 · 장소 \(places.count)곳"
                        self.lastRecommendationModel = serverModel
                        self.lastRecommendationGPU = gpuUsed ? "사용됨" : "미사용 또는 확인 불가"
                    } catch {
                        self.recommendationMessages.append(.init(role: .assistant,
                            text: "추천 요청 실패: \(error.localizedDescription)", places: []))
                        self.lastRecommendationStatus = "실패: \(error.localizedDescription)"
                    }
                    self.lastRecommendationAt = Date()
                    self.isRecommending = false
                }
            }
        }
    }

    func probeSupabase(auth: CompanionAuthSession) {
        guard !isProbingSupabase else { return }
        isProbingSupabase = true
        supabaseProbeStatus = "연결 확인 중"
        Task {
            do {
                let token = try await auth.validAccessToken()
                supabaseProbeStatus = try await RecommendationAPI.probe(accessToken: token)
            }
            catch { supabaseProbeStatus = "실패: \(error.localizedDescription)" }
            isProbingSupabase = false
        }
    }
}

struct ContentView: View {
    @StateObject private var bridge = QuestLocationBridge()
    @StateObject private var model = CompanionAppModel()
    @StateObject private var auth = CompanionAuthSession()

    var body: some View {
        TabView {
            NavigationStack { DiscoverView() }
                .tabItem { Label("탐색", systemImage: "sparkle.magnifyingglass") }
            NavigationStack { AIRecommendationChatView() }
                .tabItem { Label("AI 추천", systemImage: "bubble.left.and.bubble.right.fill") }
            NavigationStack { CompanionMapView() }
                .tabItem { Label("지도", systemImage: "map.fill") }
            NavigationStack { SavedPlacesView() }
                .tabItem { Label("저장", systemImage: "heart.fill") }
            NavigationStack { OtherView(bridge: bridge) }
                .tabItem { Label("기타", systemImage: "ellipsis.circle") }
        }
        .environmentObject(model)
        .environmentObject(auth)
        .tint(.indigo)
    }
}

private struct AIRecommendationChatView: View {
    @EnvironmentObject private var model: CompanionAppModel
    @EnvironmentObject private var auth: CompanionAuthSession
    @FocusState private var promptFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(model.recommendationMessages) { message in
                            messageRow(message)
                                .id(message.id)
                        }
                        if model.isRecommending {
                            HStack {
                                ProgressView()
                                Text("현재 위치에서 장소를 찾는 중…")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                Spacer()
                            }
                            .padding(14)
                            .background(.background, in: RoundedRectangle(cornerRadius: 18))
                            .id("loading")
                        }
                    }
                    .padding(16)
                }
                .defaultScrollAnchor(.bottom)
                .onChange(of: model.recommendationMessages.count) { _, _ in
                    if let last = model.recommendationMessages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
                .onChange(of: model.isRecommending) { _, loading in
                    if loading { withAnimation { proxy.scrollTo("loading", anchor: .bottom) } }
                }
            }

            HStack(alignment: .bottom, spacing: 10) {
                TextField("근처에서 찾고 싶은 장소를 물어보세요", text: $model.aiPrompt, axis: .vertical)
                    .lineLimit(1...4)
                    .focused($promptFocused)
                    .submitLabel(.send)
                    .onSubmit(send)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(.background, in: RoundedRectangle(cornerRadius: 20))
                Button(action: send) {
                    Image(systemName: "arrow.up")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(.indigo, in: Circle())
                }
                .disabled(model.aiPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.isRecommending)
                .accessibilityLabel("질문 보내기")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.regularMaterial)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("AI 추천")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func send() {
        guard !model.aiPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        promptFocused = false
        model.askAI(auth: auth)
    }

    @ViewBuilder
    private func messageRow(_ message: RecommendationMessage) -> some View {
        HStack(alignment: .bottom) {
            if message.role == .user { Spacer(minLength: 52) }
            VStack(alignment: .leading, spacing: 10) {
                Text(message.text)
                    .font(.body)
                    .foregroundStyle(message.role == .user ? .white : .primary)
                    .textSelection(.enabled)
                ForEach(message.places) { poi in
                    Button { model.selectedPOI = poi } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(poi.name).font(.subheadline.bold())
                            Text("\(poi.distanceMeters)m · \(poi.category)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(14)
            .background(message.role == .user ? Color.indigo : Color(uiColor: .systemBackground),
                        in: RoundedRectangle(cornerRadius: 18))
            if message.role == .assistant { Spacer(minLength: 52) }
        }
        .sheet(item: $model.selectedPOI) { POIDetailView(poi: $0) }
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
                    if model.filteredPOIs.isEmpty {
                        ContentUnavailableView("추천 장소가 없습니다", systemImage: "mappin.and.ellipse",
                            description: Text("AI 추천 탭에서 원하는 장소를 물어보세요."))
                    }
                    ForEach(model.filteredPOIs) { poi in
                        POIRow(poi: poi)
                    }
                }

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
                    HStack {
                        Text(poi.name).font(.headline)
                        if poi.rating > 0 { Text("★ \(poi.rating, specifier: "%.1f")").font(.caption).foregroundStyle(.orange) }
                    }
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
        .onAppear { model.locateOnMap() }
        .onReceive(model.$recommendationLocation) { coordinate in
            guard let coordinate else { return }
            position = .region(MKCoordinateRegion(center: coordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)))
        }
        .overlay(alignment: .topTrailing) {
            VStack(alignment: .trailing, spacing: 8) {
                Button { model.locateOnMap() } label: {
                    Label("내 위치", systemImage: "location.fill")
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.isLocatingOnMap)
                if model.isLocatingOnMap { ProgressView("GPS 확인 중").padding(8).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8)) }
                if let error = model.mapLocationError {
                    Text(error).font(.caption).padding(8).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
                }
            }
            .padding(12)
        }
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
    @EnvironmentObject private var auth: CompanionAuthSession
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
            Section("프로필") { TextField("표시 이름", text: $displayName); LabeledContent("로그인 상태", value: auth.email ?? "로그아웃") }
            Section("검색 설정") {
                Picker("언어", selection: $language) { Text("한국어").tag("한국어"); Text("English").tag("English") }
                VStack(alignment: .leading) { Text("기본 반경 \(Int(searchRadius))m"); Slider(value: $searchRadius, in: 100...1000, step: 100) }
            }
            Section("권한") { Label("위치: 앱 사용 중", systemImage: "location.fill"); Label("Bluetooth: 기기 연결", systemImage: "wave.3.right") }
        }
        .navigationTitle("프로필 및 설정")
    }
}

private struct OtherView: View {
    @EnvironmentObject private var model: CompanionAppModel
    @EnvironmentObject private var auth: CompanionAuthSession
    @ObservedObject var bridge: QuestLocationBridge
    @State private var authEmail = ""
    @State private var authPassword = ""
    @State private var nickname = ""
    @State private var isSigningUp = false
    @State private var authMessage: String?

    var body: some View {
        Form {
            Section("계정") {
                if auth.isAuthenticated {
                    LabeledContent("로그인", value: auth.email ?? "-")
                    Button("로그아웃", role: .destructive) {
                        auth.signOut()
                        authMessage = nil
                    }
                } else {
                    TextField("이메일", text: $authEmail)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("비밀번호", text: $authPassword)
                        .textContentType(isSigningUp ? .newPassword : .password)
                    if isSigningUp {
                        TextField("닉네임", text: $nickname)
                    }
                    Button(isSigningUp ? "회원가입" : "로그인") { submitAuth() }
                        .disabled(auth.isWorking || authEmail.isEmpty || authPassword.isEmpty || (isSigningUp && nickname.isEmpty))
                    Button(isSigningUp ? "기존 계정으로 로그인" : "계정 만들기") {
                        isSigningUp.toggle()
                        authMessage = nil
                    }
                    if auth.isWorking { ProgressView() }
                }
                if let authMessage { Text(authMessage).font(.footnote).foregroundStyle(.secondary) }
                Text(auth.status).font(.footnote).foregroundStyle(.secondary)
            }
            Section("앱 도구") {
                NavigationLink { ProfileView() } label: { Label("프로필 및 설정", systemImage: "person.crop.circle") }
                NavigationLink { HeadingCalibrationView(bridge: bridge) } label: { Label("헤딩 보정", systemImage: "safari") }
                NavigationLink { DeviceHubView(bridge: bridge) } label: { Label("기기 연결", systemImage: "vision.pro") }
            }
            Section("Supabase 연결") {
                LabeledContent("프로젝트", value: "pdvemspkknlfapdzkwhz")
                LabeledContent("함수", value: "chat")
                LabeledContent("상태", value: model.supabaseProbeStatus)
                Button {
                    model.probeSupabase(auth: auth)
                } label: {
                    HStack {
                        Label("연결 확인", systemImage: "network")
                        if model.isProbingSupabase { Spacer(); ProgressView() }
                    }
                }
                .disabled(model.isProbingSupabase)
            }

            Section("최근 AI 추천 요청") {
                LabeledContent("결과", value: model.lastRecommendationStatus)
                LabeledContent("응답 모델", value: model.lastRecommendationModel)
                LabeledContent("GPU", value: model.lastRecommendationGPU)
                LabeledContent("요청 시각", value: model.lastRecommendationAt?.formatted(date: .abbreviated, time: .standard) ?? "-")
                LabeledContent("GPS 정확도", value: model.lastRecommendationAccuracy.map { String(format: "%.1f m", $0) } ?? "-")
                LabeledContent("표시된 장소", value: "\(model.pois.count)곳")
            }

            Section {
                Text("연결 확인은 chat/plan 함수만 호출하며 추천 작업을 만들지 않습니다. GPU 사용 여부는 마지막 추천 응답에서 확인합니다.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("기타")
    }

    private func submitAuth() {
        let email = authEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            do {
                if isSigningUp {
                    try await auth.signUp(email: email, password: authPassword, nickname: name)
                } else {
                    try await auth.signIn(email: email, password: authPassword)
                }
                authPassword = ""
                authMessage = auth.status
            } catch {
                authMessage = error.localizedDescription
            }
        }
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

            Section("AI 추천 위치") {
                Text("현재 AI 추천 결과를 Quest에 보내면 Unity의 디버그 추천은 지워지고, 추천 장소가 보라색 POI로 표시됩니다. 일반 표시 거리 밖의 장소도 함께 생성됩니다.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                LabeledContent("추천 장소", value: "\(model.pois.count)곳")
                Button {
                    bridge.sendAiRecommendationsOnQuest(model.pois)
                } label: {
                    Label("AI 추천 위치를 Quest에 표시", systemImage: "sparkles")
                }
                .disabled(!bridge.isRunning || model.pois.isEmpty)
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
