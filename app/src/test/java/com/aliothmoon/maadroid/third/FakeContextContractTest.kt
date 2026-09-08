package com.aliothmoon.maadroid.third

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import com.aliothmoon.maadroid.TestSources

class FakeContextContractTest {

    @Test
    fun namedResolverDeclaresAcquireProvider() {
        val src = resolve("src/main/java/com/aliothmoon/maadroid/third/FakeContext.java").readText()
        assertTrue(src.contains("class ShellContentResolver"))
        assertTrue(src.contains("acquireProvider(Context"))
    }

    /** 统一走 [TestSources]：模块目录名不写死，挪模块时不必回来改 */
    private fun resolve(relativePath: String): File =
        TestSources.resolve(relativePath)
}
