package com.sb.phototool.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    @Test
    fun sanitizeDiagnosticText_redactsUrisAndCredentialLikeValues() {
        val source = "failed content://media/picker/secret-image apiKey=abc123 token: bearer-value"

        val sanitized = sanitizeDiagnosticText(source)

        assertTrue(sanitized.contains("<uri>"))
        assertTrue(sanitized.contains("apiKey=<redacted>"))
        assertTrue(sanitized.contains("token=<redacted>"))
        assertFalse(sanitized.contains("secret-image"))
        assertFalse(sanitized.contains("abc123"))
        assertFalse(sanitized.contains("bearer-value"))
    }
}
