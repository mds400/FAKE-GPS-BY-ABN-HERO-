package io.github.jqssun.gpssetter.xposed

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.jqssun.gpssetter.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.util.*
import kotlin.math.cos

object LocationHook {

    var newlat: Double = 45.0000
    var newlng: Double = 0.0000
    private const val pi = 3.14159265359
    private var accuracy: Float = 0.0f
    private val rand: Random = Random()
    private const val earth = 6378137.0
    private val settings = Xshare()
    private var mLastUpdated: Long = 0
    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    private val context by lazy { AndroidAppHelper.currentApplication() as Context }

    private fun updateLocation() {
        try {
            mLastUpdated = System.currentTimeMillis()
            val x = (rand.nextInt(50) - 15).toDouble()
            val y = (rand.nextInt(50) - 15).toDouble()
            val dlat = x / earth
            val dlng = y / (earth * cos(pi * settings.getLat / 180.0))
            newlat =
                if (settings.isRandomPosition) settings.getLat + (dlat * 180.0 / pi) else settings.getLat
            newlng =
                if (settings.isRandomPosition) settings.getLng + (dlng * 180.0 / pi) else settings.getLng
            accuracy = settings.accuracy!!.toFloat()

        } catch (e: Exception) {
            Timber.tag("GPS Setter")
                .e(e, "Failed to get XposedSettings for %s", context.packageName)
        }
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {

        // 1. استهداف خادم النظام الأساسي (System Server)
        if (lpparam.packageName == "android") { 
            XposedBridge.log("Hooking system server (Global Mode)")
            if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                if (System.currentTimeMillis() - mLastUpdated > 200) {
                    updateLocation()
                }

                if (Build.VERSION.SDK_INT < 34) {
                    val LocationManagerServiceClass = XposedHelpers.findClass("com.android.server.LocationManagerService", lpparam.classLoader)
                    
                    // منع تسريب بيانات الأقمار الصناعية الخام (GNSS)
                    for (method in LocationManagerServiceClass.declaredMethods) {
                        if (method.returnType == Boolean::class.java) {
                            if (method.name == "addGnssBatchingCallback" || method.name == "addGnssMeasurementsListener" || method.name == "addGnssNavigationMessageListener") {
                                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) { param.result = false }
                                })
                            }
                        }
                    }

                    XposedHelpers.findAndHookMethod("com.android.server.LocationManagerService.Receiver", lpparam.classLoader, "callLocationChangedLocked", Location::class.java, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            injectMockLocation(param)
                        }
                    })
                } else {
                    // دعم أندرويد 14 وما فوق (SDK 34+)
                    val LocationManagerServiceClass = XposedHelpers.findClass("com.android.server.location.LocationManagerService", lpparam.classLoader)
                    
                    // منع تسريب GNSS في أندرويد الحديث
                    for (method in LocationManagerServiceClass.declaredMethods) {
                        if (method.returnType == Void::class.java) {
                            if (method.name == "startGnssBatch" || method.name == "addGnssAntennaInfoListener" || method.name == "addGnssMeasurementsListener" || method.name == "addGnssNavigationMessageListener") {
                                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
                                })
                            }
                        }
                    }

                    XposedHelpers.findAndHookMethod(LocationManagerServiceClass, "injectLocation", Location::class.java, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            injectMockLocation(param)
                        }
                    })
                }
            }
        } 
        // 2. استهداف خدمات جوجل بلاي لضمان التزييف الشامل (Fused Location)
        else if (lpparam.packageName == "com.google.android.gms") {
            if (settings.isStarted) {
                // نقوم بحقن الكلاسات الخاصة بخدمات جوجل لضمان عدم تجاوز التزييف
                try {
                    val FusedLocationClass = XposedHelpers.findClass("com.google.android.location.fused.FusedLocationProvider", lpparam.classLoader)
                    XposedHelpers.findAndHookMethod(FusedLocationClass, "reportLocation", Location::class.java, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()
                            injectMockLocation(param)
                        }
                    })
                } catch (e: Exception) {
                    XposedBridge.log("Failed to hook GMS Fused Location: ${e.message}")
                }
            }
        }
    }

    // دالة مساعدة لتقليل تكرار الكود، تقوم بتجهيز الموقع المزيف وتخطي اكتشافه
    private fun injectMockLocation(param: XC_MethodHook.MethodHookParam) {
        lateinit var location: Location
        lateinit var originLocation: Location
        if (param.args[0] == null) {
            location = Location(LocationManager.GPS_PROVIDER)
            location.time = System.currentTimeMillis() - 300
        } else {
            originLocation = param.args[0] as Location
            location = Location(originLocation.provider)
            location.time = originLocation.time
            location.accuracy = accuracy
            location.bearing = originLocation.bearing
            location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
            location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
            location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
        }

        location.latitude = newlat
        location.longitude = newlng
        location.altitude = 0.0
        location.speed = 0F
        location.speedAccuracyMetersPerSecond = 0F
        
        try {
            HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
        } catch (e: Exception) {
            XposedBridge.log("LocationHook: unable to set mock $e")
        }
        param.args[0] = location
    }
}
        try {
            HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
        } catch (e: Exception) {
            // تجاهل الخطأ إذا فشل
        }
        
        return location
    }
}
