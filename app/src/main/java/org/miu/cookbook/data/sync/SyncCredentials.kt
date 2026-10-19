package org.miu.cookbook.data.sync

import android.content.Context

/**
 * 配对凭据（设备令牌 + 设备名）。
 *
 * **故意不放 Room**，理由有两个：
 * 1. 导入备份走的是 `clearAllTables()`，令牌放库里会被顺手清掉——「导个备份就掉线」；
 * 2. 令牌是密码级的东西，跟着备份 zip 导出到别处没有任何道理。
 *
 * 放在独立的 SharedPreferences（`MODE_PRIVATE`，卸载即失效）里刚刚好：
 * 服务端地址、同步游标这些「数据」放 Room，这份「凭据」放这里。
 */
class SyncCredentials(
    context: Context,
    /** 只有测试会传别的名字：同一个进程里要假装成两台设备。 */
    prefsName: String = PREF_NAME,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** 服务端发的一次性设备令牌，配对成功后才有。 */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    /** 配对这个服务端时用的设备名，断开时显示用。 */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DEVICE_NAME, value).apply()
        }

    val paired: Boolean get() = token.isNotEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREF_NAME = "sync-credentials"
        const val KEY_TOKEN = "device_token"
        const val KEY_DEVICE_NAME = "device_name"
    }
}
