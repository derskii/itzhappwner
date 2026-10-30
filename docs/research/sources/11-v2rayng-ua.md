# S11 — v2rayNG subscription User-Agent source

- URL: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/java/com/v2ray/ang/util/HttpUtil.kt
- Source type: primary client source.
- Scores: credibility 5/5; recency 5/5; bias risk low.
- Verbatim excerpt: `"v2rayNG/${BuildConfig.VERSION_NAME}"`.

v2rayNG builds the default subscription UA from its own build version and allows a custom user-provided value. A stable preset should use the installed app version if known or preserve this as a template; do not invent a release number.
