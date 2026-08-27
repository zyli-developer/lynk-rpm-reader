from pathlib import Path
import unittest

from scripts.public_repo_gate import markdown_findings, path_findings, text_findings


class PublicRepoGateTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
