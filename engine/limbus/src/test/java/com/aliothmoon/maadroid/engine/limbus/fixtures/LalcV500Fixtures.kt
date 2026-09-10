package com.aliothmoon.maadroid.engine.limbus.fixtures

import java.security.MessageDigest

/** Pinned upstream text fixtures; loaded from the test classpath, never a developer checkout. */
internal object LalcV500Fixtures {
    private const val ROOT = "/fixtures/lalc-v5.0.0"
    private val taskNames = listOf(
        "basic.json", "battle.json", "error.json", "event.json", "luxcavation.json",
        "mail.json", "main.json", "mirror.json", "reward.json", "utils.json",
    )

    private val hashes: Map<String, String> by lazy {
        val entry = Regex("([0-9a-f]{64})  ([a-zA-Z0-9_./-]+)")
        val result = linkedMapOf<String, String>()
        requiredBytes("SHA256SUMS").toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val match = checkNotNull(entry.matchEntire(line)) { "Invalid LALC fixture checksum entry: $line" }
                val (hash, path) = match.destructured
                check(result.put(path, hash) == null) { "Duplicate LALC fixture checksum: $path" }
            }
        result
    }

    fun taskFiles(): Map<String, String> =
        taskNames.associateWith { verifiedText("config/task/$it") }

    /** Names from the pinned Git tree, not image pixels or a recognition fixture. */
    fun templateNames(): Set<String> = verifiedText("template-names.txt")
        .lineSequence().filter { it.isNotBlank() }.toSet()

    private fun verifiedText(path: String): String {
        val expected = checkNotNull(hashes[path]) { "Missing LALC fixture checksum: $path" }
        val bytes = requiredBytes(path)
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == expected) {
            "LALC fixture SHA-256 mismatch: $path (expected $expected, got $actual); see $ROOT/README.md"
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun requiredBytes(path: String): ByteArray =
        checkNotNull(LalcV500Fixtures::class.java.getResourceAsStream("$ROOT/$path")) {
            "Missing required LALC v5.0.0 test fixture: $ROOT/$path; restore the checked-in test resources"
        }.use { it.readBytes() }
}
