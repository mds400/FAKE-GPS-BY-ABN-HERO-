package io.github.jqssun.gpssetter.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.jqssun.gpssetter.BuildConfig

class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // قائمة بالعمليات التي يجب أن يتم تطبيق الـ Hook عليها بشكل دائم لتمكين التزييف الشامل
        val targetPackages = listOf(
            "android", // System Server - العملية الأهم للتزييف الشامل
            "com.android.phone", // خدمات الهاتف
            "com.android.location.fused", // خدمة الموقع المدمجة
            "com.google.android.gms", // خدمات جوجل بلاي
            BuildConfig.APPLICATION_ID // تطبيقنا الخاص لإدارة الإعدادات
        )

        // تطبيق الـ Hook على تطبيقنا الخاص لإدارة الإعدادات (لأغراض داخلية)
        if (lpparam.packageName == BuildConfig.APPLICATION_ID) {
            XposedHelpers.findAndHookMethod("io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel", lpparam.classLoader, "init", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
        }

        // تطبيق الـ Hook على خدمات الموقع في العمليات المستهدفة
        // هذا هو التعديل الجوهري الذي يسمح بالعمل على مستوى النظام (System-wide)
        if (targetPackages.contains(lpparam.packageName)) {
            XposedBridge.log("GPS Setter: Injecting hooks into ${lpparam.packageName}")
            LocationHook.initHooks(lpparam)
        }
    }
}
