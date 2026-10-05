import Foundation

// Run on macOS without contacting Supabase or modifying the real Keychain.
private final class MemoryStorage: CompanionSessionStorage {
    var data: Data?
    var readError = false
    var writeError = false
    init(_ data: Data? = nil) { self.data = data }
    func read() throws -> Data? {
        if readError { throw CompanionAuthError.keychain(-25308) }
        return data
    }
    func write(_ data: Data) throws {
        if writeError { throw CompanionAuthError.keychain(-25308) }
        self.data = data
    }
    func delete() throws { data = nil }
}

private final class MockTransport: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, [String: String], Data))!
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (code, headers, data) = try Self.handler(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil, headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

private final class Counter {
    private let lock = NSLock()
    private var stored = 0
    var value: Int { lock.withLock { stored } }
    func increment() { lock.withLock { stored += 1 } }
}

private func check(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() { throw NSError(domain: "AuthSessionTest", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
}
private func json(_ object: [String: Any]) -> Data { try! JSONSerialization.data(withJSONObject: object) }
private func storedSession(expired: Bool = false) -> Data {
    json(["userId": "guest-1", "accessToken": "guest-access", "refreshToken": "guest-refresh",
          "expiresAt": Date().addingTimeInterval(expired ? -60 : 3600).timeIntervalSinceReferenceDate,
          "isAnonymous": true])
}
private func authResponse(access: String = "guest-access", refresh: String = "guest-refresh") -> Data {
    json(["access_token": access, "refresh_token": refresh, "expires_in": 3600,
          "user": ["id": "guest-1", "is_anonymous": true]])
}
private let usageResponse = json(["success": true, "remaining": 3,
                                 "resetAt": "2030-01-01T15:00:00+00:00", "isAnonymous": true])
private let usageHeaders = ["X-GPU-Quota-Remaining": "3", "X-GPU-Quota-Reset-At": "2030-01-01T15:00:00.000Z",
                            "X-GPU-Quota-Anonymous": "true"]

@main
private struct AuthSessionHarness {
    @MainActor static func main() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockTransport.self]
        let network = URLSession(configuration: config)
        defer { network.invalidateAndCancel() }
        var passed = 0
        func pass(_ name: String) { passed += 1; print("PASS \(name)") }

        do {
            let storage = MemoryStorage(), calls = Counter()
            MockTransport.handler = { request in
                try check(request.url?.path == "/auth/v1/signup", "Expected anonymous Auth signup")
                try check(request.value(forHTTPHeaderField: "apikey") == SupabaseConfiguration.publicKey, "Missing public project key")
                calls.increment()
                return (200, [:], authResponse())
            }
            let auth = CompanionAuthSession(storage: storage, guestStorage: MemoryStorage(), network: network)
            async let first = auth.validAccessToken()
            async let second = auth.validAccessToken()
            let tokens = try await [first, second]
            try check(tokens == ["guest-access", "guest-access"] && calls.value == 1, "Concurrent startup created two guests")
            try check(auth.isGuest && !auth.isAuthenticated && storage.data != nil, "Guest session was not persisted")
            pass("concurrent startup creates and stores one anonymous session")
        }
        do {
            MockTransport.handler = { _ in throw URLError(.badURL) }
            let auth = CompanionAuthSession(storage: MemoryStorage(storedSession()), guestStorage: MemoryStorage(), network: network)
            let token = try await auth.validAccessToken()
            try check(token == "guest-access" && auth.isGuest, "Stored session was not restored")
            pass("restart restores the same guest without another signup")
        }
        do {
            let storage = MemoryStorage(storedSession(expired: true)), calls = Counter()
            MockTransport.handler = { request in
                try check(request.url?.query == "grant_type=refresh_token", "Expected Auth refresh")
                calls.increment()
                return (200, [:], authResponse(access: "new-access", refresh: "rotated-refresh"))
            }
            let auth = CompanionAuthSession(storage: storage, guestStorage: MemoryStorage(), network: network)
            async let first = auth.validAccessToken()
            async let second = auth.validAccessToken()
            let tokens = try await [first, second]
            let record = try JSONSerialization.jsonObject(with: storage.data!) as! [String: Any]
            try check(tokens == ["new-access", "new-access"] && calls.value == 1, "Concurrent refresh was duplicated")
            try check(record["refreshToken"] as? String == "rotated-refresh", "Rotated refresh token was not stored")
            pass("concurrent refresh rotates and saves tokens once")
        }
        do {
            let original = storedSession(expired: true), storage = MemoryStorage(original), calls = Counter()
            MockTransport.handler = { request in
                try check(request.url?.path == "/auth/v1/token", "Network failure must not create a new guest")
                calls.increment(); throw URLError(.notConnectedToInternet)
            }
            let auth = CompanionAuthSession(storage: storage, guestStorage: MemoryStorage(), network: network)
            do { _ = try await auth.validAccessToken(); throw URLError(.unknown) }
            catch { try check((error as? URLError)?.code == .notConnectedToInternet, "Unexpected network error") }
            try check(storage.data == original && auth.hasSession && calls.value == 1, "Offline refresh discarded identity")
            pass("offline refresh preserves the saved identity")
        }
        do {
            let calls = Counter(), refreshes = Counter()
            MockTransport.handler = { request in
                if request.url?.path == "/auth/v1/token" {
                    refreshes.increment(); return (200, [:], authResponse(access: "new-access"))
                }
                calls.increment()
                if request.value(forHTTPHeaderField: "Authorization") == "Bearer guest-access" {
                    return (401, [:], json(["code": "INVALID_TOKEN"]))
                }
                try check(request.value(forHTTPHeaderField: "Authorization") == "Bearer new-access", "Retry used the old token")
                return (200, usageHeaders, json(["success": true]))
            }
            let auth = CompanionAuthSession(storage: MemoryStorage(storedSession()), guestStorage: MemoryStorage(), network: network)
            let (_, response) = try await auth.authenticatedRequest(URLRequest(url: SupabaseConfiguration.functionsURL.appendingPathComponent("chat")))
            try check(response.statusCode == 200 && calls.value == 2 && refreshes.value == 1, "401 must refresh and retry once")
            try check(auth.usage?.remaining == 3 && auth.usage?.resetDate != nil, "Usage response headers were not read")
            pass("401 refreshes once and response updates remaining usage")
        }
        do {
            let calls = Counter()
            MockTransport.handler = { request in
                try check(request.url?.path.contains("functions/v1/chat") == true, "429 must not refresh")
                calls.increment(); return (429, usageHeaders, json(["code": "DAILY_QUOTA_EXCEEDED"]))
            }
            let auth = CompanionAuthSession(storage: MemoryStorage(storedSession()), guestStorage: MemoryStorage(), network: network)
            let (_, response) = try await auth.authenticatedRequest(URLRequest(url: SupabaseConfiguration.functionsURL.appendingPathComponent("chat")))
            try check(response.statusCode == 429 && calls.value == 1, "Quota error was retried")
            pass("429 is returned without retrying or charging again")
        }
        do {
            let original = storedSession(), storage = MemoryStorage(original)
            MockTransport.handler = { _ in (401, [:], json(["message": "Invalid login"])) }
            let auth = CompanionAuthSession(storage: storage, guestStorage: MemoryStorage(), network: network)
            do { try await auth.signIn(email: "test@example.com", password: "invalid"); throw URLError(.unknown) }
            catch { try check(storage.data == original && auth.isGuest, "Failed login replaced the guest") }
            pass("failed account login retains the guest")
        }
        do {
            let storage = MemoryStorage(storedSession()), backup = MemoryStorage(), logouts = Counter()
            MockTransport.handler = { request in
                if request.url?.path == "/functions/v1/login" {
                    return (200, [:], json(["success": true, "userId": "registered-1", "email": "test@example.com",
                                          "accessToken": "account-access", "refreshToken": "account-refresh", "accessExpiresIn": 3600]))
                }
                if request.url?.path == "/auth/v1/logout" {
                    try check(request.value(forHTTPHeaderField: "Authorization") == "Bearer account-access", "Logout did not revoke the account session")
                    logouts.increment(); return (204, [:], Data())
                }
                try check(request.url?.path == "/functions/v1/usage", "Logout must not create another guest")
                return (200, [:], usageResponse)
            }
            let auth = CompanionAuthSession(storage: storage, guestStorage: backup, network: network)
            try await auth.signIn(email: "test@example.com", password: "valid")
            try check(auth.isAuthenticated && backup.data != nil, "Account login did not preserve the guest")
            try await auth.signOut()
            let token = try await auth.validAccessToken()
            try check(auth.isGuest && token == "guest-access" && logouts.value == 1 && auth.usage?.remaining == 3, "Logout reset guest identity or usage")
            pass("account logout revokes its session and restores the previous guest budget")
        }
        do {
            let storage = MemoryStorage(storedSession()); storage.readError = true
            let calls = Counter()
            MockTransport.handler = { _ in calls.increment(); return (200, [:], authResponse()) }
            let auth = CompanionAuthSession(storage: storage, guestStorage: MemoryStorage(), network: network)
            do { _ = try await auth.validAccessToken(); throw URLError(.unknown) }
            catch { try check(calls.value == 0, "Locked Keychain created a new guest") }
            storage.readError = false
            let token = try await auth.validAccessToken()
            try check(token == "guest-access", "Keychain unlock did not restore the existing guest")
            pass("locked Keychain never creates a replacement identity")
        }
        do {
            let storage = MemoryStorage(); storage.writeError = true
            MockTransport.handler = { _ in (200, [:], authResponse()) }
            let auth = CompanionAuthSession(storage: storage, guestStorage: MemoryStorage(), network: network)
            do { _ = try await auth.validAccessToken(); throw URLError(.unknown) }
            catch { try check(!auth.hasSession && storage.data == nil, "Unsaved guest was accepted") }
            pass("Keychain write failures do not accept an unpersisted session")
        }
        do {
            MockTransport.handler = { request in
                try check(request.url?.path == "/functions/v1/usage", "Usage read must not run inference")
                return (200, [:], usageResponse)
            }
            let auth = CompanionAuthSession(storage: MemoryStorage(storedSession()), guestStorage: MemoryStorage(), network: network)
            await auth.refreshUsage()
            try check(auth.usage?.remaining == 3 && auth.usage?.resetDate != nil, "Usage endpoint response was not displayed")
            pass("read-only usage lookup handles Supabase timestamps")
        }
        print("\(passed) authentication checks passed")
    }
}
