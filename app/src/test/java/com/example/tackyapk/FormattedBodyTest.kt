package com.example.tackyapk

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.example.tackyapk.feature.conversation.FormatSpan
import com.example.tackyapk.feature.conversation.formattedBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure formatted-body renderer: maps backend spans to SpanStyles and
 * autolinks URLs. Several cases use the exact (body, formatting) pairs tacky
 * emitted for the linuxforum chat, captured off the wire.
 */
class FormattedBodyTest {

    @Test
    fun boldSpanCoversExactRange() {
        val text = "hello world"
        val result = formattedBody(text, listOf(FormatSpan("bold", 0, 5)))
        assertEquals(text, result.text)
        assertEquals(1, result.spanStyles.size)
        val range = result.spanStyles[0]
        assertEquals(0, range.start)
        assertEquals(5, range.end)
        assertEquals(FontWeight.Bold, range.item.fontWeight)
    }

    // tacky wire: body="Enough." fmt=[bold 0,7]
    @Test
    fun realBoldHeadlineCoversWholeWord() {
        val text = "Enough."
        val result = formattedBody(text, listOf(FormatSpan("bold", 0, 7)))
        assertEquals(1, result.spanStyles.size)
        assertEquals(0, result.spanStyles[0].start)
        assertEquals(7, result.spanStyles[0].end)
        assertEquals(FontWeight.Bold, result.spanStyles[0].item.fontWeight)
    }

    @Test
    fun italicMapsToItalicStyle() {
        val result = formattedBody("abc", listOf(FormatSpan("italic", 0, 3)))
        assertEquals(FontStyle.Italic, result.spanStyles[0].item.fontStyle)
    }

    @Test
    fun overstrikeMapsToLineThrough() {
        val result = formattedBody("abc", listOf(FormatSpan("overstrike", 0, 3)))
        assertEquals(TextDecoration.LineThrough, result.spanStyles[0].item.textDecoration)
    }

    @Test
    fun monospaceMapsToMonospaceFamily() {
        val result = formattedBody("abc", listOf(FormatSpan("monospace", 0, 3)))
        assertEquals(FontFamily.Monospace, result.spanStyles[0].item.fontFamily)
    }

    @Test
    fun preformattedMapsToMonospaceFamily() {
        val result = formattedBody("abc", listOf(FormatSpan("preformatted", 0, 3)))
        assertEquals(FontFamily.Monospace, result.spanStyles[0].item.fontFamily)
    }

    // tacky wire: body="> What do you intent to build,\n..." fmt=[quote 0,30].
    // The quote covers exactly the first ("> "-prefixed) line, tinted + italic -
    // not a bright fill.
    @Test
    fun realQuoteLineIsMutedAndItalicOverFirstLineOnly() {
        val text = "> What do you intent to build,\n\"Always\" has always been here"
        val muted = Color(0xFF6E6E6E)
        val result = formattedBody(text, listOf(FormatSpan("quote", 0, 30)), quoteColor = muted)
        assertEquals(text, result.text)
        val quote = result.spanStyles.single()
        assertEquals(0, quote.start)
        assertEquals(30, quote.end)
        assertEquals(muted, quote.item.color)
        assertEquals(FontStyle.Italic, quote.item.fontStyle)
    }

    // tacky carries no link entity, so URLs are autolinked client-side. News-bot
    // form: "> Headline < https://host/path >" - the paired angle-bracket
    // delimiters are stripped so the trailing ">" doesn't read as litter, and
    // only the URL is linked.
    @Test
    fun urlInQuotedNewsLineIsLinkedAndBracketsStripped() {
        val text = "> In GCC approved adding backend for WebAssembly < https://opennet.dev/6569 >"
        val link = Color(0xFF1A73E8)
        val result = formattedBody(
            text,
            listOf(FormatSpan("quote", 0, text.length)),
            linkColor = link,
        )
        val url = "https://opennet.dev/6569"

        // The "< " and " >" wrappers are gone from the displayed text.
        assertEquals("> In GCC approved adding backend for WebAssembly $url", result.text)

        // A tappable link annotation over exactly the URL (in displayed coords).
        val urlStart = result.text.indexOf(url)
        val links = result.getLinkAnnotations(0, result.length)
        assertEquals(1, links.size)
        assertEquals(urlStart, links[0].start)
        assertEquals(urlStart + url.length, links[0].end)
        assertEquals(url, (links[0].item as LinkAnnotation.Url).url)

        // The URL range is highlighted (link colour + underline).
        val linkStyle = result.spanStyles.single {
            it.start == urlStart && it.item.color == link
        }
        assertEquals(TextDecoration.Underline, linkStyle.item.textDecoration)
    }

    // Lone, unpaired brackets are real text, not delimiters - keep them.
    @Test
    fun unpairedBracketAroundUrlIsKept() {
        val text = "see <https://example.org/x for details"
        val result = formattedBody(text, emptyList())
        assertEquals(text, result.text)
        assertEquals(
            "https://example.org/x",
            (result.getLinkAnnotations(0, result.length).single().item as LinkAnnotation.Url).url,
        )
    }

    // tacky emits overlapping styles as one compound entity ("bold.italic",
    // mirroring messagestyling::ResolveEntities); each part must apply.
    @Test
    fun compoundTypeAppliesEveryStyle() {
        val text = "bold and italic"
        val result = formattedBody(text, listOf(FormatSpan("bold.italic", 0, text.length)))
        assertEquals(2, result.spanStyles.size)
        assertNotNull(result.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.Bold })
        assertNotNull(result.spanStyles.firstOrNull { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun bareUrlWithoutSpansIsLinked() {
        val text = "see https://example.org/x now"
        val result = formattedBody(text, emptyList())
        val links = result.getLinkAnnotations(0, result.length)
        assertEquals(1, links.size)
        assertEquals("https://example.org/x", (links[0].item as LinkAnnotation.Url).url)
    }

    @Test
    fun nonUrlTextHasNoLink() {
        val result = formattedBody("just plain talk", emptyList())
        assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun overlappingSpansBothAppear() {
        val text = "hello"
        val result = formattedBody(
            text,
            listOf(FormatSpan("bold", 0, 5), FormatSpan("italic", 0, 5)),
        )
        assertEquals(2, result.spanStyles.size)
        assertNotNull(result.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.Bold })
        assertNotNull(result.spanStyles.firstOrNull { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun outOfRangeSpanIsClampedNotThrown() {
        val text = "hello"
        val result = formattedBody(text, listOf(FormatSpan("bold", 3, 100)))
        assertEquals(text, result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(3, result.spanStyles[0].start)
        assertEquals(text.length, result.spanStyles[0].end)
    }

    @Test
    fun fullyInvalidSpanIsSkipped() {
        val text = "hello"
        val result = formattedBody(text, listOf(FormatSpan("bold", 50, 5)))
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun unknownTypeIsSkipped() {
        val result = formattedBody("hello", listOf(FormatSpan("blink", 0, 5)))
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun emptySpansListYieldsPlainText() {
        val text = "hello"
        val result = formattedBody(text, emptyList())
        assertEquals(text, result.text)
        assertTrue(result.spanStyles.isEmpty())
        assertNull(result.getLinkAnnotations(0, result.length).firstOrNull())
    }
}
