import com.android.build.api.dsl.LibraryExtension
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

configure<LibraryExtension> {
    namespace = "com.bitwarden.network"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }

    defaultConfig {
        minSdk {
            version = release(libs.versions.minSdk.get().toInt())
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility(libs.versions.jvmTarget.get())
        targetCompatibility(libs.versions.jvmTarget.get())
    }
    @Suppress("UnstableApiUsage")
    testFixtures {
        enable = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

/**
 * The local vault, which stands in for the Bitwarden server. It is written in Rust and is the git
 * submodule `local-vault-rs`, which the browser extension of this fork has as well, so that both
 * read and write a vault folder the same way. See docs/DECENTRALIZATION.md for what building it
 * takes.
 */
val localVaultWorkspace: Directory = rootProject.layout.projectDirectory.dir("local-vault-rs")
val localVaultCargoTarget: Provider<Directory> = layout.buildDirectory.dir("local-vault/cargo")
val localVaultSources: FileTree = localVaultWorkspace.asFileTree.matching {
    include("Cargo.toml", "Cargo.lock", "rust-toolchain.toml", "crates/**")
}

/**
 * The file name of the library of the local vault for the machine that the build runs on.
 */
val localVaultHostLibraryName: String = System.getProperty("os.name").lowercase().let { os ->
    when {
        "mac" in os -> "liblocal_vault_uniffi.dylib"
        "windows" in os -> "local_vault_uniffi.dll"
        else -> "liblocal_vault_uniffi.so"
    }
}

/**
 * The ABIs that the app is built for, which are all of them unless the build says otherwise, as
 * `-PabiFilters=arm64-v8a` does.
 */
val localVaultAbis: Provider<List<String>> = providers
    .gradleProperty("abiFilters")
    .map { abis -> abis.split(",").map { it.trim() } }
    .orElse(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))

val generateLocalVaultKotlin = tasks.register<GenerateLocalVaultKotlin>(
    name = "generateLocalVaultKotlin",
) {
    sources.from(localVaultSources)
    workspace.set(localVaultWorkspace)
    cargoTargetDirectory.set(localVaultCargoTarget)
    hostLibrary.set(localVaultCargoTarget.map { it.file("release/$localVaultHostLibraryName") })
}

val buildLocalVaultLibraries = tasks.register<BuildLocalVaultLibraries>(
    name = "buildLocalVaultLibraries",
) {
    sources.from(localVaultSources)
    workspace.set(localVaultWorkspace)
    cargoTargetDirectory.set(localVaultCargoTarget)
    abis.set(localVaultAbis)
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generateLocalVaultKotlin,
            GenerateLocalVaultKotlin::outputDirectory,
        )
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildLocalVaultLibraries,
            BuildLocalVaultLibraries::outputDirectory,
        )
    }
}

tasks.withType<Test>().configureEach {
    // The unit tests run the local vault on the machine that runs them, with the library that the
    // Kotlin of the local vault is generated from.
    inputs.files(generateLocalVaultKotlin.flatMap { it.hostLibrary })
    systemProperty(
        "jna.library.path",
        localVaultCargoTarget.get().dir("release").asFile.absolutePath,
    )
}

dependencies {
    implementation(project(":annotation"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    // The Kotlin of the local vault calls its library through JNA.
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
    implementation(libs.kotlinx.serialization)
    implementation(platform(libs.square.okhttp.bom))
    implementation(libs.square.okhttp)
    implementation(libs.square.okhttp.logging)
    implementation(platform(libs.square.retrofit.bom))
    implementation(libs.square.retrofit)
    implementation(libs.square.retrofit.kotlinx.serialization)
    implementation(libs.timber)

    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.vintage)
    // The JNA of the machine that runs the unit tests, which the one for Android is not.
    testImplementation(libs.jna)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk.mockk)
    testImplementation(platform(libs.square.okhttp.bom))
    testImplementation(libs.square.okhttp.mockwebserver)
    testImplementation(libs.square.turbine)

    testFixturesImplementation(project(":core"))
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesRuntimeOnly(libs.junit.platform.launcher)
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.junit.vintage)
    testFixturesImplementation(libs.kotlinx.serialization)
    testFixturesImplementation(libs.mockk.mockk)
    testFixturesImplementation(platform(libs.square.okhttp.bom))
    testFixturesImplementation(libs.square.okhttp)
    testFixturesImplementation(libs.square.okhttp.mockwebserver)
    testFixturesImplementation(platform(libs.square.retrofit.bom))
    testFixturesImplementation(libs.square.retrofit)
    testFixturesImplementation(libs.square.retrofit.kotlinx.serialization)
}

/**
 * Builds the library of the local vault for the machine that the build runs on, and generates
 * its Kotlin from it. The library for Android has the same interface. The unit tests load this
 * library.
 */
abstract class GenerateLocalVaultKotlin : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:Internal
    abstract val cargoTargetDirectory: DirectoryProperty

    @get:OutputFile
    abstract val hostLibrary: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        cargo("build", "--locked", "--release", "-p", "local-vault-uniffi")
        output.deleteRecursively()
        // The generator is only run here, so it is built without optimizations, which is faster.
        cargo(
            "run", "--locked", "-p", "uniffi-bindgen", "--",
            "generate", "--no-format",
            "--library", hostLibrary.get().asFile.absolutePath,
            "--language", "kotlin",
            "--out-dir", output.absolutePath,
        )
    }

    private fun cargo(vararg arguments: String) {
        execOperations.exec {
            workingDir = workspace.get().asFile
            environment("CARGO_TARGET_DIR", cargoTargetDirectory.get().asFile.absolutePath)
            commandLine(listOf("cargo") + arguments)
        }
    }
}

/**
 * Builds the library of the local vault for each ABI of Android that the app is built for, with
 * cargo-ndk, into a directory of JNI libraries.
 */
abstract class BuildLocalVaultLibraries : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:Internal
    abstract val cargoTargetDirectory: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        execOperations.exec {
            workingDir = workspace.get().asFile
            environment("CARGO_TARGET_DIR", cargoTargetDirectory.get().asFile.absolutePath)
            commandLine(
                listOf("cargo", "ndk") +
                    abis.get().flatMap { listOf("-t", it) } +
                    listOf("-o", output.absolutePath) +
                    listOf("build", "--locked", "--release", "-p", "local-vault-uniffi"),
            )
        }
    }
}
