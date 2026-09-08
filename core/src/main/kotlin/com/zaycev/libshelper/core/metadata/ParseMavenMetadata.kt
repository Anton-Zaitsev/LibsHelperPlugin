package com.zaycev.libshelper.core.metadata

import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

fun parseMavenMetadata(xml: String): MavenMetadata {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        isXIncludeAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
    }
    val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
    val root = document.documentElement
    val versioning = root.getElementsByTagName("versioning").item(0) as? Element
    val versionsNode = versioning?.getElementsByTagName("versions")?.item(0) as? Element
    val versions = mutableListOf<String>()
    if (versionsNode != null) {
        val nodes = versionsNode.getElementsByTagName("version")
        for (i in 0 until nodes.length) {
            val value = nodes.item(i).textContent?.trim().orEmpty()
            if (value.isNotEmpty()) versions += value
        }
    }
    return MavenMetadata(
        groupId = firstTag(root, "groupId"),
        artifactId = firstTag(root, "artifactId"),
        versions = versions,
        latestTag = versioning?.let { firstTag(it, "latest") },
        releaseTag = versioning?.let { firstTag(it, "release") },
    )
}

private fun firstTag(element: Element, name: String): String? =
    element.getElementsByTagName(name).item(0)?.textContent?.trim()?.ifEmpty { null }
