# S12 — NekoBox subscription User-Agent selection source

- URL: https://github.com/MatsuriDayo/NekoBoxForAndroid/blob/main/app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt
- Related source: https://github.com/MatsuriDayo/NekoBoxForAndroid/blob/main/app/src/main/java/io/nekohasekai/sagernet/widget/UserAgentPreference.kt
- Source type: primary client source.
- Scores: credibility 5/5; recency 5/5; bias risk low.
- Verbatim excerpt: `setUserAgent(subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)`.

The code uses the configured custom User-Agent when nonblank, otherwise a project constant. This review did not resolve the constant's literal value, so no exact fallback token is asserted.
