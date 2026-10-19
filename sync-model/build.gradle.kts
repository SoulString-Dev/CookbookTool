import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 这里故意不写版本：根项目已经通过 kotlin-android 把 Kotlin 插件放上了 classpath，
    // 子项目声明同一个 id 即可复用（本机拉不到 org.jetbrains.kotlin.jvm 的插件标记包）。
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 纯 Kotlin/JVM 模块，App（Android）与服务端共用。字节码对齐 :app 的 17。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 是 api 不是 implementation：用这个模块的人要能直接拿到 @Serializable 的注解与 Json
    api(libs.kotlinx.serialization.json)
}
