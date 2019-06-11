/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.diagnostic.PerformanceWatcher
import com.intellij.idea.IdeaTestApplication
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import java.io.File
import java.util.concurrent.TimeUnit

abstract class AbstractKotlinProjectsPerformanceTest : UsefulTestCase() {

    lateinit var myProject: Project

    override fun setUp() {
        super.setUp()

        IdeaTestApplication.getInstance()
        ApplicationManager.getApplication().runWriteAction {
            val jdkTableImpl = JavaAwareProjectJdkTableImpl.getInstanceEx()
            val homePath = if (jdkTableImpl.internalJdk.homeDirectory!!.name == "jre") {
                jdkTableImpl.internalJdk.homeDirectory!!.parent.path
            } else {
                jdkTableImpl.internalJdk.homePath!!
            }

            val javaSdk = JavaSdk.getInstance()
            val j8 = javaSdk.createJdk("1.8", homePath)
            val internal = javaSdk.createJdk("IDEA jdk", homePath)

            val jdkTable = ProjectJdkTable.getInstance()
            jdkTable.addJdk(j8, testRootDisposable)
            jdkTable.addJdk(internal, testRootDisposable)
        }
        InspectionProfileImpl.INIT_INSPECTIONS = true

        // warm up: open simple small project
        val project = perfOpenProject(
            "helloKotlin", "warm-up ",
            expectedTimeMs = TimeUnit.MINUTES.toMillis(1)
        )
        perfHighlightFile(project, "src/HelloMain.kt", "warm-up ", TimeUnit.SECONDS.toMillis(40))

        ProjectManagerEx.getInstanceEx().forceCloseProject(project, true)
    }

    protected fun perfChangeDocument(fileName: String, note: String = "", block: (document: Document) -> Unit) =
        // keep expectedTimeMs at a reasonable higher level to avoid test failure due to too optimistic expectations
        perfChangeDocument(myProject, fileName, TimeUnit.SECONDS.toMillis(5), note, block)

    private fun perfChangeDocument(
        project: Project,
        fileName: String,
        expectedTimeMs: Long,
        nameOfChange: String,
        block: (document: Document) -> Unit
    ) {
        val document = openEditor(project, fileName)
        val manager = PsiDocumentManager.getInstance(project)
        CommandProcessor.getInstance().executeCommand(project, {
            ApplicationManager.getApplication().runWriteAction {
                PlatformTestUtil.startPerformanceTest("Changing doc $fileName $nameOfChange", expectedTimeMs.toInt()) {
                    block(document)

                    manager.commitDocument(document)
                }.attempts(1).assertTiming()
            }
        }, "change doc $fileName $nameOfChange", "")
    }

    protected fun perfOpenProject(name: String, path: String = "idea/testData/perfTest") {
        // keep expectedTimeMs at a reasonable higher level to avoid test failure due to too optimistic expectations
        myProject = perfOpenProject(name, path = path, expectedTimeMs = TimeUnit.MINUTES.toMillis(2))
    }

    private fun perfOpenProject(
        name: String,
        note: String = "",
        path: String = "idea/testData/perfTest",
        expectedTimeMs: Long
    ): Project {
        var project: Project? = null

        val projectPath = "$path/$name"
        println("try to open $projectPath")
        PlatformTestUtil.startPerformanceTest("Project ${note}opening $name", expectedTimeMs.toInt()) {
            val snapshot = PerformanceWatcher.takeSnapshot()
            project = ProjectManager.getInstance().loadAndOpenProject(projectPath)
            ProjectManagerEx.getInstanceEx().openTestProject(project!!)

            // open + indexing + lots of other things
            //project = ProjectUtil.openOrImport(projectPath, null, false)

            disposeOnTearDown(Disposable { ProjectManagerEx.getInstanceEx().forceCloseProject(project!!, true) })
            snapshot.logResponsivenessSinceCreation("Project ${note}opening $name")
        }.attempts(1).usesMultipleCPUCores(4).assertTiming()

        val changeListManagerImpl = ChangeListManager.getInstance(project!!) as ChangeListManagerImpl
        changeListManagerImpl.waitUntilRefreshed()

        return project!!
    }

    protected fun perfHighlightFile(name: String): List<HighlightInfo> =
        // keep expectedTimeMs at a reasonable higher level to avoid test failure due to too optimistic expectations
        perfHighlightFile(myProject, name, expectedTimeMs = TimeUnit.SECONDS.toMillis(20))


    private fun perfHighlightFile(
        project: Project,
        name: String,
        note: String = "",
        expectedTimeMs: Long
    ): List<HighlightInfo> {
        val file = openFileInEditor(project, name)

        var highlightFile: List<HighlightInfo> = emptyList()
        PlatformTestUtil.startPerformanceTest("File ${note}highlighting ${file.name}", expectedTimeMs.toInt()) {
            val snapshot = PerformanceWatcher.takeSnapshot()
            highlightFile = highlightFile(file)
            snapshot.logResponsivenessSinceCreation("File ${note}highlighting ${file.name}")
        }.attempts(1).assertTiming()
        return highlightFile
    }

    fun perfAutoCompletion(
        name: String,
        before: String,
        type: String,
        after: String
    ) = perfAutoCompletion(name, before, type, after, expectedTimeMs = TimeUnit.SECONDS.toMillis(20))

    private fun perfAutoCompletion(
        name: String,
        before: String,
        type: String,
        after: String,
        expectedTimeMs: Long
    ) {

        val fixtureBuilder = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(name)
        val myFixture = fixtureBuilder.fixture
        val codeInsightTestFixture = IdeaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(myFixture)

        with(codeInsightTestFixture) {
            setUp()
            configureByText(KotlinFileType.INSTANCE, before)
        }

        PlatformTestUtil.startPerformanceTest("Auto completion $name", expectedTimeMs.toInt()) {
            val snapshot = PerformanceWatcher.takeSnapshot()
            with(codeInsightTestFixture) {
                completeBasic()
                type(type)
            }
            snapshot.logResponsivenessSinceCreation("Auto completion $name")
        }.attempts(1).assertTiming()

        codeInsightTestFixture.checkResult(after)

        codeInsightTestFixture.tearDown()
    }

    private fun highlightFile(psiFile: PsiFile): List<HighlightInfo> {
        val document = FileDocumentManager.getInstance().getDocument(psiFile.virtualFile)!!
        val editor = EditorFactory.getInstance().getEditors(document)[0]
        return CodeInsightTestFixtureImpl.instantiateAndRun(psiFile, editor, IntArray(0), false)
    }

    private fun openFileInEditor(project: Project, name: String): PsiFile {
        val psiFile = projectFileByName(project, name)
        val vFile = psiFile.virtualFile
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(vFile, true)
        val document = FileDocumentManager.getInstance().getDocument(vFile)!!
        assertNotNull(EditorFactory.getInstance().getEditors(document))
        disposeOnTearDown(Disposable { fileEditorManager.closeFile(vFile) })
        return psiFile
    }

    private fun openEditor(project: Project, name: String): Document {
        val psiFile = projectFileByName(project, name)
        val vFile = psiFile.virtualFile
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(vFile, true)
        val document = FileDocumentManager.getInstance().getDocument(vFile)!!
        assertNotNull(EditorFactory.getInstance().getEditors(document))
        disposeOnTearDown(Disposable { fileEditorManager.closeFile(vFile) })
        return document
    }

    private fun projectFileByName(project: Project, name: String): PsiFile {
        val fileManager = VirtualFileManager.getInstance()
        val url = "file://${File("${project.basePath}/$name").absolutePath}"
        val virtualFile = fileManager.refreshAndFindFileByUrl(url)
        return virtualFile!!.toPsiFile(project)!!
    }
}