import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release_android as release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "input"
        self.source.mkdir()
        (self.source / "app-debug.apk").write_bytes(b"tested apk")
        self.meta = {"applicationId": "io.github.voldemar64.healthapp", "variantName": "debug", "elements": [
            {"versionName": "0.4.0", "versionCode": 4, "filters": [], "outputFile": "app-debug.apk"}]}
        self.write_metadata()
        self.output = self.root / "output"
        self.env = {"GITHUB_REF": "refs/heads/main", "GITHUB_EVENT_NAME": "push", "GITHUB_SHA": "a" * 40,
                    "GITHUB_REPOSITORY": "owner/repo", "GITHUB_RUN_ID": "123"}
        self.certificate = "b" * 64
        self.current = None
        self.commands = []
        self.bad_digest = False

    def write_metadata(self):
        (self.source / "output-metadata.json").write_text(json.dumps(self.meta), encoding="utf-8")

    def github(self, endpoint, *args, **kwargs):
        if "/releases/tags/" in endpoint:
            return self.current
        if "/git/ref/tags/" in endpoint:
            return None
        if endpoint.endswith("generate-notes"):
            return {"body": "Changes from reviewed PRs"}
        self.fail(f"Unexpected API call: {endpoint}")

    def command(self, *args):
        self.commands.append(args)
        if args[:3] == ("gh", "release", "create"):
            self.current = {"draft": True, "target_commitish": self.env["GITHUB_SHA"],
                            "html_url": "https://github.com/owner/repo/releases/tag/v0.4.0", "assets": []}
        elif args[:3] == ("gh", "release", "upload"):
            self.current["assets"] = [{"name": Path(p).name, "size": Path(p).stat().st_size,
                                       "digest": "sha256:" + ("0" * 64 if self.bad_digest else release.sha256(Path(p)))} for p in args[4:6]]
        elif args[:3] == ("gh", "release", "edit"):
            self.current["draft"] = False
        else:
            self.fail(f"Unexpected command: {args}")
        return ""

    def fake_sign(self, apk, output, env, fingerprint):
        self.assertEqual(b"tested apk", apk.read_bytes())
        output.write_bytes(b"signed tested apk")

    def publish(self):
        with patch.object(release, "api", side_effect=self.github), patch.object(release, "run", side_effect=self.command), \
                patch.object(release, "sign", side_effect=self.fake_sign):
            return release.publish(self.source, self.output, self.env, self.certificate)

    def test_publishes_only_after_both_asset_digests_match(self):
        url = self.publish()
        self.assertFalse(self.current["draft"])
        self.assertTrue(url.endswith("v0.4.0"))
        self.assertEqual(["create", "upload", "edit"], [call[2] for call in self.commands])
        self.assertIn("versionCode 4", (self.output / "notes.md").read_text(encoding="utf-8"))

    def test_published_release_is_immutable_and_needs_no_signing_secret(self):
        self.current = {"draft": False, "html_url": "published"}
        self.assertEqual("published", self.publish())
        self.assertEqual([], self.commands)
        self.assertFalse(self.output.exists())

    def test_retry_repairs_only_draft_at_same_commit(self):
        self.current = {"draft": True, "target_commitish": "a" * 40, "html_url": "published", "assets": []}
        self.publish()
        self.assertEqual(["upload", "edit"], [call[2] for call in self.commands])

    def test_draft_from_another_commit_is_not_modified(self):
        self.current = {"draft": True, "target_commitish": "c" * 40}
        with self.assertRaisesRegex(ValueError, "another commit"):
            self.publish()
        self.assertEqual([], self.commands)

    def test_failed_asset_verification_leaves_draft_unpublished(self):
        self.bad_digest = True
        with self.assertRaisesRegex(ValueError, "did not verify"):
            self.publish()
        self.assertTrue(self.current["draft"])
        self.assertNotIn("edit", [call[2] for call in self.commands])

    def test_pull_request_and_non_main_are_rejected(self):
        for key, value in [("GITHUB_REF", "refs/heads/feature"), ("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_SHA", "main")]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                release.require_main(self.env | {key: value})

    def test_qa_package_and_invalid_versions_are_rejected(self):
        self.meta["applicationId"] += ".qa"
        self.write_metadata()
        with self.assertRaises(ValueError):
            release.metadata(self.source)
        self.meta["applicationId"] = "io.github.voldemar64.healthapp"
        for version in ["", "v0.4.0", "0.4.0;command", "../0.4.0", "0.04.0"]:
            self.meta["elements"][0]["versionName"] = version
            self.write_metadata()
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.metadata(self.source)

    def test_missing_apk_and_split_apks_are_rejected(self):
        self.meta["elements"][0]["outputFile"] = "../other.apk"
        self.write_metadata()
        with self.assertRaises(ValueError):
            release.metadata(self.source)
        self.meta["elements"][0]["outputFile"] = "app-debug.apk"
        self.meta["elements"][0]["filters"] = [{"filterType": "ABI", "value": "x86_64"}]
        self.write_metadata()
        with self.assertRaises(ValueError):
            release.metadata(self.source)

    def test_missing_local_key_never_uses_a_new_key(self):
        with self.assertRaisesRegex(ValueError, "local signing keystore is required"):
            release.sign(self.source / "app-debug.apk", self.output / "apk", {}, self.certificate)

    def test_wrong_certificate_is_rejected_without_modifying_local_key(self):
        key = self.root / "debug.keystore"
        key.write_bytes(b"synthetic key")
        env = {"LOCAL_SIGNING_KEYSTORE": str(key), "ANDROID_HOME": "sdk"}
        with patch.object(release, "run", side_effect=["", "Signer #1 certificate SHA-256 digest: " + "c" * 64]):
            with self.assertRaisesRegex(ValueError, "certificate differs"):
                release.sign(self.source / "app-debug.apk", self.root / "signed.apk", env, self.certificate)
        self.assertEqual(b"synthetic key", key.read_bytes())

    def test_ci_creates_only_draft_without_key_or_assets(self):
        with patch.object(release, "api", side_effect=self.github), patch.object(release, "run", side_effect=self.command), \
                patch.object(release, "sign") as sign:
            release.publish(self.source, self.output, self.env, self.certificate, draft_only=True)
        sign.assert_not_called()
        self.assertTrue(self.current["draft"])
        self.assertEqual([], self.current["assets"])
        self.assertEqual(["create"], [call[2] for call in self.commands])

    def test_local_publication_requires_complete_green_main_run_and_all_jobs(self):
        info = {"head_branch": "main", "event": "push", "status": "completed", "conclusion": "success",
                "path": ".github/workflows/android.yml", "head_sha": "a" * 40}
        names = ["Build and lint", "Device tests API 29", "Device tests API 36", "Prepare Android release draft"]
        jobs = {"jobs": [{"name": name, "conclusion": "success"} for name in names]}
        with patch.object(release, "api", side_effect=[info, jobs]):
            self.assertEqual("a" * 40, release.verified_run("owner/repo", "123")["GITHUB_SHA"])
        for key, value in [("head_branch", "feature"), ("event", "pull_request"), ("status", "in_progress"),
                           ("conclusion", "failure"), ("path", "other.yml")]:
            with self.subTest(key=key), patch.object(release, "api", return_value=info | {key: value}), self.assertRaises(ValueError):
                release.verified_run("owner/repo", "123")
        jobs["jobs"][2]["conclusion"] = "skipped"
        with patch.object(release, "api", side_effect=[info, jobs]), self.assertRaisesRegex(ValueError, "jobs"):
            release.verified_run("owner/repo", "123")

    def test_api_outage_is_not_treated_as_missing_release(self):
        result = subprocess.CompletedProcess([], 1, "", "gh: outage (HTTP 503)")
        with patch.object(release.subprocess, "run", return_value=result), self.assertRaises(RuntimeError):
            release.api("repos/owner/repo/releases/tags/v0.4.0", missing_ok=True)

    def test_existing_tag_cannot_be_retargeted(self):
        def github(endpoint, *args, **kwargs):
            if "/git/ref/" in endpoint:
                return {"object": {"sha": "c" * 40}}
            if "/commits/" in endpoint:
                return {"sha": "c" * 40}
            return self.github(endpoint, *args, **kwargs)
        with patch.object(release, "api", side_effect=github), self.assertRaisesRegex(ValueError, "tag belongs"):
            release.publish(self.source, self.output, self.env, self.certificate)


if __name__ == "__main__":
    unittest.main()
