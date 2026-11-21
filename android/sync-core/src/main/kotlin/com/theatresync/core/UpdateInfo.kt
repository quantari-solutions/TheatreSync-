package com.theatresync.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * In-app updates: the newest release, as published in `latest.json` next to the APK in the public
 * releases repository (finished app files only, never code). The app compares [versionCode] with its own,
 * downloads [apkUrl], checks the download against [sha256], and hands it to Android to install.
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long = 0,
    /** Short plain-language list of what's new, shown under "Update available". */
    val notes: String = "",
) {
    fun isNewerThan(installedCode: Int) = versionCode > installedCode

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null if the text isn't a sensible release description (so a bad file never offers an update). */
        fun parse(text: String): UpdateInfo? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
            ?.takeIf { it.versionCode > 0 && it.apkUrl.startsWith("https://") && it.sha256.matches(Regex("[0-9a-fA-F]{64}")) }

        fun toJson(u: UpdateInfo): String = Json { prettyPrint = true }.encodeToString(serializer(), u)
    }
}
