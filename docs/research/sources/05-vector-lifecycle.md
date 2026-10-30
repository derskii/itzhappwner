# S5 — Vector API 102 lifecycle dispatch

- URLs: https://github.com/JingMatrix/Vector/blob/master/xposed/src/main/kotlin/org/matrix/vector/impl/VectorLifecycleManager.kt and https://github.com/JingMatrix/Vector/blob/master/xposed/src/main/kotlin/org/matrix/vector/impl/hookers/LoadedApkHookers.kt
- Source type: primary implementation of the API 102 framework by its maintainer.
- Scores: credibility 5/5 for Vector behavior; recency 5/5; bias risk low.
- Verbatim excerpts: “The API declares `onPackageLoaded` as API 29 and up”; “Build.VERSION.SDK_INT >= Build.VERSION_CODES.P”.

In this implementation, modern `onPackageReady` is dispatched from API 28 (P); `onPackageLoaded` dispatch occurs from API 29 (Q). Thus the artifact's API 26 floor does not imply these package lifecycle callbacks run on API 26–27. A module requiring hooks on those Android releases needs a separately validated initialization path.
