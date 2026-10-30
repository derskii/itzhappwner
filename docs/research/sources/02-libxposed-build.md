# S2 — libxposed API Android build metadata

- URL: https://github.com/JingMatrix/libxposed-api/blob/master/api/build.gradle.kts
- Source type: primary build configuration for the published API artifact.
- Scores: credibility 5/5; recency 5/5; bias risk low.
- Verbatim excerpts: “minSdk = 26”; “sourceCompatibility = JavaVersion.VERSION_17”; “targetCompatibility = JavaVersion.VERSION_17”.

The API library itself declares Android API 26 as its minimum and Java 17 source/target compatibility. Happwner should not advertise an unqualified API 21 floor for its native API 102 module path. The published artifact is normally consumed as `compileOnly`; the minimum still matters for module runtime/framework compatibility.
