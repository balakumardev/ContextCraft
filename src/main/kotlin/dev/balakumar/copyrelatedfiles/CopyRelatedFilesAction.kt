package dev.balakumar.copyrelatedfiles

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.impl.compiled.ClsClassImpl
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.intellij.psi.impl.java.stubs.impl.PsiJavaFileStubImpl
import javax.swing.JScrollPane
import javax.swing.JTextArea
import com.intellij.openapi.ui.ComboBox
import javax.swing.DefaultComboBoxModel
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.border.EmptyBorder
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ReadAction
import java.util.concurrent.atomic.AtomicInteger

class CopyRelatedFilesAction : AnAction() {
    private val LOG = Logger.getInstance(CopyRelatedFilesAction::class.java)

    // Constants for configuration
    private val DEFAULT_MAX_DEPTH = 1  // Reduced default depth
    private val DEFAULT_PACKAGE_SEGMENTS = 2
    private val DEFAULT_INCLUDE_IMPLEMENTATIONS = true
    private val DEFAULT_INCLUDE_JAVADOC = true
    private val DEFAULT_SMART_PRUNING = true
    private val DEFAULT_INCLUDE_DEPENDENCIES = false  // Default to false
    private val DEFAULT_INCLUDE_DECOMPILED = false    // Default to false
    private val DEFAULT_MAX_DECOMPILED_FILES = 10
    private val DEFAULT_SKIP_JAVA_PACKAGES = true
    private val DEFAULT_ONLY_DIRECT_REFERENCES = true // New option
    private val DEFAULT_RELATEDNESS_LEVEL = RelatedLevel.MEDIUM

    // Property keys for persistent settings
    private val PROPERTY_MAX_DEPTH = "copyRelatedFiles.maxDepth"
    private val PROPERTY_PACKAGE_SEGMENTS = "copyRelatedFiles.packageSegments"
    private val PROPERTY_INCLUDE_IMPLEMENTATIONS = "copyRelatedFiles.includeImplementations"
    private val PROPERTY_INCLUDE_JAVADOC = "copyRelatedFiles.includeJavadoc"
    private val PROPERTY_SMART_PRUNING = "copyRelatedFiles.smartPruning"
    private val PROPERTY_INCLUDE_DEPENDENCIES = "copyRelatedFiles.includeDependencies"
    private val PROPERTY_INCLUDE_DECOMPILED = "copyRelatedFiles.includeDecompiled"
    private val PROPERTY_MAX_DECOMPILED_FILES = "copyRelatedFiles.maxDecompiledFiles"
    private val PROPERTY_SKIP_JAVA_PACKAGES = "copyRelatedFiles.skipJavaPackages"
    private val PROPERTY_ONLY_DIRECT_REFERENCES = "copyRelatedFiles.onlyDirectReferences"
    private val PROPERTY_RELATEDNESS_LEVEL = "copyRelatedFiles.relatednessLevel"
    private val PROPERTY_EXCLUDED_PACKAGES = "copyRelatedFiles.excludedPackages"

    // Default packages to skip (Java standard library and common packages)
    private val DEFAULT_EXCLUDED_PACKAGES = listOf(
        "java.lang",
        "java.util",
        "java.io",
        "java.net",
        "java.math",
        "java.time",
        "java.text",
        "java.sql",
        "java.awt",
        "java.applet",
        "java.beans",
        "java.nio",
        "java.rmi",
        "java.security",
        "javax.swing",
        "javax.servlet",
        "javax.ejb",
        "javax.persistence",
        "javax.xml",
        "org.w3c",
        "org.xml",
        "com.sun",
        "sun.",
        "kotlin.",
        "org.jetbrains",
        "org.springframework",
        "org.apache",
        "com.google",
        "com.fasterxml"
    ).joinToString("\n")

    // Relatedness levels
    enum class RelatedLevel {
        STRICT, MEDIUM, BROAD
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val file = getTargetFile(e)

        if (project == null || file == null) {
            LOG.info("Action not performed: project=${project != null}, file=${file != null}")
            return
        }

        LOG.info("Action performed on file: ${file.path}")

        // Show configuration dialog
        val config = showConfigDialog(project) ?: return

        // Save settings
        saveSettings(config)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying Related Files", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = false
                    val clipboardContent = StringBuilder()
                    val processedFiles = mutableSetOf<String>() // Track by qualified name
                    val processedVirtualFiles = mutableSetOf<VirtualFile>()

                    // For strict tracking of direct references
                    val directReferences = mutableSetOf<String>()

                    // Track dependency relationships
                    val directDependencies = mutableSetOf<VirtualFile>()
                    val dependents = mutableSetOf<VirtualFile>()
                    val mainFile = file

                    // Counter for decompiled files to prevent excessive copying
                    val decompiledFilesCount = AtomicInteger(0)

                    // Get the project's base package from the initial file
                    val basePackage = ReadAction.compute<String, Throwable> {
                        if (config.includeDependencies) {
                            // If including dependencies, we might not need to restrict by package
                            ""
                        } else {
                            extractBasePackage(project, file, config.packageSegments) ?: ""
                        }
                    }

                    LOG.info("Using base package filter: '$basePackage'")

                    // First, collect direct references from the main file
                    if (config.onlyDirectReferences || config.relatednessLevel == RelatedLevel.STRICT) {
                        ReadAction.run<Throwable> {
                            collectDirectReferences(project, mainFile, directReferences, config.excludedPackages)
                        }
                        LOG.info("Collected ${directReferences.size} direct references")
                    }

                    // First pass: identify dependencies and dependents
                    ReadAction.run<Throwable> {
                        identifyDependencyRelationships(
                            project,
                            mainFile,
                            directDependencies,
                            dependents,
                            basePackage,
                            config,
                            indicator,
                            directReferences
                        )
                    }

                    // Add the main file first
                    processFile(
                        project,
                        mainFile,
                        clipboardContent,
                        processedFiles,
                        processedVirtualFiles,
                        config
                    )

                    // Second pass: process direct dependencies
                    LOG.info("Processing ${directDependencies.size} direct dependencies")
                    for (depFile in directDependencies) {
                        if (config.onlyDirectReferences || config.relatednessLevel == RelatedLevel.STRICT) {
                            // For strict mode, only process files that were directly referenced
                            val shouldProcess = ReadAction.compute<Boolean, Throwable> {
                                val psiFile = PsiManager.getInstance(project).findFile(depFile)
                                if (psiFile is PsiJavaFile) {
                                    for (psiClass in psiFile.classes) {
                                        val qualifiedName = psiClass.qualifiedName
                                        if (qualifiedName != null && directReferences.contains(qualifiedName)) {
                                            return@compute true
                                        }
                                    }
                                    false
                                } else {
                                    // For non-Java files, process if it's a direct dependency
                                    true
                                }
                            }

                            if (shouldProcess) {
                                processFile(
                                    project,
                                    depFile,
                                    clipboardContent,
                                    processedFiles,
                                    processedVirtualFiles,
                                    config
                                )
                            }
                        } else {
                            // For medium/broad modes, process with configurable depth
                            processFileWithReferences(
                                project,
                                depFile,
                                clipboardContent,
                                processedFiles,
                                processedVirtualFiles,
                                basePackage,
                                config,
                                indicator,
                                0,
                                decompiledFilesCount,
                                directReferences
                            )
                        }
                    }

                    // Only process dependents in MEDIUM or BROAD modes
                    if (config.relatednessLevel != RelatedLevel.STRICT) {
                        LOG.info("Processing ${dependents.size} dependents")
                        for (depFile in dependents) {
                            if (!processedVirtualFiles.contains(depFile)) {
                                if (config.onlyDirectReferences) {
                                    processFile(
                                        project,
                                        depFile,
                                        clipboardContent,
                                        processedFiles,
                                        processedVirtualFiles,
                                        config
                                    )
                                } else {
                                    processFileWithReferences(
                                        project,
                                        depFile,
                                        clipboardContent,
                                        processedFiles,
                                        processedVirtualFiles,
                                        basePackage,
                                        config,
                                        indicator,
                                        0,
                                        decompiledFilesCount,
                                        directReferences
                                    )
                                }
                            }
                        }
                    }

                    ApplicationManager.getApplication().invokeLater {
                        val stringSelection = StringSelection(clipboardContent.toString())
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(stringSelection, null)

                        // Notify user of success
                        Messages.showInfoMessage(
                            project,
                            "Successfully copied ${processedVirtualFiles.size} related files to clipboard.",
                            "Copy Successful"
                        )
                    }
                } catch (e: Exception) {
                    LOG.error("Error copying files", e)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Error copying files: ${e.message}",
                            "Error"
                        )
                    }
                }
            }
        })
    }

    private fun collectDirectReferences(
        project: Project,
        file: VirtualFile,
        directReferences: MutableSet<String>,
        excludedPackages: List<String>
    ) {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return

        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)

                when (element) {
                    is PsiJavaCodeReferenceElement -> {
                        val resolved = element.resolve()
                        if (resolved is PsiClass) {
                            val qualifiedName = resolved.qualifiedName
                            if (qualifiedName != null && !shouldSkipPackage(qualifiedName, excludedPackages)) {
                                directReferences.add(qualifiedName)
                            }
                        }
                    }
                    is PsiImportStatement -> {
                        val importedClass = element.resolve()
                        if (importedClass is PsiClass) {
                            val qualifiedName = importedClass.qualifiedName
                            if (qualifiedName != null && !shouldSkipPackage(qualifiedName, excludedPackages)) {
                                directReferences.add(qualifiedName)
                            }
                        }
                    }
                    is KtImportDirective -> {
                        val importPath = element.importPath?.pathStr
                        if (importPath != null && !shouldSkipPackage(importPath, excludedPackages)) {
                            directReferences.add(importPath)
                        }
                    }
                }
            }
        })
    }

    private fun processFile(
        project: Project,
        file: VirtualFile,
        clipboardContent: StringBuilder,
        processedFiles: MutableSet<String>,
        processedVirtualFiles: MutableSet<VirtualFile>,
        config: CopyConfig
    ) {
        if (!processedVirtualFiles.add(file) || !isSourceFile(file)) {
            return
        }

        LOG.info("Processing file: ${file.path}")

        try {
            // Add current file content
            val content = ReadAction.compute<String, Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(file)
                if (config.smartPruning && psiFile != null) {
                    // Smart pruning to extract only relevant parts
                    extractRelevantParts(psiFile, config.includeJavadoc)
                } else {
                    // Full file content
                    String(file.contentsToByteArray())
                }
            }

            clipboardContent.append("// File: ${file.path}\n")
                .append(content)
                .append("\n\n")
        } catch (e: Exception) {
            LOG.error("Error processing file ${file.path}", e)
        }
    }

    private fun identifyDependencyRelationships(
        project: Project,
        mainFile: VirtualFile,
        directDependencies: MutableSet<VirtualFile>,
        dependents: MutableSet<VirtualFile>,
        basePackage: String,
        config: CopyConfig,
        indicator: ProgressIndicator,
        directReferences: Set<String>
    ) {
        val psiFile = PsiManager.getInstance(project).findFile(mainFile)
        val mainClasses = mutableSetOf<PsiClass>()

        // Extract the main classes from the file
        when (psiFile) {
            is PsiJavaFile -> {
                for (psiClass in psiFile.classes) {
                    mainClasses.add(psiClass)
                }
            }
            is KtFile -> {
                // For Kotlin files, we'll use JavaPsiFacade to find the corresponding Java classes
                val psiFacade = JavaPsiFacade.getInstance(project)
                val packageName = psiFile.packageFqName.asString()
                val fileName = psiFile.name.substringBeforeLast(".")

                // Try to find the class by name in the package
                val potentialClass = psiFacade.findClass("$packageName.$fileName", GlobalSearchScope.projectScope(project))
                if (potentialClass != null) {
                    mainClasses.add(potentialClass)
                }
            }
        }

        // For each class, find dependencies and dependents
        for (mainClass in mainClasses) {
            // Find direct dependencies (classes this class uses)
            psiFile?.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)

                    when (element) {
                        is PsiJavaCodeReferenceElement -> {
                            val resolved = element.resolve()
                            if (resolved is PsiClass) {
                                val qualifiedName = resolved.qualifiedName
                                if (qualifiedName != null &&
                                    (basePackage.isEmpty() || qualifiedName.startsWith(basePackage)) &&
                                    !shouldSkipPackage(qualifiedName, config.excludedPackages) &&
                                    resolved.containingFile?.virtualFile != mainFile &&
                                    (config.relatednessLevel != RelatedLevel.STRICT || directReferences.contains(qualifiedName))) {

                                    val depFile = resolved.containingFile?.virtualFile
                                    if (depFile != null) {
                                        directDependencies.add(depFile)
                                    }
                                }
                            }
                        }
                        is PsiImportStatement -> {
                            val importedClass = element.resolve()
                            if (importedClass is PsiClass) {
                                val qualifiedName = importedClass.qualifiedName
                                if (qualifiedName != null &&
                                    (basePackage.isEmpty() || qualifiedName.startsWith(basePackage)) &&
                                    !shouldSkipPackage(qualifiedName, config.excludedPackages) &&
                                    importedClass.containingFile?.virtualFile != mainFile &&
                                    (config.relatednessLevel != RelatedLevel.STRICT || directReferences.contains(qualifiedName))) {

                                    val depFile = importedClass.containingFile?.virtualFile
                                    if (depFile != null) {
                                        directDependencies.add(depFile)
                                    }
                                }
                            }
                        }
                    }
                }
            })

            // Find dependents (classes that use this class)
            if (config.includeImplementations && config.relatednessLevel != RelatedLevel.STRICT) {
                // Find implementations if this is an interface
                if (mainClass.isInterface) {
                    val searchScope = if (config.includeDependencies) {
                        GlobalSearchScope.allScope(project)
                    } else {
                        GlobalSearchScope.projectScope(project)
                    }

                    val inheritors = ClassInheritorsSearch.search(mainClass, searchScope, true)
                    for (inheritor in inheritors) {
                        val qualifiedName = inheritor.qualifiedName
                        if (qualifiedName != null &&
                            !shouldSkipPackage(qualifiedName, config.excludedPackages) &&
                            inheritor.containingFile?.virtualFile != mainFile &&
                            (basePackage.isEmpty() || isInBasePackage(inheritor.qualifiedName, basePackage))) {

                            val depFile = inheritor.containingFile?.virtualFile
                            if (depFile != null) {
                                dependents.add(depFile)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shouldSkipPackage(qualifiedName: String, excludedPackages: List<String>): Boolean {
        for (excludedPackage in excludedPackages) {
            if (excludedPackage.isNotEmpty() && qualifiedName.startsWith(excludedPackage)) {
                return true
            }
        }
        return false
    }

    private fun isInBasePackage(element: PsiElement?, basePackage: String): Boolean {
        if (element == null || basePackage.isEmpty()) return true

        val file = element.containingFile
        return when (file) {
            is PsiJavaFile -> file.packageName.startsWith(basePackage)
            is KtFile -> file.packageFqName.asString().startsWith(basePackage)
            else -> false
        }
    }

    private fun isInBasePackage(qualifiedName: String?, basePackage: String): Boolean {
        return qualifiedName != null && (basePackage.isEmpty() || qualifiedName.startsWith(basePackage))
    }

    private fun getTargetFile(e: AnActionEvent): VirtualFile? {
        // Try multiple sources to get the file
        return e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.getData(CommonDataKeys.PSI_FILE)?.virtualFile
            ?: e.getData(LangDataKeys.PSI_ELEMENT)?.containingFile?.virtualFile
    }

    private fun extractBasePackage(project: Project, file: VirtualFile, packageSegments: Int): String? {
        val psiFile = PsiManager.getInstance(project).findFile(file)
        return when {
            psiFile is PsiJavaFile -> {
                // Get the full package name for Java files
                val fullPackage = psiFile.packageName
                if (fullPackage.isNotEmpty()) {
                    // Get the specified number of segments of the package name
                    fullPackage.split(".").take(packageSegments).joinToString(".")
                } else null
            }
            psiFile is KtFile -> {
                // Handle Kotlin files
                val fullPackage = psiFile.packageFqName.asString()
                if (fullPackage.isNotEmpty()) {
                    fullPackage.split(".").take(packageSegments).joinToString(".")
                } else null
            }
            psiFile is PsiClassOwner -> {
                // Generic fallback for other JVM language files
                val fullPackage = psiFile.packageName
                if (fullPackage.isNotEmpty()) {
                    fullPackage.split(".").take(packageSegments).joinToString(".")
                } else null
            }
            else -> null
        }
    }

    private fun processFileWithReferences(
        project: Project,
        file: VirtualFile,
        clipboardContent: StringBuilder,
        processedFiles: MutableSet<String>,
        processedVirtualFiles: MutableSet<VirtualFile>,
        basePackage: String,
        config: CopyConfig,
        indicator: ProgressIndicator,
        currentDepth: Int,
        decompiledFilesCount: AtomicInteger,
        directReferences: Set<String> = emptySet()
    ) {
        if (!processedVirtualFiles.add(file) || !isSourceFile(file) || currentDepth > config.maxDepth) {
            return
        }

        LOG.info("Processing file: ${file.path} (depth: $currentDepth)")
        indicator.text = "Processing: ${file.name}"
        indicator.fraction = processedVirtualFiles.size.toDouble() / 100.0 // Approximate progress

        try {
            // Add current file content
            val content = ReadAction.compute<String, Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(file)
                if (config.smartPruning && psiFile != null) {
                    // Smart pruning to extract only relevant parts
                    extractRelevantParts(psiFile, config.includeJavadoc)
                } else {
                    // Full file content
                    String(file.contentsToByteArray())
                }
            }

            clipboardContent.append("// File: ${file.path}\n")
                .append(content)
                .append("\n\n")

            // Don't process references if we've reached max depth
            if (currentDepth >= config.maxDepth) {
                return
            }

            // Only process references further if we're in BROAD mode or MEDIUM with depth > 0
            if (config.relatednessLevel == RelatedLevel.BROAD ||
                (config.relatednessLevel == RelatedLevel.MEDIUM && currentDepth < 1)) {

                // Process references using ReadAction
                val referencesToProcess = ReadAction.compute<List<ReferenceToProcess>, Throwable> {
                    val result = mutableListOf<ReferenceToProcess>()
                    val psiFile = PsiManager.getInstance(project).findFile(file)
                    if (psiFile != null) {
                        // Create a set to track processed references within this file
                        val processedReferences = mutableSetOf<String>()

                        psiFile.accept(object : PsiRecursiveElementVisitor() {
                            override fun visitElement(element: PsiElement) {
                                super.visitElement(element)

                                if (indicator.isCanceled) return

                                when (element) {
                                    is PsiJavaCodeReferenceElement -> {
                                        collectReference(element, processedReferences, result)
                                    }
                                    is PsiImportStatement -> {
                                        collectImport(element, processedReferences, result)
                                    }
                                    is KtImportDirective -> {
                                        collectKotlinImport(element, processedReferences, result)
                                    }
                                }
                            }

                            private fun collectReference(reference: PsiJavaCodeReferenceElement, processedRefs: MutableSet<String>, result: MutableList<ReferenceToProcess>) {
                                val resolved = reference.resolve()
                                if (resolved is PsiClass) {
                                    val qualifiedName = resolved.qualifiedName ?: return

                                    // Skip if this class is from an excluded package
                                    if (shouldSkipPackage(qualifiedName, config.excludedPackages)) {
                                        return
                                    }

                                    // In STRICT mode, only process direct references
                                    if (config.relatednessLevel == RelatedLevel.STRICT &&
                                        !directReferences.contains(qualifiedName)) {
                                        return
                                    }

                                    if ((basePackage.isEmpty() || qualifiedName.startsWith(basePackage)) &&
                                        !processedRefs.contains(qualifiedName) &&
                                        !processedFiles.contains(qualifiedName)) {

                                        processedRefs.add(qualifiedName)

                                        // Handle normal source files
                                        val containingFile = resolved.containingFile?.virtualFile
                                        if (containingFile != null) {
                                            result.add(ReferenceToProcess(
                                                qualifiedName = qualifiedName,
                                                file = containingFile,
                                                isDecompiled = false,
                                                isImplementation = false,
                                                decompiledText = null
                                            ))
                                        }
                                        // Handle decompiled library classes
                                        else if (config.includeDecompiled &&
                                            decompiledFilesCount.get() < config.maxDecompiledFiles &&
                                            resolved.containingFile is ClsFileImpl) {

                                            val decompiledText = decompileClass(resolved)
                                            if (decompiledText != null) {
                                                result.add(ReferenceToProcess(
                                                    qualifiedName = qualifiedName,
                                                    file = null,
                                                    isDecompiled = true,
                                                    isImplementation = false,
                                                    decompiledText = decompiledText
                                                ))
                                            }
                                        }

                                        // If this is an interface and we want implementations
                                        if (config.includeImplementations && resolved.isInterface) {
                                            val implementations = findImplementations(project, resolved, basePackage, config.includeDependencies, config.excludedPackages)
                                            for (implClass in implementations) {
                                                val implQualifiedName = implClass.qualifiedName ?: continue
                                                if (!processedFiles.contains(implQualifiedName)) {
                                                    val implFile = implClass.containingFile?.virtualFile
                                                    if (implFile != null) {
                                                        result.add(ReferenceToProcess(
                                                            qualifiedName = implQualifiedName,
                                                            file = implFile,
                                                            isDecompiled = false,
                                                            isImplementation = true,
                                                            decompiledText = null
                                                        ))
                                                    }
                                                    // Handle decompiled implementations
                                                    else if (config.includeDecompiled &&
                                                        decompiledFilesCount.get() < config.maxDecompiledFiles &&
                                                        implClass.containingFile is ClsFileImpl) {

                                                        val decompiledText = decompileClass(implClass)
                                                        if (decompiledText != null) {
                                                            result.add(ReferenceToProcess(
                                                                qualifiedName = implQualifiedName,
                                                                file = null,
                                                                isDecompiled = true,
                                                                isImplementation = true,
                                                                decompiledText = decompiledText
                                                            ))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            private fun collectImport(importStmt: PsiImportStatement, processedRefs: MutableSet<String>, result: MutableList<ReferenceToProcess>) {
                                val importedClass = importStmt.resolve()
                                if (importedClass is PsiClass) {
                                    val qualifiedName = importedClass.qualifiedName ?: return

                                    // Skip if this class is from an excluded package
                                    if (shouldSkipPackage(qualifiedName, config.excludedPackages)) {
                                        return
                                    }

                                    // In STRICT mode, only process direct references
                                    if (config.relatednessLevel == RelatedLevel.STRICT &&
                                        !directReferences.contains(qualifiedName)) {
                                        return
                                    }

                                    if ((basePackage.isEmpty() || qualifiedName.startsWith(basePackage)) &&
                                        !processedRefs.contains(qualifiedName) &&
                                        !processedFiles.contains(qualifiedName)) {

                                        processedRefs.add(qualifiedName)

                                        // Handle normal source files
                                        val containingFile = importedClass.containingFile?.virtualFile
                                        if (containingFile != null) {
                                            result.add(ReferenceToProcess(
                                                qualifiedName = qualifiedName,
                                                file = containingFile,
                                                isDecompiled = false,
                                                isImplementation = false,
                                                decompiledText = null
                                            ))
                                        }
                                        // Handle decompiled library classes
                                        else if (config.includeDecompiled &&
                                            decompiledFilesCount.get() < config.maxDecompiledFiles &&
                                            importedClass.containingFile is ClsFileImpl) {

                                            val decompiledText = decompileClass(importedClass)
                                            if (decompiledText != null) {
                                                result.add(ReferenceToProcess(
                                                    qualifiedName = qualifiedName,
                                                    file = null,
                                                    isDecompiled = true,
                                                    isImplementation = false,
                                                    decompiledText = decompiledText
                                                ))
                                            }
                                        }

                                        // If this is an interface and we want implementations
                                        if (config.includeImplementations && importedClass.isInterface) {
                                            val implementations = findImplementations(project, importedClass, basePackage, config.includeDependencies, config.excludedPackages)
                                            for (implClass in implementations) {
                                                val implQualifiedName = implClass.qualifiedName ?: continue
                                                if (!processedFiles.contains(implQualifiedName)) {
                                                    val implFile = implClass.containingFile?.virtualFile
                                                    if (implFile != null) {
                                                        result.add(ReferenceToProcess(
                                                            qualifiedName = implQualifiedName,
                                                            file = implFile,
                                                            isDecompiled = false,
                                                            isImplementation = true,
                                                            decompiledText = null
                                                        ))
                                                    }
                                                    // Handle decompiled implementations
                                                    else if (config.includeDecompiled &&
                                                        decompiledFilesCount.get() < config.maxDecompiledFiles &&
                                                        implClass.containingFile is ClsFileImpl) {

                                                        val decompiledText = decompileClass(implClass)
                                                        if (decompiledText != null) {
                                                            result.add(ReferenceToProcess(
                                                                qualifiedName = implQualifiedName,
                                                                file = null,
                                                                isDecompiled = true,
                                                                isImplementation = true,
                                                                decompiledText = decompiledText
                                                            ))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            private fun collectKotlinImport(importDirective: KtImportDirective, processedRefs: MutableSet<String>, result: MutableList<ReferenceToProcess>) {
                                val importPath = importDirective.importPath?.pathStr ?: return

                                // Skip if this import is from an excluded package
                                if (shouldSkipPackage(importPath, config.excludedPackages)) {
                                    return
                                }

                                // In STRICT mode, only process direct references
                                if (config.relatednessLevel == RelatedLevel.STRICT &&
                                    !directReferences.contains(importPath)) {
                                    return
                                }

                                if ((basePackage.isEmpty() || importPath.startsWith(basePackage)) &&
                                    !processedRefs.contains(importPath) &&
                                    !processedFiles.contains(importPath)) {

                                    processedRefs.add(importPath)

                                    // Resolve the import to a class
                                    val importedClass = resolveKotlinImport(project, importPath)
                                    if (importedClass != null) {
                                        // Handle normal source files
                                        val containingFile = importedClass.containingFile?.virtualFile
                                        if (containingFile != null) {
                                            result.add(ReferenceToProcess(
                                                qualifiedName = importPath,
                                                file = containingFile,
                                                isDecompiled = false,
                                                isImplementation = false,
                                                decompiledText = null
                                            ))
                                        }
                                        // Handle decompiled library classes
                                        else if (config.includeDecompiled &&
                                            decompiledFilesCount.get() < config.maxDecompiledFiles &&
                                            importedClass.containingFile is ClsFileImpl) {

                                            val decompiledText = decompileClass(importedClass)
                                            if (decompiledText != null) {
                                                result.add(ReferenceToProcess(
                                                    qualifiedName = importPath,
                                                    file = null,
                                                    isDecompiled = true,
                                                    isImplementation = false,
                                                    decompiledText = decompiledText
                                                ))
                                            }
                                        }

                                        // If this is an interface and we want implementations
                                        if (config.includeImplementations && importedClass.isInterface) {
                                            val implementations = findImplementations(project, importedClass, basePackage, config.includeDependencies, config.excludedPackages)
                                            for (implClass in implementations) {
                                                val implQualifiedName = implClass.qualifiedName ?: continue
                                                if (!processedFiles.contains(implQualifiedName)) {
                                                    val implFile = implClass.containingFile?.virtualFile
                                                    if (implFile != null) {
                                                        result.add(ReferenceToProcess(
                                                            qualifiedName = implQualifiedName,
                                                            file = implFile,
                                                            isDecompiled = false,
                                                            isImplementation = true,
                                                            decompiledText = null
                                                        ))
                                                    }
                                                    // Handle decompiled implementations
                                                    else if (config.includeDecompiled &&
                                                        decompiledFilesCount.get() < config.maxDecompiledFiles &&
                                                        implClass.containingFile is ClsFileImpl) {

                                                        val decompiledText = decompileClass(implClass)
                                                        if (decompiledText != null) {
                                                            result.add(ReferenceToProcess(
                                                                qualifiedName = implQualifiedName,
                                                                file = null,
                                                                isDecompiled = true,
                                                                isImplementation = true,
                                                                decompiledText = decompiledText
                                                            ))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        })
                    }
                    result
                }

                // Process collected references outside of read action
                for (reference in referencesToProcess) {
                    processedFiles.add(reference.qualifiedName)

                    if (reference.isDecompiled && reference.decompiledText != null) {
                        // Add decompiled text directly
                        val prefix = if (reference.isImplementation)
                            "// Decompiled Library Class (Implementation): ${reference.qualifiedName}\n"
                        else
                            "// Decompiled Library Class: ${reference.qualifiedName}\n"

                        clipboardContent.append(prefix)
                            .append(reference.decompiledText)
                            .append("\n\n")

                        decompiledFilesCount.incrementAndGet()
                    } else if (reference.file != null) {
                        // Process file recursively
                        processFileWithReferences(
                            project,
                            reference.file,
                            clipboardContent,
                            processedFiles,
                            processedVirtualFiles,
                            basePackage,
                            config,
                            indicator,
                            currentDepth + 1,
                            decompiledFilesCount,
                            directReferences
                        )
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Error processing file ${file.path}", e)
        }
    }

    // Helper class to store reference information
    private data class ReferenceToProcess(
        val qualifiedName: String,
        val file: VirtualFile?,
        val isDecompiled: Boolean,
        val isImplementation: Boolean,
        val decompiledText: String?
    )

    private fun decompileClass(psiClass: PsiClass): String? {
        return try {
            // For ClsClassImpl (compiled classes), we can try to get the decompiled text
            if (psiClass is ClsClassImpl) {
                val containingFile = psiClass.containingFile
                if (containingFile is ClsFileImpl) {
                    // Get decompiled text using ClsFileImpl's built-in methods
                    ApplicationManager.getApplication().runReadAction(Computable {
                        try {
                            val mirror = containingFile.mirror
                            mirror?.text
                        } catch (e: Exception) {
                            Logger.getInstance(javaClass).warn("Failed to decompile class", e)
                            null
                        }
                    })
                } else {
                    null
                }
            } else {
                // For normal classes, just get the text
                psiClass.text
            }
        } catch (e: Exception) {
            LOG.error("Error decompiling class ${psiClass.qualifiedName}", e)
            null
        }
    }

    private fun resolveKotlinImport(project: Project, importPath: String): PsiClass? {
        val psiFacade = JavaPsiFacade.getInstance(project)
        val searchScope = GlobalSearchScope.allScope(project)
        return psiFacade.findClass(importPath, searchScope)
    }

    private fun findImplementations(
        project: Project,
        interfaceClass: PsiClass,
        basePackage: String,
        includeDependencies: Boolean,
        excludedPackages: List<String>
    ): List<PsiClass> {
        val implementations = mutableListOf<PsiClass>()

        val searchScope = if (includeDependencies) {
            GlobalSearchScope.allScope(project)
        } else {
            GlobalSearchScope.projectScope(project)
        }

        val inheritors = ClassInheritorsSearch.search(interfaceClass, searchScope, true)
        for (inheritor in inheritors) {
            val qualifiedName = inheritor.qualifiedName
            if (qualifiedName != null &&
                !shouldSkipPackage(qualifiedName, excludedPackages) &&
                (basePackage.isEmpty() || qualifiedName.startsWith(basePackage))) {
                implementations.add(inheritor)
            }
        }

        return implementations
    }

    private fun extractRelevantParts(psiFile: PsiFile, includeJavadoc: Boolean): String {
        val sb = StringBuilder()

        when (psiFile) {
            is PsiJavaFile -> {
                // Add package statement
                sb.append("package ${psiFile.packageName};\n\n")

                // Add imports
                val importList = psiFile.importList
                if (importList != null) {
                    for (importStmt in importList.importStatements) {
                        sb.append("import ${importStmt.qualifiedName};\n")
                    }
                    sb.append("\n")
                }

                // Add classes with their javadoc if requested
                for (psiClass in psiFile.classes) {
                    // Add class javadoc if requested
                    if (includeJavadoc) {
                        val javadoc = psiClass.docComment
                        if (javadoc != null) {
                            sb.append(javadoc.text).append("\n")
                        }
                    }

                    // Add class declaration with fields and methods
                    sb.append(psiClass.text).append("\n")
                }
            }
            is KtFile -> {
                // For Kotlin files, we'll just include the whole file for now
                // A more sophisticated implementation would extract only relevant parts
                sb.append(psiFile.text)
            }
            is ClsFileImpl -> {
                // For compiled files, try to decompile
                try {
                    ApplicationManager.getApplication().runReadAction(Computable {
                        val mirror = psiFile.mirror
                        if (mirror != null) {
                            sb.append(mirror.text)
                        } else {
                            sb.append("// Unable to decompile file: mirror is null")
                        }
                    })
                } catch (e: Exception) {
                    LOG.error("Error decompiling file", e)
                    sb.append("// Error decompiling file: ${e.message}")
                }
            }
            else -> {
                // Default fallback - use the entire file text
                sb.append(psiFile.text)
            }
        }

        return sb.toString()
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        return true; // Always return true for now
//        val extension = file.extension?.lowercase() ?: return false
//        return extension in setOf("java", "kt", "scala", "groovy", "class")
    }

    override fun update(e: AnActionEvent) {

        // Always enable for debugging
        e.presentation.isEnabledAndVisible = true

        // Log the event for debugging
        LOG.info("Update called: context=${e.place}, data=${e.dataContext}")
//
//        // This method controls when the action is visible/enabled in the context menu
//        val project = e.project
//
//        // Try to get the file from multiple possible sources
//        val file = getTargetFile(e)
//
//        // Enable the action if we have a project and a valid source file
//        val isEnabled = project != null && file != null && isSourceFile(file)
//
//        e.presentation.isEnabledAndVisible = isEnabled
//
//        // For debugging purposes
//        if (LOG.isDebugEnabled) {
//            LOG.debug("Update called: project=${project != null}, file=${file?.path}, enabled=$isEnabled")
//        }
    }

    // Load settings from persistent storage
    private fun loadSettings(): CopyConfig {
        val properties = PropertiesComponent.getInstance()

        val maxDepth = properties.getInt(PROPERTY_MAX_DEPTH, DEFAULT_MAX_DEPTH)
        val packageSegments = properties.getInt(PROPERTY_PACKAGE_SEGMENTS, DEFAULT_PACKAGE_SEGMENTS)
        val includeImplementations = properties.getBoolean(PROPERTY_INCLUDE_IMPLEMENTATIONS, DEFAULT_INCLUDE_IMPLEMENTATIONS)
        val includeJavadoc = properties.getBoolean(PROPERTY_INCLUDE_JAVADOC, DEFAULT_INCLUDE_JAVADOC)
        val smartPruning = properties.getBoolean(PROPERTY_SMART_PRUNING, DEFAULT_SMART_PRUNING)
        val includeDependencies = properties.getBoolean(PROPERTY_INCLUDE_DEPENDENCIES, DEFAULT_INCLUDE_DEPENDENCIES)
        val includeDecompiled = properties.getBoolean(PROPERTY_INCLUDE_DECOMPILED, DEFAULT_INCLUDE_DECOMPILED)
        val maxDecompiledFiles = properties.getInt(PROPERTY_MAX_DECOMPILED_FILES, DEFAULT_MAX_DECOMPILED_FILES)
        val skipJavaPackages = properties.getBoolean(PROPERTY_SKIP_JAVA_PACKAGES, DEFAULT_SKIP_JAVA_PACKAGES)
        val onlyDirectReferences = properties.getBoolean(PROPERTY_ONLY_DIRECT_REFERENCES, DEFAULT_ONLY_DIRECT_REFERENCES)

        val relatednessLevelStr = properties.getValue(PROPERTY_RELATEDNESS_LEVEL, DEFAULT_RELATEDNESS_LEVEL.name)
        val relatednessLevel = try {
            RelatedLevel.valueOf(relatednessLevelStr)
        } catch (e: Exception) {
            DEFAULT_RELATEDNESS_LEVEL
        }

        val excludedPackagesStr = properties.getValue(PROPERTY_EXCLUDED_PACKAGES, DEFAULT_EXCLUDED_PACKAGES)
        val excludedPackages = excludedPackagesStr.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return CopyConfig(
            maxDepth = maxDepth,
            packageSegments = packageSegments,
            includeImplementations = includeImplementations,
            includeJavadoc = includeJavadoc,
            smartPruning = smartPruning,
            includeDependencies = includeDependencies,
            includeDecompiled = includeDecompiled,
            maxDecompiledFiles = maxDecompiledFiles,
            excludedPackages = excludedPackages,
            onlyDirectReferences = onlyDirectReferences,
            relatednessLevel = relatednessLevel
        )
    }

    // Save settings to persistent storage
    private fun saveSettings(config: CopyConfig) {
        val properties = PropertiesComponent.getInstance()

        properties.setValue(PROPERTY_MAX_DEPTH, config.maxDepth, DEFAULT_MAX_DEPTH)
        properties.setValue(PROPERTY_PACKAGE_SEGMENTS, config.packageSegments, DEFAULT_PACKAGE_SEGMENTS)
        properties.setValue(PROPERTY_INCLUDE_IMPLEMENTATIONS, config.includeImplementations, DEFAULT_INCLUDE_IMPLEMENTATIONS)
        properties.setValue(PROPERTY_INCLUDE_JAVADOC, config.includeJavadoc, DEFAULT_INCLUDE_JAVADOC)
        properties.setValue(PROPERTY_SMART_PRUNING, config.smartPruning, DEFAULT_SMART_PRUNING)
        properties.setValue(PROPERTY_INCLUDE_DEPENDENCIES, config.includeDependencies, DEFAULT_INCLUDE_DEPENDENCIES)
        properties.setValue(PROPERTY_INCLUDE_DECOMPILED, config.includeDecompiled, DEFAULT_INCLUDE_DECOMPILED)
        properties.setValue(PROPERTY_MAX_DECOMPILED_FILES, config.maxDecompiledFiles, DEFAULT_MAX_DECOMPILED_FILES)
        properties.setValue(PROPERTY_SKIP_JAVA_PACKAGES, config.skipJavaPackages, DEFAULT_SKIP_JAVA_PACKAGES)
        properties.setValue(PROPERTY_ONLY_DIRECT_REFERENCES, config.onlyDirectReferences, DEFAULT_ONLY_DIRECT_REFERENCES)
        properties.setValue(PROPERTY_RELATEDNESS_LEVEL, config.relatednessLevel.name, DEFAULT_RELATEDNESS_LEVEL.name)

        val excludedPackagesStr = config.excludedPackages.joinToString("\n")
        properties.setValue(PROPERTY_EXCLUDED_PACKAGES, excludedPackagesStr, DEFAULT_EXCLUDED_PACKAGES)
    }

    // Configuration dialog
    private fun showConfigDialog(project: Project): CopyConfig? {
        // Load saved settings
        val savedConfig = loadSettings()

        val dialog = object : DialogWrapper(project, true) {
            private val maxDepthSlider = JSlider(1, 10, savedConfig.maxDepth)
            private val packageSegmentsField = JBTextField(savedConfig.packageSegments.toString())
            private val includeImplementationsCheckbox = JBCheckBox("Include Interface Implementations", savedConfig.includeImplementations)
            private val includeJavadocCheckbox = JBCheckBox("Include Javadoc Comments", savedConfig.includeJavadoc)
            private val smartPruningCheckbox = JBCheckBox("Smart Content Pruning", savedConfig.smartPruning)
            private val includeDependenciesCheckbox = JBCheckBox("Include External Dependencies", savedConfig.includeDependencies)
            private val includeDecompiledCheckbox = JBCheckBox("Include Decompiled Library Classes", savedConfig.includeDecompiled)
            private val maxDecompiledFilesField = JBTextField(savedConfig.maxDecompiledFiles.toString())
            private val skipJavaPackagesCheckbox = JBCheckBox("Skip Java Standard Library", savedConfig.skipJavaPackages)
            private val excludedPackagesTextArea = JTextArea(savedConfig.excludedPackages.joinToString("\n"), 10, 40)
            private val onlyDirectReferencesCheckbox = JBCheckBox("Only Direct References", savedConfig.onlyDirectReferences)

            // Relatedness level combo box
            private val relatedLevelModel = DefaultComboBoxModel(RelatedLevel.values())
            private val relatedLevelCombo = ComboBox(relatedLevelModel)

            init {
                title = "Copy Related Files Configuration"
                relatedLevelCombo.selectedItem = savedConfig.relatednessLevel
                init()
            }

            override fun createCenterPanel(): JComponent {
                val panel = JPanel(GridBagLayout())
                val gbc = GridBagConstraints()

                // Create a titled panel for relatedness settings
                val relatedPanel = Box.createVerticalBox()
                relatedPanel.border = EmptyBorder(0, 0, 10, 0)
                relatedPanel.add(JLabel("Relatedness Level:"))
                relatedPanel.add(relatedLevelCombo)
                relatedPanel.add(Box.createVerticalStrut(5))
                relatedPanel.add(onlyDirectReferencesCheckbox)

                gbc.gridx = 0
                gbc.gridy = 0
                gbc.gridwidth = 2
                gbc.anchor = GridBagConstraints.WEST
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.weightx = 1.0
                panel.add(relatedPanel, gbc)

                gbc.gridx = 0
                gbc.gridy = 1
                gbc.gridwidth = 1
                gbc.fill = GridBagConstraints.NONE
                gbc.weightx = 0.0
                panel.add(JBLabel("Max Depth:"), gbc)

                gbc.gridx = 1
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.weightx = 1.0
                maxDepthSlider.majorTickSpacing = 1
                maxDepthSlider.paintTicks = true
                maxDepthSlider.paintLabels = true
                panel.add(maxDepthSlider, gbc)

                gbc.gridx = 0
                gbc.gridy = 2
                gbc.fill = GridBagConstraints.NONE
                gbc.weightx = 0.0
                panel.add(JBLabel("Package Segments:"), gbc)

                gbc.gridx = 1
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.weightx = 1.0
                panel.add(packageSegmentsField, gbc)

                gbc.gridx = 0
                gbc.gridy = 3
                gbc.gridwidth = 2
                panel.add(includeImplementationsCheckbox, gbc)

                gbc.gridy = 4
                panel.add(includeJavadocCheckbox, gbc)

                gbc.gridy = 5
                panel.add(smartPruningCheckbox, gbc)

                gbc.gridy = 6
                panel.add(includeDependenciesCheckbox, gbc)

                gbc.gridy = 7
                panel.add(includeDecompiledCheckbox, gbc)

                gbc.gridx = 0
                gbc.gridy = 8
                gbc.gridwidth = 1
                gbc.fill = GridBagConstraints.NONE
                panel.add(JBLabel("Max Decompiled Files:"), gbc)

                gbc.gridx = 1
                gbc.fill = GridBagConstraints.HORIZONTAL
                panel.add(maxDecompiledFilesField, gbc)

                gbc.gridx = 0
                gbc.gridy = 9
                gbc.gridwidth = 2
                panel.add(skipJavaPackagesCheckbox, gbc)

                gbc.gridx = 0
                gbc.gridy = 10
                gbc.gridwidth = 2
                gbc.fill = GridBagConstraints.NONE
                panel.add(JBLabel("Excluded Packages (one per line):"), gbc)

                gbc.gridy = 11
                gbc.fill = GridBagConstraints.BOTH
                gbc.weighty = 1.0
                excludedPackagesTextArea.lineWrap = true
                val scrollPane = JScrollPane(excludedPackagesTextArea)
                panel.add(scrollPane, gbc)

                // Enable/disable related fields based on checkboxes
                includeDecompiledCheckbox.addChangeListener {
                    maxDecompiledFilesField.isEnabled = includeDecompiledCheckbox.isSelected
                }
                maxDecompiledFilesField.isEnabled = includeDecompiledCheckbox.isSelected

                skipJavaPackagesCheckbox.addChangeListener {
                    excludedPackagesTextArea.isEnabled = skipJavaPackagesCheckbox.isSelected
                }
                excludedPackagesTextArea.isEnabled = skipJavaPackagesCheckbox.isSelected

                // Update UI based on relatedness level
                relatedLevelCombo.addActionListener {
                    val level = relatedLevelCombo.selectedItem as RelatedLevel
                    when (level) {
                        RelatedLevel.STRICT -> {
                            maxDepthSlider.value = 1
                            maxDepthSlider.isEnabled = false
                            onlyDirectReferencesCheckbox.isSelected = true
                            onlyDirectReferencesCheckbox.isEnabled = false
                        }
                        RelatedLevel.MEDIUM -> {
                            maxDepthSlider.value = 1
                            maxDepthSlider.isEnabled = true
                            onlyDirectReferencesCheckbox.isEnabled = true
                        }
                        RelatedLevel.BROAD -> {
                            maxDepthSlider.value = 3
                            maxDepthSlider.isEnabled = true
                            onlyDirectReferencesCheckbox.isEnabled = true
                        }
                    }
                }

                // Initial setup based on saved relatedness level
                val level = relatedLevelCombo.selectedItem as RelatedLevel
                if (level == RelatedLevel.STRICT) {
                    maxDepthSlider.isEnabled = false
                    onlyDirectReferencesCheckbox.isEnabled = false
                }

                return panel
            }

            fun getConfig(): CopyConfig {
                // Parse excluded packages from text area
                val excludedPackages = if (skipJavaPackagesCheckbox.isSelected) {
                    excludedPackagesTextArea.text.split("\n")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }

                return CopyConfig(
                    maxDepth = maxDepthSlider.value,
                    packageSegments = packageSegmentsField.text.toIntOrNull() ?: DEFAULT_PACKAGE_SEGMENTS,
                    includeImplementations = includeImplementationsCheckbox.isSelected,
                    includeJavadoc = includeJavadocCheckbox.isSelected,
                    smartPruning = smartPruningCheckbox.isSelected,
                    includeDependencies = includeDependenciesCheckbox.isSelected,
                    includeDecompiled = includeDecompiledCheckbox.isSelected,
                    maxDecompiledFiles = maxDecompiledFilesField.text.toIntOrNull() ?: DEFAULT_MAX_DECOMPILED_FILES,
                    excludedPackages = excludedPackages,
                    onlyDirectReferences = onlyDirectReferencesCheckbox.isSelected,
                    relatednessLevel = relatedLevelCombo.selectedItem as RelatedLevel,
                    skipJavaPackages = skipJavaPackagesCheckbox.isSelected
                )
            }
        }

        dialog.show()
        return if (dialog.isOK) dialog.getConfig() else null
    }

    // Configuration data class
    data class CopyConfig(
        val maxDepth: Int,
        val packageSegments: Int,
        val includeImplementations: Boolean,
        val includeJavadoc: Boolean,
        val smartPruning: Boolean,
        val includeDependencies: Boolean,
        val includeDecompiled: Boolean,
        val maxDecompiledFiles: Int,
        val excludedPackages: List<String>,
        val onlyDirectReferences: Boolean,
        val relatednessLevel: RelatedLevel,
        val skipJavaPackages: Boolean = true
    )
}

