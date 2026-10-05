import Foundation

// Public client configuration. Never put service-role or GPU secrets here.
enum SupabaseConfiguration {
    static let projectURL = URL(string: "https://pdvemspkknlfapdzkwhz.supabase.co")!
    static let functionsURL = projectURL.appendingPathComponent("functions/v1")
    static let authURL = projectURL.appendingPathComponent("auth/v1")
    // Legacy public anon key published by the server's web client.
    static let publicKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBkdmVtc3Bra25sZmFwZHprd2h6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ3MjA4NzUsImV4cCI6MjEwMDI5Njg3NX0.vPORaYvoSz-8kUeZkT6_4amYZx4sf1SmbN1-edtVZKA"
}
