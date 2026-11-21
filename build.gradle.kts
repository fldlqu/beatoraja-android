import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application") version "9.3.1"
}

android {
    namespace = "bms.player.beatoraja"
    compileSdk = 36

    defaultConfig {
        applicationId = "bms.player.beatoraja"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.9.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDir("src/main/java")
            assets.srcDir("src/main/assets")
            // IR plugin archives converted to DEX by dexIrPlugins (empty when ir/ has no jars).
            assets.srcDir(layout.buildDirectory.dir("generated/irAssets").get().asFile)
            // Non-class plugin resources are exposed to plugins through the application class loader.
            resources.srcDir(layout.buildDirectory.dir("generated/irResources").get().asFile)
            // Native libraries unpacked from the libGDX Maven artifacts by copyAndroidNatives.
            jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs").get().asFile)
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Reflection-heavy game code (Lua bindings, bean row mapping) is not minified yet.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

val gdxVersion = "1.14.2"
val natives: Configuration by configurations.creating
// D8 (shipped inside R8) converts the supplied IR plugin archives to DEX at build time.
val irDex: Configuration by configurations.creating
dependencies {
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-freetype:$gdxVersion")
    implementation("com.badlogicgames.gdx-controllers:gdx-controllers-core:2.2.4")
    implementation("com.badlogicgames.gdx-controllers:gdx-controllers-android:2.2.4")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.badlogicgames.jlayer:jlayer:1.0.2-gdx") { isTransitive = false }
    implementation("org.jcraft:jorbis:0.0.17") { isTransitive = false }
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.junrar:junrar:8.1.2")
    // Pure-Java libraries that are not on Maven Central in a usable form.
    implementation(files(
        "libs/jbms-parser.jar",
        "libs/jflac-codec-1.5.3.jar",
        "libs/luaj-jse-3.0.2-custom.jar"
    ))
    implementation(files(layout.buildDirectory.file("generated/libs/jbmstable-parser.jar").get().asFile))
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-arm64-v8a")
    irDex("com.android.tools:r8:8.11.18")
}

// jbms-parser and jbmstable-parser both embed Jackson; the dexer rejects duplicate classes.
// Keep the copy from jbms-parser and strip the embedded one from the table parser.
val filteredTableParser by tasks.registering {
    val sourceJar = rootProject.file("libs/jbmstable-parser.jar")
    val outputJar = layout.buildDirectory.file("generated/libs/jbmstable-parser.jar")
    inputs.file(sourceJar)
    outputs.file(outputJar)
    doLast {
        val out = outputJar.get().asFile
        out.parentFile.mkdirs()
        ZipFile(sourceJar).use { zip ->
            ZipOutputStream(out.outputStream()).use { zos ->
                val written = HashSet<String>()
                for (entry in zip.entries()) {
                    if (entry.name.startsWith("com/fasterxml/")) continue
                    if (!written.add(entry.name)) continue
                    zos.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) zip.getInputStream(entry).copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
    }
}

val copyAndroidNatives by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/jniLibs/arm64-v8a")
    inputs.files(natives)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.mkdirs()
        natives.files.forEach { jar -> copy { from(zipTree(jar)); into(out) } }
    }
}

tasks.named("preBuild") { dependsOn(copyAndroidNatives, filteredTableParser) }

// Converts IR plugin JARs placed in ir/ into DEX plugin archives bundled in assets/ir.
// Multi-DEX plugin archives are packaged as-is; the runtime class loader loads all of their DEX files.
val dexIrPlugins by tasks.registering {
    val pluginDirectory = rootProject.file("ir")
    val outputDirectory = layout.buildDirectory.dir("generated/irAssets/ir")
    val resourcesDirectory = layout.buildDirectory.dir("generated/irResources")
    inputs.files(fileTree(pluginDirectory) { include("*.jar") })
    outputs.dir(outputDirectory)
    outputs.dir(resourcesDirectory)
    doLast {
        val output = outputDirectory.get().asFile
        // Remove stale archives from previous plugin sets (e.g. removed third-party plugins).
        output.deleteRecursively()
        output.mkdirs()
        resourcesDirectory.get().asFile.deleteRecursively()
        resourcesDirectory.get().asFile.mkdirs()
        val pluginJars = fileTree(pluginDirectory) { include("*.jar") }.files.sortedBy { it.name }
        if (pluginJars.isEmpty()) {
            logger.lifecycle("dexIrPlugins: no IR plugin jars in ir/ (IR support is loaded from user-provided plugins)")
            return@doLast
        }
        val sdkDir = androidSdkDirectory()
        if (sdkDir == null) {
            logger.warn("dexIrPlugins: Android SDK not found, skipping IR plugin conversion")
            return@doLast
        }
        val androidJar = findAndroidJar(sdkDir)
        if (androidJar == null) {
            logger.warn("dexIrPlugins: android.jar not found under " + sdkDir + ", skipping IR plugin conversion")
            return@doLast
        }
        val r8Classpath = irDex.files.joinToString(File.pathSeparator) { it.absolutePath }
        val javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
        val r8Jar = irDex.files.firstOrNull { it.name.startsWith("r8") }
        for (pluginJar in pluginJars) {
            val work = layout.buildDirectory.dir("tmp/ir/" + pluginJar.nameWithoutExtension).get().asFile
            work.deleteRecursively()
            val dexDirectory = File(work, "dex")
            dexDirectory.mkdirs()
            val args = mutableListOf(
                javaExecutable, "-Xmx2g", "-cp", r8Classpath,
                "com.android.tools.r8.D8",
                "--min-api", "26", "--release",
                "--output", dexDirectory.absolutePath,
                "--classpath", androidJar.absolutePath,
                pluginJar.absolutePath
            )
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val processOutput = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor()
            if (finished != 0) {
                logger.warn("dexIrPlugins: D8 failed for " + pluginJar.name + ": " + processOutput.takeLast(4000))
                continue
            }
            val dexFiles = dexDirectory.listFiles { file -> file.name.endsWith(".dex") }
                ?.sortedBy { file ->
                    val digits = file.name.removeSuffix(".dex").removePrefix("classes")
                    digits.toIntOrNull() ?: 1
                } ?: emptyList()
            if (dexFiles.isEmpty()) {
                logger.warn("dexIrPlugins: no DEX output for " + pluginJar.name)
                continue
            }

            val classes = pluginClassIndex(pluginJar)
            if (classes.isEmpty()) {
                logger.warn("dexIrPlugins: no IRConnection implementation found in " + pluginJar.name)
                continue
            }
            val pluginResources = pluginResources(pluginJar, resourcesDirectory.get().asFile)
            ZipOutputStream(File(output, pluginJar.name).outputStream()).use { zip ->
                dexFiles.forEach { dex ->
                    zip.putNextEntry(ZipEntry(dex.name))
                    dex.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("META-INF/beatoraja-ir/classes.txt"))
                zip.write(classes.joinToString("\n").toByteArray())
                zip.closeEntry()
                for (resource in pluginResources) {
                    zip.putNextEntry(ZipEntry(resource.first))
                    resource.second.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            logger.lifecycle("dexIrPlugins: prepared " + pluginJar.name + " with " + classes.size + " IR class(es)")
        }
    }
}

/** Reads sdk.dir from local.properties or the environment. */
fun androidSdkDirectory(): File? {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        val properties = Properties()
        propertiesFile.inputStream().use { properties.load(it) }
        val path = properties.getProperty("sdk.dir")
        if (path != null && File(path).isDirectory) return File(path)
    }
    for (key in listOf("ANDROID_SDK_ROOT", "ANDROID_HOME")) {
        val path = System.getenv(key)
        if (path != null && File(path).isDirectory) return File(path)
    }
    return null
}

fun findAndroidJar(sdkDir: File): File? {
    val platforms = File(sdkDir, "platforms").listFiles { file -> file.isDirectory } ?: return null
    return platforms.sortedByDescending { it.name }
        .map { File(it, "android.jar") }
        .firstOrNull { it.isFile }
}

/**
 * Copies non-class, non-signature entries of the plugin archive into the generated resource roots so
 * plugin code can resolve them through the application class loader.
 */
fun pluginResources(pluginJar: File, resourcesRoot: File): List<Pair<String, File>> {
    val result = ArrayList<Pair<String, File>>()
    ZipFile(pluginJar).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || entry.name.endsWith(".class") || entry.name.startsWith("META-INF/")) continue
            val target = File(resourcesRoot, entry.name)
            target.parentFile?.mkdirs()
            target.outputStream().use { output -> zip.getInputStream(entry).use { it.copyTo(output) } }
            result.add(entry.name to target)
        }
    }
    return result
}

/** Finds classes implementing IRConnection in the plugin archive without loading the archive. */
fun pluginClassIndex(pluginJar: File): List<String> {
    val marker = "bms/player/beatoraja/ir/IRConnection".toByteArray(Charsets.ISO_8859_1)
    val result = ArrayList<String>()
    ZipFile(pluginJar).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.endsWith(".class")) continue
            val bytes = zip.getInputStream(entry).readBytes()
            if (bytes.containsSequence(marker)) {
                result.add(entry.name.removeSuffix(".class").replace('/', '.'))
            }
        }
    }
    return result
}

fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
    if (sequence.isEmpty() || size < sequence.size) return false
    outer@ for (i in 0..(size - sequence.size)) {
        for (j in sequence.indices) {
            if (this[i + j] != sequence[j]) continue@outer
        }
        return true
    }
    return false
}

// The asset merger must run after the generated IR plugin archives exist.
tasks.matching {
    (it.name.startsWith("merge") && (it.name.endsWith("Assets") || it.name.endsWith("Resources")))
            || (it.name.startsWith("process") && it.name.endsWith("JavaRes"))
}.configureEach {
    dependsOn(dexIrPlugins)
}
