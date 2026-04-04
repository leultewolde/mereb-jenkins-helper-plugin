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
}
