package com.sabihon.todo

import com.sabihon.todo.data.ai.ClaudeMessages
import com.sabihon.todo.data.ai.ClaudeMessages.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Anthropic request/response shape.
 *
 * The request body is assembled by hand rather than by a serializer, so these tests are
 * the compiler for that string: a [TinyJson] reader is defined at the bottom and every
 * generated body is parsed with it, which fails loudly if an escape is wrong instead of
 * letting malformed JSON reach the server as a 400 that looks like a network problem.
 */
class ClaudeMessagesTest {

    /* ------------------------------ request body ------------------------------ */

    @Test
    fun `a plain prompt produces the documented shape`() {
        val body = ClaudeMessages.requestBody(
            model = "claude-haiku-5-5",
            prompt = "Suggest one task.",
            maxTokens = 512
        )
        val root = TinyJson.parse(body).obj()

        assertEquals("claude-haiku-5-5", root.str("model"))
        assertEquals(512, root.num("max_tokens"))
        assertEquals(ClaudeMessages.SYSTEM_PREAMBLE, root.str("system"))

        val messages = root.arr("messages")
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].obj().str("role"))
        assertEquals("Suggest one task.", messages[0].obj().str("content"))
    }

    @Test
    fun `a blank model falls back to the default and the budget is clamped`() {
        val blank = TinyJson.parse(ClaudeMessages.requestBody("   ", "x")).obj()
        assertEquals(ClaudeMessages.DEFAULT_MODEL, blank.str("model"))

        assertEquals(64, TinyJson.parse(ClaudeMessages.requestBody("m", "x", 1)).obj().num("max_tokens"))
        assertEquals(8_192, TinyJson.parse(ClaudeMessages.requestBody("m", "x", 999_999)).obj().num("max_tokens"))
        assertEquals(ClaudeMessages.MAX_TOKENS, TinyJson.parse(ClaudeMessages.requestBody("m", "x")).obj().num("max_tokens"))
    }

    /**
     * The reason the body is escaped at all: task titles are user text and regularly
     * contain quotes ("Call mum about \"the thing\""), backslashes and newlines.
     */
    @Test
    fun `quotes, backslashes and newlines survive a round trip`() {
        val nasty = "Call \"mum\" about C:\\temp\nthen\n\tstop"
        val root = TinyJson.parse(ClaudeMessages.requestBody("m", nasty)).obj()
        assertEquals(nasty, root.arr("messages")[0].obj().str("content"))
    }

    @Test
    fun `control characters are escaped rather than emitted raw`() {
        val withControls = "bell:\u0007 backspace:\b formfeed:\u000C nul:\u0000 end"
        val body = ClaudeMessages.requestBody("m", withControls)

        // Raw control characters would make the document invalid JSON.
        assertFalse(body.contains('\u0007'))
        assertFalse(body.contains('\u0000'))

        val root = TinyJson.parse(body).obj()
        assertEquals(withControls, root.arr("messages")[0].obj().str("content"))
    }

    @Test
    fun `emoji and accents pass through unchanged`() {
        val text = "Réunion crème brûlée 🎯 完了"
        val root = TinyJson.parse(ClaudeMessages.requestBody("m", text)).obj()
        assertEquals(text, root.arr("messages")[0].obj().str("content"))
    }

    /**
     * A lone surrogate cannot be encoded as UTF-8, so sending one would throw inside the
     * HTTP layer. Each unpaired half becomes U+FFFD; a proper pair is left alone.
     */
    @Test
    fun `a lone surrogate is replaced but a valid pair survives`() {
        val loneHigh = "a" + '\uD83C' + "b"          // no low half
        val loneLow = "a" + '\uDFAF' + "b"           // no high half
        val pair = "a" + "\uD83C\uDFAF" + "b"        // 🎯

        assertEquals("a\uFFFDb", ClaudeMessages.escape(loneHigh))
        assertEquals("a\uFFFDb", ClaudeMessages.escape(loneLow))
        assertEquals(pair, ClaudeMessages.escape(pair))
    }

    @Test
    fun `headers carry the key and the pinned api version`() {
        val headers = ClaudeMessages.headers("  sk-ant-abc  ")
        assertEquals("sk-ant-abc", headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        assertEquals("application/json; charset=UTF-8", headers["content-type"])
    }

    /* ------------------------------ model candidates ------------------------------ */

    @Test
    fun `an override leads, then the defaults, without duplicates`() {
        val candidates = ClaudeMessages.modelCandidates("claude-sonnet-5-5")
        assertEquals("claude-sonnet-5-5", candidates.first())
        assertEquals(candidates.distinct(), candidates)
        assertTrue(ClaudeMessages.DEFAULT_MODEL in candidates)
    }

    @Test
    fun `a blank or absurd override is ignored`() {
        assertFalse("" in ClaudeMessages.modelCandidates(""))
        assertFalse("   " in ClaudeMessages.modelCandidates("   "))
        assertEquals(
            ClaudeMessages.modelCandidates(null),
            ClaudeMessages.modelCandidates("x".repeat(ClaudeMessages.MAX_MODEL_ID_CHARS + 1))
        )
        assertEquals(ClaudeMessages.DEFAULT_MODEL, ClaudeMessages.modelCandidates(null).first())
    }

    /* ------------------------------ response parsing ------------------------------ */

    /**
     * The 5.x generation returns thinking blocks in the same array as the answer. If they
     * were concatenated, every JSON parse would fail on reasoning prose.
     */
    @Test
    fun `thinking blocks are ignored and only text blocks are returned`() {
        val blocks = listOf(
            Block("thinking", "Let me consider the user's list and pick something sensible."),
            Block("text", "{\"steps\":[\"One\"]}"),
            Block("redacted_thinking", null)
        )
        assertEquals("{\"steps\":[\"One\"]}", ClaudeMessages.textFromBlocks(blocks))
    }

    @Test
    fun `multiple text blocks are joined and trimmed`() {
        val blocks = listOf(Block("text", "  {\"a\":1}"), Block("text", "  "))
        assertEquals("{\"a\":1}", ClaudeMessages.textFromBlocks(blocks))
        assertNull(ClaudeMessages.textFromBlocks(emptyList()))
        assertNull(ClaudeMessages.textFromBlocks(listOf(Block("thinking", "only thinking"))))
    }

    @Test
    fun `json is found inside fences and prose`() {
        val fenced = "```json\n{\"steps\":[\"a\",\"b\"]}\n```"
        val chatty = "Sure, here is the plan:\n{\"steps\":[\"a\"]}\nHope that helps!"
        val nested = "{\"a\":{\"b\":\"}\"}}"

        assertEquals("{\"steps\":[\"a\",\"b\"]}", ClaudeMessages.extractJsonObject(fenced))
        assertEquals("{\"steps\":[\"a\"]}", ClaudeMessages.extractJsonObject(chatty))
        assertEquals(
            "a brace inside a string does not end the object",
            nested,
            ClaudeMessages.extractJsonObject(nested)
        )
    }

    /**
     * The old helper took everything from the first `{` to the last `}`, which silently
     * produced an unparseable slice when the answer was cut off by `max_tokens`.
     */
    @Test
    fun `truncated output yields null rather than a broken slice`() {
        assertNull(ClaudeMessages.extractJsonObject("{\"steps\":[\"a\",\"b"))
        assertNull(ClaudeMessages.extractJsonObject("no json at all"))
        assertNull(ClaudeMessages.extractJsonObject(null))
        assertNull(ClaudeMessages.extractJsonObject("   "))
    }

    /**
     * The other half of that rule: a complete object followed by junk still yields the
     * object, because the first complete value is the answer and the rest is the model
     * talking to itself.
     */
    @Test
    fun `a complete object wins even when more text follows`() {
        assertEquals(
            "{\"a\":1}",
            ClaudeMessages.extractJsonObject("{\"a\":1} trailing {\"b\":2")
        )
        assertEquals(
            "{\"steps\":[\"a\"]}",
            ClaudeMessages.extractJsonObject("{\"steps\":[\"a\"]}\n\nLet me know if you need more.")
        )
    }

    @Test
    fun `only max_tokens counts as truncation`() {
        assertTrue(ClaudeMessages.isTruncated("max_tokens"))
        assertFalse(ClaudeMessages.isTruncated("end_turn"))
        assertFalse(ClaudeMessages.isTruncated(null))
    }

    @Test
    fun `a retired model is retryable but a bad key is not`() {
        assertTrue(ClaudeMessages.isRetryableModelError(404, ""))
        assertTrue(ClaudeMessages.isRetryableModelError(400, "{\"type\":\"not_found_error\",\"message\":\"model\"}"))
        assertFalse("a bad key must not spend four requests", ClaudeMessages.isRetryableModelError(401, "invalid x-api-key"))
        assertFalse(ClaudeMessages.isRetryableModelError(429, "rate limited"))

        assertTrue(ClaudeMessages.isAuthError(401))
        assertTrue(ClaudeMessages.isAuthError(403))
        assertFalse(ClaudeMessages.isAuthError(500))
    }

    @Test
    fun `errors say what the user can do about them`() {
        assertTrue(ClaudeMessages.friendlyError(401, "").contains("Admin > AI & Config"))
        assertTrue(ClaudeMessages.friendlyError(404, "").contains("model id"))
        assertTrue(ClaudeMessages.friendlyError(429, "").contains("limit"))
        assertTrue(ClaudeMessages.friendlyError(503, "").contains("unavailable"))
        assertTrue(ClaudeMessages.friendlyError(400, "bad json").contains("bad json"))
        assertFalse(
            "an error message must not paste an entire HTML error page into the snackbar",
            ClaudeMessages.friendlyError(400, "x".repeat(500)).length > 260
        )
    }
}

/**
 * A minimal JSON reader used only by these tests.
 *
 * Its job is to *reject* malformed output from [ClaudeMessages.requestBody], so it is
 * strict where it matters: unescaped control characters, unterminated strings and trailing
 * garbage all throw.
 */
internal object TinyJson {

    fun parse(text: String): Node = Reader(text).readDocument()

    sealed interface Node {
        fun obj(): Obj
        fun str(): String
        fun num(): Int
        fun arr(): List<Node>
    }

    class Obj(private val map: Map<String, Node>) : Node {
        fun str(key: String): String = map[key]?.str() ?: error("missing string '$key'")
        fun num(key: String): Int = map[key]?.num() ?: error("missing number '$key'")
        fun arr(key: String): List<Node> = map[key]?.arr() ?: error("missing array '$key'")
        fun has(key: String): Boolean = map.containsKey(key)
        override fun obj(): Obj = this
        override fun str(): String = error("object is not a string")
        override fun num(): Int = error("object is not a number")
        override fun arr(): List<Node> = error("object is not an array")
    }

    class Arr(private val items: List<Node>) : Node {
        val size get() = items.size
        operator fun get(index: Int): Node = items[index]
        override fun obj(): Obj = error("array is not an object")
        override fun str(): String = error("array is not a string")
        override fun num(): Int = error("array is not a number")
        override fun arr(): List<Node> = items
    }

    class Str(val value: String) : Node {
        override fun obj(): Obj = error("string is not an object")
        override fun str(): String = value
        override fun num(): Int = value.toIntOrNull() ?: error("'$value' is not a number")
        override fun arr(): List<Node> = error("string is not an array")
    }

    class Num(val value: Int) : Node {
        override fun obj(): Obj = error("number is not an object")
        override fun str(): String = value.toString()
        override fun num(): Int = value
        override fun arr(): List<Node> = error("number is not an array")
    }

    private class Reader(private val src: String) {
        private var pos = 0

        fun readDocument(): Node {
            val node = readValue()
            skipWhitespace()
            if (pos != src.length) error("trailing garbage at $pos: '${src.substring(pos).take(20)}'")
            return node
        }

        private fun readValue(): Node {
            skipWhitespace()
            if (pos >= src.length) error("unexpected end of input")
            return when (val ch = src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> Str(readString())
                't' -> readLiteral("true", Num(1))
                'f' -> readLiteral("false", Num(0))
                'n' -> readLiteral("null", Num(0))
                else -> if (ch == '-' || ch.isDigit()) readNumber() else error("unexpected '$ch' at $pos")
            }
        }

        private fun readLiteral(literal: String, node: Node): Node {
            if (!src.startsWith(literal, pos)) error("expected $literal at $pos")
            pos += literal.length
            return node
        }

        private fun readObject(): Obj {
            expect('{')
            val map = LinkedHashMap<String, Node>()
            skipWhitespace()
            if (peek() == '}') { pos++; return Obj(map) }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                map[key] = readValue()
                skipWhitespace()
                when (val ch = next()) {
                    ',' -> continue
                    '}' -> return Obj(map)
                    else -> error("expected ',' or '}' at ${pos - 1}, found '$ch'")
                }
            }
        }

        private fun readArray(): Arr {
            expect('[')
            val items = mutableListOf<Node>()
            skipWhitespace()
            if (peek() == ']') { pos++; return Arr(items) }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (val ch = next()) {
                    ',' -> continue
                    ']' -> return Arr(items)
                    else -> error("expected ',' or ']' at ${pos - 1}, found '$ch'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (pos >= src.length) error("unterminated string")
                when (val ch = src[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= src.length) error("unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) error("short \\u escape")
                                val hex = src.substring(pos, pos + 4)
                                pos += 4
                                out.append(hex.toIntOrNull(16)?.toChar() ?: error("bad \\u$hex"))
                            }
                            else -> error("unknown escape '\\$esc'")
                        }
                    }
                    // A raw control character means the escaper missed a case. This is the
                    // assertion that makes the whole approach safe.
                    else -> if (ch.code < 0x20) error("raw control character 0x${ch.code.toString(16)}") else out.append(ch)
                }
            }
        }

        private fun readNumber(): Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            if (pos == start) error("expected a number at $start")
            return Num(src.substring(start, pos).toIntOrNull() ?: error("bad number at $start"))
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? = src.getOrNull(pos)

        private fun next(): Char {
            if (pos >= src.length) error("unexpected end of input")
            return src[pos++]
        }

        private fun expect(ch: Char) {
            if (next() != ch) error("expected '$ch' at ${pos - 1}")
        }
    }
}
