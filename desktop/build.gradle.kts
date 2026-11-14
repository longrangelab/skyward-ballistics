import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val appVersion = "1.4.0"
val appName = "Skyward Ballistics"

val sharedSources = listOf(
    "com/longrange/dragcurve/engine/**",
    "com/longrange/dragcurve/model/**",
    "com/longrange/dragcurve/store/**",
    "com/longrange/dragcurve/exchange/**",
    "com/longrange/dragcurve/analysis/**",
    "com/longrange/dragcurve/ui/calc/Units.kt",
    "com/longrange/dragcurve/ui/calc/CalcLogic.kt",
    "com/longrange/dragcurve/sync/RemoteFile.kt",
    "com/longrange/dragcurve/sync/SyncEngine.kt",
    "com/longrange/dragcurve/sync/SyncFile.kt",
    "com/longrange/dragcurve/sync/SharedFileSync.kt",
)

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets {
        named("main") {
            kotlin.srcDir(rootProject.file("app/src/main/java"))
            kotlin.include(sharedSources)
            kotlin.include("com/longrange/dragcurve/desktop/**")
            kotlin.include("com/longrange/dragcurve/ui/calc/DesktopShims.kt")
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

val launcherPng = rootProject.file("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
val iconDir = layout.buildDirectory.dir("icon")
val makeWindowsIcon by tasks.registering {
    description = "Converts the Android launcher icon into a Windows .ico"
    inputs.file(launcherPng)
    outputs.dir(iconDir)
    doLast {
        val src = ImageIO.read(launcherPng)
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
        val pngs = sizes.map { s ->
            val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, s, s, null)
            g.dispose()
            ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }
        val out = ByteArrayOutputStream()
        fun le16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        fun le32(v: Int) { le16(v and 0xffff); le16((v ushr 16) and 0xffff) }
        le16(0); le16(1); le16(sizes.size)
        var offset = 6 + 16 * sizes.size
        sizes.forEachIndexed { i, s ->
            out.write(if (s >= 256) 0 else s); out.write(if (s >= 256) 0 else s)
            out.write(0); out.write(0); le16(1); le16(32)
            le32(pngs[i].size); le32(offset)
            offset += pngs[i].size
        }
        pngs.forEach { out.write(it) }
        val dir = iconDir.get().asFile.apply { mkdirs() }
        File(dir, "skyward.ico").writeBytes(out.toByteArray())
        File(dir, "skyward.png").writeBytes(pngs[sizes.indexOf(256)])
    }
}

sourceSets.named("main") { resources.srcDir(iconDir) }
tasks.named("processResources") { dependsOn(makeWindowsIcon) }

val packageBase: File = (findProperty("skywardPackageDir") as String?)?.let { File(it) }
    ?: if (rootDir.absolutePath.length > 80) File(System.getProperty("java.io.tmpdir"), "skyward-desktop-package")
    else layout.buildDirectory.dir("compose/binaries").get().asFile

compose.desktop {
    application {
        mainClass = "com.longrange.dragcurve.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Xmx1g")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = appName
            packageVersion = appVersion
            description = "Long-range ballistic calculator"
            vendor = appName
            copyright = "Skyward Ballistics"
            outputBaseDir.set(packageBase)
            modules("java.instrument", "java.naming", "java.net.http", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets", "jdk.localedata", "jdk.zipfs")

            windows {
                iconFile.set(iconDir.map { it.file("skyward.ico") })
                menuGroup = appName
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f0b6d0e-3d8c-4c39-9a0c-5b8f2a8e7c11"
            }
        }
    }
}

tasks.matching { it.name.startsWith("createDistributable") || it.name.startsWith("package") || it.name.startsWith("createRuntimeImage") }
    .configureEach { dependsOn(makeWindowsIcon) }

val windowsZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds the Windows app folder and zips it to dist/SkywardBallistics-Windows-<version>.zip"
    dependsOn("createDistributable")
    from(File(packageBase, "main/app"))
    archiveFileName.set("SkywardBallistics-Windows-${appVersion.substringBeforeLast(".0")}.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
}

