package com.standby.clock

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/** Device admin limited to force-lock (see res/xml/device_admin.xml), used to lock a charging phone on timeout. */
class LockAdmin : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) = ComponentName(context, LockAdmin::class.java)

        fun isActive(context: Context) =
            context.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(context))
    }
}
