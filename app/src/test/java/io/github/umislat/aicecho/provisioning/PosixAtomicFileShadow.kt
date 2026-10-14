package io.github.umislat.aicecho.provisioning

import android.util.AtomicFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Android uses POSIX rename-over-existing; Windows File.renameTo cannot do that. */
@Implements(AtomicFile::class)
class PosixAtomicFileShadow {
    companion object {
        var failRename = false

        @JvmStatic @Implementation(minSdk = 30)
        fun rename(source: File, target: File) {
            if (failRename) return
            if (target.isDirectory) check(target.delete())
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } else check(source.renameTo(target))
        }

        @JvmStatic @Resetter fun reset() { failRename = false }
    }
}
