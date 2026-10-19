package org.miu.cookbook.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.miu.cookbook.data.CookbookRepository
import org.miu.cookbook.data.db.AppDatabase
import org.miu.cookbook.data.db.ContentSection
import org.miu.cookbook.data.db.SyncStateEntity
import org.miu.cookbook.data.image.ImageStore
import org.miu.cookbook.data.image.ImportedImage
import org.miu.cookbook.domain.meal.MealSlot
import org.miu.cookbook.domain.recipe.ContentLine
import org.miu.cookbook.domain.recipe.IngredientLine
import org.miu.cookbook.sync.model.SyncProtocol
import org.miu.cookbook.sync.server.ServerConfig
import org.miu.cookbook.sync.server.SyncServer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * 同步引擎的端到端测试：**进程内起一个真的服务端**（随机端口、真 SQLite、真 HTTP），
 * 再用两套独立的 AppDatabase + 图片目录假装两台设备。
 *
 * 不手写假服务端是刻意的：协议只有一份实现，假服务端里的「以为」会掩盖真问题
 * （比如分页、冲突判定、图片按哈希去重）。这里跑通的事，装机后基本就是那样。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: SyncServer
    private lateinit var base: String
    private val devices = mutableListOf<Device>()

    @Before
    fun setUp() {
        server = SyncServer(
            ServerConfig(
                port = 0,
                dataDir = temp.newFolder("server"),
                pairCode = PAIR_CODE,
                // 测试里不设限流：一个用例会连着发几十个请求
                apiPerMinute = 0,
                blobPerMinute = 0,
                pairPerMinute = 0,
            ),
        )
        base = "http://127.0.0.1:${server.start()}"
    }

    @After
    fun tearDown() {
        devices.forEach {
            it.scope.cancel()
            it.db.close()
        }
        server.close()
    }

    @Test
    fun `另一台设备拉下来的菜谱带着原料做法分类和图片`() = runBlocking {
        val a = newDevice("a")
        val b = newDevice("b")

        val category = a.repo.ensureCategory("家常菜")
        val recipeId = a.repo.createRecipe("红烧肉")
        a.repo.setIngredients(recipeId, listOf(IngredientLine("五花肉", "500", "克")))
        a.repo.setContent(
            recipeId,
            ContentSection.DIRECTIONS,
            listOf(ContentLine("焯水", level = 0), ContentLine("小火炖一小时", level = 1)),
        )
        a.repo.setCategories(recipeId, setOf(category))

        // 图片：自己造两个文件（内容随意，哈希就是它的身份），再挂到菜谱上
        File(a.dir, "images/pork.jpg").writeBytes(ByteArray(128) { (it * 7).toByte() })
        File(a.dir, "thumbs/pork.jpg").writeBytes(ByteArray(64) { (it * 3).toByte() })
        val sha = ImageStore.sha256Of(File(a.dir, "images/pork.jpg"))!!
        val thumbSha = ImageStore.sha256Of(File(a.dir, "thumbs/pork.jpg"))!!
        a.repo.addImage(
            recipeId,
            ImportedImage("images/pork.jpg", "thumbs/pork.jpg", 40, 30, sha, thumbSha),
        )

        a.pairAndSync(base)

        val report = b.pairAndSync(base)

        assertEquals(1, report.pulled)

        val row = b.repo.recipeList.first().single()
        assertEquals("红烧肉", row.recipe.title)
        assertEquals(sha, row.images.single().sha256)
        assertEquals(1, row.ingredients.size)
        assertEquals("五花肉", row.ingredients.single().name)
        assertEquals("家常菜", row.categories.single().name)

        val detail = b.repo.recipeDetail(row.recipe.id).first()
        assertNotNull("正文应该跟着一起下来", detail)
        assertEquals(
            listOf("焯水", "小火炖一小时"),
            detail!!.contentBlocks.map { it.text },
        )
        assertEquals(listOf(0, 1), detail.contentBlocks.map { it.level })

        // 图真的落盘了，而且落在内容地址上（路径换台设备就没用了，所以本地用哈希当文件名）
        val localImage = File(b.dir, "images/$sha.jpg")
        assertTrue("图片应该下载到本地：${localImage.path}", localImage.isFile)
        assertTrue(File(b.dir, "thumbs/$thumbSha.jpg").isFile)
    }

    @Test
    fun `两边都改过同一道菜时各留一份冲突副本`() = runBlocking {
        val a = newDevice("a")
        val b = newDevice("b")

        val recipeId = a.repo.createRecipe("红烧肉")
        a.pairAndSync(base)
        b.pairAndSync(base)

        // A 先改并同步上去
        a.repo.renameRecipe(recipeId, "红烧肉（砂锅版）")
        a.pairAndSync(base)

        // B 在不知道 A 改过的情况下也改了，然后同步
        val bRow = b.repo.recipeList.first().single()
        b.repo.renameRecipe(bRow.recipe.id, "红烧肉（电饭煲版）")
        val report = b.pairAndSync(base)

        assertTrue("应该报出至少一份冲突", report.conflicts >= 1)

        val titles = b.repo.recipeList.first().map { it.recipe.title }
        assertTrue(
            "应保留 B 的版本，并把 A 的版本变成副本：$titles",
            titles.contains("红烧肉（电饭煲版）"),
        )
        assertTrue(
            "副本名字后面要带标记：$titles",
            titles.any { it.endsWith(SyncProtocol.CONFLICT_SUFFIX) },
        )
        assertEquals(1, b.repo.conflictCopies.first())
    }

    @Test
    fun `A 删掉的菜在 B 变成墓碑，但 B 那天吃过的记录还在`() = runBlocking {
        val a = newDevice("a")
        val b = newDevice("b")

        val recipeId = a.repo.createRecipe("红烧肉")
        a.pairAndSync(base)
        b.pairAndSync(base)

        // B 自己也记了一笔「吃过」
        val bRow = b.repo.recipeList.first().single()
        b.repo.logMeal(bRow.recipe.id, epochDay = 20_000, slot = MealSlot.LUNCH)
        assertEquals(1, b.repo.countMealLogs(listOf(bRow.recipe.id)))

        a.repo.deleteRecipes(listOf(recipeId))
        a.pairAndSync(base)
        b.pairAndSync(base)

        assertTrue("B 的列表里应该看不到它了", b.repo.recipeList.first().isEmpty())
        assertEquals(
            "家人删菜不该顺手抹掉我的饮食记录",
            1,
            b.repo.mealHistory.first().size,
        )

        // 行还在，只是打了墓碑（本机删除也不物理删行，否则下次同步会把它当新菜又推上去）
        val tombstone = b.db.recipeDao().findByUuid(bRow.recipe.uuid)
        assertNotNull(tombstone)
        assertTrue(tombstone!!.deletedAt > 0)
    }

    @Test
    fun `第二次同步不会把同一条再拉一遍`() = runBlocking {
        val a = newDevice("a")
        val b = newDevice("b")

        a.repo.createRecipe("红烧肉")
        a.pairAndSync(base)

        val first = b.pairAndSync(base)
        assertEquals(1, first.pulled)

        val second = b.pairAndSync(base)
        assertEquals("水位推进了就不该重复拉", 0, second.pulled)
        assertEquals("没有改动就没什么可推的", 0, second.pushed)

        val third = a.pairAndSync(base)
        assertEquals(0, third.pulled)
        assertEquals(0, third.pushed)
        assertEquals(1, a.repo.recipeList.first().size)
    }

    @Test
    fun `v2 时代的老图没有哈希，同步前会补算出来再传`() = runBlocking {
        val a = newDevice("a")
        val b = newDevice("b")

        val recipeId = a.repo.createRecipe("红烧肉")
        // 假装这是升级上来的老数据：文件在，但图片行没有哈希
        File(a.dir, "images/old.jpg").writeBytes(ByteArray(96) { (it * 5).toByte() })
        File(a.dir, "thumbs/old.jpg").writeBytes(ByteArray(48) { (it * 11).toByte() })
        a.repo.addImage(
            recipeId,
            ImportedImage("images/old.jpg", "thumbs/old.jpg", 12, 9, "", ""),
        )

        val sha = ImageStore.sha256Of(File(a.dir, "images/old.jpg"))!!
        assertTrue("前提：图片行一开始确实没有哈希", a.repo.recipeList.first().single().images.single().sha256.isEmpty())

        a.pairAndSync(base)

        assertEquals("推之前应该先把哈希补上并写回", sha, a.repo.recipeList.first().single().images.single().sha256)

        b.pairAndSync(base)
        assertTrue("补算出来的哈希照样能传过去", File(b.dir, "images/$sha.jpg").isFile)
    }

    @Test
    fun `局域网地址连不上时自动落到公网地址`() = runBlocking {
        val a = newDevice("a")
        a.repo.createRecipe("红烧肉")

        // 端口 1 一定没人监听：连接会被立刻拒掉，正好模拟「不在家」
        val dead = "http://127.0.0.1:1"
        a.engine.pair(primaryUrl = base, localUrl = dead, code = PAIR_CODE, deviceName = "厨房的手机").getOrThrow()

        assertEquals("配对时就该落到能通的那个地址", base, a.credentials.lastGoodUrl)
        assertTrue("配完仍然算配对成功", a.credentials.paired)

        val report = a.engine.syncNow().getOrThrow()
        assertEquals(1, report.pushed)
        assertEquals(1, server.store().recipeCount())
    }

    @Test
    fun `地址按局域网优先排列，末尾才轮到兜底`() {
        val credentials = SyncCredentials(context, prefsName = "sync-credentials-order")
        credentials.localUrl = " http://192.168.1.10:8080/ "
        credentials.lastGoodUrl = "https://home.example.com"
        credentials.primaryUrl = "https://home.example.com/"

        assertEquals(
            listOf("http://192.168.1.10:8080", "https://home.example.com", "http://127.0.0.1:9"),
            credentials.candidateUrls("http://127.0.0.1:9"),
        )
    }

    @Test
    fun `编辑完请求同步会真的把改动推上去`() = runBlocking {
        val a = newDevice("a")
        a.pairAndSync(base)
        assertEquals(0, server.store().recipeCount())

        // 模拟「从编辑页退出来」：只发一个合并信号，不直接等同步结果
        a.repo.createRecipe("可乐鸡翅")
        a.engine.requestSync()

        val pushed = awaitUntil { server.store().recipeCount() == 1L }
        assertTrue("退出编辑后应该自己同步上去", pushed)

        // 连续请求多次也只该落一次，而且不报错
        a.repo.createRecipe("西红柿炒蛋")
        repeat(5) { a.engine.requestSync() }
        assertTrue("合并后的多次请求最终也要同步上去", awaitUntil { server.store().recipeCount() == 2L })
    }

    @Test
    fun `没配对时同步会失败并说清原因`() = runBlocking {
        val a = newDevice("a")
        val result = a.engine.syncNow()
        assertTrue(result.isFailure)
        assertEquals("还没配对", result.exceptionOrNull()?.message)
    }

    @Test
    fun `配对码不对就配不上`() = runBlocking {
        val a = newDevice("a")
        val result = a.engine.pair(base, "", "000000", "厨房的手机")
        assertTrue(result.isFailure)
        assertTrue("失败时不能留下半截令牌", !a.credentials.paired)
    }

    /** 只配对一次，之后每次都是普通同步（重复配对会把水位打回 0，那是另一回事）。 */
    private suspend fun Device.pairAndSync(serverUrl: String): SyncReport {
        if (!credentials.paired) engine.pair(serverUrl, "", PAIR_CODE, tag).getOrThrow()
        return engine.syncNow().getOrThrow()
    }


    @Test
    fun `同步不会占住调用方的线程`() = runBlocking {
        // 界面是在主线程上点「配对」「立即同步」的。网络调用要是不切线程，
        // Android 会直接抛 NetworkOnMainThreadException，而它的 message 是 null——
        // 界面上只会显示「未知原因」（1.1.0 的配对失败就是这么来的）。
        val device = newDevice("slow")

        // 一个只会「接住连接、拖着不答」的地址：让这一轮同步在 600ms 内一直飞着
        val socket = ServerSocket(0)
        val slowBase = "http://127.0.0.1:${socket.localPort}"
        val acceptor = Thread {
            runCatching {
                socket.accept().use { client ->
                    client.getInputStream().read(ByteArray(4096))
                    Thread.sleep(600)
                }
            }
        }.apply { isDaemon = true; start() }

        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            device.credentials.token = "token-from-server"
            device.credentials.primaryUrl = slowBase
            device.repo.saveSyncState(SyncStateEntity(serverUrl = slowBase, deviceId = "dev"))

            val job = launch(caller) { device.engine.syncNow() }

            // 同一个（单线程）调度器上再塞一件事：它要能在同步还没结束时跑起来
            val bystander = CompletableDeferred<Boolean>()
            val second = launch(caller) {
                delay(100)
                bystander.complete(true)
            }
            val ranWhileBusy = withTimeoutOrNull(1_500) { bystander.await() } == true
            assertTrue("同步把调用方线程占住了：网络调用必须切到 IO 线程", ranWhileBusy && job.isActive)

            job.join()
            second.join()
        } finally {
            caller.close()
            runCatching { socket.close() }
            acceptor.interrupt()
        }
    }

    private class Device(
        val tag: String,
        val db: AppDatabase,
        val repo: CookbookRepository,
        val engine: SyncEngine,
        val credentials: SyncCredentials,
        val dir: File,
        val scope: CoroutineScope,
    )

    private fun newDevice(tag: String): Device {
        val dir = temp.newFolder("device-$tag")
        // 真机上的 filesDir 是有这两个子目录的（ImageStore 自己建，这里先建好省得依赖它）
        File(dir, "images").mkdirs()
        File(dir, "thumbs").mkdirs()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, "cookbook-$tag.db")
            .allowMainThreadQueries()
            .build()
        val repo = CookbookRepository(db, dir)
        val credentials = SyncCredentials(context, prefsName = "sync-credentials-$tag")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SyncEngine(db, repo, dir, credentials, scope)

        return Device(tag, db, repo, engine, credentials, dir, scope).also { devices += it }
    }

    /** 后台同步是「过一会儿」发生的：轮询等它发生，而不是 sleep 一个拍脑袋的时长。 */
    private suspend fun awaitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(20)
        }
        return condition()
    }

    private companion object {
        const val PAIR_CODE = "246810"
    }
}
