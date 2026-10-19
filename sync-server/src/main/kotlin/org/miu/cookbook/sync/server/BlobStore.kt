package org.miu.cookbook.sync.server

import java.io.File

/**
 * 按内容寻址的图片仓库：文件名就是内容的 sha256，所以同一张图谁也不会有第二份。
 *
 * 目录按哈希前两位分一层（`ab/abcd…`），不然几万张图堆在一个目录里，
 * 某些 NAS 的文件系统会很难看。
 */
class BlobStore(private val root: File) {

    init {
        root.mkdirs()
    }

    fun file(sha256: String): File = File(File(root, sha256.substring(0, 2)), sha256)

    fun exists(sha256: String): Boolean = isSha256(sha256) && file(sha256).isFile

    /**
     * 落盘。**先核对内容哈希再写**：客户端算错了哈希、或者传了半截文件，
     * 都要在这里挡住，否则以后所有人都会拿到一份坏图。
     *
     * @return 内容哈希与文件名不符时返回 false
     */
    fun put(sha256: String, bytes: ByteArray): Boolean {
        if (!isSha256(sha256)) return false
        if (sha256Hex(bytes) != sha256) return false
        val target = file(sha256)
        if (target.isFile && target.length() == bytes.size.toLong()) return true
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile("blob", ".tmp", target.parentFile)
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                // 有的文件系统上 rename 目标已存在会失败，先删再改名
                target.delete()
                check(tmp.renameTo(target)) { "写不进图片目录：${target.absolutePath}" }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return true
    }

    fun read(sha256: String): ByteArray? {
        if (!isSha256(sha256)) return null
        val target = file(sha256)
        return if (target.isFile) target.readBytes() else null
    }

    fun delete(sha256: String) {
        if (isSha256(sha256)) file(sha256).delete()
    }

    fun all(): List<String> =
        root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.map { it.name }.toList()

    /**
     * 清理没人引用的图片。
     *
     * **只删够旧的**：客户端是先传图再推记录的（顺序反了对方会拉到指向空图的记录），
     * 刚传上来还没被任何记录引用是正常状态。
     *
     * @param keep 仍被引用的哈希
     * @return 删掉几个
     */
    fun deleteUnreferencedOlderThan(cutoffMs: Long, keep: Set<String>): Int {
        var removed = 0
        for (name in all()) {
            if (name in keep) continue
            val target = file(name)
            if (target.lastModified() >= cutoffMs) continue
            if (target.delete()) removed += 1
        }
        return removed
    }
}
