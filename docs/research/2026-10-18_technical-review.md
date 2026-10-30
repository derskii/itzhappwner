# Happwner fork: API 102 and subscription identity review

## Findings

Native libxposed API 102 is a full module migration. The maintained API README specifies `compileOnly("io.github.libxposed:api:102.0.0")`, an `XposedModule` entry in `META-INF/xposed/java_init.list`, and R8 keep/resource-adaptation rules. Hooking uses `hook(Executable).intercept(Hooker)` with an interceptor `Chain`; legacy `IXposedHookLoadPackage`, `XC_MethodHook`, and `XposedHelpers` are not the API 102 entry/hook contract. Modules targeting 102 cannot invoke `de.robv.android.xposed` APIs. See [S1](sources/01-libxposed-readme.md), [S3](sources/03-libxposed-interface.md), [S4](sources/04-libxposed-module.md), and [S6](sources/06-lsposed-guide.md).

The API library declares Android `minSdk = 26` and Java 17. In Vector's current API 102 implementation, `onPackageReady` runs from Android P/API 28 and `onPackageLoaded` from Android Q/API 29. API 26–27 do not receive these package lifecycle callbacks through that code path. The fork should not retain the Android 5+ claim for the native API 102 implementation without a separately verified pre-28 initialization route. See [S2](sources/02-libxposed-build.md) and [S5](sources/05-vector-lifecycle.md).

Happ's developer docs establish a `change-user-agent` management option, but do not establish `x-os`, `x-ver`, or `x-device-model` as Happ contract headers. The XTLS/Xray discussion separately proposes optional `x-device-os`, `x-ver-os`, and `x-device-model` headers; its User-Agent section identifies the app client, not device model. The proposal is not evidence that Happ sends or requires those fields. Do not run live subscription requests to infer this. See [S7](sources/07-happ-docs.md) and [S8](sources/08-xtls-header-proposal.md).

Open client source shows versioned, dynamic UA patterns: Hiddify uses `HiddifyNext/$version ($operatingSystem) like ClashMeta v2ray sing-box` (or `HiddifyNextX` for Xray core); v2rayNG uses `v2rayNG/${BuildConfig.VERSION_NAME}` by default. NekoBox uses a custom UA or a project constant whose literal was not resolved here. No exact tokens were established for closed-source clients. See [S10](sources/10-hiddify-ua.md), [S11](sources/11-v2rayng-ua.md), and [S12](sources/12-nekobox-ua.md).

The project README states non-commercial terms and attribution requirements; public/commercial distribution of a fork needs a terms check. See [S9](sources/09-happwner-terms.md).

## Adversarial check

- Could the dependency alone preserve old Xposed behavior? No: API 102 has a different entry resource, superclass, callback lifecycle, and interceptor hook model.
- Does the API artifact's API 26 minimum prove every framework callback is available on API 26? No: Vector dispatches its package callbacks from API 28/29 as above.
- Does a subscription header proposal prove Happ uses it? No: Happ's public docs do not state that contract.
- Can current UA versions be safely copied into presets? No: the source uses app-build values and custom overrides.

Limitations: no runtime device tests were performed; exact NekoBox fallback UA, closed-source client tokens, and Happ's undocumented request headers remain unresolved.
