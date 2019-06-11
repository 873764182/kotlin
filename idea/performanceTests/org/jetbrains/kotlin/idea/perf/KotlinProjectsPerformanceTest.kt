/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf

class KotlinProjectsPerformanceTest : AbstractKotlinProjectsPerformanceTest() {

    fun testHelloWorldProject() {
        perfOpenProject("helloKotlin")

        // highlight
        perfHighlightFile("src/HelloMain.kt")
        perfHighlightFile("src/HelloMain2.kt")

        // change document
        perfChangeDocument("src/HelloMain2.kt", "type a single char") { doc ->
            val text = doc.text
            val offset = text.indexOf("println")

            doc.insertString(offset, "p\n")
        }

        perfChangeDocument("src/HelloMain.kt", "type val expression") { doc ->
            val text = doc.text
            val offset = text.indexOf("println")

            doc.insertString(offset, "val s =\n")
        }
    }

    fun testAutoCompletion() {
        perfAutoCompletion(
            "println",
            """
            fun bar(){
                printl<caret>
            }
            """,
            "n",
            """
            fun bar(){
                println
            }
            """
        )

        for (i in 2..5) {
            perfAutoCompletion(
                "println$i",
                """
            fun bar(){
                printl<caret>
            }
            """,
                "n",
                """
            fun bar(){
                println
            }
            """
            )
        }
    }

    fun testKotlinProject() {
        perfOpenProject("perfTestProject", "..")

        perfHighlightFile("compiler/psi/src/org/jetbrains/kotlin/psi/KtFile.kt")

        perfHighlightFile("compiler/psi/src/org/jetbrains/kotlin/psi/KtElement.kt")
    }


}