package org.miu.cookbook.sync.server

import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

/** 服务端入口。参数见 [ServerConfig.USAGE]。 */
fun main(args: Array<String>) {
    val config = ServerConfig.fromArgs(args)
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
