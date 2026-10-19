package org.miu.cookbook.sync.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配置解析：容器里命令行参数和环境变量都会用，别让它们打架。 */
class ConfigTest {

    @Test
    fun `默认是本地 8080 与相对数据目录`() {
        val cfg = ServerConfig.fromArgs(emptyArray(), emptyMap())
        assertEquals(8080, cfg.port)
        assertEquals(File("data"), cfg.dataDir)
        assertEquals("", cfg.pairCode)
        assertEquals(listOf("127.0.0.1", "::1"), cfg.trustedProxies)
        assertNull(cfg.backup)
        assertFalse(cfg.requireHttps)
    }

    @Test
    fun `环境变量都会被读进来`() {
        val cfg = ServerConfig.fromArgs(
            emptyArray(),
            mapOf(
                "COOKBOOK_PORT" to "9000",
                "COOKBOOK_DATA" to "/volume1/cookbook",
                "COOKBOOK_PAIR_CODE" to "246810",
                "COOKBOOK_API_PER_MINUTE" to "60",
                "COOKBOOK_REQUIRE_HTTPS" to "1",
                "COOKBOOK_TRUSTED_PROXIES" to "127.0.0.1, 172.16.0.0/12",
            ),
        )
        assertEquals(9000, cfg.port)
        assertEquals(File("/volume1/cookbook"), cfg.dataDir)
        assertEquals("246810", cfg.pairCode)
        assertEquals(60, cfg.apiPerMinute)
        assertTrue(cfg.requireHttps)
        assertEquals(listOf("127.0.0.1", "172.16.0.0/12"), cfg.trustedProxies)
    }

    @Test
    fun `命令行参数压过环境变量，没给的还是用环境变量`() {
        val env = mapOf("COOKBOOK_PORT" to "9000", "COOKBOOK_DATA" to "/volume1/cookbook")
        val cfg = ServerConfig.fromArgs(arrayOf("--port", "8080", "--pair-code", "111111"), env)
        assertEquals(8080, cfg.port)
        assertEquals("111111", cfg.pairCode)
        assertEquals(File("/volume1/cookbook"), cfg.dataDir)
    }

    @Test
    fun `require-https 认 1 和 true，别的都算关`() {
        assertTrue(ServerConfig.fromArgs(arrayOf("--require-https"), emptyMap()).requireHttps)
        assertTrue(ServerConfig.fromArgs(emptyArray(), mapOf("COOKBOOK_REQUIRE_HTTPS" to "true")).requireHttps)
        assertFalse(ServerConfig.fromArgs(emptyArray(), mapOf("COOKBOOK_REQUIRE_HTTPS" to "0")).requireHttps)
        assertFalse(ServerConfig.fromArgs(emptyArray(), mapOf("COOKBOOK_REQUIRE_HTTPS" to "")).requireHttps)
    }

    @Test
    fun `backup 可以从参数或环境变量来，默认是关的`() {
        assertNull(ServerConfig.fromArgs(emptyArray(), emptyMap()).backup)
        assertEquals(File("/tmp/b.db"), ServerConfig.fromArgs(arrayOf("--backup", "/tmp/b.db"), emptyMap()).backup)
        assertEquals(
            File("/data/x.db"),
            ServerConfig.fromArgs(emptyArray(), mapOf("COOKBOOK_BACKUP" to "/data/x.db")).backup,
        )
    }
}
