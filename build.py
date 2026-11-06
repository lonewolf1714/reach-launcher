"""Builds Reach Launcher without Gradle: aapt2 -> javac -> d8 -> zip -> zipalign -> apksigner.

Same toolchain as ../WidgetStudio/build.py. Needs the Android SDK at %LOCALAPPDATA%\\Android\\Sdk
(build-tools 35.0.1, platforms/android-35) and a JDK (javac, keytool) on PATH.

Usage: python -I build.py            build ReachLauncher.apk
       python -I build.py --install  build, then `adb install -r` to the connected device
"""
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk")
BT = os.path.join(SDK, "build-tools", "35.0.1")
JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")
OUT = os.path.join(ROOT, "build")
ADB = os.path.join(os.path.dirname(ROOT), "adb.exe")


def tool(name):
    for ext in (".exe", ".bat", ""):
        p = os.path.join(BT, name + ext)
        if os.path.exists(p):
            return p
    raise SystemExit("missing build tool " + name)


def run(cmd, **kw):
    print(">", " ".join(os.path.basename(c) if i == 0 else c for i, c in enumerate(cmd))[:200])
    r = subprocess.run(cmd, **kw)
    if r.returncode != 0:
        raise SystemExit("FAILED: %s" % cmd[0])


WS = os.path.join(os.path.dirname(ROOT), "WidgetStudio")
# Last Widget Studio version that built cleanly; used when the live folder is mid-edit and doesn't compile.
SNAP = os.path.join(ROOT, ".widgetstudio_snapshot")


def merge_widget_studio(ws_dir):
    """Builds Widget Studio's widgets into Reach: merged res dir, merged manifest, its source dir.

    Reach's resources win name clashes (launcher icon, ic_fg); Widget Studio's fonts are the same files
    Reach already ships. Widget Studio components keep their com.widgetstudio class names.
    Returns (res_dir, manifest_path, ws_src_or_None).
    """
    reach_res = os.path.join(ROOT, "res")
    reach_manifest = os.path.join(ROOT, "AndroidManifest.xml")
    WS = ws_dir
    if not WS or not os.path.isdir(os.path.join(WS, "src")):
        print("Widget Studio not found next to ReachLauncher; building Reach alone")
        return reach_res, reach_manifest, None

    merged = os.path.join(OUT, "merged_res")
    shutil.copytree(reach_res, merged)
    ws_res = os.path.join(WS, "res")
    for dp, _, fs in os.walk(ws_res):
        rel = os.path.relpath(dp, ws_res)
        top = rel.split(os.sep)[0]
        if top == "font":
            continue  # identical font files already in Reach
        for f in fs:
            src = os.path.join(dp, f)
            dst_dir = os.path.join(merged, rel)
            name = f
            if top == "values":
                name = "ws_" + f  # don't overwrite Reach's values/*.xml files
            dst = os.path.join(dst_dir, name)
            if os.path.exists(dst):
                continue  # Reach's own resource of the same name wins
            os.makedirs(dst_dir, exist_ok=True)
            if rel == "xml" and f == "shortcuts.xml":
                with open(src, encoding="utf-8") as fh:
                    txt = fh.read().replace('android:targetPackage="com.widgetstudio"',
                                            'android:targetPackage="com.reach.launcher"')
                with open(dst, "w", encoding="utf-8") as fh:
                    fh.write(txt)
            else:
                shutil.copy2(src, dst)

    with open(os.path.join(WS, "AndroidManifest.xml"), encoding="utf-8") as fh:
        ws = fh.read()
    with open(reach_manifest, encoding="utf-8") as fh:
        reach = fh.read()
    import re
    perms = [p for p in re.findall(r'<uses-permission[^>]*/>', ws) if p not in reach]
    app = re.search(r'<application[^>]*>(.*)</application>', ws, re.S).group(1)
    app = re.sub(r'android:name="\.', 'android:name="com.widgetstudio.', app)
    # Widget Studio screens keep their own look (Reach's app theme is the see-through home theme)
    app = re.sub(r'<activity(?![^>]*android:theme)', '<activity android:theme="@style/AppTheme"', app)
    app = app.replace('android:name="com.widgetstudio.MainActivity"',
                      'android:name="com.widgetstudio.MainActivity" android:label="Widget Studio"')
    reach = reach.replace("<queries>", "\n".join("    " + p for p in perms) + "\n\n    <queries>", 1)
    reach = reach.replace("</application>", "\n        <!-- Widget Studio (built in) -->" + app + "</application>")
    manifest = os.path.join(OUT, "AndroidManifest.xml")
    with open(manifest, "w", encoding="utf-8") as fh:
        fh.write(reach)
    return merged, manifest, os.path.join(WS, "src")


# Widget Studio ships as its own app (com.widgetstudio). Building it into Reach too made every widget
# appear twice in the widget picker, so Reach is built alone. Set this to True to bundle it again.
BUNDLE_WIDGET_STUDIO = False


def main():
    if not BUNDLE_WIDGET_STUDIO:
        build("")  # no Widget Studio dir -> Reach alone
    else:
        try:
            build(WS)
            snapshot()
        except SystemExit as e:
            if not os.path.isdir(os.path.join(SNAP, "src")):
                raise
            print("!! Widget Studio's current code doesn't build (%s); using the last good snapshot instead" % e)
            build(SNAP)
    if "--install" in sys.argv:
        run([ADB if os.path.exists(ADB) else "adb", "install", "-r", os.path.join(ROOT, "ReachLauncher.apk")])


def snapshot():
    """Keeps a copy of the Widget Studio sources that just built, for the fallback above."""
    if not os.path.isdir(os.path.join(WS, "src")):
        return
    tmp = SNAP + ".tmp"
    if os.path.isdir(tmp):
        shutil.rmtree(tmp)
    os.makedirs(tmp)
    shutil.copytree(os.path.join(WS, "src"), os.path.join(tmp, "src"))
    shutil.copytree(os.path.join(WS, "res"), os.path.join(tmp, "res"))
    shutil.copy2(os.path.join(WS, "AndroidManifest.xml"), os.path.join(tmp, "AndroidManifest.xml"))
    if os.path.isdir(SNAP):
        shutil.rmtree(SNAP)
    os.rename(tmp, SNAP)


def build(ws_dir):
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    for d in ("gen", "classes", "dex"):
        os.makedirs(os.path.join(OUT, d))

    res_dir, manifest, ws_src = merge_widget_studio(ws_dir)
    res_zip = os.path.join(OUT, "res.zip")
    run([tool("aapt2"), "compile", "--dir", res_dir, "-o", res_zip])
    unsigned = os.path.join(OUT, "unsigned.apk")
    link = [tool("aapt2"), "link", "-o", unsigned, "-I", JAR,
            "--manifest", manifest,
            "--java", os.path.join(OUT, "gen"), "--min-sdk-version", "26", "--target-sdk-version", "35",
            "--version-code", "1", "--version-name", "1.0", "--auto-add-overlay"]
    if ws_src:
        link += ["--extra-packages", "com.widgetstudio"]  # Widget Studio code keeps using com.widgetstudio.R
    run(link + [res_zip])

    srcs = []
    for base in [os.path.join(ROOT, "src"), os.path.join(OUT, "gen")] + ([ws_src] if ws_src else []):
        for dp, _, fs in os.walk(base):
            srcs += [os.path.join(dp, f) for f in fs if f.endswith(".java")]
    run(["javac", "-encoding", "UTF-8", "-source", "11", "-target", "11", "-nowarn", "-Xlint:-options",
         "-classpath", JAR, "-d", os.path.join(OUT, "classes")] + srcs)

    classes_jar = os.path.join(OUT, "classes.jar")
    cdir = os.path.join(OUT, "classes")
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for dp, _, fs in os.walk(cdir):
            for f in fs:
                if f.endswith(".class"):
                    full = os.path.join(dp, f)
                    z.write(full, os.path.relpath(full, cdir).replace(os.sep, "/"))
    run([tool("d8"), "--release", "--lib", JAR, "--min-api", "26", "--output", os.path.join(OUT, "dex"), classes_jar])

    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(OUT, "dex", "classes.dex"), "classes.dex")

    aligned = os.path.join(OUT, "aligned.apk")
    run([tool("zipalign"), "-f", "-p", "4", unsigned, aligned])

    ks = os.path.join(ROOT, "debug.keystore")
    if not os.path.exists(ks):
        run(["keytool", "-genkeypair", "-keystore", ks, "-storepass", "android", "-keypass", "android",
             "-alias", "debug", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=Reach Launcher Debug"])
    final = os.path.join(ROOT, "ReachLauncher.apk")
    run([tool("apksigner"), "sign", "--ks", ks, "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--out", final, aligned])
    print("BUILT", final, os.path.getsize(final), "bytes")


if __name__ == "__main__":
    main()
