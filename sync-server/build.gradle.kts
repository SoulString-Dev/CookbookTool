import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 版本同 :sync-model：复用根项目 classpath 上的 Kotlin 插件
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("org.miu.cookbook.sync.server.MainKt")
}

dependencies {
    implementation(project(":sync-model"))
    implementation(libs.kotlinx.serialization.json)
    // HTTP 用 JDK 自带的 com.sun.net.httpserver（见 Server.kt 里的说明），数据库用 SQLite
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.junit)
}

/**
 * 打一个能直接 `java -jar` 的胖包：Dockerfile 里只拷这一个文件，不用装 Gradle。
 *
 * 没用 shadow 插件——本机拉不到新依赖，手工展开运行时 classpath 就够（服务端只有
 * kotlinx-serialization 与 sqlite-jdbc 两个第三方件）。
 */
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "把服务端连同依赖打成一个可执行 jar"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "org.miu.cookbook.sync.server.MainKt")
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    // 依赖包里的签名文件与 module-info 对合并后的包没意义，留着反而会让 JVM 校验失败
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    exclude("META-INF/versions/*/module-info.class", "module-info.class")
}
