# S4 — libxposed entry class and lifecycle API

- URL: https://github.com/JingMatrix/libxposed-api/blob/master/api/src/main/java/io/github/libxposed/api/XposedModuleInterface.java
- Source type: primary API source.
- Scores: credibility 5/5; recency 5/5; bias risk low.
- Verbatim excerpts: “onModuleLoaded(ModuleLoadedParam param)”; “@RequiresApi(Build.VERSION_CODES.Q)”; “onPackageReady(PackageReadyParam param)”.

The entry class should extend `XposedModule`, whose inherited wrapper is attached by the framework; module initialization belongs in `onModuleLoaded`, not its constructor. `onPackageLoaded` is guarded as Android Q/API 29+. `onPackageReady` is a later package/classloader lifecycle callback. API 102 adds hot-reload callbacks.
