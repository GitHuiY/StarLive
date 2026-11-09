package io.github.githuiy.starlive;

import android.util.AtomicFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Keep Android AtomicFile's replace-existing rename semantics on the Windows host.
 * Android uses POSIX rename; Windows java.io.File.renameTo cannot replace a file.
 * Only that private platform operation is adapted; real streams, sync, readback,
 * repository serialization and recovery remain exercised. Never ships in the APK.
 */
@Implements(value = AtomicFile.class, isInAndroidSdk = false)
public final class PosixAtomicFileShadow {
    @Implementation(minSdk = 30)
    protected static void rename(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
