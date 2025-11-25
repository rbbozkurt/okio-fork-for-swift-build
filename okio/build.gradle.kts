import aQute.bnd.gradle.BundleTaskExtension
import com.vanniktech.maven.publish.JavadocJar.Dokka
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.collections.associate
import kotlin.collections.ifEmpty
import kotlin.jvm.java
import kotlinx.validation.ApiValidationExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithTests
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable

plugins {
  kotlin("multiplatform")
  id("app.cash.burst")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("build-support")
  id("binary-compatibility-validator")
}

/*
 * Here's the main hierarchy of variants. Any `expect` functions in one level of the tree are
 * `actual` functions in a (potentially indirect) child node.
 *
 * ```
 *   common
 *   |-- js
 *   |-- jvm
 *   |-- native
 *   |   |-- mingw
 *   |   |   '-- mingwX64
 *   |   '-- unix
 *   |       |-- apple
 *   |       |   |-- iosArm64
 *   |       |   |-- iosX64
 *   |       |   |-- macosX64
 *   |       |   |-- tvosArm64
 *   |       |   |-- tvosX64
 *   |       |   |-- watchosArm32
 *   |       |   |-- watchosArm64
 *   |       '-- linux
 *   |           |-- linuxX64
 *   |           '-- linuxArm64
 *   '-- wasm
 *       '-- wasmJs
 *       '-- wasmWasi
 * ```
 *
 * The `nonJvm`, `nonJs`, `nonApple`, etc. source sets exclude the corresponding platforms.
 *
 * The `hashFunctions` source set builds on all platforms. It ships as a main source set on non-JVM
 * platforms and as a test source set on the JVM platform.
 *
 * The `systemFileSystem` source set is used on jvm and native targets, and provides the FileSystem.SYSTEM property.
 */
kotlin {
  configureOrCreateOkioPlatforms()

  sourceSets {
    all {
      languageSettings.apply {
        // Required for CPointer etc. since Kotlin 1.9.
        optIn("kotlinx.cinterop.ExperimentalForeignApi")
        // Required for Contract API. since Kotlin 1.3.
        optIn("kotlin.contracts.ExperimentalContracts")
      }
    }
    matching { it.name.endsWith("Test") }.all {
      languageSettings {
        optIn("kotlin.time.ExperimentalTime")
      }
    }

    val commonMain by getting
    val commonTest by getting {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(projects.okioTestingSupport)
      }
    }

    val hashFunctions by creating {
      dependsOn(commonMain)
    }

    val nonAppleMain by creating {
      dependsOn(hashFunctions)
    }

    val nonWasmTest by creating {
      dependsOn(commonTest)
      dependencies {
        implementation(libs.kotlin.time)
        implementation(projects.okioFakefilesystem)
      }
    }

    val nonJvmMain by creating {
      dependsOn(hashFunctions)
      dependsOn(commonMain)
    }

    val nonJsMain by creating {
      dependsOn(commonMain)
    }

    val systemFileSystemMain by creating {
      dependsOn(commonMain)
    }

    val nonJvmTest by creating {
      dependsOn(commonTest)
    }

    val zlibMain by creating {
      dependsOn(commonMain)
    }

    val zlibTest by creating {
      dependsOn(commonTest)
      dependencies {
        implementation(libs.test.assertk)
      }
    }

    val jvmMain by getting {
      dependsOn(zlibMain)
      dependsOn(systemFileSystemMain)
      dependsOn(nonJsMain)
    }
    val jvmTest by getting {
      kotlin.srcDir("src/hashFunctions")
      dependsOn(nonWasmTest)
      dependsOn(zlibTest)
      dependencies {
        implementation(libs.test.junit)
        implementation(libs.test.assertj)
        implementation(libs.test.jimfs)
      }
    }

    if (kmpJsEnabled) {
      val jsMain by getting {
        dependsOn(nonJvmMain)
        dependsOn(nonAppleMain)
      }
      val jsTest by getting {
        dependsOn(nonWasmTest)
        dependsOn(nonJvmTest)
      }
    }

    if (kmpNativeEnabled) {
      createSourceSet("nativeMain", parent = nonJvmMain)
        .also { nativeMain ->
          nativeMain.dependsOn(zlibMain)
          nativeMain.dependsOn(systemFileSystemMain)
          createSourceSet(
              "mingwMain",
              parent = nativeMain,
              children = mingwTargets,
          ).also { mingwMain ->
            mingwMain.dependsOn(nonAppleMain)
            mingwMain.dependsOn(nonJsMain)
          }
          createSourceSet("unixMain", parent = nativeMain)
            .also { unixMain ->
              unixMain.dependsOn(nonJsMain)
              createSourceSet(
                  "linuxMain",
                  parent = unixMain,
                  children = linuxTargets,
              ).also { linuxMain ->
                linuxMain.dependsOn(nonAppleMain)
              }
              createSourceSet("appleMain", parent = unixMain, children = appleTargets)
            }
        }

      createSourceSet("nativeTest", parent = commonTest, children = mingwTargets + linuxTargets)
        .also { nativeTest ->
          nativeTest.dependsOn(nonJvmTest)
          nativeTest.dependsOn(nonWasmTest)
          nativeTest.dependsOn(zlibTest)
          createSourceSet("appleTest", parent = nativeTest, children = appleTargets)
        }
    }

    if (kmpWasmEnabled) {
      createSourceSet("wasmMain", parent = commonMain, children = wasmTargets)
        .also { wasmMain ->
          wasmMain.dependsOn(nonJsMain)
          wasmMain.dependsOn(nonJvmMain)
          wasmMain.dependsOn(nonAppleMain)
        }
      createSourceSet("wasmTest", parent = commonTest, children = wasmTargets)
        .also { wasmTest ->
          wasmTest.dependsOn(nonJvmTest)
        }
    }
  }

  targets.withType<KotlinNativeTargetWithTests<*>> {
    binaries {
      // Configure a separate test where code runs in background
      test("background", setOf(NativeBuildType.DEBUG)) {
        freeCompilerArgs += "-trw"
      }
    }
    testRuns {
      val background by creating {
        setExecutionSourceFrom(binaries.getByName("backgroundDebugTest") as TestExecutable)
      }
    }
  }
}

tasks {
  val jvmJar by getting(Jar::class) {
    // BundleTaskExtension() crashes unless there's a 'main' source set.
    sourceSets.create(SourceSet.MAIN_SOURCE_SET_NAME)
    val bndExtension = BundleTaskExtension(this)
    bndExtension.setBnd(
      """
      Export-Package: okio
      Automatic-Module-Name: okio
      Bundle-SymbolicName: com.squareup.okio
      """,
    )
    // Call the extension when the task has finished to modify the jar to contain OSGi metadata.
    doLast {
      bndExtension.buildAction()
        .execute(this)
    }
  }
}

configure<MavenPublishBaseExtension> {
  configure(
    KotlinMultiplatform(javadocJar = Dokka("dokkaGfm")),
  )
}

plugins.withId("binary-compatibility-validator") {
  configure<ApiValidationExtension> {
    ignoredProjects += "jmh"
  }
}



// Prefer an immutable Map; switch to `mutableMapOf` if you plan to mutate it.
data class RemoteSPM(val url: String, val exact: String, val productName : String, val productPackage : String)

// If you map by Maven coordinate, use "group:name" to avoid ambiguity:
val kmpToSpm: Map<String, RemoteSPM> = mapOf(
  "com.squareup.okio:okio" to RemoteSPM(
    url = "https://github.com/rbbozkurt/okio-fork-for-swift-build",
    exact = "3.17.0-swiftpm-SNAPSHOT.0", // ensure this matches an actual Git tag in the SPM repo,
    productName = "okio",
    productPackage = "okio-fork-for-swift-build",
  )
)

fun RemoteSPM.toAnnotation() : String {
  return ".package(url : \"${url}\", exact : \"${exact}\")"
}

fun RemoteSPM.asDepAnnotation() : String {
  return ".product(name : \"${productName}\", package : \"${productPackage}\")"
}



val PKG_PLACEHOLDER = "__PACKAGE_DIR__"

data class ModuleCoord(val group: String, val name: String, val version: String)
data class ProjNode(val proj: Project, val targetName: String)


fun ModuleCoord.name() : String {
  return "${group}:${name}"
}
/* -------------------- Helpers -------------------- */

fun String.spmSafe(): String {
  var s = replace(Regex("[^A-Za-z0-9_]"), "_")
  if (s.firstOrNull()?.isLetter() != true && s.firstOrNull() != '_') s = "_$s"
  return s
}

fun createRelativeSymlink(link: java.nio.file.Path, target: java.nio.file.Path) {
  Files.createDirectories(link.parent)
  Files.deleteIfExists(link)
  val linkParentAbs = link.parent.toAbsolutePath().normalize()
  val targetAbs = target.toAbsolutePath().normalize()
  val relTarget = try { linkParentAbs.relativize(targetAbs) } catch (_: Exception) { target }
  println("Creating symlink: LINK=$link -> TARGET=$relTarget")
  Files.createSymbolicLink(link, relTarget)
}

fun createSPMStructure(packageRoot: File, moduleName: String, sources: Map<String, List<File>>) {
  sources.forEach { (setName, files) ->
    val targetDir = File(packageRoot, "Sources/$moduleName/$setName")
    targetDir.mkdirs()
    val used = mutableSetOf<String>()
    files.forEach { file ->
      var name = "${file.name}.swift" // keep .swift suffix in the link filename
      while (!used.add(name)) {
        val dot = name.lastIndexOf('.')
        val base = if (dot >= 0) name.substring(0, dot) else name
        val ext = if (dot >= 0) name.substring(dot) else ""
        name = "${base}_1$ext"
      }
      createRelativeSymlink(targetDir.resolve(name).toPath(), file.toPath())
    }
  }
}

/** Kotlin sources by source set via Kotlin Gradle extension (no hard-coded paths). */
fun Project.collectKotlinSourcesGeneric(preferSetNames: Set<String>): Map<String, List<File>> {
  val ext = extensions.findByType(KotlinProjectExtension::class.java) ?: return emptyMap()
  val allSets = ext.sourceSets.toList()
  val picked = allSets.filter { it.name in preferSetNames }.ifEmpty {
    allSets.filter { it.name == "main" } // fallback for JVM-style
  }
  return picked.associate { ss ->
    val files = ss.kotlin.srcDirs
      .flatMap { dir -> fileTree(dir).matching { include("**/*.kt", "**/*.kts") }.files }
      .filter { it.isFile }
      .sortedBy { it.path }
    ss.name to files
  }.filterValues { it.isNotEmpty() }
}

/** Direct project() deps for given source-set names (+ root impl/api). */
fun directProjectDepsForSets(host: Project, dependencyProject: Project, setNames: Set<String>): Set<Project> {
  val out = linkedSetOf<Project>()
  setNames.forEach { setName ->
    listOf("${setName}Implementation", "${setName}Api").forEach { confName ->
      dependencyProject.configurations.findByName(confName)?.dependencies?.forEach { dep ->
        if (dep is ProjectDependency) out += host.project(dep.path)
      }
    }
  }
  // iosSimulatorArm64() //
//    listOf("implementation", "api").forEach { confName ->
//        dependencyProject.configurations.findByName(confName)?.dependencies?.forEach { dep ->
//            if (dep is ProjectDependency) out += host.project(dep.path)
//        }
//    }
  return out
}

/** Direct EXTERNAL module deps (group:name:version) declared on the given sets (+ root impl/api). */
fun directExternalModulesForSets(p: Project, setNames: Set<String>): Set<ModuleCoord> {
  fun collectFrom(confName: String, acc: MutableSet<ModuleCoord>) {
    println("Collecting direct external modules for 2 ${p.path} @ $confName")
    val conf = p.configurations.findByName(confName) ?: return
    conf.dependencies.forEach { dep ->
      if (dep is ExternalModuleDependency) {
        val g = dep.group
        val n = dep.name
        val v = dep.version
        if (!g.isNullOrBlank() && n.isNotBlank() && !v.isNullOrBlank()) {
          acc += ModuleCoord(g, n, v)
        }
      }
    }
  }
  val out = linkedSetOf<ModuleCoord>()
  setNames.forEach { set ->
    println("Collecting direct external modules for 1 ${p.path} @ $set")
    collectFrom("${set}Implementation", out)
    collectFrom("${set}Api", out)
  }
  collectFrom("implementation", out)
  collectFrom("api", out)

  return out
}

fun collectProjectDepGraph(graph: LinkedHashMap<Project, Set<Project>>, host: Project, start: Project, setNames: Set<String>) {
  val kotlinExtension = host.extensions.findByName("kotlin") ?: return
  val kmpExtension = kotlinExtension as? KotlinMultiplatformExtension ?: return
  if (!kmpExtension.targets.names.contains("iosSimulatorArm64")) return
  val seen = mutableSetOf<Project>()
  val q = ArrayDeque<Project>()
//    q.add(host)
  val hostDeps = directProjectDepsForSets(host, start, setNames)
  hostDeps.forEach { q += it }
  graph[host] = hostDeps
  while (q.isNotEmpty()) {
    val dependencyProject = q.removeFirst()
    if (!seen.add(dependencyProject)) continue
    val deps = directProjectDepsForSets(host, dependencyProject, setNames).filter { it != host }.toSet()
    graph[dependencyProject] = deps
    deps.forEach { if (!seen.contains(it)) q += it }
  }
}




/** Make a path relative to pkgRoot (fallback to absolute if relativize fails). */
private fun relFromPkgRoot(pkgRoot: File, absPath: String): String {
  return try {
    val base = pkgRoot.toPath().toAbsolutePath().normalize()
    val p = File(absPath).toPath().toAbsolutePath().normalize()
    base.relativize(p).toString()
  } catch (_: Exception) {
    absPath
  }
}



/* -------------------- NEW: Build -Xfragment* using the SPM links (remove .swift) -------------------- */

private fun buildKonanFragmentArgsUsingLinks(
  p: Project,
  preferSetNames: Set<String>,
  pkgRoot: File,
  targetName: String
): List<String> {
  val ext = p.extensions.findByType(KotlinProjectExtension::class.java) ?: return emptyList()
  val allSets: List<KotlinSourceSet> = ext.sourceSets.toList()

  val nonTest = allSets.filterNot { it.name.endsWith("Test", true) || it.name.endsWith("AndroidTest", true) }
  val chosen: List<KotlinSourceSet> = when {
    nonTest.any { it.name in preferSetNames } -> nonTest.filter { it.name in preferSetNames }
    nonTest.any { it.name == "main" } -> listOf(nonTest.first { it.name == "main" })
    else -> nonTest
  }
  if (chosen.isEmpty()) return emptyList()

  val byName = chosen.associateBy { it.name }
  val parents: Map<String, Set<String>> = chosen.associate { ss ->
    ss.name to ss.dependsOn.mapNotNull { byName[it.name]?.name }.toSet()
  }
  val memo = mutableMapOf<String, Int>()
  fun depth(name: String): Int = memo.getOrPut(name) {
    val ps = parents[name].orEmpty()
    if (ps.isEmpty()) 0 else ps.maxOf { depth(it) } + 1
  }

  val xFragments = chosen.sortedByDescending { depth(it.name) }.joinToString(",") { it.name }
  val xRefines = chosen.flatMap { c -> c.dependsOn.map { pset -> "${c.name}:${pset.name}" } }
    .distinct().joinToString(",")

  val spmTargetRoot = File(pkgRoot, "Sources/$targetName")

  // Build -Xfragment-sources from symlinked .swift files, but:
  //  - strip ".swift"
  //  - make path relative to pkgRoot
  //  - prefix with ${PACKAGE_DIR}/
  val perSetFiles: Map<String, List<String>> = chosen.associate { ss ->
    val setDir = File(spmTargetRoot, ss.name)
    val files = if (setDir.isDirectory) {
      setDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".swift") }
        .map { swiftLink ->
          val stem = swiftLink.absolutePath.removeSuffix(".swift")
          val rel = relFromPkgRoot(pkgRoot, stem)
          "${PKG_PLACEHOLDER}/$rel"
        }
        .distinct()
        .sorted()
        .toList()
    } else emptyList()
    ss.name to files
  }.filterValues { it.isNotEmpty() }

  val xSources = perSetFiles.entries
    .flatMap { (set, files) -> files.map { f -> "$set:$f" } }
    .joinToString(",")

  val args = mutableListOf<String>()
  args += "-Xfragments=$xFragments"
  if (xRefines.isNotEmpty()) args += "-Xfragment-refines=$xRefines"
  if (xSources.isNotEmpty()) args += "-Xfragment-sources=$xSources"
  args += "-Xmulti-platform"

  if (xSources.contains("/src/")) {
    println("WARNING: xSources contains '/src/' — expected symlink paths under Sources/. Check link creation order.")
  } else {
    println("xSources built from SPM links with \${PACKAGE_DIR} for target=$targetName")
  }
  return args
}

/* -------------------- Task -------------------- */

tasks.register("convertToSwiftPMPackage") {
  group = "swiftpm"
  description = "Export this KMP project (+ deps) as an SPM package via symlinks and generate Package.swift with cSettings flags from SPM links."

  doLast {
    val moduleName = project.name.spmSafe()
//        val baseAbsProp = project.findProperty("spmBaseAbs") as String?
//            ?: error("Pass base path with -PspmBaseAbs=/ABS/PATH")
//        val baseAbsProp = rootProject.layout.projectDirectory.dir(".").asFile
    val baseAbs = rootProject.layout.projectDirectory.dir(".").asFile
//        require(baseAbs.isAbsolute) { "spmBaseAbs must be absolute: $baseAbsProp" }

    val pkgRoot = baseAbs
    // project.delete(pkgRocot)

    // Source-set universe for dependency discovery (use iOS sim arm64 'main' comp)
    val comp = kotlin.iosSimulatorArm64().compilations.getByName("main")
    val setNamesForDeps: Set<String> = comp.allKotlinSourceSets.map { it.name }.toSet()

    // Main project sources (filtered, for symlinking)
    val mainSourcesBySet: Map<String, List<File>> =
      comp.allKotlinSourceSets.associate { ss ->
        val files = ss.kotlin.sourceDirectories
          .flatMap { dir -> project.fileTree(dir).matching { include("**/*.kt", "**/*.kts") }.files }
          .filter { it.isFile }.distinct().sortedBy { it.path }
        ss.name to files
      }.filterValues { it.isNotEmpty() }
    if (mainSourcesBySet.isEmpty()) error("No Kotlin sources to export.")

    // 1) Create main module links first
    createSPMStructure(pkgRoot, moduleName, mainSourcesBySet)

    // Project() dependency graph
    val depGraph = LinkedHashMap<Project, Set<Project>>()
    allprojects.forEach {
      collectProjectDepGraph(depGraph, it, it, setNamesForDeps)
    }
//        error(allprojects.map { it.name })
//        error(depGraph.keys.map { it.name })

    // For each project node, collect its external modules (direct only)
    val allProjects = listOf(project) + depGraph.keys.toList()
    val externalsByProject: Map<Project, Set<ModuleCoord>> =
      allProjects.associateWith { p -> directExternalModulesForSets(p, setNamesForDeps) }

    val remoteTargets = mutableMapOf<ModuleCoord, RemoteSPM>()
    val projectNodes = mutableMapOf<Project, ProjNode>()

    // Main’s direct project deps (for manifest wiring)
//        val mainDirectProjDeps = directProjectDepsForSets(project, project, setNamesForDeps)
//        val mainDirectProjTargetNames = mainDirectProjDeps.map { it.name.spmSafe() }.toSet()

    // 2) Mirror each project() node’s sources and remember its target name (create links BEFORE computing flags)
    depGraph.keys.forEach { p ->
      val name = p.name.spmSafe()
      val srcs = p.collectKotlinSourcesGeneric(setNamesForDeps)
      if (srcs.isNotEmpty()) {
        createSPMStructure(pkgRoot, name, srcs)
        projectNodes[p] = ProjNode(p, name)
      } else {
        println("Skipping ${p.path} (${p.name}): no sources matching $setNamesForDeps (or 'main')")
      }
    }
    // 3) Realize remote targets (download + link) so we can compute flags from links
    externalsByProject.values.flatten().forEach { coord ->
      remoteTargets.computeIfAbsent(coord) { key ->
        println("Remote target ${key.name()}")
        println("Spm in ${ kmpToSpm[key.name()]}")
        kmpToSpm[key.name()] ?: error("Missing SPM mapping for ${key.name()}")
      }
    }
    /* --------- Build per-target OTHER_CFLAGS payloads from SPM links --------- */

    val perProjCOtherFlags: Map<Project, List<String>> =
      projectNodes.mapValues { (proj, pn) ->
        buildKonanFragmentArgsUsingLinks(proj, setNamesForDeps, pkgRoot, pn.targetName)
      }


    // ---------- Package.swift ----------
    val packageSwift = File(pkgRoot, "Package.swift")
    val manifest = buildString {
      appendLine("// swift-tools-version: 6.0")
      appendLine("import PackageDescription")
      appendLine()
      appendLine("let package = Package(")
      appendLine("    name: \"$moduleName\",")
      appendLine("    platforms: [.iOS(.v15), .macOS(.v13)],")
      appendLine("    products: [")
      val targetNames = projectNodes.values.joinToString(", ") { "\"${it.targetName}\"" }
      appendLine("        .library(name: \"$moduleName\", targets: [${targetNames}]),")
      appendLine("    ],")
      val remoteDeps = remoteTargets.values.joinToString(", ") { it.toAnnotation() }
      appendLine(" dependencies: [ $remoteDeps ],")
      appendLine("    targets: [")

      // For each project() node
      val projList = projectNodes.values.toList()
      projList.forEachIndexed { idx, pn ->
        val projRemoteDeps = externalsByProject[pn.proj].orEmpty()
          .mapNotNull { remoteTargets[it]?.asDepAnnotation() }
          .sorted()
        val projDirectProjDeps = depGraph[pn.proj].orEmpty()
          .map { d -> "\"${projectNodes[d]?.targetName}\"" }                    .sorted()
        val depsForProj = (projDirectProjDeps + projRemoteDeps).sorted()

        val flags = perProjCOtherFlags[pn.proj].orEmpty().joinToString(" ")
        val cFlagsLine =
          if (flags.isNotEmpty())
            "            , cSettings: [.define( \"KOTLIN\", to: \"$flags\")]"
          else ""
        appendLine("        .target(")
        appendLine("            name: \"${pn.targetName}\",")
        appendLine("            dependencies: [${depsForProj.joinToString(", ") { it }}],")
        appendLine("            path: \"Sources/${pn.targetName}\"$cFlagsLine")
        appendLine("        )" + if (idx != projList.lastIndex || remoteTargets.isNotEmpty()) "," else "")
      }

      appendLine("    ]")
      appendLine(")")
    }
    packageSwift.writeText(manifest)

    println("SPM package root: $pkgRoot")
    println("Wrote: ${packageSwift.path}")
    println("NOTE: -Xfragment-sources now points to SPM symlink paths (with .swift).")
  }
}

