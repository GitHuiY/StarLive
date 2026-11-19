package io.github.githuiy.starlive;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Keeps AndroidX's provider boundary check valid for Windows-hosted Robolectric paths. */
@Implements(className = "androidx.core.content.FileProvider$SimplePathStrategy", isInAndroidSdk = false)
public final class FileProviderPathShadow {
    @Implementation
    protected boolean belongsToRoot(String filePath, String rootPath) {
        String normalizedFile = filePath.replace('\\', '/');
        String normalizedRoot = rootPath.replace('\\', '/');
        return normalizedFile.equals(normalizedRoot) || normalizedFile.startsWith(normalizedRoot + '/');
    }
}
