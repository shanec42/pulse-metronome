package com.austinlinux.pulsemetronome.ui.legal

import android.content.Context

/** Reads a legal doc bundled from docs/legal at build time. */
fun loadLegalDoc(context: Context, doc: LegalDoc): String =
    context.assets.open("legal/${doc.fileName}").bufferedReader().use { it.readText() }

/** SHA-256 hex of the notice text; acceptance is tied to the exact wording the user saw. */
fun noticeFingerprint(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun shouldShowNotice(acceptedHash: String?, currentHash: String): Boolean = acceptedHash != currentHash

/** Fingerprint of the bundled first-launch notice. */
fun bundledNoticeFingerprint(context: Context): String =
    context.assets.open("legal/${LegalDoc.NOTICE.fileName}").use { noticeFingerprint(it.readBytes()) }
