package com.example.tackyapk.util

/**
 * Format an OMEMO identity-key fingerprint for display: lowercase hex split into
 * 8-char groups, wrapped across two lines (half the groups per line), mirroring
 * the desktop omemokeyspanel layout. Whitespace in the input is stripped first
 * since the backend may return the hex already space-grouped.
 */
fun formatFingerprint(hex: String): String {
    val clean = hex.filterNot { it.isWhitespace() }.lowercase()
    if (clean.isEmpty()) return "(unknown)"
    val groups = clean.chunked(8)
    val half = (groups.size + 1) / 2
    val line1 = groups.take(half).joinToString(" ")
    val line2 = groups.drop(half).joinToString(" ")
    return if (line2.isEmpty()) line1 else "$line1\n$line2"
}
