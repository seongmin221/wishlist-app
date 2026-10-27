package app.wishlist.android.testing

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Reads `<string>` values of a resource file (the module directory is the test working directory). */
class StringsXml private constructor(private val values: Map<String, String>, private val plurals: Map<String, String>) {
    /** The value of [name] formatted like `getString(name, *args)` (`\'` unescaped). */
    fun format(name: String, vararg args: Any): String {
        val raw = values[name] ?: error("No string $name")
        return String.format(raw.replace("\\'", "'"), *args)
    }

    /** The `other` item of plurals [name] formatted with [count] (Korean has only `other`). */
    fun plural(name: String, count: Int): String {
        val raw = plurals[name] ?: error("No plurals $name")
        return String.format(raw.replace("\\'", "'"), count)
    }

    companion object {
        fun load(path: String): StringsXml {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
            val nodes = doc.getElementsByTagName("string")
            val map = (0 until nodes.length).associate { i ->
                val node = nodes.item(i)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            val pluralNodes = doc.getElementsByTagName("plurals")
            val plurals = (0 until pluralNodes.length).associate { i ->
                val node = pluralNodes.item(i)
                val items = node.childNodes
                val other = (0 until items.length).map { items.item(it) }
                    .first { it.nodeName == "item" && it.attributes.getNamedItem("quantity").nodeValue == "other" }
                node.attributes.getNamedItem("name").nodeValue to other.textContent
            }
            return StringsXml(map, plurals)
        }
    }
}
