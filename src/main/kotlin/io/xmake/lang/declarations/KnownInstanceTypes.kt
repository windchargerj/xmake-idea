package io.xmake.lang.declarations

/**
 * Known xmake instance type names used for type inference.
 *
 * This set is derived from `xmake show -l apis` script instance API prefixes.
 * Domain keywords that do not appear in that surface are not treated as proven
 * receiver types here.
 */
object KnownInstanceTypes {
    fun all(api: XMakeApi): Set<String> = api.instanceTypes()

    fun contains(name: String, api: XMakeApi): Boolean = name in all(api)
}
