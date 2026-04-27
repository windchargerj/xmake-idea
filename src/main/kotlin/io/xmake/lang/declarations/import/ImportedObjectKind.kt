package io.xmake.lang.declarations.import

enum class ImportedObjectKind {
    MODULE,
    DIRECTORY,
    NATIVE_BINARY,
    NATIVE_SHARED,
    CALLABLE
}

enum class ModuleMemberSurface {
    KNOWN,
    UNKNOWN
}

val ImportedObjectKind.isModuleLike: Boolean
    get() = this != ImportedObjectKind.CALLABLE
