package io.github.umislat.aicecho.provisioning

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/** android-all 16 includes an old TAR library; exercise the app's resolved dependency. */
class BundledArchiveTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            // Robolectric 4.16.1's sandbox cache key does not include package exclusions.
            .doNotAcquireClass("org.apache.commons.compress.archivers.tar.TarArchiveInputStream")
            .doNotAcquirePackage("org.apache.commons.").build()
}
