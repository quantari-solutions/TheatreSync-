package com.theatresync.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the 0.3.1 start-up crash: an init block placed above the
 * properties it uses sees them as null. In these classes the init block must come
 * after every property declaration.
 */
class InitOrderTest {
    private val dir = File("src/main/kotlin/com/theatresync/app")

    @Test
    fun initBlocksComeAfterAllProperties() {
        for (name in listOf("MovieSession.kt", "TvWatchers.kt", "HeadphonePlayer.kt", "MainActivity.kt")) {
            val lines = File(dir, name).readLines()
            val initLine = lines.indexOfFirst { it.trim() == "init {" }
            if (initLine < 0) continue
            val lastProperty = lines.indexOfLast { Regex("^    (private |internal )?(val|var) ").containsMatchIn(it) }
            assertTrue("$name: init block (line ${initLine + 1}) must come after the last property (line ${lastProperty + 1})", initLine > lastProperty)
        }
    }
}
