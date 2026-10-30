# S10 — Hiddify app User-Agent source

- URL: https://github.com/hiddify/hiddify-app/blob/main/lib/core/model/app_info_entity.dart
- Related source: https://github.com/hiddify/hiddify-app/blob/main/lib/features/profile/data/profile_parser.dart
- Source type: primary client source.
- Scores: credibility 5/5; recency 5/5; bias risk low.
- Verbatim excerpt: `"HiddifyNext/$version ($operatingSystem) like ClashMeta v2ray sing-box"`.

The version and operating system are runtime values, not a fixed version string. The profile parser substitutes `HiddifyNextX` when it selects the Xray core. Do not hard-code a guessed current version into a UA preset.
