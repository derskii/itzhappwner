# S6 — LSPosed modern module guide

- URL: https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API
- Source type: primary framework developer documentation.
- Scores: credibility 5/5; recency 4/5 (guide edited 2026-08-08); bias risk low.
- Verbatim excerpts: “Java entry now uses `META-INF/xposed/java_init.list` instead of `assets/xposed_init`”; “scope.list”; “module.prop”.

The guide identifies modern Java entry registration, per-module scope configuration, and Java-properties module metadata. The `module.prop` API fields `minApiVersion` and `targetApiVersion` are Xposed API versions, separate from Android `minSdk`. If the module calls API 102-only methods, its minimum Xposed API must reflect that.
