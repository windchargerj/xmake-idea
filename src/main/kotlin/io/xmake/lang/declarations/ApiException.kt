package io.xmake.lang.declarations

sealed class ApiException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class ImportParseException(
        message: String,
        cause: Throwable? = null
    ) : ApiException("Import parse error: $message", cause)
}
