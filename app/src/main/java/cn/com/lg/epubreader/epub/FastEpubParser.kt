package cn.com.lg.epubreader.epub

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

class FastEpubParser(private val epubFile: File) {

    data class ManifestItem(val id: String, val href: String, val mediaType: String)
    data class NavItem(val title: String, val href: String)

    var title: String = ""
    var author: String = ""
    var opfPath: String = ""
    var opfDir: String = ""
    val manifest = mutableMapOf<String, ManifestItem>()
    val spine = mutableListOf<String>() // List of HREFs in order
    val toc = mutableListOf<NavItem>()
    private var tocId: String? = null
    private var navHref: String? = null

    init {
        parseStructure()
    }

    private fun parseStructure() {
        val zip = ZipFile(epubFile)
        try {
            // 1. Find OPF path from container.xml
            val containerEntry = zip.getEntry("META-INF/container.xml") ?: return
            opfPath = parseContainer(zip.getInputStream(containerEntry)) ?: return
            opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") else ""

            // 2. Parse OPF
            val opfEntry = zip.getEntry(opfPath) ?: return
            parseOpf(zip.getInputStream(opfEntry))

            // 3. Parse TOC
            // Priority 1: Nav (EPUB 3)
            val tocFileHref = navHref ?: tocId?.let { manifest[it]?.href }
            if (tocFileHref != null) {
                val tocEntry = zip.getEntry(getZipPath(tocFileHref))
                if (tocEntry != null) {
                    if (navHref != null) {
                        parseNav(zip.getInputStream(tocEntry))
                    } else {
                        parseNcx(zip.getInputStream(tocEntry))
                    }
                }
            }
        } finally {
            zip.close()
        }
    }

    private fun parseContainer(inputStream: InputStream): String? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
            eventType = parser.next()
        }
        return null
    }

    private fun parseOpf(inputStream: InputStream) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)
        var eventType = parser.eventType
        
        var inMetadata = false
        var inManifest = false
        var inSpine = false

        val idToHref = mutableMapOf<String, String>()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "metadata" -> inMetadata = true
                        "manifest" -> inManifest = true
                        "spine" -> {
                            inSpine = true
                            tocId = parser.getAttributeValue(null, "toc")
                        }
                        "item" -> if (inManifest) {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val type = parser.getAttributeValue(null, "media-type")
                            val properties = parser.getAttributeValue(null, "properties")
                            if (id != null && href != null) {
                                manifest[id] = ManifestItem(id, href, type ?: "")
                                idToHref[id] = href
                                if (properties == "nav") {
                                    navHref = href
                                }
                            }
                        }
                        "itemref" -> if (inSpine) {
                            val idref = parser.getAttributeValue(null, "idref")
                            idToHref[idref]?.let { spine.add(it) }
                        }
                        "dc:title", "title" -> if (inMetadata) title = parser.nextText()
                        "dc:creator", "creator" -> if (inMetadata) author = parser.nextText()
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (name) {
                        "metadata" -> inMetadata = false
                        "manifest" -> inManifest = false
                        "spine" -> inSpine = false
                    }
                }
            }
            eventType = parser.next()
        }
    }

    private fun parseNcx(inputStream: InputStream) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)
        var eventType = parser.eventType
        
        var depth = 0
        var currentLabel: String? = null
        var currentHref: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "navPoint" -> {
                            depth++
                            currentLabel = null
                            currentHref = null
                        }
                        "text" -> {
                            if (currentLabel == null) currentLabel = parser.nextText()
                        }
                        "content" -> {
                            if (currentHref == null) {
                                currentHref = parser.getAttributeValue(null, "src")
                                if (currentLabel != null && currentHref != null) {
                                    val cleanHref = currentHref!!.substringBefore("#")
                                    val indent = "    ".repeat(depth - 1)
                                    toc.add(NavItem("$indent$currentLabel", cleanHref))
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "navPoint") depth--
                }
            }
            eventType = parser.next()
        }
    }

    private fun parseNav(inputStream: InputStream) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)
        var eventType = parser.eventType
        
        var inNav = false
        var depth = 0

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name == "nav") inNav = true
                    if (inNav) {
                        if (name == "ol" || name == "ul") {
                            depth++
                        } else if (name == "a") {
                            val href = parser.getAttributeValue(null, "href")
                            val label = parser.nextText()
                            if (label != null && href != null) {
                                val cleanHref = href.substringBefore("#")
                                val indent = "    ".repeat(depth - 1).coerceAtLeast("")
                                toc.add(NavItem("$indent$label", cleanHref))
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "nav") {
                        inNav = false
                        depth = 0
                    }
                    if (inNav && (name == "ol" || name == "ul")) {
                        depth--
                    }
                }
            }
            eventType = parser.next()
        }
    }

    // Helper to get actual entry path in ZIP
    fun getZipPath(href: String): String {
        return if (opfDir.isEmpty()) href else "$opfDir/$href"
    }
}
