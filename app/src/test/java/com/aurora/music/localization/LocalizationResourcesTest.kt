package com.aurora.music.localization

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourcesTest {
    @Test fun russianResourcesAreCompleteAndHaveUniqueNames() {
        fun strings(directory: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            File("src/main/res/$directory").listFiles()!!.filter { it.extension == "xml" }.forEach { file ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes = document.getElementsByTagName("string")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index) as Element
                    val name = node.getAttribute("name")
                    assertNull("Duplicate $directory/$name", result.put(name, node.textContent))
                }
            }
            return result
        }
        val english = strings("values")
        val russian = strings("values-ru")
        assertEquals(english.keys - "app_name", russian.keys)
        val format = Regex("(?<!%)%(?:[0-9]+\\$)?[-+0-9.]*[sdf]")
        russian.forEach { (name, text) ->
            assertEquals(name, format.findAll(english.getValue(name)).map { it.value }.sorted().toList(),
                format.findAll(text).map { it.value }.sorted().toList())
        }
    }
}
