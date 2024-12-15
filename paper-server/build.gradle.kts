import io.papermc.paperweight.attribute.DevBundleOutput
import io.papermc.paperweight.core.ext
import io.papermc.paperweight.util.*
import java.time.Instant

plugins {
    `java-library`
    `maven-publish`
    id("io.papermc.paperweight.core")
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

dependencies {
    mache("io.papermc:mache:1.21.4+build.6")
    paperclip("io.papermc:paperclip:3.0.3")
    remapper("net.fabricmc:tiny-remapper:0.10.3:fat")
}

paperweight {
    minecraftVersion = providers.gradleProperty("mcVersion")
    // macheOldPath = file("F:\\Projects\\PaperTooling\\mache\\versions\\1.21.4\\src\\main\\java")
    // gitFilePatches = true

    paper {
        reobfMappingsPatch = layout.projectDirectory.file("../build-data/reobf-mappings-patch.tiny")
        reobfPackagesToFix.addAll(
            "co.aikar.timings",
            "com.destroystokyo.paper",
            "com.mojang",
            "io.papermc.paper",
            "ca.spottedleaf",
            "net.kyori.adventure.bossbar",
            "net.minecraft",
            "org.bukkit.craftbukkit",
            "org.spigotmc",
        )
    }

    spigot {
        buildDataRef = "3edaf46ec1eed4115ce1b18d2846cded42577e42"
        packageVersion = "v1_21_R3" // also needs to be updated in MappingEnvironment
    }
}

tasks.generateDevelopmentBundle {
    libraryRepositories.addAll(
        "https://repo.maven.apache.org/maven2/",
        paperMavenPublicUrl,
    )
}

// TODO remove me again, this is just to not run setupMacheSource when patches change
tasks.setupMacheSources {
    paperPatches.setFrom(project.ext.paper.sourcePatchDir.dir("com"), project.ext.paper.featurePatchDir)
    // paperPatches.from(project.ext.paper.sourcePatchDir, project.ext.paper.featurePatchDir)
}

abstract class Services {
    @get:Inject
    abstract val softwareComponentFactory: SoftwareComponentFactory

    @get:Inject
    abstract val archiveOperations: ArchiveOperations
}
val services = objects.newInstance<Services>()

publishing {
    if (project.providers.gradleProperty("publishDevBundle").isPresent) {
        val devBundleComponent = services.softwareComponentFactory.adhoc("devBundle")
        components.add(devBundleComponent)

        val devBundle = configurations.consumable("devBundle") {
            attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.ZIP))
            outgoing.artifact(tasks.generateDevelopmentBundle.flatMap { it.devBundleFile })
        }
        devBundleComponent.addVariantsFromConfiguration(devBundle.get()) {}

        val runtime = configurations.consumable("serverRuntimeClasspath") {
            attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.SERVER_DEPENDENCIES))
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            extendsFrom(configurations.runtimeClasspath.get())
        }
        devBundleComponent.addVariantsFromConfiguration(runtime.get()) {
            mapToMavenScope("runtime")
        }

        val compile = configurations.consumable("serverCompileClasspath") {
            attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.SERVER_DEPENDENCIES))
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
            extendsFrom(configurations.compileClasspath.get())
        }
        devBundleComponent.addVariantsFromConfiguration(compile.get()) {
            mapToMavenScope("compile")
        }

        tasks.withType(GenerateMavenPom::class).configureEach {
            doLast {
                val text = destination.readText()
                // Remove dependencies from pom, dev bundle is designed for gradle module metadata consumers
                destination.writeText(
                    text.substringBefore("<dependencies>") + text.substringAfter("</dependencies>")
                )
            }
        }

        publications.create<MavenPublication>("devBundle") {
            artifactId = "dev-bundle"
            from(devBundleComponent)
        }
    }
}

val log4jPlugins = sourceSets.create("log4jPlugins")
configurations.named(log4jPlugins.compileClasspathConfigurationName) {
    extendsFrom(configurations.compileClasspath.get())
}
val alsoShade: Configuration by configurations.creating

// Paper start - configure mockito agent that is needed in newer java versions
val mockitoAgent = configurations.register("mockitoAgent")
abstract class MockitoAgentProvider : CommandLineArgumentProvider {
    @get:CompileClasspath
    abstract val fileCollection: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> {
        return listOf("-javaagent:" + fileCollection.files.single().absolutePath)
    }
}
// Paper end - configure mockito agent that is needed in newer java versions

dependencies {
    implementation(project(":paper-api"))
    implementation("ca.spottedleaf:concurrentutil:0.0.2") // Paper - Add ConcurrentUtil dependency
    // Paper start
    implementation("org.jline:jline-terminal-ffm:3.27.1") // use ffm on java 22+
    implementation("org.jline:jline-terminal-jni:3.27.1") // fall back to jni on java 21
    implementation("net.minecrell:terminalconsoleappender:1.3.0")
    implementation("net.kyori:adventure-text-serializer-ansi:4.17.0") // Keep in sync with adventureVersion from Paper-API build file
    /*
          Required to add the missing Log4j2Plugins.dat file from log4j-core
          which has been removed by Mojang. Without it, log4j has to classload
          all its classes to check if they are plugins.
          Scanning takes about 1-2 seconds so adding this speeds up the server start.
     */
    implementation("org.apache.logging.log4j:log4j-core:2.19.0") // Paper - implementation
    log4jPlugins.annotationProcessorConfigurationName("org.apache.logging.log4j:log4j-core:2.19.0") // Paper - Needed to generate meta for our Log4j plugins
    runtimeOnly(log4jPlugins.output)
    alsoShade(log4jPlugins.output)
    implementation("io.netty:netty-codec-haproxy:4.1.97.Final") // Paper - Add support for proxy protocol
    // Paper end
    implementation("org.apache.logging.log4j:log4j-iostreams:2.24.1") // Paper - remove exclusion
    implementation("org.ow2.asm:asm-commons:9.7.1")
    implementation("org.spongepowered:configurate-yaml:4.2.0-SNAPSHOT") // Paper - config files
    implementation("commons-lang:commons-lang:2.6")
    runtimeOnly("org.xerial:sqlite-jdbc:3.47.0.0")
    runtimeOnly("com.mysql:mysql-connector-j:9.1.0")
    runtimeOnly("com.lmax:disruptor:3.4.4") // Paper

    runtimeOnly("org.apache.maven:maven-resolver-provider:3.9.6")
    runtimeOnly("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18")
    runtimeOnly("org.apache.maven.resolver:maven-resolver-transport-http:1.9.18")

    testImplementation("io.github.classgraph:classgraph:4.8.47") // Paper - mob goal test
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.platform:junit-platform-suite-engine:1.10.0")
    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.mockito:mockito-core:5.14.1")
    mockitoAgent("org.mockito:mockito-core:5.14.1") { isTransitive = false } // Paper - configure mockito agent that is needed in newer java versions
    testImplementation("org.ow2.asm:asm-tree:9.7.1")
    testImplementation("org.junit-pioneer:junit-pioneer:2.2.0") // Paper - CartesianTest
    implementation("net.neoforged:srgutils:1.0.9") // Paper - mappings handling
    implementation("net.neoforged:AutoRenamingTool:2.0.3") // Paper - remap plugins
    // Paper start - Remap reflection
    val reflectionRewriterVersion = "0.0.3"
    implementation("io.papermc:reflection-rewriter:$reflectionRewriterVersion")
    implementation("io.papermc:reflection-rewriter-runtime:$reflectionRewriterVersion")
    implementation("io.papermc:reflection-rewriter-proxy-generator:$reflectionRewriterVersion")
    // Paper end - Remap reflection
    // Paper start - spark
    implementation("me.lucko:spark-api:0.1-20240720.200737-2")
    implementation("me.lucko:spark-paper:1.10.119-SNAPSHOT")
    // Paper end - spark
}


tasks.jar {
    manifest {
        val git = Git(rootProject.layout.projectDirectory.path)
        val mcVersion = rootProject.providers.gradleProperty("mcVersion").get()
        val build = System.getenv("BUILD_NUMBER") ?: null
        val gitHash = git.exec(providers, "rev-parse", "--short=7", "HEAD").get().trim()
        val implementationVersion = "$mcVersion-${build ?: "DEV"}-$gitHash"
        val date = git.exec(providers, "show", "-s", "--format=%ci", gitHash).get().trim() // Paper
        val gitBranch = git.exec(providers, "rev-parse", "--abbrev-ref", "HEAD").get().trim() // Paper
        attributes(
            "Main-Class" to "org.bukkit.craftbukkit.Main",
            "Implementation-Title" to "Paper",
            "Implementation-Version" to implementationVersion,
            "Implementation-Vendor" to date, // Paper
            "Specification-Title" to "Paper",
            "Specification-Version" to project.version,
            "Specification-Vendor" to "Paper Team",
            "Brand-Id" to "papermc:paper",
            "Brand-Name" to "Paper",
            "Build-Number" to (build ?: ""),
            "Build-Time" to Instant.now().toString(),
            "Git-Branch" to gitBranch, // Paper
            "Git-Commit" to gitHash, // Paper
        )
        for (tld in setOf("net", "com", "org")) {
            attributes("$tld/bukkit", "Sealed" to true)
        }
    }
}

// Paper start - compile tests with -parameters for better junit parameterized test names
tasks.compileTestJava {
    options.compilerArgs.add("-parameters")
}
// Paper end

// Paper start
val scanJar = tasks.register("scanJarForBadCalls", io.papermc.paperweight.tasks.ScanJarForBadCalls::class) {
    badAnnotations.add("Lio/papermc/paper/annotation/DoNotUse;")
    jarToScan.set(tasks.jar.flatMap { it.archiveFile })
    classpath.from(configurations.compileClasspath)
}
tasks.check {
    dependsOn(scanJar)
}
// Paper end
// Paper start - use TCA for console improvements
tasks.jar {
    val archiveOperations = services.archiveOperations
    from(alsoShade.elements.map {
        it.map { f ->
            if (f.asFile.isFile) {
                archiveOperations.zipTree(f.asFile)
            } else {
                f.asFile
            }
        }
    })
}
// Paper end - use TCA for console improvements

tasks.test {
    include("**/**TestSuite.class")
    workingDir = temporaryDir
    useJUnitPlatform {
        forkEvery = 1
        excludeTags("Slow")
    }
    // Paper start - configure mockito agent that is needed in newer java versions
    val provider = objects.newInstance<MockitoAgentProvider>()
    provider.fileCollection.from(mockitoAgent)
    jvmArgumentProviders.add(provider)
    // Paper end - configure mockito agent that is needed in newer java versions
}

fun TaskContainer.registerRunTask(
    name: String,
    block: JavaExec.() -> Unit
): TaskProvider<JavaExec> = register<JavaExec>(name) {
    group = "runs"
    mainClass.set("org.bukkit.craftbukkit.Main")
    standardInput = System.`in`
    workingDir = rootProject.layout.projectDirectory
        .dir(providers.gradleProperty("paper.runWorkDir").getOrElse("run"))
        .asFile
    javaLauncher.set(project.javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    })
    jvmArgs("-XX:+AllowEnhancedClassRedefinition", "-XX:+AllowRedefinitionToAddDeleteMethods")

    if (rootProject.childProjects["test-plugin"] != null) {
        val testPluginJar = rootProject.project(":test-plugin").tasks.jar.flatMap { it.archiveFile }
        inputs.file(testPluginJar)
        args("-add-plugin=${testPluginJar.get().asFile.absolutePath}")
    }

    args("--nogui")
    systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", true)
    if (providers.gradleProperty("paper.runDisableWatchdog").getOrElse("false") == "true") {
        systemProperty("disable.watchdog", true)
    }
    systemProperty("io.papermc.paper.suppress.sout.nags", true)

    val memoryGb = providers.gradleProperty("paper.runMemoryGb").getOrElse("2")
    minHeapSize = "${memoryGb}G"
    maxHeapSize = "${memoryGb}G"

    doFirst {
        workingDir.mkdirs()
    }

    block(this)
}

tasks.registerRunTask("runServer") {
    description = "Spin up a test server from the Mojang mapped server jar"
    classpath(tasks.includeMappings.flatMap { it.outputJar })
    classpath(configurations.runtimeClasspath)
}

tasks.registerRunTask("runReobfServer") {
    description = "Spin up a test server from the reobfJar output jar"
    classpath(tasks.reobfJar.flatMap { it.outputJar })
    classpath(configurations.runtimeClasspath)
}

tasks.registerRunTask("runDevServer") {
    description = "Spin up a test server without assembling a jar"
    classpath(sourceSets.main.map { it.runtimeClasspath })
    jvmArgs("-DPaper.pushPaperAssetsRoot=true")
}

tasks.registerRunTask("runBundler") {
    description = "Spin up a test server from the Mojang mapped bundler jar"
    classpath(tasks.named<io.papermc.paperweight.tasks.CreateBundlerJar>("createMojmapBundlerJar").flatMap { it.outputZip })
    mainClass.set(null as String?)
}
tasks.registerRunTask("runReobfBundler") {
    description = "Spin up a test server from the reobf bundler jar"
    classpath(tasks.named<io.papermc.paperweight.tasks.CreateBundlerJar>("createReobfBundlerJar").flatMap { it.outputZip })
    mainClass.set(null as String?)
}
tasks.registerRunTask("runPaperclip") {
    description = "Spin up a test server from the Mojang mapped Paperclip jar"
    classpath(tasks.named<io.papermc.paperweight.tasks.CreatePaperclipJar>("createMojmapPaperclipJar").flatMap { it.outputZip })
    mainClass.set(null as String?)
}
tasks.registerRunTask("runReobfPaperclip") {
    description = "Spin up a test server from the reobf Paperclip jar"
    classpath(tasks.named<io.papermc.paperweight.tasks.CreatePaperclipJar>("createReobfPaperclipJar").flatMap { it.outputZip })
    mainClass.set(null as String?)
}
