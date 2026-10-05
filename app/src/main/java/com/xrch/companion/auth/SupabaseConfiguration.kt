package com.xrch.companion.auth

/**
 * Public client configuration. Never put service-role or GPU secrets here.
 */
object SupabaseConfiguration {
    const val PROJECT_URL = "https://pdvemspkknlfapdzkwhz.supabase.co"
    const val FUNCTIONS_URL = "$PROJECT_URL/functions/v1"
    const val AUTH_URL = "$PROJECT_URL/auth/v1"

    // Legacy public anon key published by the server's web client.
    const val PUBLIC_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBkdmVtc3Bra25sZmFwZHprd2h6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ3MjA4NzUsImV4cCI6MjEwMDI5Njg3NX0.vPORaYvoSz-8kUeZkT6_4amYZx4sf1SmbN1-edtVZKA"
}
