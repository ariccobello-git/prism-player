# Prism Player release rules (release builds are not part of the debug CI workflow).
# kotlinx.serialization, Media3 and Compose ship their own consumer rules.

# NanoHTTPD logs through java.util.logging and references no optional servlet classes.
-dontwarn fi.iki.elonen.**
