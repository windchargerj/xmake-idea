package io.xmake.lang.declarations

object ModulePath {

    fun parse(path: String): List<String> = path.split(".")

    fun join(segments: List<String>): String = segments.joinToString(".")

    fun parent(path: String): String? =
        path.substringBeforeLast('.', "").ifEmpty { null }

    fun basename(path: String): String = path.substringAfterLast('.')

    fun isNested(path: String): Boolean = path.contains('.')

    fun isPrefixOf(prefix: String, path: String): Boolean = path.startsWith("$prefix.")

    fun childModules(paths: Set<String>, parentPath: String): List<String> {
        val prefix = parentPath.normalizeModulePrefix()
        return paths.asSequence()
            .mapNotNull { module -> module.directChildName(prefix) }
            .distinct()
            .sorted()
            .toList()
    }

    fun extractModule(fullPath: String, moduleIdentifiers: Set<String>): String? {
        val segments = parse(fullPath)
        return (segments.size downTo 1)
            .asSequence()
            .map { join(segments.take(it)) }
            .firstOrNull(moduleIdentifiers::contains)
    }

    fun extractMemberPath(fullPath: String, moduleIdentifier: String): String =
        fullPath.takeIf { it.startsWith("$moduleIdentifier.") }
            ?.substring(moduleIdentifier.length + 1)
            .orEmpty()

    private fun String.normalizeModulePrefix(): String =
        when {
            isEmpty() -> ""
            endsWith('.') -> this
            else -> "$this."
        }

    private fun String.directChildName(prefix: String): String? {
        val module = when {
            prefix.isEmpty() -> this
            startsWith(prefix) -> removePrefix(prefix)
            else -> return null
        }
        return module.childSegment()
    }

    private fun String.childSegment(): String? =
        when (val dotIndex = indexOf('.')) {
            in 1..Int.MAX_VALUE -> substring(0, dotIndex)
            -1 -> takeIf(String::isNotEmpty)
            else -> null
        }
}


fun String.splitByDot(): Pair<String?, String> = splitAt(indexOf('.'))

fun String.splitByLastDot(): Pair<String?, String> = splitAt(lastIndexOf('.'))

fun String.splitByColon(): Pair<String?, String> = splitAt(indexOf(':'))

fun String.toModuleAndName(): Pair<String, String>? {
    val (module, name) = splitByDot()
    return module?.let { it to name }
}

private fun String.splitAt(index: Int): Pair<String?, String> =
    if (index > 0) substring(0, index) to substring(index + 1) else null to this
