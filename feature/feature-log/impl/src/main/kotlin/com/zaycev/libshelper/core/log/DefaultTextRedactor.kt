package com.zaycev.libshelper.core.log

object DefaultTextRedactor : TextRedactor {
    private val bearer = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]+""")
    private val authHeader = Regex("""(?i)(authorization\s*[:=]\s*)(?:(?:bearer|basic)\s+)?\S+""")
    private val cookie = Regex("""(?i)((?:set-cookie|cookie)\s*[:=]\s*)\S+""")
    private val userInfo = Regex("""(?i)([a-z][a-z0-9+.-]*://)(?:[^/\s:@]+:[^/\s@]+|[^/\s:@]+)@""")
    private val tokenParam = Regex("""(?i)((?:token|password|secret|api[_-]?key|access_token)\s*[:=]\s*)\S+""")

    override fun redact(text: String, home: String?): String {
        var value = text
        if (!home.isNullOrBlank()) {
            value = Regex(Regex.escape(home), RegexOption.IGNORE_CASE).replace(value, "~")
        }
        value = bearer.replace(value, "Bearer ***")
        value = authHeader.replace(value, "$1***")
        value = cookie.replace(value, "$1***")
        value = userInfo.replace(value, "$1***@")
        value = tokenParam.replace(value, "$1***")
        return value
    }

    fun redact(text: String): String = redact(text, System.getProperty("user.home"))
}
