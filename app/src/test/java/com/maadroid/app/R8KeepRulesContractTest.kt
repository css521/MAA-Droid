package com.maadroid.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R8KeepRulesContractTest {

    @Test
    fun minifyEnabledAndKeepRulesCoverNamedEntryPoints() {
        val gradle = resolve("build.gradle.kts").readText()
        assertTrue(Regex("""isMinifyEnabled\s*=\s*true""").containsMatchIn(gradle))
        assertTrue(Regex("""isShrinkResources\s*=\s*true""").containsMatchIn(gradle))

        val engineRules = TestSources.inModuleOwning(
            "src/main/aidl/com/maadroid/app/MaaCoreService.aidl", "consumer-rules.pro",
        ).readText()
        val rules = resolve("proguard-rules.pro").readText() + "\n" + engineRules
        val activeRules = rules.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()
        assertFalse(activeRules.any { it.startsWith("-dontoptimize") })
        assertFalse(activeRules.any { it.startsWith("-dontshrink") })
        assertFalse(activeRules.any { it.startsWith("-dontobfuscate") })
        assertFalse(activeRules.any { it.startsWith("-keep class com.maadroid.app.**") })
        assertFalse(activeRules.any { it.contains("com.sun.jna.**") })
        listOf(
            "com.maadroid.app.bridge.NativeBridgeLib",
            "com.maadroid.app.maa.DriverClass",
            "com.maadroid.app.engine.arknights.core.MaaCoreLibrary",
            "com.maadroid.app.engine.arknights.core.AsstApiCallback",
            "com.maadroid.app.MaaCoreService",
            "com.maadroid.app.MaaCoreCallback",
            "com.sun.jna.*",
            "com.sun.jna.Structure",
            "com.sun.jna.Callback",
            "com.maadroid.app.remote.MaaDroidRemoteService",
            "com.maadroid.app.remote.LogcatCaptureServiceImpl",
            "com.maadroid.app.root.RootServiceStarter",
            "touchDown",
            "startApp",
            "org.eclipse.angus.mail.smtp.**",
            "org.eclipse.angus.mail.imap.**",
            "org.eclipse.angus.mail.pop3.**",
            "org.eclipse.angus.mail.handlers.**",
            "org.eclipse.angus.mail.util.MailStreamProvider",
            "org.eclipse.angus.activation.*RegistryProviderImpl",
            "org.eclipse.tm4e.**",
            "io.github.rosemoe.sora.langs.textmate.**",
            "org.joni.**",
            "org.jcodings.**",
            "-keepclassmembers enum com.maadroid.app.**",
            "valueOf(java.lang.String)",
            "com.maadroid.app.third.FakeContext",
            "com.maadroid.app.third.FakeContext$*",
            "android.content.ContentResolver",
            "acquireProvider(",
            "com.xzakota.hyper.notification.**",
        ).forEach { token ->
            assertTrue("missing keep token: $token", rules.contains(token))
        }

        val keepXml = resolve("src/main/res/raw/keep.xml").readText()
        assertTrue(keepXml.contains("@string/maa_*"))
        assertTrue(keepXml.contains("@string/achievement_*"))
    }

    /**
     * R8 规则、keep.xml 与 minify 配置都是**宿主自己的**，故明确落在 app。
     * 用 TestSources.resolve 会因 build.gradle.kts 多模块同名而报歧义。
     */
    private fun resolve(relativePath: String): File =
        TestSources.inApp(relativePath)
}
