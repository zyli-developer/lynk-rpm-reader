# Contributing

Issues and pull requests are welcome. Keep changes focused, add or update tests for protocol and gauge logic, and never submit vehicle owner data, third-party code, binaries, signing material, credentials, device dumps, protocol definitions, or assets without documented redistribution rights. See `PROVENANCE.md` before contributing.

Before opening a pull request, run:

```bash
./gradlew :rpmreader:rpmLogicTest :rpmreader:assembleDebug
```
