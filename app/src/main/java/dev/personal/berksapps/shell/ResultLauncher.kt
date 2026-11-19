package dev.personal.berksapps.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import dev.personal.berksapps.adb.ShellCommand
import dev.personal.berksapps.install.AndroidAutoRule
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

object ResultLauncher {
    private const val OK = "ok"

    @JvmStatic
    fun main(args: Array<String>) {
        val message = try {
            launch(args)
        } catch (e: InvocationTargetException) {
            (e.cause ?: e).toString()
        } catch (t: Throwable) {
            t.toString()
        }
        println(message)
        exitProcess(if (message == OK) 0 else 1)
    }

    @SuppressLint("PrivateApi", "UseKtx")
    private fun launch(args: Array<String>): String {
        if (args.size != 3) return "wrong arguments"
        val component = ComponentName.unflattenFromString(args[0]) ?: return "bad component"
        val uri = Uri.parse(args[1])
        val requestCode = args[2].toInt()
        val token = fetchToken() ?: return "no activity token"
        val intent = Intent("android.intent.action.INSTALL_PACKAGE")
        intent.component = component
        intent.setDataAndType(uri, ShellCommand.APK_MIME)
        intent.putExtra("android.intent.extra.INSTALLER_PACKAGE_NAME", AndroidAutoRule.VENDING)
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        intent.putExtra("android.intent.extra.INSTALL_REASON", 1)
        intent.putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://" + AndroidAutoRule.VENDING)
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)

        val taskManager = Class.forName("android.app.ActivityTaskManager")
            .getMethod("getService").invoke(null) ?: return "no activity task manager"
        for (method in taskManager.javaClass.methods) {
            if (method.name != "startActivity") continue
            val types = method.parameterTypes
            if (types.size == 11 && types[3] == Intent::class.java) {
                val result = method.invoke(
                    taskManager,
                    null, AndroidAutoRule.SHELL, null, intent, ShellCommand.APK_MIME,
                    token, null, requestCode, 0, null, null,
                ) as Int
                return if (result >= 0) OK else "startActivity returned $result"
            }
        }
        return "no usable startActivity"
    }

    private fun fetchToken(): IBinder? {
        val activityManager = ActivityManager::class.java.getMethod("getService").invoke(null)
            ?: return null
        val holder = activityManager.javaClass.getMethod(
            "getContentProviderExternal",
            String::class.java,
            Int::class.javaPrimitiveType,
            IBinder::class.java,
            String::class.java,
        ).invoke(activityManager, TokenProvider.AUTHORITY, 0, null, "berksapps") ?: return null
        val provider = holder.javaClass.getField("provider").get(holder) ?: return null
        try {
            val result = callProvider(provider) ?: return null
            return result.getBinder(TokenProvider.KEY_TOKEN)
        } finally {
            try {
                activityManager.javaClass.getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java,
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(activityManager, TokenProvider.AUTHORITY, null, 0)
            } catch (ignored: Throwable) {
                Unit
            }
        }
    }

    private fun callProvider(provider: Any): Bundle? {
        val extras = Bundle()
        for (method in provider.javaClass.methods) {
            if (method.name != "call") continue
            val types = method.parameterTypes
            if (Build.VERSION.SDK_INT >= 31 && types.size == 5 &&
                types[0] == AttributionSource::class.java
            ) {
                val source = AttributionSource.Builder(Process.myUid())
                    .setPackageName(AndroidAutoRule.SHELL)
                    .build()
                return method.invoke(
                    provider, source, TokenProvider.AUTHORITY, TokenProvider.METHOD_TOKEN, null, extras,
                ) as Bundle?
            }
            if (types.size == 6 && types[0] == String::class.java) {
                return method.invoke(
                    provider,
                    AndroidAutoRule.SHELL, null, TokenProvider.AUTHORITY,
                    TokenProvider.METHOD_TOKEN, null, extras,
                ) as Bundle?
            }
        }
        return null
    }
}
