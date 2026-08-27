# Contributing

Issues and pull requests are welcome. Keep changes focused, add or update tests for protocol and gauge logic, and never submit vehicle owner data, third-party code, binaries, signing material, credentials, device dumps, protocol definitions, or assets without documented redistribution rights. See `PROVENANCE.md` before contributing.

Every contribution must pass the public repository gate. Before staging or pushing changes, review the complete diff as if it were immediately visible to everyone. Keep raw head-unit logs, screenshots, device inventories, decompiled applications, serial numbers, VINs, location history, account data, credentials, signing material, and machine-specific paths outside this repository. Commit only the minimum redacted conclusion and reproducible steps.

Before opening a pull request, run:

```bash
python3 -m unittest discover -s scripts/tests -p 'test_public_repo_gate.py'
python3 scripts/public_repo_gate.py
./gradlew :rpmreader:rpmLogicTest :rpmreader:assembleDebug
```
