package com.scrolldock

import java.util.Base64

enum class ScrollMethod {
    AUTO,
    LOCKED,
    GESTURE_ONLY,
}

enum class MessageRole {
    ANY,
    USER,
    ASSISTANT,
}

data class AppProfile(
    val buttonSizeDp: Int = 48,
    val opacityPercent: Int = 82,
    val pagePercent: Int = 85,
    val intervalMs: Long = 420L,
    val collapsed: Boolean = false,
    val scrollMethod: ScrollMethod = ScrollMethod.AUTO,
    val messageButtonsEnabled: Boolean = false,
) {
    fun sanitized(): AppProfile = copy(
        buttonSizeDp = buttonSizeDp.coerceIn(40, 72),
        opacityPercent = opacityPercent.coerceIn(30, 100),
        pagePercent = pagePercent.coerceIn(50, 95),
        intervalMs = intervalMs.coerceIn(200L, 1_200L),
    )
}

data class TargetSignature(
    val viewId: String?,
    val className: String?,
    val path: List<Int>,
    val centerXPercent: Int,
    val centerYPercent: Int,
) {
    fun encode(): String = listOf(
        encodePart(viewId.orEmpty()),
        encodePart(className.orEmpty()),
        path.joinToString("."),
        centerXPercent.coerceIn(0, 100).toString(),
        centerYPercent.coerceIn(0, 100).toString(),
    ).joinToString("|")

    companion object {
        fun decode(value: String?): TargetSignature? {
            if (value.isNullOrBlank()) return null
            val parts = value.split('|')
            if (parts.size != 5) return null
            return runCatching {
                TargetSignature(
                    viewId = decodePart(parts[0]).ifBlank { null },
                    className = decodePart(parts[1]).ifBlank { null },
                    path = parts[2].takeIf(String::isNotBlank)?.split('.')?.map(String::toInt).orEmpty(),
                    centerXPercent = parts[3].toInt().coerceIn(0, 100),
                    centerYPercent = parts[4].toInt().coerceIn(0, 100),
                )
            }.getOrNull()
        }

        private fun encodePart(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decodePart(value: String): String =
            String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    }
}