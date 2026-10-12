package app.testutil

import app.extraction.HttpFetchResponse
import java.net.InetAddress

/** Small HTML builders for product metadata extraction tests. */
object MetadataFixtures {
    fun page(head: String = "", body: String = ""): String = "<html><head>$head</head><body>$body</body></html>"

    fun jsonLd(json: String): String = """<script type="application/ld+json">$json</script>"""

    fun meta(property: String, content: String): String = """<meta property="$property" content="$content">"""

    fun product(name: String = "Neo Daichi", offers: String? = null, brand: String? = null): String = jsonLd(buildString {
        append("""{"@context":"https://schema.org","@type":"Product","name":"$name"""")
        if (brand != null) append(""","brand":$brand""")
        if (offers != null) append(""","offers":$offers""")
        append("}")
    })

    /** Serves fixture HTML by URL; unknown URLs return 404. */
    fun fetch(pages: Map<String, String>): (String, List<InetAddress>) -> HttpFetchResponse = { url, _ ->
        pages[url]?.let { HttpFetchResponse(200, mapOf("content-type" to "text/html"), it) }
            ?: HttpFetchResponse(404, emptyMap(), "")
    }
}
