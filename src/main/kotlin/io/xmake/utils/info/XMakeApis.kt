package io.xmake.utils.info

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class XMakeApis(
    @SerialName("description_builtin_apis")
    val descriptionBuiltinApis: Apis = emptyList(),
    @SerialName("description_builtin_module_apis")
    val descriptionBuiltinModuleApis: Apis = emptyList(),
    @SerialName("script_instance_apis")
    val scriptInstanceApis: Apis = emptyList(),
    @SerialName("script_extension_module_apis")
    val scriptExtensionModuleApis: Apis = emptyList(),
    @SerialName("description_scope_apis")
    val descriptionScopeApis: Apis = emptyList(),
    @SerialName("script_builtin_apis")
    val scriptBuiltinApis: Apis = emptyList(),
    @SerialName("script_builtin_module_apis")
    val scriptBuiltinModuleApis: Apis = emptyList(),
)

typealias Apis = List<String>