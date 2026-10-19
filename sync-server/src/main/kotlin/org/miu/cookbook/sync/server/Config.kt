package org.miu.cookbook.sync.server

import java.io.File

/** 服务端配置。命令行参数优先于环境变量（容器里两样都能用）。 */
data class ServerConfig(
    val port: Int = 8080,
    val dataDir: File = File("data"),
    /**
     * 固定配对码。留空则用数据库里的一次性配对码（启动时生成并写到 `pair-code.txt`）。
     * 家用 NAS 上填一个固定值最省事；想让配对码用过即废就留空。
     */
    val pairCode: String = "",
    /** 同步接口限流：每分钟每台设备多少次请求。 */
    val apiPerMinute: Int = 240,
    /** 图片上传/下载限流：单独一档，因为首次同步可能要传几百张图。 */
    val blobPerMinute: Int = 1200,
    /** 配对接口限流（按来源 IP）：配对码只有 6 位数字，不限流等于敞开让人爆破。 */
    val pairPerMinute: Int = 30,
    /**
     * 可信反代的地址。**只有来源地址在名单里才认 `X-Forwarded-For`**，
     * 否则伪造一个头就能绕过按 IP 的限流。支持单个 IP 或 IPv4 的 `a.b.c.d/nn`。
     */
    val trustedProxies: List<String> = listOf("127.0.0.1", "::1"),
    /** 墓碑保留天数，到期物理清掉。 */
    val tombstoneDays: Int = 30,
    /** 打日志用，也回给 /health。 */
    val version: String = "1",
) {
    companion object {
        const val USAGE = """
用法: cookbook-sync [选项]
  --port <端口>              监听端口（默认 8080，环境变量 COOKBOOK_PORT）
  --data <目录>              数据目录（默认 ./data，环境变量 COOKBOOK_DATA）
  --pair-code <6位数字>      固定配对码（默认用一次性配对码，环境变量 COOKBOOK_PAIR_CODE）
  --trusted-proxies <列表>   逗号分隔的可信反代地址（环境变量 COOKBOOK_TRUSTED_PROXIES）
  --help                     看这个
"""

        fun fromArgs(args: Array<String>, env: Map<String, String> = System.getenv()): ServerConfig {
            var cfg = ServerConfig(
                port = env["COOKBOOK_PORT"]?.toIntOrNull() ?: 8080,
                dataDir = File(env["COOKBOOK_DATA"] ?: "data"),
                pairCode = env["COOKBOOK_PAIR_CODE"].orEmpty(),
                apiPerMinute = env["COOKBOOK_API_PER_MINUTE"]?.toIntOrNull() ?: 240,
                blobPerMinute = env["COOKBOOK_BLOB_PER_MINUTE"]?.toIntOrNull() ?: 1200,
                trustedProxies = env["COOKBOOK_TRUSTED_PROXIES"]
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: listOf("127.0.0.1", "::1"),
                version = env["COOKBOOK_VERSION"] ?: "1",
            )
            var i = 0
            while (i < args.size) {
                val key = args[i]
                fun value(): String {
                    require(i + 1 < args.size) { "$key 后面要跟一个值" }
                    i += 1
                    return args[i]
                }
                when (key) {
                    "--port" -> cfg = cfg.copy(port = value().toInt())
                    "--data" -> cfg = cfg.copy(dataDir = File(value()))
                    "--pair-code" -> cfg = cfg.copy(pairCode = value())
                    "--trusted-proxies" ->
                        cfg = cfg.copy(trustedProxies = value().split(',').map { it.trim() }.filter { it.isNotEmpty() })

                    "--help", "-h" -> {
                        println(USAGE)
                        kotlin.system.exitProcess(0)
                    }

                    else -> error("不认识的参数：$key\n$USAGE")
                }
                i += 1
            }
            return cfg
        }
    }
}
