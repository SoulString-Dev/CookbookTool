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

    /** 配对时用的地址，通常是公网地址或域名——在外面靠它。 */
    var primaryUrl: String
        get() = prefs.getString(KEY_PRIMARY_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PRIMARY_URL, value).apply()
        }

    /** 局域网地址，可以留空。在家优先走它，省一趟公网往返（路由器不一定支持回环）。 */
    var localUrl: String
        get() = prefs.getString(KEY_LOCAL_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LOCAL_URL, value).apply()
        }

    /** 最近一次连上的地址。下次先试它，免得每次都先撞一遍不通的那条。 */
    var lastGoodUrl: String
        get() = prefs.getString(KEY_LAST_GOOD_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LAST_GOOD_URL, value).apply()
        }

    /**
     * 这一轮按什么顺序试地址。
     *
     * 「局域网 → 上次成功的 → 配对时的地址 → 库里记的地址」：
     * 在家第一条就通，一条内网请求完事；在外面局域网那条会很快失败（不在同一个网段时通常几百毫秒
     * 就报「连不上」，最多等一个短超时），自然落到公网地址上。去重是为了别对同一个地址试两遍。
     */
    fun candidateUrls(fallback: String = ""): List<String> =
        listOf(localUrl, lastGoodUrl, primaryUrl, fallback)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()

    val paired: Boolean get() = token.isNotEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREF_NAME = "sync-credentials"
        const val KEY_TOKEN = "device_token"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_PRIMARY_URL = "primary_url"
        const val KEY_LOCAL_URL = "local_url"
        const val KEY_LAST_GOOD_URL = "last_good_url"
    }
}
