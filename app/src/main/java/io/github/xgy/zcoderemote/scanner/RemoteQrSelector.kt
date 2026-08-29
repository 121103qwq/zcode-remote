package io.github.xgy.zcoderemote.scanner

import io.github.xgy.zcoderemote.security.RemoteUrlPolicy

/** Chooses the first trusted Remote payload without exposing unrelated QR text to the UI. */
object RemoteQrSelector {
    fun firstValid(texts: List<String>): RemoteUrlPolicy.Parsed? =
        texts.firstNotNullOfOrNull(RemoteUrlPolicy::parseOrNull)
}
