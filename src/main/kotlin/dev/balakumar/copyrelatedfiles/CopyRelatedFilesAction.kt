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

class CopyRelatedFilesAction : AnAction() {
    private val LOG = Logger.getInstance(CopyRelatedFilesAction::class.java)

    // Constants for configuration
    private val DEFAULT_MAX_DEPTH = 3
    private val DEFAULT_PACKAGE_SEGMENTS = 2
    private val DEFAULT_INCLUDE_IMPLEMENTATIONS = true
    private val DEFAULT_INCLUDE_JAVADOC = true
    private val DEFAULT_SMART_PRUNING = true

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

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying Related Files", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = false
                    val clipboardContent = StringBuilder()
                    val processedFiles = mutableSetOf<VirtualFile>()

                    // Track dependency relationships
                    val directDependencies = mutableSetOf<VirtualFile>()
                    val dependents = mutableSetOf<VirtualFile>()
                    val mainFile = file

                    // Get the project's base package from the initial file
                    val basePackage = extractBasePackage(project, file, config.packageSegments)
                    if (basePackage != null) {
                        LOG.info("Detected base package: $basePackage")

                        // First pass: identify dependencies and dependents
                        identifyDependencyRelationships(
                            project,
                            mainFile,
                            directDependencies,
                            dependents,
                            basePackage,
                            config,
                            indicator
                        )

                        // Second pass: process files in dependency order
                        // 1. First process direct dependencies
                        for (depFile in directDependencies) {
                            processFileWithReferences(
                                project,
                                depFile,
                                clipboardContent,
                                processedFiles,
                                basePackage,
                                config,
                                indicator,
                                0
                            )
                        }

                        // 2. Then process the main file
                        processFileWithReferences(
                            project,
                            mainFile,
                            clipboardContent,
                            processedFiles,
                            basePackage,
                            config,
                            indicator,
                            0
                        )

                        // 3. Finally process dependents
                        for (depFile in dependents) {
                            if (!processedFiles.contains(depFile)) {
                                processFileWithReferences(
                                    project,
                                    depFile,
                                    clipboardContent,
                                    processedFiles,
                                    basePackage,
                                    config,
                                    indicator,
                                    0
                                )
                            }
                        }

                        ApplicationManager.getApplication().invokeLater {
                            val stringSelection = StringSelection(clipboardContent.toString())
                            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                            clipboard.setContents(stringSelection, null)

                            // Notify user of success
                            Messages.showInfoMessage(
                                project,
                                "Successfully copied ${processedFiles.size} related files to clipboard.",
                                "Copy Successful"
                            )
                        }
                    } else {
                        LOG.warn("Could not detect base package for file: ${file.path}")
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showWarningDialog(
                                project,
                                "Could not detect base package for file: ${file.path}",
                                "Warning"
                            )
                        }
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

    private fun identifyDependencyRelationships(
        project: Project,
        mainFile: VirtualFile,
        directDependencies: MutableSet<VirtualFile>,
        dependents: MutableSet<VirtualFile>,
        basePackage: String,
        config: CopyConfig,
        indicator: ProgressIndicator
    ) {
        ApplicationManager.getApplication().runReadAction {
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
                                        qualifiedName.startsWith(basePackage) &&
                                        resolved.containingFile?.virtualFile != mainFile) {

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
                                        qualifiedName.startsWith(basePackage) &&
                                        importedClass.containingFile?.virtualFile != mainFile) {

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
                if (config.includeImplementations) {
                    // Find implementations if this is an interface
                    if (mainClass.isInterface) {
                        val inheritors = ClassInheritorsSearch.search(mainClass, GlobalSearchScope.projectScope(project), true)
                        for (inheritor in inheritors) {
                            val depFile = inheritor.containingFile?.virtualFile
                            if (depFile != null &&
                                depFile != mainFile &&
                                isInBasePackage(inheritor.qualifiedName, basePackage)) {
                                dependents.add(depFile)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isInBasePackage(element: PsiElement?, basePackage: String): Boolean {
        if (element == null) return false

        val file = element.containingFile
        return when (file) {
            is PsiJavaFile -> file.packageName.startsWith(basePackage)
            is KtFile -> file.packageFqName.asString().startsWith(basePackage)
            else -> false
        }
    }

    private fun isInBasePackage(qualifiedName: String?, basePackage: String): Boolean {
        return qualifiedName != null && qualifiedName.startsWith(basePackage)
    }

    private fun getTargetFile(e: AnActionEvent): VirtualFile? {
        // Try multiple sources to get the file
        return e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.getData(CommonDataKeys.PSI_FILE)?.virtualFile
            ?: e.getData(LangDataKeys.PSI_ELEMENT)?.containingFile?.virtualFile
    }

    private fun extractBasePackage(project: Project, file: VirtualFile, packageSegments: Int): String? {
        return ApplicationManager.getApplication().runReadAction<String?> {
            val psiFile = PsiManager.getInstance(project).findFile(file)
            when {
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
    }

    private fun processFileWithReferences(
        project: Project,
        file: VirtualFile,
        clipboardContent: StringBuilder,
        processedFiles: MutableSet<VirtualFile>,
        basePackage: String,
        config: CopyConfig,
        indicator: ProgressIndicator,
        currentDepth: Int
    ) {
        if (!processedFiles.add(file) || !isSourceFile(file) || currentDepth > config.maxDepth) {
            return
        }

        LOG.info("Processing file: ${file.path} (depth: $currentDepth)")
        indicator.text = "Processing: ${file.name}"
        indicator.fraction = processedFiles.size.toDouble() / 100.0 // Approximate progress

        try {
            // Add current file content
            val content = ApplicationManager.getApplication()
                .runReadAction<String> {
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

            // Process references
            ApplicationManager.getApplication().runReadAction {
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
                                    processReference(element, processedReferences)
                                }
                                is PsiImportStatement -> {
                                    processImport(element, processedReferences)
                                }
                                is KtImportDirective -> {
                                    processKotlinImport(element, processedReferences)
                                }
                            }
                        }

                        private fun processReference(reference: PsiJavaCodeReferenceElement, processedRefs: MutableSet<String>) {
                            val resolved = reference.resolve()
                            if (resolved is PsiClass) {
                                val qualifiedName = resolved.qualifiedName
                                if (qualifiedName != null &&
                                    qualifiedName.startsWith(basePackage) &&
                                    !processedRefs.contains(qualifiedName)) {

                                    processedRefs.add(qualifiedName)
                                    LOG.info("Found reference: $qualifiedName")

                                    val containingFile = resolved.containingFile?.virtualFile
                                    if (containingFile != null && !processedFiles.contains(containingFile)) {
                                        processFileWithReferences(
                                            project,
                                            containingFile,
                                            clipboardContent,
                                            processedFiles,
                                            basePackage,
                                            config,
                                            indicator,
                                            currentDepth + 1
                                        )
                                    }

                                    // If this is an interface and we want implementations
                                    if (config.includeImplementations && resolved.isInterface) {
                                        findImplementations(project, resolved, basePackage).forEach { implClass ->
                                            val implFile = implClass.containingFile?.virtualFile
                                            if (implFile != null && !processedFiles.contains(implFile)) {
                                                processFileWithReferences(
                                                    project,
                                                    implFile,
                                                    clipboardContent,
                                                    processedFiles,
                                                    basePackage,
                                                    config,
                                                    indicator,
                                                    currentDepth + 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        private fun processImport(importStmt: PsiImportStatement, processedRefs: MutableSet<String>) {
                            val importedClass = importStmt.resolve()
                            if (importedClass is PsiClass) {
                                val qualifiedName = importedClass.qualifiedName
                                if (qualifiedName != null &&
                                    qualifiedName.startsWith(basePackage) &&
                                    !processedRefs.contains(qualifiedName)) {

                                    processedRefs.add(qualifiedName)
                                    LOG.info("Found import: $qualifiedName")

                                    val containingFile = importedClass.containingFile?.virtualFile
                                    if (containingFile != null && !processedFiles.contains(containingFile)) {
                                        processFileWithReferences(
                                            project,
                                            containingFile,
                                            clipboardContent,
                                            processedFiles,
                                            basePackage,
                                            config,
                                            indicator,
                                            currentDepth + 1
                                        )
                                    }

                                    // If this is an interface and we want implementations
                                    if (config.includeImplementations && importedClass.isInterface) {
                                        findImplementations(project, importedClass, basePackage).forEach { implClass ->
                                            val implFile = implClass.containingFile?.virtualFile
                                            if (implFile != null && !processedFiles.contains(implFile)) {
                                                processFileWithReferences(
                                                    project,
                                                    implFile,
                                                    clipboardContent,
                                                    processedFiles,
                                                    basePackage,
                                                    config,
                                                    indicator,
                                                    currentDepth + 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        private fun processKotlinImport(importDirective: KtImportDirective, processedRefs: MutableSet<String>) {
                            val importPath = importDirective.importPath?.pathStr ?: return
                            if (importPath.startsWith(basePackage) && !processedRefs.contains(importPath)) {
                                processedRefs.add(importPath)
                                LOG.info("Found Kotlin import: $importPath")

                                // Resolve the import to a class
                                val importedClass = resolveKotlinImport(project, importPath)
                                if (importedClass != null) {
                                    val containingFile = importedClass.containingFile?.virtualFile
                                    if (containingFile != null && !processedFiles.contains(containingFile)) {
                                        processFileWithReferences(
                                            project,
                                            containingFile,
                                            clipboardContent,
                                            processedFiles,
                                            basePackage,
                                            config,
                                            indicator,
                                            currentDepth + 1
                                        )
                                    }

                                    // If this is an interface and we want implementations
                                    if (config.includeImplementations && importedClass.isInterface) {
                                        findImplementations(project, importedClass, basePackage).forEach { implClass ->
                                            val implFile = implClass.containingFile?.virtualFile
                                            if (implFile != null && !processedFiles.contains(implFile)) {
                                                processFileWithReferences(
                                                    project,
                                                    implFile,
                                                    clipboardContent,
                                                    processedFiles,
                                                    basePackage,
                                                    config,
                                                    indicator,
                                                    currentDepth + 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    })
                }
            }
        } catch (e: Exception) {
            LOG.error("Error processing file ${file.path}", e)
        }
    }

    private fun resolveKotlinImport(project: Project, importPath: String): PsiClass? {
        val psiFacade = JavaPsiFacade.getInstance(project)
        return psiFacade.findClass(importPath, GlobalSearchScope.projectScope(project))
    }

    private fun findImplementations(project: Project, interfaceClass: PsiClass, basePackage: String): List<PsiClass> {
        val implementations = mutableListOf<PsiClass>()

        val inheritors = ClassInheritorsSearch.search(interfaceClass, GlobalSearchScope.projectScope(project), true)
        for (inheritor in inheritors) {
            val qualifiedName = inheritor.qualifiedName
            if (qualifiedName != null && qualifiedName.startsWith(basePackage)) {
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
            else -> {
                // Default fallback - use the entire file text
                sb.append(psiFile.text)
            }
        }

        return sb.toString()
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val extension = file.extension?.lowercase() ?: return false
        return extension in setOf("java", "kt", "scala", "groovy")
    }

    override fun update(e: AnActionEvent) {
        // This method controls when the action is visible/enabled in the context menu
        val project = e.project

        // Try to get the file from multiple possible sources
        val file = getTargetFile(e)

        // Enable the action if we have a project and a valid source file
        val isEnabled = project != null && file != null && isSourceFile(file)

        e.presentation.isEnabledAndVisible = isEnabled

        // For debugging purposes
        if (LOG.isDebugEnabled) {
            LOG.debug("Update called: project=${project != null}, file=${file?.path}, enabled=$isEnabled")
        }
    }

    // Configuration dialog
    private fun showConfigDialog(project: Project): CopyConfig? {
        val dialog = object : DialogWrapper(project, true) {
            private val maxDepthSlider = JSlider(1, 10, DEFAULT_MAX_DEPTH)
            private val packageSegmentsField = JBTextField(DEFAULT_PACKAGE_SEGMENTS.toString())
            private val includeImplementationsCheckbox = JBCheckBox("Include Interface Implementations", DEFAULT_INCLUDE_IMPLEMENTATIONS)
            private val includeJavadocCheckbox = JBCheckBox("Include Javadoc Comments", DEFAULT_INCLUDE_JAVADOC)
            private val smartPruningCheckbox = JBCheckBox("Smart Content Pruning", DEFAULT_SMART_PRUNING)

            init {
                title = "Copy Related Files Configuration"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val panel = JPanel(GridBagLayout())
                val gbc = GridBagConstraints()

                gbc.gridx = 0
                gbc.gridy = 0
                gbc.gridwidth = 1
                gbc.anchor = GridBagConstraints.WEST
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
                gbc.gridy = 1
                gbc.fill = GridBagConstraints.NONE
                gbc.weightx = 0.0
                panel.add(JBLabel("Package Segments:"), gbc)

                gbc.gridx = 1
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.weightx = 1.0
                panel.add(packageSegmentsField, gbc)

                gbc.gridx = 0
                gbc.gridy = 2
                gbc.gridwidth = 2
                panel.add(includeImplementationsCheckbox, gbc)

                gbc.gridy = 3
                panel.add(includeJavadocCheckbox, gbc)

                gbc.gridy = 4
                panel.add(smartPruningCheckbox, gbc)

                return panel
            }

            fun getConfig(): CopyConfig {
                return CopyConfig(
                    maxDepth = maxDepthSlider.value,
                    packageSegments = packageSegmentsField.text.toIntOrNull() ?: DEFAULT_PACKAGE_SEGMENTS,
                    includeImplementations = includeImplementationsCheckbox.isSelected,
                    includeJavadoc = includeJavadocCheckbox.isSelected,
                    smartPruning = smartPruningCheckbox.isSelected
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
        val smartPruning: Boolean
    )
}
