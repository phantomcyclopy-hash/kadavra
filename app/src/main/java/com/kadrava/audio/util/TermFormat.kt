package com.kadrava.audio.util

/** mm:ss, or h:mm:ss from one hour up. Negative values clamp to zero. */
fun formatTime(ms: Long): String {
    val total = (if (ms < 0) 0L else ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Minimal ASCII meter, e.g. [#####-----]. NaN counts as empty; values clamp to 0..1. */
fun asciiBar(fraction: Float, width: Int = 20): String {
    val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val filled = (f * width).toInt().coerceIn(0, width)
    return "[" + "#".repeat(filled) + "-".repeat(width - filled) + "]"
}

/** Peak/level in dBFS text; non-positive linear values print "-inf". */
fun dbfsText(linear: Float): String =
    if (!(linear > 0f)) "-inf" else "%.1f".format(20.0 * Math.log10(linear.toDouble()))

/** m:ss (no leading zero on minutes), or h:mm:ss from one hour up. */
fun formatClock(ms: Long): String {
    val total = (if (ms < 0) 0L else ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "0.3.0" -> "v0.03.0" (the app's display format). Unparseable input is shown as-is. */
fun designVersion(versionName: String?): String {
    val p = versionName?.split(".") ?: return "v?"
    return if (p.size == 3) "v${p[0]}.${p[1].padStart(2, '0')}.${p[2]}" else "v$versionName"
}

/** Shortens long names keeping the extension: "very_long_name.wav" -> "very_l…wav". */
fun ellipsize(s: String, max: Int): String =
    if (s.length <= max || max < 6) (if (s.length <= max) s else s.take(max)) else s.take(max - 5) + "…" + s.takeLast(4)
