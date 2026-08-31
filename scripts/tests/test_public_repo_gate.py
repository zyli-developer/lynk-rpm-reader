from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock

from scripts.public_repo_gate import (
    MAX_TEXT_BYTES,
    file_findings,
    markdown_findings,
    path_findings,
    release_identity_findings,
    text_findings,
)


class PublicRepoGateTest(unittest.TestCase):
    @staticmethod
    def mock_file(relative_path: str, data: bytes) -> Mock:
        path = Mock(spec=Path)
        path.is_file.return_value = True
        path.relative_to.return_value = Path(relative_path)
        path.stat.return_value.st_size = len(data)
        path.read_bytes.return_value = data
        path.suffix = Path(relative_path).suffix
        return path

    def test_blocks_private_artifact_paths(self):
        self.assertTrue(list(path_findings("backups/device/logcat.txt")))
        self.assertTrue(list(path_findings("release-signing.properties")))
        self.assertTrue(list(path_findings("release/app.apk")))

    def test_detects_credentials_and_private_keys(self):
        token = "gh" + "p_" + "A" * 24
        private_key = "-----BEGIN " + "PRIVATE KEY-----"
        root = Path.cwd()
        path = root / "example.txt"
        findings = list(text_findings(root, path, token + "\n" + private_key))
        self.assertEqual(2, len(findings))

    def test_detects_device_identifiers_and_absolute_paths(self):
        serial = "headunit_" + "A1B2C3D4E5F60708"
        local_path = "D" + ":\\private-workspace\\capture.txt"
        root = Path.cwd()
        path = root / "report.txt"
        findings = list(text_findings(root, path, serial + "\n" + local_path))
        self.assertEqual(2, len(findings))

    def test_rejects_links_outside_repository(self):
        root = Path.cwd()
        report = root / "docs" / "report.md"
        findings = list(markdown_findings(root, report, "[private](../../capture.txt)"))
        self.assertEqual(1, len(findings))
        self.assertIn("escapes", findings[0].reason)

    def test_accepts_existing_repository_link_and_placeholder(self):
        root = Path.cwd()
        report = root / "docs" / "report.md"
        text = "[source](../README.md)\n<private-capture>\\inventory\\log.txt"
        findings = list(text_findings(root, report, text))
        self.assertEqual([], findings)

    def test_scans_utf16_text_instead_of_skipping_it(self):
        root = Path.cwd()
        evidence = self.mock_file("evidence.txt", ("VIN: " + "A" * 17).encode("utf-16"))

        findings = list(file_findings(root, evidence))

        self.assertEqual(1, len(findings))
        self.assertEqual("vehicle identification number", findings[0].reason)

    def test_rejects_unrecognized_binary_content(self):
        root = Path.cwd()
        evidence = self.mock_file("evidence.txt", b"\x00\xff\x00\xfe")

        findings = list(file_findings(root, evidence))

        self.assertEqual(1, len(findings))
        self.assertIn("unreviewed binary", findings[0].reason)

    def test_rejects_oversized_files_instead_of_skipping_them(self):
        root = Path.cwd()
        evidence = self.mock_file("evidence.txt", b"")
        evidence.stat.return_value.st_size = MAX_TEXT_BYTES + 1

        findings = list(file_findings(root, evidence))

        self.assertEqual(1, len(findings))
        self.assertIn("size limit", findings[0].reason)

    def test_accepts_binary_only_at_an_explicitly_allowed_path(self):
        root = Path.cwd()
        image = self.mock_file("docs/assets/public-image.png", b"\x89PNG\r\n\x1a\n\x00")

        findings = list(file_findings(root, image))

        self.assertEqual([], findings)

    def test_release_identity_requires_the_formal_chinese_name(self):
        with TemporaryDirectory(dir=Path.cwd()) as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rpmreader/src/main/AndroidManifest.xml"
            strings = root / "rpmreader/src/main/res/values/strings.xml"
            build = root / "rpmreader/build.gradle"
            manifest.parent.mkdir(parents=True)
            strings.parent.mkdir(parents=True)
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<application android:label="@string/app_name" /></manifest>',
                encoding="utf-8",
            )
            strings.write_text(
                '<resources><string name="app_name">领克转速监视器</string></resources>',
                encoding="utf-8",
            )
            build.write_text(
                'outputFileName = "领克转速监视器-v${variant.versionName}-release.apk"',
                encoding="utf-8",
            )

            self.assertEqual([], list(release_identity_findings(root)))

            strings.write_text(
                '<resources><string name="app_name">LynkRPMReader</string></resources>',
                encoding="utf-8",
            )
            findings = list(release_identity_findings(root))
            self.assertEqual(1, len(findings))
            self.assertIn("release app_name", findings[0].reason)


if __name__ == "__main__":
    unittest.main()
