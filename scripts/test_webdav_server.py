#!/usr/bin/env python3
"""Isolated WebDAV server used by Android connected tests (v4 journal sync).

Serves an in-memory repository under a single remote path with the minimal
subset the clients use: MKCOL, PUT, GET, PROPFIND (Depth 0/1) and OPTIONS.
Credentials are fixed to notebook/test-app-password.
"""
import argparse
import base64
import json
import os
import posixpath
import re
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UUID_PATTERN = re.compile(r"^[0-9a-f-]{36}$", re.I)

DAV_NS = "{DAV:}"
PROPFIND_BODY = b'<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:allprop/></d:propfind>'


def multistatus(hrefs):
    responses = "\n".join(
        "  <d:response><d:href>{}</d:href><d:propstat><d:prop><d:resourcetype/></d:prop>"
        "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>".format(href)
        for href in hrefs
    )
    return '<?xml version="1.0" encoding="utf-8"?>\n<d:multistatus xmlns:d="DAV:">\n{}\n</d:multistatus>'.format(responses).encode()


def make_handler(files, dirs, authorized, state_lock, requests, faults):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _authorized(self):
            if not authorized(self.headers.get("Authorization", "")):
                self.send_response(401)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return False
            return True

        def _path(self):
            return urllib.parse.unquote(urllib.parse.urlparse(self.path).path).removeprefix("/dav/")

        def _record_or_fail(self, method, path):
            with state_lock:
                requests.append({"method": method, "path": path})
                for fault in faults:
                    if fault["remaining"] > 0 and fault["method"] == method and path.endswith(fault["suffix"]):
                        fault["remaining"] -= 1
                        self._send(fault["code"])
                        return True
            return False

        def _send(self, code, body=b"", content_type="application/octet-stream"):
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_OPTIONS(self):
            if not self._authorized():
                return
            self.send_response(200)
            self.send_header("DAV", "1")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_MKCOL(self):
            if not self._authorized():
                return
            path = self._path()
            if self._record_or_fail("MKCOL", path):
                return
            if path in dirs or path in files or any(item.startswith(path + "/") for item in dirs | files.keys()):
                self._send(405)
                return
            dirs.add(path)
            self._send(201)

        def do_PUT(self):
            if not self._authorized():
                return
            length = int(self.headers.get("Content-Length", "0"))
            path = self._path()
            data = self.rfile.read(length)
            if self._record_or_fail("PUT", path):
                return
            with state_lock:
                files[path] = data
            self._send(201)

        def do_GET(self):
            if not self._authorized():
                return
            parsed_path = urllib.parse.urlparse(self.path).path
            if parsed_path == "/__test__/snapshot":
                with state_lock:
                    body = json.dumps({
                        "requests": list(requests),
                        "files": {path: base64.b64encode(data).decode("ascii") for path, data in files.items()},
                    }, sort_keys=True).encode()
                self._send(200, body, "application/json")
                return
            path = self._path()
            if self._record_or_fail("GET", path):
                return
            with state_lock:
                data = files.get(path)
            if data is None:
                self._send(404)
                return
            self._send(200, data)

        def do_POST(self):
            if not self._authorized():
                return
            path = urllib.parse.urlparse(self.path).path
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length) or b"{}")
            with state_lock:
                if path == "/__test__/reset-stats":
                    requests.clear()
                    if payload.get("clearFaults"):
                        faults.clear()
                elif path == "/__test__/fail":
                    faults.append({
                        "method": str(payload["method"]).upper(),
                        "suffix": str(payload["suffix"]),
                        "remaining": int(payload.get("count", 1)),
                        "code": int(payload.get("code", 503)),
                    })
                elif path == "/__test__/corrupt":
                    target = str(payload["path"])
                    if target not in files:
                        self._send(404)
                        return
                    files[target] = base64.b64decode(payload["dataBase64"])
                else:
                    self._send(404)
                    return
            self._send(200, b"{}", "application/json")

        def do_PROPFIND(self):
            if not self._authorized():
                return
            path = self._path()
            if self._record_or_fail("PROPFIND", path):
                return
            depth = self.headers.get("Depth", "1")
            if not (path in dirs or path in files or any(item.startswith(path + "/") for item in dirs | files.keys())):
                self._send(404)
                return
            children = []
            if depth == "0":
                if path in dirs or path in files:
                    children.append(path)
            else:
                for item in dirs | files.keys():
                    rest = item.removeprefix(path + "/")
                    if item != path and rest and "/" not in rest:
                        children.append(item if item in files else item + "/")
            self._send(207, multistatus(children), "application/xml")

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=2223)
    parser.add_argument("--require-auth", action="store_true", help="Require Basic notebook:test-app-password")
    args = parser.parse_args()

    def authorized(header):
        if not args.require_auth:
            return True
        import base64
        expected = "Basic " + base64.b64encode(b"notebook:test-app-password").decode()
        return header == expected

    files, dirs = {}, set()
    state_lock = threading.RLock()
    requests, faults = [], []
    server = ThreadingHTTPServer((args.host, args.port), make_handler(files, dirs, authorized, state_lock, requests, faults))
    print("WebDAV fixture listening on %s:%d" % (args.host, args.port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
