package com.aliothmoon.maadroid

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

        val rules = resolve("proguard-rules.pro").readText()
        val activeRules = rules.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()
        assertFalse(activeRules.any { it.startsWith("-dontoptimize") })
        assertFalse(activeRules.any { it.startsWith("-dontshrink") })
        assertFalse(activeRules.any { it.startsWith("-dontobfuscate") })
        assertFalse(activeRules.any { it.startsWith("-keep class com.aliothmoon.maadroid.**") })
        assertFalse(activeRules.any { it.contains("com.sun.jna.**") })
        listOf(
            "com.aliothmoon.maadroid.bridge.NativeBridgeLib",
            "com.aliothmoon.maadroid.maa.DriverClass",
            "com.aliothmoon.maadroid.maa.**",
            "com.sun.jna.*",
            "com.sun.jna.Structure",
            "com.sun.jna.Callback",
            "com.aliothmoon.maadroid.remote.MaaDroidRemoteService",
            "com.aliothmoon.maadroid.remote.LogcatCaptureServiceImpl",
            "com.aliothmoon.maadroid.root.RootServiceStarter",
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
            "-keepclassmembers enum com.aliothmoon.maadroid.**",
            "valueOf(java.lang.String)",
            "com.aliothmoon.maadroid.third.FakeContext",
            "com.aliothmoon.maadroid.third.FakeContext$*",
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
