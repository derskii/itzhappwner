# S3 — libxposed `XposedInterface` API 102

- URL: https://github.com/JingMatrix/libxposed-api/blob/master/api/src/main/java/io/github/libxposed/api/XposedInterface.java
- Source type: primary API source.
- Scores: credibility 5/5; recency 5/5; bias risk low.
- Verbatim excerpts: “int API_102 = 102”; “HookBuilder hook(@NonNull Executable origin)”; “Object intercept(@NonNull Chain chain) throws Throwable”; “Libxposed modules can not call legacy `de.robv.android.xposed` APIs.”

Native hooks are built around `Executable`, a `HookBuilder`, an interceptor `Hooker`, and a `Chain`; old `XposedHelpers` / `XC_MethodHook` callback code is not the native API 102 shape. API 102 targeting forbids calls into the legacy namespace. API-102-only additions include detach, hot reload, and hook replacement/IDs; use `getApiVersion()` or set module metadata appropriately before calling newer-only APIs.
