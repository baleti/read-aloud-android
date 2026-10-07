package dev.local.readaloud

/** Stateful per-read line filter for ScrollReader; finish() releases anything held back at the end. */
interface StreamFilter {
    fun feed(lines: List<String>): List<String>
    fun finish(): List<String> = emptyList()
}
