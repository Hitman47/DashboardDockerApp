# Pont JS → Kotlin : le nom de la méthode doit survivre (pas de minification de toute façon).
-keepclassmembers class dev.mkdev.dockerdashboard.** { @android.webkit.JavascriptInterface <methods>; }
