"""Staged screenshots of the Axiom editor, used to locate the tool panel."""

import argparse
import os
import shutil
import subprocess
import sys
import time
import traceback
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ingame_driver as drv


def shot(out, name):
    path = os.path.join(out, name + ".png")
    drv.shell("import", "-display", os.environ["DISPLAY"], "-window", "root", path)
    ok = os.path.isfile(path)
    drv.log("shot %s: %s %s" % (name, ok, drv.ocr_text(path) if ok else ""))
    return ok


def focus():
    ids = drv.shell("xdotool", "search", "--name", "Minecraft").stdout.split()
    if not ids:
        return False
    drv.shell("xdotool", "windowactivate", "--sync", ids[-1])
    drv.shell("xdotool", "windowfocus", "--sync", ids[-1])
    return True


def key(name):
    drv.shell("xdotool", "key", "--clearmodifiers", name)


def run_script(out, script):
    """Tiny action list: click/move/wheel/key/drag/sleep/shot."""
    for step in script.split(";"):
        parts = step.split()
        if not parts:
            continue
        verb, rest = parts[0], parts[1:]
        try:
            if verb == "click":
                x, y = int(rest[0]), int(rest[1])
                drv.shell("xdotool", "mousemove", "--sync", str(x), str(y))
                time.sleep(0.3)
                drv.shell("xdotool", "click", "1")
            elif verb == "move":
                drv.shell("xdotool", "mousemove", "--sync", rest[0], rest[1])
            elif verb == "wheel":
                drv.shell("xdotool", "mousemove", "--sync", rest[0], rest[1])
                for _ in range(int(rest[2])):
                    drv.shell("xdotool", "click", "4")
            elif verb == "key":
                drv.shell("xdotool", "key", "--clearmodifiers", rest[0])
            elif verb == "drag":
                drv.shell("xdotool", "mousemove", "--sync", rest[0], rest[1])
                drv.shell("xdotool", "mousedown", "1")
                time.sleep(0.2)
                drv.shell("xdotool", "mousemove", "--sync", rest[2], rest[3])
                time.sleep(0.2)
                drv.shell("xdotool", "mouseup", "1")
            elif verb == "sleep":
                time.sleep(float(rest[0]))
            elif verb == "shot":
                shot(out, rest[0])
            else:
                drv.log("unknown action: " + step)
        except Exception:
            drv.log("action failed (%s):\n%s" % (step, traceback.format_exc()))


def save_logs(mc_dir, out):
    src = os.path.join(mc_dir, "logs", "latest.log")
    if os.path.isfile(src):
        shutil.copy2(src, os.path.join(out, "latest.log"))
    for extra in ("logs/debug.log",):
        src = os.path.join(mc_dir, extra)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(out, os.path.basename(extra)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc-dir", default="run-mc")
    ap.add_argument("--out", default="panel")
    ap.add_argument("--mod", required=True)
    ap.add_argument("--java", required=True)
    ap.add_argument("--display", default=":99")
    ap.add_argument("--wait", type=int, default=180)
    ap.add_argument("--script", default="")
    args = ap.parse_args()

    args.mc_dir = os.path.abspath(args.mc_dir)
    args.out = os.path.abspath(args.out)
    args.mod = os.path.abspath(args.mod)
    args.java = os.path.abspath(args.java)
    os.makedirs(args.out, exist_ok=True)
    os.environ["DISPLAY"] = args.display
    os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
    os.environ.setdefault("MESA_GL_VERSION_OVERRIDE", "4.5")

    import minecraft_launcher_lib as mcl

    proc = None
    console = None
    try:
        drv.log("mod: %s (%s)" % (args.mod, os.path.isfile(args.mod)))
        version = drv.install(args.mc_dir, args.java)
        drv.log("version: " + version)
        save = drv.superflat_world(args.mc_dir, args.java, args.out)
        drv.enable_cheats(save)

        mods = os.path.join(args.mc_dir, "mods")
        os.makedirs(mods, exist_ok=True)
        for f in os.listdir(mods):
            os.remove(os.path.join(mods, f))
        shutil.copy2(args.mod, os.path.join(mods, os.path.basename(args.mod)))
        drv.http_download(drv.AXIOM_URL, os.path.join(mods, "axiom.jar"))
        drv.fabric_api_jar(mods)
        drv.write_options(args.mc_dir)

        options = {
            "username": "ArenaTest",
            "uuid": str(uuid.uuid3(uuid.NAMESPACE_DNS, "ArenaTest")),
            "token": "",
            "executablePath": args.java,
            "gameDirectory": args.mc_dir,
            "jvmArguments": ["-Xmx2G"],
        }
        cmd = mcl.command.get_minecraft_command(version, args.mc_dir, options)
        cmd += ["--quickPlaySingleplayer", drv.WORLD_NAME,
                "--width", "1280", "--height", "720"]
        console = open(os.path.join(args.out, "console.log"), "w",
                       encoding="utf-8", errors="replace")
        drv.log("launching: " + " ".join(cmd[:6]) + " ...")
        proc = subprocess.Popen(cmd, cwd=args.mc_dir, stdout=console,
                                stderr=subprocess.STDOUT)

        drv.log("waiting for the world")
        log_path = os.path.join(args.mc_dir, "logs", "latest.log")
        deadline = time.time() + args.wait
        ready = False
        while time.time() < deadline and proc.poll() is None:
            time.sleep(5)
            text = drv.tail(log_path, 400)
            if "Started serving on" in text or "Loaded" in text and "Axiom" in text:
                ready = True
                break
            if "Sound engine started" in text:
                ready = True
                break
        drv.log("ready=%s alive=%s" % (ready, proc.poll() is None))
        time.sleep(12)
        drv.log("focused: %s" % focus())
        shot(args.out, "panel-0-world")

        drv.log("opening the editor")
        key("Shift_R")
        time.sleep(8)
        shot(args.out, "panel-1-editor")

        if args.script:
            run_script(args.out, args.script)
        else:
            for n in range(1, 10):
                key(str(n))
                time.sleep(2.5)
                shot(args.out, "panel-2-slot%d" % n)

        drv.log("closing")
        key("Shift_R")
        time.sleep(2)
    except Exception:
        drv.log("driver failed:\n" + traceback.format_exc())
    finally:
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
        if console is not None:
            console.close()
        save_logs(args.mc_dir, args.out)
        drv.log("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
