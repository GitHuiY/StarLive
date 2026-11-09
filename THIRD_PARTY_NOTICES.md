# Third-party notices

The existing StarLive Web prototype and new project code retain the repository's MIT license.

Generated Gradle Wrapper scripts and `android/gradle/wrapper/gradle-wrapper.jar` originate from Gradle 8.11.1 and retain their original Apache License 2.0 notices. The license supplied with that distribution is preserved at `android/gradle/wrapper/LICENSE`.

The capability probe and controlled usage fixture use Android platform APIs without a bundled third-party runtime library. Android SDK, Eclipse Temurin, Gradle distributions and their caches are local development tools outside this repository; their supplied licenses remain with those distributions.

The native `:app` and `:plan-core` modules also use the following unmodified upstream libraries under the Apache License 2.0:

| Component | Pinned version / source |
| --- | --- |
| AndroidX Compose UI/Foundation/Material 3 and related AndroidX libraries | Compose BOM 2025.06.01 (Compose 1.8.3, Material 3 1.3.2); Activity 1.10.1; Lifecycle 2.9.1; [Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin standard library and annotations | Kotlin 2.2.0; JetBrains annotations 23.0.0; [JetBrains Kotlin](https://github.com/JetBrains/kotlin) |
| kotlinx.coroutines | 1.7.3; [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Gson | 2.11.0; [Google Gson](https://github.com/google/gson) |
| Guava ListenableFuture | 1.0; [Google Guava](https://github.com/google/guava) |
| JSpecify annotations | 1.0.0; [JSpecify](https://github.com/jspecify/jspecify) |

Copyright remains with Google, The Android Open Source Project, JetBrains, and the respective upstream contributors. A full Apache License 2.0 copy and these attributions are bundled in the native APK's `assets/licenses/` directory. No downloaded fonts or bitmap assets are bundled; the interface uses Android system fonts and native drawing.

Development checks additionally use JUnit 4.13.2 (Eclipse Public License 1.0), Robolectric 4.15.1 (MIT), and AndroidX Test (Apache License 2.0). They are test dependencies, not production app capabilities. Compose tooling and its test host are confined to debug builds. Gradle, Android Gradle Plugin, Kotlin compiler, and their transitive build/test dependencies retain their upstream licenses in the workspace caches; they are not copied into the release app as development tools.
