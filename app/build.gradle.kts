import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** Uses the build JDK, so replacing icon.png needs no Python or Android Studio asset wizard. */
@CacheableTask
abstract class GenerateLauncherIcons : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artwork: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private fun scaled(source: BufferedImage, size: Int): BufferedImage =
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { target ->
            target.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(source, 0, 0, size, size, null)
                dispose()
            }
        }

    @TaskAction
    fun generate() {
        val source = ImageIO.read(artwork.get().asFile)
            ?: throw GradleException("Cannot read icon.png. Supply a square PNG image.")
        require(source.width == source.height) { "icon.png must be square; 512 x 512 or larger is recommended." }
        val destination = outputDirectory.get().asFile
        fun save(image: BufferedImage, path: String) {
            val file = destination.resolve(path)
            file.parentFile.mkdirs()
            check(ImageIO.write(image, "png", file)) { "Cannot write launcher icon $file" }
        }
        val art = scaled(source, 512)
        save(art, "drawable-nodpi/ic_launcher_art.png")
        val themed = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        val transparent = (0 until art.height).any { y -> (0 until art.width).any { x -> art.getRGB(x,y).ushr(24) < 255 } }
        var visible = false
        for(y in 0 until 512) for(x in 0 until 512) {
            val pixel = art.getRGB(x,y)
            val red = pixel.ushr(16) and 255; val green = pixel.ushr(8) and 255; val blue = pixel and 255
            val neutral = maxOf(red,green,blue)-minOf(red,green,blue) < 30 && minOf(red,green,blue) < 245
            val alpha = if(transparent || neutral) pixel.ushr(24) else 0
            themed.setRGB(x,y,alpha shl 24)
            if(alpha != 0) visible = true
        }
        if(!visible) for(y in 0 until 512) for(x in 0 until 512) themed.setRGB(x,y,art.getRGB(x,y) and -0x1000000)
        save(themed, "drawable-nodpi/ic_launcher_monochrome_art.png")
        for((density,size) in listOf("mdpi" to 48,"hdpi" to 72,"xhdpi" to 96,"xxhdpi" to 144,"xxxhdpi" to 192)) {
            val icon = scaled(source,size)
            save(icon,"mipmap-$density/ic_launcher.png")
            val round = BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB)
            round.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
                color = Color.WHITE; fillOval(0,0,size,size)
                composite = AlphaComposite.SrcIn
                drawImage(icon,0,0,null); dispose()
            }
            save(round,"mipmap-$density/ic_launcher_round.png")
        }
    }
}

androidComponents.onVariants { variant ->
    val icons = tasks.register<GenerateLauncherIcons>("generate${variant.name.replaceFirstChar { it.uppercase() }}LauncherIcons") {
        artwork.set(rootProject.layout.projectDirectory.file("icon.png"))
        outputDirectory.set(layout.buildDirectory.dir("generated/launcherIcons/${variant.name}/res"))
    }
    variant.sources.res?.addGeneratedSourceDirectory(icons, GenerateLauncherIcons::outputDirectory)
}

android {
    namespace = "com.example.carriex"
    compileSdk {
        version = release(37)
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    defaultConfig {
        applicationId = "com.example.carriex"
        minSdk = 24
        targetSdk = 37
        versionCode = 11
        versionName = "1.10"
        val updateRepository = providers.gradleProperty("carriexUpdateRepository").getOrElse("khivus/Carriex")
        require(updateRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("x86_64", "arm64-v8a")
            }
        }
        release {
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    // Both languages must be available offline when changing language in Settings.
    bundle {
        language {
            enableSplit = false
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.drawerlayout:drawerlayout:1.1.1")
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
