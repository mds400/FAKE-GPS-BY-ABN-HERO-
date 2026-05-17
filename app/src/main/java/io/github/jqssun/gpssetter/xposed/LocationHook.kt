package io.github.jqssun.gpssetter.xposed

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.jqssun.gpssetter.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber

object LocationHook {
    var newlat: Double = 45.0000
    var newlng: Double = 0.0000
    private var accuracy: Float = 0.0f
    private val settings = Xshare()
    private var mLastUpdated: Long = 0

    private fun updateLocation() {
        try {
            mLastUpdated = System.currentTimeMillis()
            newlat = settings.getLat
            newlng = settings.getLng
            accuracy = settings.accuracy ?: 0.0f
        } catch (e: Exception) {
            Timber.tag("GPS Setter").e(e, "Failed to update location settings")
        }
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // التحقق من تفعيل ميزة التزييف من الإعدادات
        if (!settings.isStarted) return

        // تحديث الإحداثيات من الإعدادات
        if (System.currentTimeMillis() - mLastUpdated > 200) {
            updateLocation()
        }

        val classLoader = lpparam.classLoader

        // استهداف 'android' (System Server) هو المفتاح للتزييف الشامل
        if (lpparam.packageName == "android") {
            XposedBridge.log("GPS Setter: Hooking System Server for Global Spoofing")
            
            val locationManagerServiceClass = XposedHelpers.findClass(
                "com.android.server.LocationManagerService",
                classLoader
            )

            // Hook لدالة getLastLocation - لضمان إرجاع الموقع المزيف لجميع التطبيقات
            XposedHelpers.findAndHookMethod(
                locationManagerServiceClass,
                "getLastLocation",
                LocationRequest::class.java,
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val location = createMockLocation(LocationManager.GPS_PROVIDER)
                        param.result = location
                    }
                }
            )

            // Hook لدالة callLocationChangedLocked - لتحديث الموقع بشكل مستمر
            XposedHelpers.findAndHookMethod(
                "com.android.server.LocationManagerService\$Receiver",
                classLoader,
                "callLocationChangedLocked",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val originalLocation = param.args[0] as? Location
                        val mockLocation = createMockLocation(originalLocation?.provider ?: LocationManager.GPS_PROVIDER)
                        
                        // نسخ البيانات الإضافية من الموقع الأصلي إذا وجد
                        originalLocation?.let {
                            mockLocation.time = it.time
                            mockLocation.elapsedRealtimeNanos = it.elapsedRealtimeNanos
                        }
                        
                        param.args[0] = mockLocation
                    }
                }
            )
        }

        // يمكن إضافة المزيد من الـ Hooks هنا للعمليات الأخرى مثل com.google.android.gms
    }

    private fun createMockLocation(provider: String): Location {
        val location = Location(provider)
        location.time = System.currentTimeMillis()
        location.latitude = newlat
        location.longitude = newlng
        location.altitude = 0.0
        location.speed = 0f
        location.accuracy = accuracy
        
        // استخدام HiddenApiBypass لتعيين setIsFromMockProvider إلى false لتجنب الكشف
        try {
            HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
        } catch (e: Exception) {
            // تجاهل الخطأ إذا فشل
        }
        
        return location
    }
}
