package pl.jardol.e2stream

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

class SleepTestAdminReceiver : DeviceAdminReceiver()

internal object SleepDevice {
    enum class Result { DISABLED, RECORDING, PERMISSION_MISSING, LOCKED }

    fun attempt(context: Context, enabled: Boolean, recording: Boolean): Result {
        if (!enabled) return Result.DISABLED
        if (recording) return Result.RECORDING
        val admin = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!admin.isAdminActive(ComponentName(context, SleepTestAdminReceiver::class.java))) {
            return Result.PERMISSION_MISSING
        }
        admin.lockNow()
        return Result.LOCKED
    }
}
