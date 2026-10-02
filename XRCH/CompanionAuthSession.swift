import Combine
import Foundation
import Security

private struct StoredCompanionSession: Codable {
    let userId: String
    let email: String
    let accessToken: String
    let refreshToken: String
    let expiresAt: Date
}

private struct AuthResponse: Decodable {
    let success: Bool
    let message: String?
    let userId: String?
    let email: String?
    let accessToken: String?
    let refreshToken: String?
    let accessExpiresIn: Int?
}

enum CompanionAuthError: LocalizedError {
    case loginRequired
    case invalidResponse
    case server(String)
    case keychain(OSStatus)

    var errorDescription: String? {
        switch self {
        case .loginRequired: "AI 추천을 사용하려면 로그인해 주세요."
        case .invalidResponse: "인증 서버 응답을 읽을 수 없습니다."
        case .server(let message): message
        case .keychain: "로그인 정보를 안전하게 저장할 수 없습니다."
        }
    }
}

@MainActor
final class CompanionAuthSession: ObservableObject {
    @Published private(set) var email: String?
    @Published private(set) var status = "로그인이 필요합니다."
    @Published private(set) var isWorking = false

    private static let functionsURL = URL(string: "https://pdvemspkknlfapdzkwhz.supabase.co/functions/v1")!
    private static let keychainService = "com.personalteam.XRCH.supabase-session"
    private static let keychainAccount = "current-user"
    private var session: StoredCompanionSession?
    private var refreshTask: Task<AuthResponse, Error>?

    var isAuthenticated: Bool { session != nil }

    init() {
        do {
            session = try Self.readStoredSession()
            email = session?.email
            status = session == nil ? "로그인이 필요합니다." : "로그인됨"
        } catch {
            status = error.localizedDescription
        }
    }

    func signIn(email: String, password: String) async throws {
        guard !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        let response = try await Self.send("login", body: ["email": email, "password": password])
        try save(response)
        status = "로그인됨"
    }

    func signUp(email: String, password: String, nickname: String) async throws {
        guard !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        let response = try await Self.send("signup", body: [
            "email": email, "password": password, "nickname": nickname, "language": "ko"
        ])
        if response.accessToken != nil, response.refreshToken != nil {
            try save(response)
            status = "가입 및 로그인 완료"
        } else {
            status = "가입되었습니다. 이메일 인증 후 로그인해 주세요."
        }
    }

    func validAccessToken() async throws -> String {
        guard let session else { throw CompanionAuthError.loginRequired }
        if session.expiresAt.timeIntervalSinceNow > 60 { return session.accessToken }

        if refreshTask == nil {
            refreshTask = Task {
                try await Self.send("refresh", body: ["refreshToken": session.refreshToken])
            }
        }
        defer { refreshTask = nil }
        do {
            let response = try await refreshTask!.value
            guard self.session?.refreshToken == session.refreshToken else {
                throw CompanionAuthError.loginRequired
            }
            try save(response)
            status = "로그인됨"
            return self.session!.accessToken
        } catch {
            if self.session?.refreshToken == session.refreshToken { signOut() }
            throw CompanionAuthError.server("세션이 만료되었습니다. 다시 로그인해 주세요.")
        }
    }

    func signOut() {
        refreshTask?.cancel()
        refreshTask = nil
        Self.deleteStoredSession()
        session = nil
        email = nil
        status = "로그아웃되었습니다."
    }

    private func save(_ response: AuthResponse) throws {
        guard let userId = response.userId,
              let email = response.email,
              let accessToken = response.accessToken,
              let refreshToken = response.refreshToken else {
            throw CompanionAuthError.invalidResponse
        }
        let record = StoredCompanionSession(
            userId: userId, email: email, accessToken: accessToken,
            refreshToken: refreshToken,
            expiresAt: Date().addingTimeInterval(TimeInterval(response.accessExpiresIn ?? 3600))
        )
        try Self.writeStoredSession(record)
        session = record
        self.email = email
    }

    private static func send(_ function: String, body: [String: String]) async throws -> AuthResponse {
        var request = URLRequest(url: functionsURL.appendingPathComponent(function))
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw CompanionAuthError.invalidResponse }
        guard let decoded = try? JSONDecoder().decode(AuthResponse.self, from: data) else {
            throw CompanionAuthError.invalidResponse
        }
        guard (200...299).contains(http.statusCode), decoded.success else {
            throw CompanionAuthError.server(decoded.message ?? "인증 요청에 실패했습니다. (HTTP \(http.statusCode))")
        }
        return decoded
    }

    private static func baseQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: keychainService,
         kSecAttrAccount as String: keychainAccount]
    }

    private static func readStoredSession() throws -> StoredCompanionSession? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw CompanionAuthError.keychain(status)
        }
        return try JSONDecoder().decode(StoredCompanionSession.self, from: data)
    }

    private static func writeStoredSession(_ record: StoredCompanionSession) throws {
        let data = try JSONEncoder().encode(record)
        deleteStoredSession()
        var query = baseQuery()
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw CompanionAuthError.keychain(status) }
    }

    private static func deleteStoredSession() {
        SecItemDelete(baseQuery() as CFDictionary)
    }
}
