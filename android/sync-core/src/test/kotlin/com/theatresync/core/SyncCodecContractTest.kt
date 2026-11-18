package com.theatresync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Verifies the Kotlin codec agrees with the shared JSON fixtures in /contracts. */
class SyncCodecContractTest {
    private val contracts = File(System.getProperty("contracts.dir") ?: "../../contracts")
    private fun fixtures(kind: String) =
        File(contracts, "examples/$kind").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    @Test
    fun everyValidFixtureRoundTrips() {
        val files = fixtures("valid")
        assertTrue("expected fixtures in $contracts", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            val decoded = SyncCodec.decode(text)
            val reencoded = SyncCodec.encode(decoded)
            assertEquals(
                "round-trip mismatch for ${f.name}",
                Json.parseToJsonElement(text), Json.parseToJsonElement(reencoded) as JsonElement,
            )
        }
    }

    @Test
    fun everyInvalidFixtureIsRejected() {
        for (f in fixtures("invalid")) {
            try {
                SyncCodec.decode(f.readText())
                fail("${f.name} should have been rejected")
            } catch (_: ProtocolException) {
                // expected
            }
        }
    }

    @Test
    fun everySchemaTypeHasAFixture() {
        val schema = File(contracts, "schemas/sync-message.v1.schema.json").readText()
        val types = Regex("\"type\": \\{ \"enum\": \\[([^]]*)]").find(schema)!!.groupValues[1]
            .split(",").map { it.trim().trim('"') }.toSet()
        val covered = fixtures("valid").map { SyncCodec.decode(it.readText()) }.map {
            SyncCodec.encode(it).substringAfter("\"type\":\"").substringBefore('"')
        }.toSet()
        assertEquals(types, covered)
    }
}
