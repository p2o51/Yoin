#!/usr/bin/env python3
"""Fake Google Drive v3 appDataFolder for Yoin cloud-sync QA (no Google, no network).

Implements exactly what the shared DriveAppDataClient calls:
  GET    /drive/v3/about?fields=user(...)
  GET    /drive/v3/files?spaces=appDataFolder&pageSize&fields&pageToken
  GET    /drive/v3/files/generateIds?space=appDataFolder&count=N
  GET    /drive/v3/files/<id>?alt=media
  DELETE /drive/v3/files/<id>
  POST   /upload/drive/v3/files?uploadType=multipart   (multipart/related: JSON metadata + media)
  PATCH  /upload/drive/v3/files/<id>?uploadType=media

  python3 fake_drive.py [--port 8765] [--seed sync-golden.json] [--state DIR] [--token x]

--seed loads Android's files from iosApp/YoinTests/Fixtures/sync-golden.json (gzipping each file's JSON,
appProperties as Android's CloudSyncManager sets them), so a client sees what a Pixel uploaded.
--state keeps the folder on disk (files/<id>.bin + index.json) and logs every write to writes.log.
"""
import argparse, gzip, json, os, re, sys, threading, time, uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LOCK = threading.Lock()
FILES = {}          # id -> dict(id, name, version, modifiedTime, appProperties, mimeType, bytes)
VERSION = [100]
ARGS = None


def now_rfc3339():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def meta(f):
    return {"id": f["id"], "name": f["name"], "version": str(f["version"]), "modifiedTime": f["modifiedTime"],
            "size": str(len(f["bytes"])), "appProperties": f["appProperties"]}


def save():
    if not ARGS.state:
        return
    os.makedirs(os.path.join(ARGS.state, "files"), exist_ok=True)
    index = {}
    for f in FILES.values():
        with open(os.path.join(ARGS.state, "files", f["id"] + ".bin"), "wb") as out:
            out.write(f["bytes"])
        index[f["id"]] = {k: v for k, v in f.items() if k != "bytes"}
    with open(os.path.join(ARGS.state, "index.json"), "w") as out:
        json.dump({"version": VERSION[0], "files": index}, out, indent=1, sort_keys=True)


def log_write(what):
    line = "%s %s" % (now_rfc3339(), what)
    print(line, flush=True)
    if ARGS.state:
        with open(os.path.join(ARGS.state, "writes.log"), "a") as out:
            out.write(line + "\n")


def load_state():
    path = os.path.join(ARGS.state or "", "index.json")
    if not ARGS.state or not os.path.exists(path):
        return False
    data = json.load(open(path))
    VERSION[0] = data["version"]
    for fid, f in data["files"].items():
        f["bytes"] = open(os.path.join(ARGS.state, "files", fid + ".bin"), "rb").read()
        FILES[fid] = f
    return True


def seed(path):
    golden = json.load(open(path))
    device = golden["deviceId"]
    for f in golden["files"]:
        VERSION[0] += 1
        fid = "android-" + f["fileKind"] + "-" + str(f["shard"])
        FILES[fid] = dict(id=fid, name=f["name"], version=VERSION[0], modifiedTime=now_rfc3339(),
                          mimeType="application/gzip",
                          appProperties={"yoinKind": f["fileKind"], "deviceId": device, "shard": str(f["shard"]),
                                         "schema": "1"},
                          bytes=gzip.compress(f["json"].encode("utf-8"), mtime=0))
    print("seeded %d files from %s (Android device %s, scope %s)" % (len(golden["files"]), path, device,
                                                                     golden["scope"]), flush=True)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        sys.stderr.write("%s %s\n" % (self.command, self.path))

    def reply(self, code, body=b"", ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def error(self, code, reason):
        self.reply(code, {"error": {"code": code, "message": reason, "errors": [{"reason": reason}]}})

    def authorized(self):
        if self.headers.get("Authorization") != "Bearer " + ARGS.token:
            self.error(401, "authError")
            return False
        return True

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def do_GET(self):
        if not self.authorized():
            return
        u = urlparse(self.path)
        q = parse_qs(u.query)
        with LOCK:
            if u.path == "/drive/v3/about":
                return self.reply(200, {"user": {"permissionId": ARGS.permission, "emailAddress": ARGS.email,
                                                 "displayName": "QA"}})
            if u.path == "/drive/v3/files":
                if q.get("spaces", [""])[0] != "appDataFolder":
                    return self.error(400, "invalidSpaces")
                return self.reply(200, {"files": [meta(f) for f in FILES.values()]})
            if u.path == "/drive/v3/files/generateIds":
                count = int(q.get("count", ["1"])[0])
                return self.reply(200, {"ids": [uuid.uuid4().hex[:28] for _ in range(count)], "space": "appDataFolder"})
            m = re.fullmatch(r"/drive/v3/files/([^/]+)", u.path)
            if m and q.get("alt", [""])[0] == "media":
                f = FILES.get(m.group(1))
                return self.reply(200, f["bytes"], f.get("mimeType", "application/octet-stream")) if f else self.error(404, "notFound")
        self.error(404, "notFound")

    def do_DELETE(self):
        if not self.authorized():
            return
        m = re.fullmatch(r"/drive/v3/files/([^/]+)", urlparse(self.path).path)
        with LOCK:
            if not m or m.group(1) not in FILES:
                return self.error(404, "notFound")
            f = FILES.pop(m.group(1))
            log_write("DELETE %s %s" % (f["id"], f["name"]))
            save()
        self.reply(204)

    def do_POST(self):
        if not self.authorized():
            return
        u = urlparse(self.path)
        data = self.body()
        if u.path != "/upload/drive/v3/files" or parse_qs(u.query).get("uploadType") != ["multipart"]:
            return self.error(404, "notFound")
        boundary = re.search(r"boundary=([^;]+)", self.headers.get("Content-Type", "")).group(1).encode()
        parts = [p for p in data.split(b"--" + boundary) if p.strip(b"\r\n") and p.strip() != b"--"]
        head, metadata = parts[0].split(b"\r\n\r\n", 1)
        info = json.loads(metadata.strip())
        mhead, media = parts[1].split(b"\r\n\r\n", 1)
        media = media[:-2] if media.endswith(b"\r\n") else media
        with LOCK:
            if info["id"] in FILES:
                return self.error(409, "duplicate")
            if info.get("parents") != ["appDataFolder"]:
                return self.error(400, "badParents")
            VERSION[0] += 1
            f = dict(id=info["id"], name=info["name"], version=VERSION[0], modifiedTime=now_rfc3339(),
                     mimeType=info.get("mimeType"), appProperties=info.get("appProperties", {}), bytes=media)
            FILES[f["id"]] = f
            log_write("CREATE %s %s %s %d bytes" % (f["id"], f["name"], json.dumps(f["appProperties"]), len(media)))
            save()
            self.reply(200, meta(f))

    def do_PATCH(self):
        if not self.authorized():
            return
        u = urlparse(self.path)
        data = self.body()
        m = re.fullmatch(r"/upload/drive/v3/files/([^/]+)", u.path)
        with LOCK:
            if not m or m.group(1) not in FILES:
                return self.error(404, "notFound")
            f = FILES[m.group(1)]
            VERSION[0] += 1
            f.update(bytes=data, version=VERSION[0], modifiedTime=now_rfc3339())
            log_write("UPDATE %s %s %d bytes" % (f["id"], f["name"], len(data)))
            save()
            self.reply(200, meta(f))


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--seed")
    p.add_argument("--state")
    p.add_argument("--token", default="x")
    p.add_argument("--email", default="qa@example.com")
    p.add_argument("--permission", default="qa-permission")
    ARGS = p.parse_args()
    if not load_state() and ARGS.seed:
        seed(ARGS.seed)
        save()
    print("fake Drive on 127.0.0.1:%d (%d files)" % (ARGS.port, len(FILES)), flush=True)
    ThreadingHTTPServer(("127.0.0.1", ARGS.port), H).serve_forever()
