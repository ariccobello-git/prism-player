# Prism Player shrinking rules (the published debug APK is shrunk and optimised by R8, see app/build.gradle.kts).
# kotlinx.serialization, Media3 and Compose ship their own consumer rules.

# NanoHTTPD logs through java.util.logging and references no optional servlet classes.
-dontwarn fi.iki.elonen.**

# Keep readable stack traces in crash reports (only class and line numbers; no addresses are ever logged).
-keepattributes SourceFile,LineNumberTable
