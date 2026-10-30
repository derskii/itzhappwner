# S1 — libxposed API maintainer README

- URL: https://github.com/JingMatrix/libxposed-api/blob/master/README.md
- Source type: primary project repository; current API maintainer repository.
- Scores: credibility 5/5; recency 5/5 (repository file modified 2026-06-13); bias risk low.
- Verbatim excerpts: “compileOnly(`io.github.libxposed:api:102.0.0`)”; “minSdk-26”; “-adaptresourcefilecontents META-INF/xposed/java_init.list”.

The README prescribes the API dependency for module developers, calls out Android API 26 as the minimum, and provides R8 rules to preserve/adjust the modern Java entry resource. These are integration requirements; they do not by themselves migrate legacy callbacks.
