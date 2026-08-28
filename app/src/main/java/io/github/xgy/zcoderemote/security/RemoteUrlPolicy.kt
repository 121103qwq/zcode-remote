package io.github.xgy.zcoderemote.security

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object RemoteUrlPolicy {
    private const val TRUSTED_SCHEME = "https"
    private const val TRUSTED_HOST = "zcode.z.ai"
    private const val MAX_URL_LENGTH = 8_192
    private val remotePath = Regex("^/remote/v(?:3|4)/?$")
    private val requiredParameters = setOf("sid", "hash", "t")

    data class Parsed(
        val original: String,
        val displayName: String,
        val displayLocation: String,
    )

    fun parseOrNull(raw: String?): Parsed? = runCatching { parse(raw) }.getOrNull()

    fun parse(raw: String?): Parsed {
        val candidate = raw?.trim().orEmpty()
        require(candidate.isNotEmpty()) { "empty URL" }
        require(candidate.length <= MAX_URL_LENGTH) { "URL too long" }
        require(candidate.none(::isControlCharacter)) { "control character in URL" }

        val uri = URI(candidate)
        require(uri.scheme?.equals(TRUSTED_SCHEME, ignoreCase = true) == true) {
            "HTTPS is required"
        }
        require(uri.host?.equals(TRUSTED_HOST, ignoreCase = true) == true) {
            "untrusted host"
        }
        require(uri.userInfo == null) { "user info is not allowed" }
        require(uri.port == -1 || uri.port == 443) { "non-default port" }
        require(uri.fragment == null) { "fragments are not allowed" }
        require(remotePath.matches(uri.rawPath.orEmpty())) { "unsupported remote path" }

        val parameters = parseQuery(uri.rawQuery)
        require(requiredParameters.all { key -> parameters[key]?.singleOrNull()?.isNotBlank() == true }) {
            "missing or duplicate remote credential"
        }
        require(parameters.getValue("t").single().toLongOrNull() != null) {
            "invalid remote timestamp"
        }

        val displayName = parameters["name"]
            ?.singleOrNull()
            ?.filterNot(::isControlCharacter)
            ?.trim()
            ?.take(48)
            ?.takeIf(String::isNotBlank)
            ?: "ZCode Remote"

        // Keep the exact signed query bytes and ordering. Do not decode and rebuild the URL.
        return Parsed(
            original = candidate,
            displayName = displayName,
            displayLocation = "$TRUSTED_SCHEME://$TRUSTED_HOST${uri.rawPath}",
        )
    }

    fun isTrustedTopLevelNavigation(raw: String?): Boolean {
        if (raw.isNullOrBlank() || raw.length > MAX_URL_LENGTH || raw.any(::isControlCharacter)) {
            return false
        }
        return runCatching {
            val uri = URI(raw)
            uri.scheme?.equals(TRUSTED_SCHEME, ignoreCase = true) == true &&
                uri.host?.equals(TRUSTED_HOST, ignoreCase = true) == true &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443)
        }.getOrDefault(false)
    }

    fun isExternalHttps(raw: String?): Boolean {
        if (raw.isNullOrBlank() || raw.length > MAX_URL_LENGTH || raw.any(::isControlCharacter)) {
            return false
        }
        return runCatching {
            val uri = URI(raw)
            uri.scheme?.equals(TRUSTED_SCHEME, ignoreCase = true) == true &&
                uri.host != null &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443)
        }.getOrDefault(false)
    }

    private fun parseQuery(rawQuery: String?): Map<String, List<String>> {
        require(!rawQuery.isNullOrBlank()) { "missing query" }
        val result = linkedMapOf<String, MutableList<String>>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val separator = pair.indexOf('=')
            val rawKey = if (separator >= 0) pair.substring(0, separator) else pair
            val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
            val key = decodeQueryPart(rawKey)
            val value = decodeQueryPart(rawValue)
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }

    private fun decodeQueryPart(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun isControlCharacter(character: Char): Boolean =
        character.code <= 0x1F || character.code == 0x7F
}
