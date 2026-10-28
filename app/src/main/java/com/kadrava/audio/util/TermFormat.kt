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
