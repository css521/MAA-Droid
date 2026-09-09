#!/usr/bin/env python3
"""Run the offline diagnostic JVM tests using cached Kotlin/JUnit jars, without Gradle.

Requires Java 17, cached Kotlin 2.4.10 and JUnit 4.13.2. Writes only to a temporary
directory, removed on exit. This does not build an APK or exercise Android APIs.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = next(p for p in Path(__file__).resolve().parents if (p / "settings.gradle.kts").is_file())
CACHE = Path(os.environ.get("DIAGNOSTIC_JAR_CACHE", str(Path.home() / ".gradle/caches/modules-2/files-2.1")))


def jar(group, artifact, version):
    matches = list((CACHE / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if not matches:
        raise SystemExit(f"Missing cached jar: {group}:{artifact}:{version}; no downloads attempted")
    return str(matches[0])


def main():
    java = os.environ.get("DIAGNOSTIC_JAVA")
    if not java:
        java_home = subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()
        java = str(Path(java_home) / "bin/java")
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10")
    annotations = jar("org.jetbrains", "annotations", "13.0")
    compiler = [
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.10"),
        jar("org.jetbrains.kotlin", "kotlin-build-tools-api", "2.4.10"),
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.4.10"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
        stdlib, annotations,
    ]
    dependencies = [stdlib, annotations, jar("junit", "junit", "4.13.2"), jar("org.hamcrest", "hamcrest-core", "1.3")]
    main_dir = ROOT / "app/src/main/java/com/aliothmoon/maadroid"
    test_dir = ROOT / "app/src/test/java/com/aliothmoon/maadroid"
    sources = [main_dir / "diagnostics" / name for name in (
        "DiagnosticText.kt", "DiagnosticStore.kt", "DiagnosticCrashHandler.kt", "DiagnosticArchive.kt",
    )] + [main_dir / "domain/service/LogExportCollector.kt", main_dir / "constant/LogConfig.kt"]
    sources += sorted((test_dir / "diagnostics").glob("*Test.kt"))
    sources += [test_dir / "domain/service/LogExportCollectorTest.kt"]
    with tempfile.TemporaryDirectory(prefix="maadroid-diagnostics-jvm-") as directory:
        classes = str(Path(directory) / "classes")
        print("Compiling diagnostic sources/tests with Kotlin 2.4.10, JVM target 17 (no Gradle)", flush=True)
        subprocess.run([java, "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", os.pathsep.join(dependencies),
                        "-d", classes, *map(str, sources)], check=True)
        tests = ["com.aliothmoon.maadroid.diagnostics." + name for name in (
            "DiagnosticStoreTest", "DiagnosticArchiveTest", "DiagnosticCrashHandlerTest",
        )] + ["com.aliothmoon.maadroid.domain.service.LogExportCollectorTest"]
        subprocess.run([java, "-cp", os.pathsep.join([classes, *dependencies]), "org.junit.runner.JUnitCore", *tests], check=True)


if __name__ == "__main__":
    main()
