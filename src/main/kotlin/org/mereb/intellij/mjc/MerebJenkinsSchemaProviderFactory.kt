package org.mereb.intellij.mjc

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import java.net.URL

class MerebJenkinsSchemaProviderFactory : JsonSchemaProviderFactory {

    override fun getProviders(project: Project): List<JsonSchemaFileProvider> {
        return listOf(MerebJenkinsSchemaFileProvider())
    }
}

class MerebJenkinsSchemaFileProvider : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean {
        return MerebJenkinsConfigPaths.isSchemaTarget(file)
    }

    override fun getName(): String = "Mereb Jenkins configuration schema"

    fun getSchemaResource(): URL? {
        return javaClass.classLoader.getResource(SCHEMA_RESOURCE)
    }

    override fun getSchemaFile(): VirtualFile? {
        val resource = getSchemaResource() ?: return null
        return VfsUtil.findFileByURL(resource)
    }

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema

    private companion object {
        const val SCHEMA_RESOURCE = "schemas/ci.schema.json"
    }
}
