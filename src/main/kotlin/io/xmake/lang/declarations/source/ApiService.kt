package io.xmake.lang.declarations.source

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import io.xmake.utils.info.XMakeApis
import io.xmake.utils.info.XMakeInfoManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service(Service.Level.PROJECT)
class ApiService(private val project: Project) {

    private val stateLock = ReentrantLock()
    private val apiModificationTracker = SimpleModificationTracker()

    @Volatile
    private var _apis: XMakeApis? = null

    @Volatile
    private var _apiCount: Int = -1

    val modificationTracker: ModificationTracker
        get() = apiModificationTracker

    fun getApis(): XMakeApis =
        _apis ?: stateLock.withLock {
            _apis ?: loadApis().also { _apis = it }
        }

    fun getApiCount(): Int =
        _apiCount.takeIf { it >= 0 } ?: computeApiCount(getApis()).also { _apiCount = it }

    private fun computeApiCount(apis: XMakeApis): Int =
        apis.descriptionBuiltinApis.size +
            apis.descriptionBuiltinModuleApis.size +
            apis.scriptInstanceApis.size +
            apis.scriptExtensionModuleApis.size +
            apis.descriptionScopeApis.size +
            apis.scriptBuiltinApis.size +
            apis.scriptBuiltinModuleApis.size

    fun isLoaded(): Boolean = _apis != null

    private fun loadApis(): XMakeApis {
        return XMakeInfoManager.getInstance(project).xmakeInfo.apis
    }

    fun reload() {
        stateLock.withLock {
            val apis = loadApis()
            _apis = apis
            _apiCount = computeApiCount(apis)
            apiModificationTracker.incModificationCount()
        }
    }

    fun reset() {
        stateLock.withLock {
            _apis = null
            _apiCount = -1
            apiModificationTracker.incModificationCount()
        }
    }

    companion object {
        fun getInstance(project: Project): ApiService = project.service()

        fun getInstanceOrNull(project: Project): ApiService? = project.serviceOrNull()
    }
}
