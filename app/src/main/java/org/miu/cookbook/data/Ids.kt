package org.miu.cookbook.data

import java.util.UUID

/**
 * 跨设备身份。建一条新记录时生成一次，之后这行走到哪台设备都还认得是自己。
 *
 * 用标准带连字符的 UUID：36 字符相比 32 位紧凑写法只多一点体积，
 * 但日志、curl 输出、数据库里都一眼能和别的十六进制串区分开。
 */
fun newUuid(): String = UUID.randomUUID().toString()
