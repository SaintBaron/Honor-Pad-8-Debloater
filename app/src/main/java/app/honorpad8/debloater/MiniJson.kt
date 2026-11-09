package app.honorpad8.debloater

/**
 * A small, strict JSON reader for the two release feeds the app reads (F-Droid's and GitHub's).
 * Objects become Map<String, Any?>, arrays List<Any?>, numbers Long or Double. Anything malformed
 * throws, so a broken or hostile response is rejected instead of half-read.
 */
object MiniJson {

    class JsonError(msg: String) : Exception(msg)

    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value(0)
        p.skipWs()
        if (p.i != text.length) throw JsonError("unexpected data after the JSON value")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonError("nested too deeply")
            skipWs()
            if (i >= s.length) throw JsonError("unexpected end")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw JsonError("bad literal at $i")
            i += w.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw JsonError("expected a key at $i")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw JsonError("expected ':' at $i")
                i++
                m[k] = value(depth + 1)
                skipWs()
                if (i >= s.length) throw JsonError("unexpected end in object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw JsonError("expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l += value(depth + 1)
                skipWs()
                if (i >= s.length) throw JsonError("unexpected end in array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw JsonError("expected ',' or ']' at $i")
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonError("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonError("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonError("bad \\u escape")
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw JsonError("bad \\u escape"))
                                i += 4
                            }
                            else -> throw JsonError("bad escape \\$e")
                        }
                    }
                    c < ' ' -> throw JsonError("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            if (t.isEmpty() || t == "-") throw JsonError("bad value at $start")
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw JsonError("bad number at $start")
        }
    }
}
