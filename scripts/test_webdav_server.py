#!/usr/bin/env python3
"""Isolated WebDAV server used by Android connected tests (v4 journal sync).

Serves an in-memory repository under a single remote path with the minimal
subset the clients use: MKCOL, PUT, GET, PROPFIND (Depth 0/1) and OPTIONS.
Credentials are fixed to notebook/test-app-password.
"""
import argparse
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


def make_handler(files, dirs, authorized):
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
            if path in dirs or path in files or any(item.startswith(path + "/") for item in dirs | files.keys()):
                self._send(405)
                return
            dirs.add(path)
            self._send(201)

        def do_PUT(self):
            if not self._authorized():
                return
            length = int(self.headers.get("Content-Length", "0"))
            files[self._path()] = self.rfile.read(length)
            self._send(201)

        def do_GET(self):
            if not self._authorized():
                return
            data = files.get(self._path())
            if data is None:
                self._send(404)
                return
            self._send(200, data)

        def do_PROPFIND(self):
            if not self._authorized():
                return
            path = self._path()
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
    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(files, dirs, authorized))
    print("WebDAV fixture listening on 0.0.0.0:%d" % args.port, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
