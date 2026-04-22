package io.xmake.lang.declarations.import

data class ImportSpec(
    val modulePath: String,
    val alias: String? = null,
    val anonymous: Boolean = false,
    val inherit: Boolean = false,
    val tryImport: Boolean = false,
    val alwaysBuild: Boolean = false,
    val rootDir: String? = null
)
