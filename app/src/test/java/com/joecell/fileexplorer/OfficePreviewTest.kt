package com.joecell.fileexplorer

import org.junit.Assert.assertTrue
import org.junit.Test

class OfficePreviewTest {
    @Test fun extractsWordParagraphs() {
        val xml = """<w:document><w:p><w:r><w:t>Hello</w:t></w:r></w:p><w:p><w:r><w:t>World &amp; team</w:t></w:r></w:p></w:document>"""
        val preview = buildOpenXmlPreview("notes.docx", mapOf("word/document.xml" to xml))
        assertTrue(preview.contains("Hello"))
        assertTrue(preview.contains("World & team"))
    }

    @Test fun extractsPresentationSlidesInOrder() {
        val preview = buildOpenXmlPreview(
            "deck.pptx",
            mapOf(
                "ppt/slides/slide2.xml" to """<p:sld><a:t>Second</a:t></p:sld>""",
                "ppt/slides/slide1.xml" to """<p:sld><a:t>First</a:t></p:sld>"""
            )
        )
        assertTrue(preview.indexOf("First") < preview.indexOf("Second"))
        assertTrue(preview.contains("Slide 1"))
        assertTrue(preview.contains("Slide 2"))
    }

    @Test fun resolvesSpreadsheetSharedStrings() {
        val preview = buildOpenXmlPreview(
            "budget.xlsx",
            mapOf(
                "xl/sharedStrings.xml" to """<sst><si><t>Rent</t></si><si><t>Food</t></si></sst>""",
                "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row><row r="2"><c r="A2"><v>1200</v></c></row></sheetData></worksheet>"""
            )
        )
        assertTrue(preview.contains("A1: Rent"))
        assertTrue(preview.contains("B1: Food"))
        assertTrue(preview.contains("A2: 1200"))
    }

    @Test fun decodesXmlEntitiesSafely() {
        assertTrue(officeXmlText("<w:t>A &amp; B &lt; C</w:t>").contains("A & B < C"))
    }
}
