"""Publish the tested APK after CI, retaining the prototype's existing signing identity."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def run(*args):
    result = subprocess.run(args, text=True, encoding="utf-8", capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed: {result.stderr.strip()}")
    return result.stdout


def api(endpoint, *args, missing_ok=False):
    result = subprocess.run(["gh", "api", endpoint, *args], text=True, encoding="utf-8", capture_output=True, check=False)
    if result.returncode:
        if missing_ok and "(HTTP 404)" in result.stderr:
            return None
        raise RuntimeError(f"GitHub API failed: {result.stderr.strip()}")
    return json.loads(result.stdout)


def metadata(directory):
    data = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
    if data.get("applicationId") != "io.github.voldemar64.healthapp" or data.get("variantName") != "debug":
        raise ValueError("Only the normal application debug APK may be published")
    elements = data.get("elements", [])
    if len(elements) != 1 or elements[0].get("filters") != []:
        raise ValueError("Expected one universal APK")
    element = elements[0]
    version = element.get("versionName", "")
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("versionName must be MAJOR.MINOR.PATCH")
    code = element.get("versionCode")
    if type(code) is not int or code < 1:
        raise ValueError("versionCode must be a positive integer")
    filename = element.get("outputFile", "")
    if filename != "app-debug.apk" or not (directory / filename).is_file():
        raise ValueError("Missing app-debug.apk from the build artifact")
    return version, code, directory / filename


def require_main(env):
    if env.get("GITHUB_REF") != "refs/heads/main" or env.get("GITHUB_EVENT_NAME") not in {"push", "workflow_dispatch"}:
        raise ValueError("Releases are allowed only for main push/manual CI runs")
    if not re.fullmatch(r"[0-9a-f]{40}", env.get("GITHUB_SHA", "")):
        raise ValueError("Missing exact source commit")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", env.get("GITHUB_REPOSITORY", "")):
        raise ValueError("Invalid repository")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def sign(apk, output, env, expected_fingerprint):
    key = Path(env.get("LOCAL_SIGNING_KEYSTORE", ""))
    if not key.is_file():
        raise ValueError("Existing local signing keystore is required")
    sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Android SDK is required")
    signer = str(Path(sdk) / "build-tools" / "35.0.0" / ("apksigner.bat" if os.name == "nt" else "apksigner"))
    run(signer, "sign", "--ks", str(key), "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", str(output), str(apk))
    certificate = run(signer, "verify", "--print-certs", str(output))
    fingerprints = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)", certificate)
    if [value.lower() for value in fingerprints] != [expected_fingerprint.lower()]:
        raise ValueError("APK certificate differs from the existing published app")


def publish(directory, output, env, expected_fingerprint, draft_only=False):
    require_main(env)
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected_fingerprint):
        raise ValueError("Invalid expected signing certificate")
    version, code, apk = metadata(directory)
    tag = f"v{version}"
    repo, commit = env["GITHUB_REPOSITORY"], env["GITHUB_SHA"]
    endpoint = f"repos/{repo}/releases/tags/{tag}"
    existing = api(endpoint, missing_ok=True)
    if existing and not existing["draft"]:
        print(f"Already published, unchanged: {existing['html_url']}")
        return existing["html_url"]
    if existing and existing["target_commitish"] != commit:
        raise ValueError("Draft release belongs to another commit; rerun its original CI run")
    ref = api(f"repos/{repo}/git/ref/tags/{tag}", missing_ok=True)
    if ref and api(f"repos/{repo}/commits/{tag}")["sha"] != commit:
        raise ValueError("Existing version tag belongs to another commit")
    output.mkdir(parents=True, exist_ok=True)
    signed = output / f"health-diary-{version}-debug.apk"
    checksums = output / "SHA256SUMS.txt"
    if not existing:
        generated = api(f"repos/{repo}/releases/generate-notes", "-X", "POST", "-f", f"tag_name={tag}", "-f", f"target_commitish={commit}")
        notes = output / "notes.md"
        run_url = f"https://github.com/{repo}/actions/runs/{env['GITHUB_RUN_ID']}"
        notes.write_text(
            f"Тестовый debug-прототип для Android 10+. Версия {version}, versionCode {code}.\n\n"
            "APK подписан тем же ключом, что и предыдущие опубликованные версии. "
            "Устанавливайте поверх приложения, сохраняя историю. При конфликте подписи не удаляйте приложение с нужными данными.\n\n"
            "Русская модель Vosk встроена; озвучивание требует установленного русского офлайн-голоса Android. "
            "Точность редких названий и речи в шуме проверяется на физическом телефоне.\n\n"
            f"[Все проверки CI]({run_url}): сборка, JVM-тесты, lint и тесты API 29/36. "
            "SHA-256 APK находится в SHA256SUMS.txt.\n\n" + generated["body"], encoding="utf-8")
        run("gh", "release", "create", tag, "--repo", repo, "--target", commit, "--draft", "--prerelease",
            "--title", f"Дневник здоровья {version} — тестовая версия", "--notes-file", str(notes))
    if draft_only:
        print(f"Draft {tag} ready; sign and publish locally after this CI run completes")
        return tag
    sign(apk, signed, env, expected_fingerprint)
    checksums.write_text(f"{sha256(signed)}  {signed.name}\n", encoding="utf-8")
    # Only drafts may be repaired. Published releases above are never overwritten.
    run("gh", "release", "upload", tag, str(signed), str(checksums), "--repo", repo, "--clobber")
    release = api(endpoint)
    expected = {path.name: (path.stat().st_size, "sha256:" + sha256(path)) for path in (signed, checksums)}
    assets = {asset["name"]: (asset["size"], asset.get("digest")) for asset in release["assets"]}
    if not release["draft"] or release["target_commitish"] != commit or assets != expected:
        raise ValueError("Draft assets/source did not verify; release remains unpublished")
    run("gh", "release", "edit", tag, "--repo", repo, "--draft=false", "--prerelease")
    result = api(endpoint)
    if result["draft"]:
        raise ValueError("GitHub did not publish the release")
    print(result["html_url"])
    return result["html_url"]


def inspect_downloaded_apk(directory, env):
    """Legacy CI artifacts contain only the APK; read their metadata from the APK itself."""
    sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Android SDK is required")
    aapt = str(Path(sdk) / "build-tools" / "35.0.0" / ("aapt.exe" if os.name == "nt" else "aapt"))
    info = run(aapt, "dump", "badging", str(directory / "app-debug.apk"))
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", info, re.MULTILINE)
    if not package or "application-debuggable" not in info:
        raise ValueError("Expected a valid debug APK")
    data = {"applicationId": package[1], "variantName": "debug", "elements": [
        {"versionCode": int(package[2]), "versionName": package[3], "filters": [], "outputFile": "app-debug.apk"}]}
    (directory / "output-metadata.json").write_text(json.dumps(data), encoding="utf-8")


def verified_run(repo, run_id):
    if not re.fullmatch(r"[1-9][0-9]*", str(run_id)):
        raise ValueError("Invalid workflow run ID")
    info = api(f"repos/{repo}/actions/runs/{run_id}")
    if (info["head_branch"] != "main" or info["event"] not in {"push", "workflow_dispatch"}
            or info["status"] != "completed" or info["conclusion"] != "success"
            or info["path"] != ".github/workflows/android.yml"):
        raise ValueError("Only completed green Android CI on main may be published")
    jobs = api(f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100")["jobs"]
    required = {"Build and lint", "Device tests API 29", "Device tests API 36"}
    if not required.issubset({job["name"] for job in jobs if job["conclusion"] == "success"}):
        raise ValueError("Required CI jobs did not all succeed")
    return {"GITHUB_REF": "refs/heads/main", "GITHUB_EVENT_NAME": info["event"],
            "GITHUB_SHA": info["head_sha"], "GITHUB_REPOSITORY": repo, "GITHUB_RUN_ID": str(run_id)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--certificate", type=Path, required=True)
    parser.add_argument("--draft-only", action="store_true")
    parser.add_argument("--run-id")
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--repo", default="voldemar64/Health-app")
    args = parser.parse_args()
    env = dict(os.environ)
    if args.draft_only:
        if args.keystore or args.run_id:
            parser.error("CI draft preparation must not receive a signing key")
    else:
        if not args.run_id or not args.keystore:
            parser.error("Local publication requires --run-id and --keystore")
        env.update(verified_run(args.repo, args.run_id))
        env["LOCAL_SIGNING_KEYSTORE"] = str(args.keystore.resolve())
        # Fresh directory prevents accidentally publishing a stale local APK.
        args.input.mkdir(parents=True, exist_ok=False)
        run("gh", "run", "download", args.run_id, "--repo", args.repo,
            "--name", "health-app-debug", "--dir", str(args.input))
        inspect_downloaded_apk(args.input, env)
    publish(args.input, args.output, env, args.certificate.read_text(encoding="utf-8").strip(), args.draft_only)
