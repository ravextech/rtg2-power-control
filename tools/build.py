#!/usr/bin/env python3
"""Small pinned Android build: Python 3 + JDK 17+, no Gradle or global SDK install."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
CACHE = Path(os.environ.get("RTG2_BUILD_CACHE", str(BUILD / "tools"))).resolve()
FILES = {
    "r8.jar": ("https://storage.googleapis.com/r8-releases/raw/8.3.37/r8.jar", "900dfbc649519969fc5a4c7520d6b7355338e565fa1249874e0190b8d61b1199"),
    "apksig.jar": ("https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.3.2/apksig-8.3.2.jar", "468ee1852fa12461c6629608fe47be70e50f60be7c4744bb7f62ca48e85c0a6c"),
    "platform34.zip": ("https://dl.google.com/android/repository/platform-34-ext7_r03.zip", "16fdb74c55e59ae3ef52def135aec713508467bd56d7dabcd8c9be31fa8b20f3"),
    "aapt2-darwin.jar": ("https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.3.2-10880808/aapt2-8.3.2-10880808-osx.jar", "fec87105b3f1d2a0df2d94a04f9795bce98ff7832c74c91a43ed1412147bd2a0"),
    "aapt2-linux.jar": ("https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.3.2-10880808/aapt2-8.3.2-10880808-linux.jar", "11ceee7c4306c1fa1249556e47e3820dec80ee6f64bb7f1c46828af45bf1ad7d"),
}

def run(*args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)

def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / name if home else None
    if candidate and candidate.is_file(): return candidate
    result = shutil.which(name)
    if not result: raise SystemExit("JDK 17+ required: cannot find " + name)
    return Path(result)

def fetch(name):
    url, expected = FILES[name]
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / name
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == expected: return target
    print("Downloading", name, flush=True)
    temporary = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as output:
        shutil.copyfileobj(response, output)
    if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
        temporary.unlink()
        raise SystemExit("SHA-256 mismatch for " + name)
    temporary.replace(target)
    return target

def tests():
    classes = BUILD / "tests"
    classes.mkdir(parents=True, exist_ok=True)
    run(java_tool("javac"), "--release", "8", "-Xlint:-options", "-d", classes,
        ROOT / "app/src/main/java/io/github/ravextech/rtg2powercontrol/BootModeController.java",
        ROOT / "tests/BootModeControllerTest.java")
    run(java_tool("java"), "-cp", classes, "BootModeControllerTest", ROOT / "app/src/main/assets")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-only", action="store_true")
    parser.add_argument("--release", action="store_true", help="Sign with a privately managed release key")
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--password-file", type=Path)
    parser.add_argument("--alias", default="release")
    options = parser.parse_args()
    tests()
    if options.test_only: return
    system = platform.system().lower()
    if system not in ("darwin", "linux"): raise SystemExit("Build supported on macOS and Linux (or WSL).")
    if system == "linux" and platform.machine().lower() not in ("x86_64", "amd64"):
        raise SystemExit("The pinned Linux aapt2 binary requires x86-64.")
    platform_zip = fetch("platform34.zip")
    android = CACHE / "android.jar"
    expected_android = "6cea1df3efb77103ac3e2beb9bf4718964b0e0869ab16d39d29d5cbae1c147ad"
    if not android.exists() or hashlib.sha256(android.read_bytes()).hexdigest() != expected_android:
        with zipfile.ZipFile(platform_zip) as archive:
            name = next(n for n in archive.namelist() if n.endswith("/android.jar"))
            data = archive.read(name)
        if hashlib.sha256(data).hexdigest() != expected_android: raise SystemExit("Android platform JAR checksum mismatch")
        android.write_bytes(data)
    aapt_archive = fetch("aapt2-" + system + ".jar")
    with zipfile.ZipFile(aapt_archive) as archive:
        aapt = CACHE / ("aapt2-" + system)
        aapt.write_bytes(archive.read("aapt2"))
    aapt.chmod(0o755)
    r8, apksig = fetch("r8.jar"), fetch("apksig.jar")
    classes, dex, signer_classes, generated = BUILD / "classes", BUILD / "dex", BUILD / "signer", BUILD / "generated"
    for directory in (classes, dex, signer_classes, generated):
        if directory.exists(): shutil.rmtree(directory)
        directory.mkdir(parents=True)
    resources = BUILD / "resources.zip"
    run(aapt, "compile", "--dir", ROOT / "app/src/main/res", "-o", resources)
    unsigned = BUILD / "unsigned.apk"
    run(aapt, "link", "-I", android, "--manifest", ROOT / "app/src/main/AndroidManifest.xml",
        "-A", ROOT / "app/src/main/assets", "--java", generated, "--auto-add-overlay", "-o", unsigned, resources)
    sources = sorted((ROOT / "app/src/main/java").rglob("*.java")) + sorted(generated.rglob("*.java"))
    run(java_tool("javac"), "--release", "8", "-Xlint:-options", "-cp", android, "-d", classes, *sources)
    run(java_tool("java"), "-cp", r8, "com.android.tools.r8.D8", "--min-api", "23", "--lib", android,
        "--output", dex, *sorted(classes.rglob("*.class")))
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(dex.glob("*.dex")): archive.write(path, path.name)
    if options.release:
        if not options.keystore or not options.password_file: parser.error("--release needs --keystore and --password-file")
        keystore, password_file, alias = options.keystore.resolve(), options.password_file.resolve(), options.alias
    else:
        keystore, password_file, alias = BUILD / "debug.p12", BUILD / "debug-password.txt", "debug"
        password_file.write_text("android\n")
        password_file.chmod(0o600)
        if not keystore.exists():
            run(java_tool("keytool"), "-genkeypair", "-alias", alias, "-keystore", keystore,
                "-storetype", "PKCS12", "-storepass:file", password_file, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000", "-dname", "CN=RTG2 Power Control Debug")
    run(java_tool("javac"), "-cp", apksig, "-d", signer_classes, ROOT / "tools/SignApk.java")
    output = BUILD / ("RTG2-Power-Control-1.0.0" + ("" if options.release else "-debug") + ".apk")
    run(java_tool("java"), "-cp", str(signer_classes) + os.pathsep + str(apksig), "SignApk",
        keystore, password_file, alias, unsigned, output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(".apk.sha256").write_text(digest + "  " + output.name + "\n")
    print("Built", output)
    print("SHA-256", digest)

if __name__ == "__main__": main()
