package io.xmake.lang.declarations.import

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.psi.XMakeLuaFile
import java.util.concurrent.ConcurrentHashMap

/**
 * File-level cache for import declaration collections and position-aware views.
 *
 * This cache centralizes declaration collection per PSI scan root, then filters
 * those cached facts into a position-aware [ImportLookupView] without
 * rescanning PSI from scratch on every call site.
 */
internal class ImportLookupCache private constructor(
    private val project: Project,
    private val file: XMakeLuaFile,
    private val moduleCollectionsByRoot: Map<ImportScanRootKey, ImportDeclarationCollector.CollectionResult>,
    private val addImportCollection: ImportDeclarationCollector.CollectionResult,
    private val scriptDirectoryByRoot: Map<ImportScanRootKey, VirtualFile?>
) {
    private val lookupViewCache = ConcurrentHashMap<LookupKey, ImportLookupView>()

    fun viewAt(place: PsiElement? = null): ImportLookupView {
        val mappedPlace = ImportLookupAnchorResolver.mapToFile(file, place)
        val anchor = mappedPlace?.let(ImportLookupAnchorResolver::anchor) ?: file
        val importScanRoot = ImportCallParser.resolveImportScanRoot(anchor)
        val importScanRootKey = ImportScanRootKey.from(importScanRoot)
        val lookupOffset = mappedPlace?.textOffset
        val lookupKey = LookupKey(
            importScanRootKey = importScanRootKey,
            lookupOffset = lookupOffset,
            unfiltered = mappedPlace == null || mappedPlace is XMakeLuaFile
        )

        return lookupViewCache.computeIfAbsent(lookupKey) {
            computeViewAt(
                importScanRootKey = importScanRootKey,
                place = mappedPlace,
                lookupOffset = lookupOffset ?: anchor.textOffset
            )
        }
    }

    private fun computeViewAt(
        importScanRootKey: ImportScanRootKey,
        place: PsiElement?,
        lookupOffset: Int
    ): ImportLookupView {
        val moduleCollections = moduleCollectionsFor(importScanRootKey, place, lookupOffset)
        val declarations = (moduleCollections.flatMap { it.declarations } + addImportCollection.declarations)
            .distinctBy { declaration ->
                listOf(
                    declaration::class.qualifiedName.orEmpty(),
                    declaration.modulePath,
                    declaration.declarationElement?.textRange?.startOffset ?: -1
                )
            }
        val reachableDeclarations = ImportDeclarationCollector.filterReachableDeclarations(
            declarations = declarations,
            place = place,
            lookupOffset = lookupOffset
        )
        val scriptDirectory = scriptDirectoryByRoot[importScanRootKey]

        return ImportLookupView(
            project = project,
            api = project.xmakeApi,
            declarations = reachableDeclarations,
            initialIssues = moduleCollections.flatMap { it.issues } + addImportCollection.issues,
            scriptDirectory = scriptDirectory
        )
    }

    private fun moduleCollectionsFor(
        importScanRootKey: ImportScanRootKey,
        place: PsiElement?,
        lookupOffset: Int
    ): List<ImportDeclarationCollector.CollectionResult> {
        if (place == null || place is XMakeLuaFile) {
            return listOf(moduleCollectionsByRoot[importScanRootKey] ?: ImportDeclarationCollector.CollectionResult(emptyList()))
        }
        return moduleCollectionsByRoot
            .filterKeys { key -> key.contains(lookupOffset) }
            .values
            .toList()
            .ifEmpty { listOf(ImportDeclarationCollector.CollectionResult(emptyList())) }
    }

    private data class ImportScanRootKey(
        val startOffset: Int,
        val endOffset: Int,
        val isFileRoot: Boolean
    ) {
        fun contains(offset: Int): Boolean =
            offset in startOffset..endOffset

        companion object {
            fun from(root: PsiElement): ImportScanRootKey =
                ImportScanRootKey(
                    startOffset = root.textRange.startOffset,
                    endOffset = root.textRange.endOffset,
                    isFileRoot = root is XMakeLuaFile
                )
        }
    }

    private data class LookupKey(
        val importScanRootKey: ImportScanRootKey,
        val lookupOffset: Int?,
        val unfiltered: Boolean
    )

    companion object {
        fun forFile(project: Project, file: XMakeLuaFile): ImportLookupCache =
            ReadAction.compute<ImportLookupCache, RuntimeException> {
                CachedValuesManager.getCachedValue(file) {
                    CachedValueProvider.Result.create(
                        collectForFile(project, file),
                        file
                    )
                }
            }

        private fun collectForFile(project: Project, file: XMakeLuaFile): ImportLookupCache {
            val importScanRoots = buildList {
                add(file as PsiElement)
                addAll(XMakeScopeQuery.model(file).scriptSearchRoots.mapNotNull { root -> root.resolve() })
            }
                .distinctBy { root -> ImportScanRootKey.from(root) }

            val moduleCollectionsByRoot = importScanRoots.associate { root ->
                ImportScanRootKey.from(root) to ImportDeclarationCollector.collectModuleDeclarations(root)
            }
            val scriptDirectoryByRoot = importScanRoots.associate { root ->
                ImportScanRootKey.from(root) to ImportModuleFileResolver.scriptDirectoryOf(root)
            }
            return ImportLookupCache(
                project = project,
                file = file,
                moduleCollectionsByRoot = moduleCollectionsByRoot,
                addImportCollection = ImportDeclarationCollector.collectAddImportDeclarations(project, file),
                scriptDirectoryByRoot = scriptDirectoryByRoot
            )
        }
    }
}
