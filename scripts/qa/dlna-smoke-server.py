#!/usr/bin/env python3
"""A credential-free HTTP/range audio fixture for an actual DLNA renderer.

Run with --bind 0.0.0.0 only on the test LAN, then pass the computer's LAN
address to debug DlnaProbeActivity. This tests renderer/session transport;
it does not validate a user's Subsonic account, server, or audio collection.
"""

import argparse
import hashlib
import io
import json
import math
import struct
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


def sample(seconds, frequency):
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(2)
        audio.setsampwidth(2)
        audio.setframerate(44100)
        frames = bytearray()
        for index in range(seconds * 44100):
            # Quiet, short fades avoid clicks without changing speaker volume.
            edge = min(1.0, index / 4410, (seconds * 44100 - index) / 4410)
            value = int(1800 * edge * math.sin(2 * math.pi * frequency * index / 44100))
            frames.extend(struct.pack("<hh", value, value))
        audio.writeframes(frames)
    return output.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18765)
    parser.add_argument("--seconds", type=int, default=12)
    parser.add_argument("--require-auth", action="store_true", help="Verify the fixture/fixture Subsonic token on every request")
    options = parser.parse_args()
    if not 3 <= options.seconds <= 120:
        parser.error("--seconds must be 3 through 120")
    samples = {"a": sample(options.seconds, 440), "b": sample(options.seconds, 660)}

    class Handler(BaseHTTPRequestHandler):
        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, write_body):
            url = urlsplit(self.path)
            query = parse_qs(url.query)
            if options.require_auth:
                salt = query.get("s", [""])[0]
                expected = hashlib.md5(("fixture" + salt).encode()).hexdigest()
                if not salt or query.get("u") != ["fixture"] or query.get("t") != [expected]:
                    self.send_error(401)
                    return
            identifier = query.get("id", ["a"])[0]
            if url.path.startswith("/rest/") and url.path not in ("/rest/stream", "/rest/stream.view"):
                endpoint = url.path.rsplit("/", 1)[-1].removesuffix(".view")
                songs = [{"id": key, "title": f"DLNA smoke {key}", "artist": "Yoin hardware validation",
                          "album": "DLNA fixture", "albumId": "fixture", "duration": options.seconds,
                          "contentType": "audio/wav", "suffix": "wav", "size": len(samples[key]),
                          "bitRate": 1411, "track": number} for number, key in enumerate(samples, 1)]
                album = {"id": "fixture", "name": "DLNA fixture", "songCount": 2,
                         "artist": "Yoin hardware validation", "duration": options.seconds * 2,
                         "created": "2026-10-20T00:00:00Z", "song": songs}
                replies = {
                    "ping": {}, "getArtists": {"artists": {"index": []}},
                    "getAlbumList2": {"albumList2": {"album": [album]}},
                    "getAlbum": {"album": album},
                    "getStarred2": {"starred2": {"song": [], "album": [], "artist": []}},
                    "getRandomSongs": {"randomSongs": {"song": songs}},
                    "getPlaylists": {"playlists": {"playlist": []}},
                    "getLyricsBySongId": {"lyricsList": {"structuredLyrics": []}},
                    "scrobble": {},
                }
                fields = replies.get(endpoint)
                if fields is None or endpoint == "getAlbum" and identifier != "fixture":
                    reply = {"status": "failed", "version": "1.16.1",
                             "error": {"code": 70, "message": "Fixture data not found"}}
                else:
                    reply = {"status": "ok", "version": "1.16.1", **fields}
                encoded = json.dumps({"subsonic-response": reply}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                if write_body:
                    self.wfile.write(encoded)
                print(f"api endpoint={url.path.rsplit('/', 1)[-1]}", flush=True)
                return
            if url.path not in ("/rest/stream", "/rest/stream.view") or identifier not in samples:
                self.send_error(404)
                return
            body = samples[identifier]
            start, end, partial = 0, len(body) - 1, False
            requested = self.headers.get("Range", "")
            if requested:
                try:
                    unit, bounds = requested.split("=", 1)
                    first, last = bounds.split("-", 1)
                    if unit != "bytes" or "," in bounds:
                        raise ValueError()
                    if first:
                        start = int(first)
                        end = min(int(last), end) if last else end
                    else:
                        start = max(0, len(body) - int(last))
                    if not 0 <= start <= end < len(body):
                        raise ValueError()
                    partial = True
                except ValueError:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{len(body)}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
            self.send_response(206 if partial else 200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(body)}")
            self.end_headers()
            print(f"request method={self.command} item={identifier} range={start}-{end}", flush=True)
            if write_body:
                try:
                    self.wfile.write(body[start:end + 1])
                except (BrokenPipeError, ConnectionResetError):
                    pass

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer((options.bind, options.port), Handler)
    print(f"ready port={options.port} seconds={options.seconds}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
