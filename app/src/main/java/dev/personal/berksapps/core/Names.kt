package dev.personal.berksapps.core

object Names {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*([.][A-Za-z][A-Za-z0-9_]*)+$")
    private val CLASS = Regex("^[A-Za-z0-9_.$]+$")

    fun isPackageName(value: String): Boolean = value.length <= 255 && PACKAGE.matches(value)

    fun isComponentName(value: String): Boolean {
        val slash = value.indexOf('/')
        if (slash <= 0 || slash != value.lastIndexOf('/')) return false
        val cls = value.substring(slash + 1)
        return isPackageName(value.substring(0, slash)) && cls.isNotEmpty() && CLASS.matches(cls)
    }
}
