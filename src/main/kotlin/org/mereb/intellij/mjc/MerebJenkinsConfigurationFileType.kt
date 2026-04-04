package org.mereb.intellij.mjc

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import org.jetbrains.annotations.NonNls
import org.jetbrains.yaml.YAMLLanguage
import javax.swing.Icon

class MerebJenkinsConfigurationFileType private constructor() : LanguageFileType(YAMLLanguage.INSTANCE) {

    override fun getName(): String = "Mereb Jenkins Configuration"

    override fun getDescription(): String = "Mereb Jenkins YAML configuration"

    override fun getDefaultExtension(): @NonNls String = "mjc"

    override fun getIcon(): Icon = IconHolder.FILE_ICON

    companion object {
        @JvmField
        val INSTANCE = MerebJenkinsConfigurationFileType()
    }

    private object IconHolder {
        val FILE_ICON: Icon = IconLoader.getIcon("/icons/mereb-config.svg", MerebJenkinsConfigurationFileType::class.java)
    }
}
