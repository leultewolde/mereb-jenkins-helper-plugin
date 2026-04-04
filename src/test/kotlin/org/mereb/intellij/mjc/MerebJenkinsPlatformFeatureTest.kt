package org.mereb.intellij.mjc

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MerebJenkinsPlatformFeatureTest : BasePlatformTestCase() {

    fun `test documentation provider returns recipe docs`() {
        val file = myFixture.tempDirFixture.createFile(
            ".ci/ci.mjc",
            """
            version: 1
            recipe: service
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(file)
        val offset = myFixture.file.text.indexOf("service")
        val element = myFixture.file.findElementAt(offset)
        val doc = MerebJenkinsDocumentationProvider().generateDoc(element, element)

        assertNotNull(doc)
        assertTrue(doc?.contains("Declares the primary pipeline executor") == true)
    }

    fun `test completion suggests recipe values`() {
        val file = myFixture.tempDirFixture.createFile(
            ".ci/ci.mjc",
            """
            version: 1
            recipe: <caret>
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(file)

        myFixture.completeBasic()

        val lookupItems = myFixture.lookupElementStrings.orEmpty()
        if (lookupItems.isNotEmpty()) {
            assertContainsElements(lookupItems, "service")
        } else {
            assertTrue(myFixture.editor.document.text.contains("recipe: service"))
        }
    }

    fun `test semantic highlight appears on invalid recipe value`() {
        val file = myFixture.tempDirFixture.createFile(
            ".ci/ci.mjc",
            """
            version: 1
            recipe: services
            image:
              repository: registry.example.com/demo
            deploy:
              dev:
                namespace: apps-dev
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(file)

        val highlights = myFixture.doHighlighting()

        assertTrue(highlights.any { (it.description ?: "").contains("Recipe must be one of") || (it.description ?: "").contains("recipe") })
    }
}
