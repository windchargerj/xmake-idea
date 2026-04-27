package io.xmake.lang.declarations.import

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

object ImportModuleFileResolver {

    enum class ModuleFileSource {
        LOCAL,
        XMAKE
    }

    enum class ModuleFileKind {
        LUA_FILE,
        LUA_DIRECTORY,
        NATIVE_BINARY,
        NATIVE_SHARED
    }

    enum class ModuleSearchRootSource {
        SCRIPT_DIRECTORY,
        ROOTDIR_ABSOLUTE,
        ROOTDIR_RELATIVE_VIRTUAL,
        ROOTDIR_RELATIVE_LOCAL_PATH,
        XMAKE_ENV_MODULES,
        XMAKE_GLOBAL_MODULES,
        XMAKE_MODULES,
        XMAKE_CORE_IMPORT_MODULES
    }

    data class ResolvedModuleFile(
        val file: VirtualFile,
        val kind: ModuleFileKind,
        val source: ModuleFileSource,
        val searchRootSource: ModuleSearchRootSource
    )

    private data class ModuleSearchRoot(
        val virtualFile: VirtualFile? = null,
        val localPath: String? = null,
        val source: ModuleSearchRootSource
    ) {
        val identity: String
            get() = virtualFile?.url ?: localPath.orEmpty()
    }

    private data class ModuleFileCandidate(
        val file: VirtualFile,
        val kind: ModuleFileKind
    )

    fun scriptDirectoryOf(anchor: PsiElement?): VirtualFile? =
        anchor?.containingFile?.virtualFile?.parent
            ?: anchor?.containingFile?.originalFile?.virtualFile?.parent

    fun currentFileStemOf(anchor: PsiElement?): String? =
        anchor?.containingFile?.virtualFile?.nameWithoutExtension
            ?: anchor?.containingFile?.originalFile?.virtualFile?.nameWithoutExtension

    fun resolveModuleFile(
        scriptDirectory: VirtualFile?,
        modulePath: String,
        rootDir: String? = null,
        noLocal: Boolean = false
    ): ResolvedModuleFile? {
        val moduleSubpath = moduleSubpath(modulePath)

        localSearchRoots(scriptDirectory, rootDir, noLocal).forEach { root ->
            val candidate = findModule(root, moduleSubpath)
            if (candidate != null) {
                return ResolvedModuleFile(candidate.file, candidate.kind, ModuleFileSource.LOCAL, root.source)
            }
        }

        xmakeSearchRoots().forEach { root ->
            val candidate = findModule(root, moduleSubpath)
            if (candidate != null) {
                return ResolvedModuleFile(candidate.file, candidate.kind, ModuleFileSource.XMAKE, root.source)
            }
        }

        return null
    }

    fun childModules(
        scriptDirectory: VirtualFile?,
        currentFileStem: String?,
        parentPath: String,
        rootDir: String? = null,
        noLocal: Boolean = false,
        extensionChildModules: Collection<String> = emptyList()
    ): List<String> {
        val localChildren =
            localSearchRoots(scriptDirectory, rootDir, noLocal)
                .flatMap { root -> collectChildModules(root, parentPath, currentFileStem) }

        return (localChildren + extensionChildModules)
            .distinct()
            .sorted()
    }

    private fun localSearchRoots(
        scriptDirectory: VirtualFile?,
        rootDir: String?,
        noLocal: Boolean
    ): List<ModuleSearchRoot> {
        if (noLocal) {
            return emptyList()
        }
        val roots = linkedMapOf<String, ModuleSearchRoot>()
        if (rootDir.isNullOrBlank()) {
            addRoot(roots, scriptDirectory?.let {
                ModuleSearchRoot(virtualFile = it, source = ModuleSearchRootSource.SCRIPT_DIRECTORY)
            })
        } else {
            addRoot(roots, resolveRootDir(scriptDirectory, rootDir))
        }
        return roots.values.toList()
    }

    private fun xmakeSearchRoots(): List<ModuleSearchRoot> = buildList {
        val xmakeDir = findXMakeDirectory() ?: return@buildList
        System.getenv("XMAKE_MODULES_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizePath)
            ?.let { addLocalPathRoot(it, ModuleSearchRootSource.XMAKE_ENV_MODULES) }
        findGlobalXMakeDirectory()
            ?.let { pathOf(it, "modules") }
            ?.let { addLocalPathRoot(it, ModuleSearchRootSource.XMAKE_GLOBAL_MODULES) }
        addLocalPathRoot(pathOf(xmakeDir, "modules"), ModuleSearchRootSource.XMAKE_MODULES)
        addLocalPathRoot(pathOf(xmakeDir, "core/sandbox/modules/import"), ModuleSearchRootSource.XMAKE_CORE_IMPORT_MODULES)
    }

    internal fun xmakeSearchRootsForTests(): List<String> =
        xmakeSearchRoots().mapNotNull { it.localPath }

    private fun resolveRootDir(
        scriptDirectory: VirtualFile?,
        rootDir: String?
    ): ModuleSearchRoot? {
        if (rootDir.isNullOrBlank()) {
            return null
        }
        if (File(rootDir).isAbsolute) {
            val normalized = normalizePath(rootDir)
            return ModuleSearchRoot(
                virtualFile = findFile(normalized),
                localPath = normalized,
                source = ModuleSearchRootSource.ROOTDIR_ABSOLUTE
            )
        }

        scriptDirectory?.let { directory ->
            VfsUtilCore.findRelativeFile(rootDir, directory)?.let { resolved ->
                return ModuleSearchRoot(
                    virtualFile = resolved,
                    source = ModuleSearchRootSource.ROOTDIR_RELATIVE_VIRTUAL
                )
            }
            if (directory.fileSystem is LocalFileSystem) {
                return ModuleSearchRoot(
                    localPath = pathOf(directory.path, rootDir),
                    source = ModuleSearchRootSource.ROOTDIR_RELATIVE_LOCAL_PATH
                )
            }
        }
        return null
    }

    private fun collectChildModules(
        root: ModuleSearchRoot,
        parentPath: String,
        currentFileStem: String?
    ): List<String> =
        root.virtualFile?.let { collectChildModules(it, parentPath, currentFileStem) }
            ?: root.localPath?.let { collectLocalChildModules(it, parentPath, currentFileStem) }
            ?: emptyList()

    private fun collectChildModules(
        root: VirtualFile,
        parentPath: String,
        currentFileStem: String?
    ): List<String> {
        val parentDir = if (parentPath.isBlank()) {
            root
        } else {
            root.findFileByRelativePath(parentPath.replace('.', '/'))
        }
        if (parentDir == null || !parentDir.isDirectory) {
            return emptyList()
        }

        return collectChildModuleNames(
            children = parentDir.children.asIterable(),
            parentPath = parentPath,
            currentFileStem = currentFileStem,
            directoryName = { child -> if (child.isDirectory) child.name else null },
            luaFileStem = { child ->
                child
                    .takeUnless { it.isDirectory }
                    ?.takeIf { it.extension.equals("lua", ignoreCase = true) }
                    ?.nameWithoutExtension
            }
        )
    }

    private fun collectLocalChildModules(
        root: String,
        parentPath: String,
        currentFileStem: String?
    ): List<String> {
        val parentDir = if (parentPath.isBlank()) {
            File(root)
        } else {
            File(root, parentPath.replace('.', File.separatorChar))
        }
        if (!parentDir.isDirectory) {
            return emptyList()
        }

        return collectChildModuleNames(
            children = parentDir.listFiles().orEmpty().asIterable(),
            parentPath = parentPath,
            currentFileStem = currentFileStem,
            directoryName = { child -> if (child.isDirectory) child.name else null },
            luaFileStem = { child ->
                child
                    .takeIf { it.isFile }
                    ?.takeIf { it.extension.equals("lua", ignoreCase = true) }
                    ?.nameWithoutExtension
            }
        )
    }

    private inline fun <T> collectChildModuleNames(
        children: Iterable<T>,
        parentPath: String,
        currentFileStem: String?,
        directoryName: (T) -> String?,
        luaFileStem: (T) -> String?
    ): List<String> {
        val excludeCurrentFileStem = parentPath.isBlank()
        return children
            .mapNotNull { child ->
                directoryName(child) ?: luaFileStem(child)?.takeUnless {
                    excludeCurrentFileStem && it == currentFileStem
                }
            }
            .distinct()
            .sorted()
    }

    private fun findModule(root: ModuleSearchRoot, moduleSubpath: String): ModuleFileCandidate? {
        root.virtualFile?.let { findVirtualModule(it, moduleSubpath)?.let { candidate -> return candidate } }
        root.localPath?.let { findLocalModule(it, moduleSubpath)?.let { candidate -> return candidate } }
        return null
    }

    private fun findVirtualModule(root: VirtualFile, moduleSubpath: String): ModuleFileCandidate? {
        findRelativeVirtualFile(root, "$moduleSubpath.lua")
            ?.takeIf { it.exists() && !it.isDirectory }
            ?.let { return ModuleFileCandidate(it, ModuleFileKind.LUA_FILE) }

        val moduleDirectory = findRelativeVirtualFile(root, moduleSubpath)
            ?.takeIf { it.exists() && it.isDirectory }
            ?: return null
        val projectFile = moduleDirectory.findChild("xmake.lua")
            ?.takeIf { it.exists() && !it.isDirectory }
        if (projectFile != null) {
            return when (nativeModuleKind(projectFile)) {
                ModuleFileKind.NATIVE_BINARY -> ModuleFileCandidate(moduleDirectory, ModuleFileKind.NATIVE_BINARY)
                ModuleFileKind.NATIVE_SHARED -> ModuleFileCandidate(moduleDirectory, ModuleFileKind.NATIVE_SHARED)
                else -> null
            }
        }
        return ModuleFileCandidate(moduleDirectory, ModuleFileKind.LUA_DIRECTORY)
    }

    private fun findLocalModule(root: String, moduleSubpath: String): ModuleFileCandidate? {
        val moduleFullPath = pathOf(root, moduleSubpath)
        findFile("$moduleFullPath.lua")
            ?.takeIf { it.exists() && !it.isDirectory }
            ?.let { return ModuleFileCandidate(it, ModuleFileKind.LUA_FILE) }

        val moduleDirectoryFile = File(moduleFullPath)
        if (!moduleDirectoryFile.isDirectory) {
            return null
        }
        val moduleDirectory = findFile(normalizePath(moduleDirectoryFile.path)) ?: return null
        val projectFile = File(moduleDirectoryFile, "xmake.lua")
        if (projectFile.isFile) {
            return when (nativeModuleKind(projectFile)) {
                ModuleFileKind.NATIVE_BINARY -> ModuleFileCandidate(moduleDirectory, ModuleFileKind.NATIVE_BINARY)
                ModuleFileKind.NATIVE_SHARED -> ModuleFileCandidate(moduleDirectory, ModuleFileKind.NATIVE_SHARED)
                else -> null
            }
        }
        return ModuleFileCandidate(moduleDirectory, ModuleFileKind.LUA_DIRECTORY)
    }

    private fun findRelativeVirtualFile(root: VirtualFile, relativePath: String): VirtualFile? {
        var current: VirtualFile? = root
        relativePath.split('/', '\\').forEach { segment ->
            current = when (segment) {
                "", "." -> current
                ".." -> current?.parent
                else -> current?.findChild(segment)
            }
            if (current == null) {
                return null
            }
        }
        return current
    }

    private fun nativeModuleKind(projectFile: VirtualFile): ModuleFileKind? =
        runCatching {
            projectFile.inputStream.use { input -> nativeModuleKind(input.reader().readText()) }
        }.getOrNull()

    private fun nativeModuleKind(projectFile: File): ModuleFileKind? =
        runCatching { nativeModuleKind(projectFile.readText()) }.getOrNull()

    private fun nativeModuleKind(content: String): ModuleFileKind? {
        val kind = Regex("""add_rules\("module\.(binary|shared)"\)""")
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return when (kind) {
            "binary" -> ModuleFileKind.NATIVE_BINARY
            "shared" -> ModuleFileKind.NATIVE_SHARED
            else -> null
        }
    }

    private fun moduleSubpath(modulePath: String): String = buildString {
        var startDots = true
        modulePath.forEach { char ->
            when {
                char == '.' && startDots -> append("../")
                char == '.' -> append('/')
                else -> {
                    startDots = false
                    append(char)
                }
            }
        }
    }.trimEnd('/')

    private fun addRoot(roots: MutableMap<String, ModuleSearchRoot>, root: ModuleSearchRoot?) {
        if (root == null) {
            return
        }
        if (root.identity.isBlank()) {
            return
        }
        roots.putIfAbsent(root.identity, root)
    }

    private fun MutableList<ModuleSearchRoot>.addLocalPathRoot(path: String, source: ModuleSearchRootSource) {
        val normalized = normalizePath(path)
        if (File(normalized).isDirectory) {
            add(ModuleSearchRoot(localPath = normalized, source = source))
        }
    }

    private fun pathOf(root: String, relativePath: String): String =
        normalizePath(File(root, relativePath).path)

    private fun findFile(path: String): VirtualFile? =
        try {
            LocalFileSystem.getInstance().findFileByPath(path)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
        } catch (_: Exception) {
            null
        }

    private fun normalizePath(path: String): String =
        File(path).absoluteFile.normalize().path.replace('\\', '/')

    private fun findXMakeDirectory(): String? {
        return System.getenv("XMAKE_DIR")?.takeIf { it.isNotBlank() }
            ?: findXMakeInPath()
    }

    private fun findGlobalXMakeDirectory(): String? {
        System.getenv("XMAKE_GLOBALDIR")
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizePath)
            ?.let { return it }

        val appData = System.getenv("APPDATA")
        if (!appData.isNullOrBlank()) {
            val appDataDirectory = pathOf(appData, ".xmake")
            if (File(appDataDirectory).isDirectory) {
                return appDataDirectory
            }
        }

        val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
        return pathOf(userHome, ".xmake")
    }

    private fun findXMakeInPath(): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        val pathSeparator = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) ';' else ':'
        val xmakeName =
            if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "xmake.exe" else "xmake"

        return pathEnv.split(pathSeparator).firstNotNullOfOrNull { dir ->
            val candidate = File(dir, xmakeName)
            if (candidate.exists()) normalizePath(dir) else null
        }
    }
}
