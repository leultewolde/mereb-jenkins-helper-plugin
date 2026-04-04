package org.mereb.intellij.mjc

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement

class MerebJenkinsDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = element ?: originalElement ?: return null
        val file = target.containingFile?.virtualFile ?: return null
        if (!MerebJenkinsConfigPaths.isSchemaTarget(file)) return null

        val path = MerebJenkinsPsiUtils.elementPathString(target) ?: return null
        val doc = when {
            path == "recipe" -> """
                <h2>recipe</h2>
                <p>Declares the primary pipeline executor. Valid values: <code>build</code>, <code>package</code>, <code>image</code>, <code>service</code>, <code>microfrontend</code>, <code>terraform</code>.</p>
                <p>Mereb recommendation: set this explicitly even when the runtime can auto-detect it.</p>
            """
            path == "delivery.mode" -> """
                <h2>delivery.mode</h2>
                <p>Controls how promotion rules are interpreted. Common values are <code>staged</code> and <code>custom</code>.</p>
                <p>Runtime note: in staged mode, some per-environment keys like <code>when</code>, <code>autoPromote</code>, and <code>approval</code> are ignored.</p>
            """
            path == "release.autoTag.bump" -> """
                <h2>release.autoTag.bump</h2>
                <p>Determines the semantic version bump used when auto-tagging. Typical values are <code>patch</code>, <code>minor</code>, and <code>major</code>.</p>
                <p>If omitted, Mereb Jenkins defaults to <code>patch</code>.</p>
            """
            path.startsWith("deploy.") -> """
                <h2>deploy</h2>
                <p>Defines service deployment environments and their order.</p>
                <p>Runtime note: under <code>delivery.mode: staged</code>, environment-level <code>when</code>, <code>autoPromote</code>, and <code>approval</code> are ignored.</p>
            """
            path.startsWith("microfrontend.environments") || path.startsWith("microfrontend.") -> """
                <h2>microfrontend</h2>
                <p>Configures remote publishing environments, ordering, and CDN/public base settings.</p>
                <p>Runtime note: in staged mode, environment-level <code>when</code> and <code>approval</code> are ignored.</p>
            """
            path.startsWith("terraform.environments") || path.startsWith("terraform.") -> """
                <h2>terraform</h2>
                <p>Configures Terraform path, execution options, and environment sequencing.</p>
                <p>Use <code>terraform.environments</code> for per-environment plan/apply settings and <code>terraform.order</code> to control execution order.</p>
            """
            else -> null
        }
        return doc?.trimIndent()
    }
}

