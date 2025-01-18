package dev.balakumar

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyRelatedFilesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        if (project == null || file == null) {
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying Files", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = false
                    val clipboardContent = StringBuilder()
                    val processedFiles = mutableSetOf<VirtualFile>()

                    // Get the project's base package from the initial file
                    val basePackage = extractBasePackage(project, file)
                    if (basePackage != null) {
                        println("Detected base package: $basePackage")
                        processFileWithReferences(project, file, clipboardContent, processedFiles, basePackage)

                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            val stringSelection = StringSelection(clipboardContent.toString())
                            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                            clipboard.setContents(stringSelection, null)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project,
                        "Error copying files: ${e.message}",
                        "Error"
                    )
                }
            }
        })
    }

    private fun extractBasePackage(project: Project, file: VirtualFile): String? {
        return com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction<String?> {
            val psiFile = PsiManager.getInstance(project).findFile(file)
            if (psiFile is PsiJavaFile) {
                // Get the full package name
                val fullPackage = psiFile.packageName
                // Get the first two segments of the package name
                fullPackage.split(".").take(2).joinToString(".")
            } else null
        }
    }

    private fun processFileWithReferences(
        project: Project,
        file: VirtualFile,
        clipboardContent: StringBuilder,
        processedFiles: MutableSet<VirtualFile>,
        basePackage: String
    ) {
        if (!processedFiles.add(file) || !isSourceFile(file)) {
            return
        }

        println("Processing file: ${file.path}")

        try {
            // Add current file content
            val content = com.intellij.openapi.application.ApplicationManager.getApplication()
                .runReadAction<String> {
                    String(file.contentsToByteArray())
                }

            clipboardContent.append("// File: ${file.path}\n")
                .append(content)
                .append("\n\n")

            // Process references
            com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
                val psiFile = PsiManager.getInstance(project).findFile(file)
                if (psiFile != null) {
                    // Create a set to track processed references within this file
                    val processedReferences = mutableSetOf<String>()

                    psiFile.accept(object : PsiRecursiveElementVisitor() {
                        override fun visitElement(element: PsiElement) {
                            super.visitElement(element)

                            when (element) {
                                is PsiJavaCodeReferenceElement -> {
                                    processReference(element, processedReferences)
                                }
                                is PsiImportStatement -> {
                                    processImport(element, processedReferences)
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
                                    println("Found reference: $qualifiedName")

                                    val containingFile = resolved.containingFile?.virtualFile
                                    if (containingFile != null && !processedFiles.contains(containingFile)) {
                                        processFileWithReferences(project, containingFile, clipboardContent, processedFiles, basePackage)
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
                                    println("Found import: $qualifiedName")

                                    val containingFile = importedClass.containingFile?.virtualFile
                                    if (containingFile != null && !processedFiles.contains(containingFile)) {
                                        processFileWithReferences(project, containingFile, clipboardContent, processedFiles, basePackage)
                                    }
                                }
                            }
                        }
                    })
                }
            }
        } catch (e: Exception) {
            println("Error processing file ${file.path}: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val extension = file.extension?.lowercase() ?: return false
        return extension in setOf("java", "kt", "scala", "groovy")
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && file != null && isSourceFile(file)
    }
}
