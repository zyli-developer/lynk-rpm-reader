#!/usr/bin/env python3
"""Fail CI when tracked content is unsafe or unusable in a public repository."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence
from urllib.parse import unquote


MAX_TEXT_BYTES = 2 * 1024 * 1024
REQUIRED_RELEASE_APP_NAME = "领克转速监视器"
REQUIRED_RELEASE_APK_PATTERN = (
    'outputFileName = "领克转速监视器-v${variant.versionName}-release.apk"'
)
ANDROID_XML_NAMESPACE = "http://schemas.android.com/apk/res/android"

ALLOWED_BINARY_PATH_PATTERNS: Sequence[re.Pattern[str]] = (
    re.compile(r"^gradle/wrapper/gradle-wrapper\.jar$", re.IGNORECASE),
    re.compile(r"^docs/assets/[^/]+\.(?:png|jpe?g|webp)$", re.IGNORECASE),
    re.compile(
        r"^rpmreader/src/main/res/mipmap-[^/]+/[^/]+\.png$",
        re.IGNORECASE,
    ),
)

BLOCKED_PATH_PATTERNS: Sequence[tuple[re.Pattern[str], str]] = (
    (
        re.compile(
            r"(?:^|/)(?:backups?|captures?|device[-_]?dumps?|private|secrets?|decompiled)(?:/|$)",
            re.IGNORECASE,
        ),
        "private capture, dump, secret, or decompiled directory",
    ),
    (
        re.compile(r"(?:^|/)(?:local\.properties|release-signing\.properties|\.env)$", re.IGNORECASE),
        "machine-local or signing configuration",
    ),
    (
        re.compile(r"\.(?:apk|aab|hprof|jks|keystore|p12|pfx|pem|key|log)$", re.IGNORECASE),
        "binary, diagnostic, signing, or log artifact",
    ),
)

CONTENT_PATTERNS: Sequence[tuple[re.Pattern[str], str]] = (
    (
        re.compile(r"-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----"),
        "private key material",
    ),
    (
        re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"),
        "GitHub access token",
    ),
    (
        re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
        "AWS access key ID",
    ),
    (
        re.compile(r"\bAIza[0-9A-Za-z_-]{30,}\b"),
        "Google API key",
    ),
    (
        re.compile(
            r"(?i)\b(?:password|passwd|secret|token|api[_-]?key)\b\s*[:=]\s*[\"']?[A-Za-z0-9_./+=-]{12,}"
        ),
        "credential-like assignment",
    ),
    (
        re.compile(r"(?i)\bheadunit_[A-Za-z0-9]{12,}\b"),
        "device-specific capture name or serial",
    ),
    (
        re.compile(r"(?i)\b(?:ro\.serialno|serial(?:number|no)?|device[_ -]?id)\b\s*[:=]\s*[A-Za-z0-9_-]{8,}"),
        "device identifier",
    ),
    (
        re.compile(r"(?i)\bVIN\b\s*[:=]\s*[A-HJ-NPR-Z0-9]{17}\b"),
        "vehicle identification number",
    ),
    (
        re.compile(r"(?<![<\w])[A-Za-z]:[\\/](?![<>])"),
        "machine-specific absolute Windows path",
    ),
    (
        re.compile(r"(?<![<\w])/(?:Users|home)/[A-Za-z0-9._-]+/"),
        "machine-specific user home path",
    ),
)

MARKDOWN_LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\)")


@dataclass(frozen=True)
class Finding:
    path: str
    line: int | None
    reason: str

    def render(self) -> str:
        location = self.path if self.line is None else f"{self.path}:{self.line}"
        return f"{location}: {self.reason}"


def repository_files(root: Path) -> list[Path]:
    command = ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"]
    result = subprocess.run(command, cwd=root, check=True, capture_output=True)
    return [root / item.decode("utf-8") for item in result.stdout.split(b"\0") if item]


def path_findings(relative_path: str) -> Iterable[Finding]:
    normalized = relative_path.replace("\\", "/")
    for pattern, reason in BLOCKED_PATH_PATTERNS:
        if pattern.search(normalized):
            yield Finding(relative_path, None, reason)


def markdown_findings(root: Path, path: Path, text: str) -> Iterable[Finding]:
    if path.suffix.lower() != ".md":
        return
    for match in MARKDOWN_LINK.finditer(text):
        target = unquote(match.group(1)).split("#", 1)[0]
        if not target or re.match(r"^(?:https?://|mailto:|#)", target, re.IGNORECASE):
            continue
        line = text.count("\n", 0, match.start()) + 1
        resolved = (path.parent / target).resolve()
        try:
            resolved.relative_to(root)
        except ValueError:
            yield Finding(str(path.relative_to(root)), line, "Markdown link escapes the repository")
            continue
        if not resolved.exists():
            yield Finding(str(path.relative_to(root)), line, f"Markdown link target does not exist: {target}")


def text_findings(root: Path, path: Path, text: str) -> Iterable[Finding]:
    relative = str(path.relative_to(root)).replace("\\", "/")
    for line_number, line in enumerate(text.splitlines(), start=1):
        for pattern, reason in CONTENT_PATTERNS:
            if pattern.search(line):
                yield Finding(relative, line_number, reason)
    yield from markdown_findings(root, path, text)


def is_allowed_binary(relative_path: str) -> bool:
    normalized = relative_path.replace("\\", "/")
    return any(pattern.fullmatch(normalized) for pattern in ALLOWED_BINARY_PATH_PATTERNS)


def decode_text(data: bytes) -> str | None:
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        try:
            return data.decode("utf-16")
        except UnicodeDecodeError:
            return None
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return None


def file_findings(root: Path, path: Path) -> Iterable[Finding]:
    if not path.is_file():
        return
    relative = str(path.relative_to(root)).replace("\\", "/")
    if path.stat().st_size > MAX_TEXT_BYTES:
        yield Finding(
            relative,
            None,
            f"file exceeds the public-review size limit ({MAX_TEXT_BYTES} bytes)",
        )
        return
    data = path.read_bytes()
    text = decode_text(data)
    if text is not None and (b"\0" not in data or data.startswith((b"\xff\xfe", b"\xfe\xff"))):
        yield from text_findings(root, path, text)
        return
    if not is_allowed_binary(relative):
        yield Finding(relative, None, "unreviewed binary or unsupported text encoding")


def scan_repository(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in repository_files(root):
        relative = str(path.relative_to(root)).replace("\\", "/")
        findings.extend(path_findings(relative))
        findings.extend(file_findings(root, path))
    findings.extend(release_identity_findings(root))
    return sorted(set(findings), key=lambda item: (item.path, item.line or 0, item.reason))


def release_identity_findings(root: Path) -> Iterable[Finding]:
    manifest_relative = "rpmreader/src/main/AndroidManifest.xml"
    strings_relative = "rpmreader/src/main/res/values/strings.xml"
    build_relative = "rpmreader/build.gradle"
    manifest_path = root / manifest_relative
    strings_path = root / strings_relative
    build_path = root / build_relative

    try:
        application = ET.parse(manifest_path).getroot().find("application")
        label = None if application is None else application.get(
            f"{{{ANDROID_XML_NAMESPACE}}}label"
        )
        if label != "@string/app_name":
            yield Finding(
                manifest_relative,
                None,
                "release application label must reference @string/app_name",
            )
    except (OSError, ET.ParseError):
        yield Finding(manifest_relative, None, "release Android manifest is missing or invalid")

    try:
        resources = ET.parse(strings_path).getroot()
        app_name = next(
            (item.text for item in resources.findall("string") if item.get("name") == "app_name"),
            None,
        )
        if app_name != REQUIRED_RELEASE_APP_NAME:
            yield Finding(
                strings_relative,
                None,
                f"release app_name must be {REQUIRED_RELEASE_APP_NAME!r}",
            )
    except (OSError, ET.ParseError):
        yield Finding(strings_relative, None, "release string resources are missing or invalid")

    try:
        build_text = build_path.read_text(encoding="utf-8")
        if REQUIRED_RELEASE_APK_PATTERN not in build_text:
            yield Finding(
                build_relative,
                None,
                "release APK filename rule is missing or incorrect",
            )
    except OSError:
        yield Finding(build_relative, None, "release Gradle configuration is missing")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="repository root")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    findings = scan_repository(root)
    if findings:
        print("Public repository gate failed:", file=sys.stderr)
        for finding in findings:
            print(f"  - {finding.render()}", file=sys.stderr)
        print(
            "Remove or redact private material. Keep raw vehicle evidence in a private archive.",
            file=sys.stderr,
        )
        return 1
    print("Public repository gate passed: tracked content is safe for public review.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
