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
import org.jetbrains.kotlin.psi.KtFile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages

class CopyRelatedFilesAction : AnAction() {
    private val LOG = Logger.getInstance(CopyRelatedFilesAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val file = getTargetFile(e)

        if (project == null || file == null) {
            LOG.info("Action not performed: project=${project != null}, file=${file != null}")
            return
        }

        LOG.info("Action performed on file: ${file.path}")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying Related Files", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = false
                    val clipboardContent = StringBuilder()
                    val processedFiles = mutableSetOf<VirtualFile>()

                    // Get the project's base package from the initial file
                    val basePackage = extractBasePackage(project, file)
                    if (basePackage != null) {
                        LOG.info("Detected base package: $basePackage")
                        processFileWithReferences(project, file, clipboardContent, processedFiles, basePackage, indicator)

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

    private fun getTargetFile(e: AnActionEvent): VirtualFile? {
        // Try multiple sources to get the file
        return e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.getData(CommonDataKeys.PSI_FILE)?.virtualFile
            ?: e.getData(LangDataKeys.PSI_ELEMENT)?.containingFile?.virtualFile
    }

    private fun extractBasePackage(project: Project, file: VirtualFile): String? {
        return ApplicationManager.getApplication().runReadAction<String?> {
            val psiFile = PsiManager.getInstance(project).findFile(file)
            when {
                psiFile is PsiJavaFile -> {
                    // Get the full package name for Java files
                    val fullPackage = psiFile.packageName
                    if (fullPackage.isNotEmpty()) {
                        // Get the first two segments of the package name
                        fullPackage.split(".").take(2).joinToString(".")
                    } else null
                }
                psiFile is KtFile -> {
                    // Handle Kotlin files
                    val fullPackage = psiFile.packageFqName.asString()
                    if (fullPackage.isNotEmpty()) {
                        fullPackage.split(".").take(2).joinToString(".")
                    } else null
                }
                psiFile is PsiClassOwner -> {
                    // Generic fallback for other JVM language files
                    val fullPackage = psiFile.packageName
                    if (fullPackage.isNotEmpty()) {
                        fullPackage.split(".").take(2).joinToString(".")
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
        indicator: ProgressIndicator
    ) {
        if (!processedFiles.add(file) || !isSourceFile(file)) {
            return
        }

        LOG.info("Processing file: ${file.path}")
        indicator.text = "Processing: ${file.name}"
        indicator.fraction = processedFiles.size.toDouble() / 100.0 // Approximate progress

        try {
            // Add current file content
            val content = ApplicationManager.getApplication()
                .runReadAction<String> {
                    String(file.contentsToByteArray())
                }

            clipboardContent.append("// File: ${file.path}\n")
                .append(content)
                .append("\n\n")

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
                                // Add more element types as needed for Kotlin, etc.
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
                                        processFileWithReferences(project, containingFile, clipboardContent, processedFiles, basePackage, indicator)
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
                                        processFileWithReferences(project, containingFile, clipboardContent, processedFiles, basePackage, indicator)
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
}