package org.miu.cookbook.sync.server

import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

/** 服务端入口。参数见 [ServerConfig.USAGE]。 */
fun main(args: Array<String>) {
    val config = ServerConfig.fromArgs(args)

    // 备份模式：导出快照就退出，不起服务。适合放在 NAS 的定时任务里，对着**正在跑的**实例执行：
    //   docker compose exec cookbook-sync java -jar /app/cookbook-sync.jar --backup /data/backup.db
    config.backup?.let { target ->
        val db = File(config.dataDir, Store.DB_NAME)
        if (!db.isFile) {
            System.err.println("数据目录里没有 ${db.path}，没什么好备份的")
            kotlin.system.exitProcess(1)
        }
        Store(config.dataDir).use { it.backupTo(target) }
        log("备份好了：${target.absolutePath}（${target.length()} 字节）")
        return
    }

    val server = SyncServer(config)
    val port = server.start(InetSocketAddress(config.port))

    // 配对码：固定码就照配置打；一次性码则把「当前有效的」落一份到文件，方便 cat 查看
    val code = server.currentPairCode()
    runCatching { server.pairCodeFile().writeText(code + "\n") }

    log("cookbook-sync（版本 ${config.version}）已启动")
    log("  监听：0.0.0.0:$port")
    log("  数据：${config.dataDir.absolutePath}")
    if (config.pairCode.isEmpty()) {
        log("  配对码：$code（用过即换，也写在 ${server.pairCodeFile().path}）")
    } else {
        log("  配对码：$code（固定，来自配置）")
    }
    // 验收脚本要抓这一行
    println("PAIR_CODE=$code")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log("收到退出信号，关服务")
            server.close()
        },
    )
    CountDownLatch(1).await()
}
