package io.xmake.lang.declarations.import

/**
 * Ordered binding candidates for a visible import name.
 *
 * `primary` preserves the current compatibility behavior where the latest visible binding wins.
 * `shadowed` keeps older bindings that resolve to the same imported module as `primary`.
 * `conflicts` keeps older bindings for the same name that resolve to different modules.
 */
data class ImportBindingTargets(
    val primary: ImportBindingTarget,
    val shadowed: List<ImportBindingTarget> = emptyList(),
    val conflicts: List<ImportBindingTarget> = emptyList()
)
