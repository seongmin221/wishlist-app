package app.wishlist.android.share

import android.content.Intent

/**
 * The text of an `ACTION_SEND` share handed to the shared parser (`receiveShared`). Link extraction
 * and validation stay in KMP; this only picks which extras to pass:
 * `EXTRA_TEXT` first, else `EXTRA_SUBJECT`; when both exist and the text has no link,
 * `"$subject $text"` (apps that put the product URL in the subject).
 */
object ShareIntentText {
    private val link = Regex("https?://", RegexOption.IGNORE_CASE)

    fun from(intent: Intent): String? = from(
        action = intent.action,
        type = intent.type,
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
        subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
    )

    /** JVM-testable core of [from] over the intent's fields. */
    fun from(action: String?, type: String?, text: CharSequence?, subject: CharSequence?): String? {
        if (action != Intent.ACTION_SEND) return null
        if (type != null && !type.startsWith("text/")) return null
        val body = text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val title = subject?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            body == null -> title
            title == null || link.containsMatchIn(body) -> body
            else -> "$title $body"
        }
    }
}
