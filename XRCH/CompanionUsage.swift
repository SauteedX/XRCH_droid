import Foundation

struct CompanionUsage: Decodable {
    let remaining: Int?
    let resetAt: String
    let isAnonymous: Bool

    var resetDate: Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: resetAt) ?? ISO8601DateFormatter().date(from: resetAt)
    }

    var summary: String {
        isAnonymous ? "오늘 무료 AI 추천 \(remaining ?? 0)회 남음" : "계정으로 이용 중"
    }

    init?(response: HTTPURLResponse) {
        guard let remaining = response.value(forHTTPHeaderField: "X-GPU-Quota-Remaining"),
              let reset = response.value(forHTTPHeaderField: "X-GPU-Quota-Reset-At"),
              let anonymous = response.value(forHTTPHeaderField: "X-GPU-Quota-Anonymous"),
              anonymous == "true" || anonymous == "false" else { return nil }
        guard remaining == "unlimited" || Int(remaining) != nil else { return nil }
        self.remaining = Int(remaining)
        resetAt = reset
        isAnonymous = anonymous == "true"
    }
}
