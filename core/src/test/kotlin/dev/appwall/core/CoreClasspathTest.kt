package dev.appwall.core

import org.junit.Assert.fail
import org.junit.Test

/** The point of :core: Android isn't available here, so nothing in it can depend on Android. */
class CoreClasspathTest {

    @Test
    fun androidIsNotOnTheClasspath() {
        try {
            Class.forName("android.os.Build")
            fail("android.os.Build is reachable from :core; the module must stay Android-free")
        } catch (expected: ClassNotFoundException) {
        }
    }
}
