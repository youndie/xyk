#!/usr/bin/env python3
"""A subscriber with a known, fixed latency — the instrument B-14 measures against.

`SINK_DELAY_MS` is the whole point. The question is whether delivery throughput scales with the
number of workers or is capped by the curl engine's single-threaded dispatcher, and those two
predict the same thing against an instant subscriber and different things against a slow one. So the
delay is a parameter and the fast arm is the control.

It counts rather than logs: a log line per delivery would make the sink the bottleneck at the rates
this is looking for.
"""
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from time import sleep

DELAY = float(os.environ.get("SINK_DELAY_MS", "0")) / 1000.0
count = 0
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        global count
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        if DELAY:
            sleep(DELAY)
        with lock:
            count += 1
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def do_GET(self):
        # The only way the harness learns how many arrived. Cheap, and outside the measured path.
        with lock:
            body = str(count).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


# The port is a parameter because two harnesses run this on one host: a delivery sweep and a
# soak. Sharing a port would make one of them read the other's counter and call it a result.
PORT = int(os.environ.get("SINK_PORT", "9100"))
ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
