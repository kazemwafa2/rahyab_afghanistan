#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
آزمون قرارداد دانلود بسته‌های راه‌یاب — شبیه‌سازی قطعی‌های مکرر اینترنت.

سرور HTTP محلی با پشتیبانی کامل Range (مثل GitHub Releases) راه‌اندازی می‌شود و
سپس سرویس‌گیرنده‌ای با همان الگوریتم PackageDownloader.kt (درخواست Range با
byte-offset ذخیره‌شده، پذیرش ۲۰۶، ادامه از نقطه قطع، کنترل SHA-256) فایل را
می‌گیرد. سرور هر چند ثانیه اتصال را ناگهان می‌بُرد تا ادامه دانلود آزموده شود.

کاربرد:
  python3 tools/test_download_contract.py --file <zip> [--cuts 4]
خروجی موفق: «آزمون قرارداد دانلود موفق ✔»
"""
import argparse
import hashlib
import http.server
import os
import re
import socketserver
import sys
import threading
import time
import urllib.request


class RangeHandler(http.server.BaseHTTPRequestHandler):
    """سرور فایل با پشتیبانی Range — رفتار GitHub Releases را تقلید می‌کند."""
    cuts_left = 0          # چند اتصال دیگر وسط کار بُرده شود
    cut_after_bytes = 300_000

    def do_HEAD(self):
        self.do_GET(head_only=True)

    def do_GET(self, head_only=False):
        path = os.path.join(self.server.root, os.path.basename(self.path))
        if not os.path.isfile(path):
            self.send_error(404)
            return
        size = os.path.getsize(path)
        rng = self.headers.get("Range")
        start = 0
        code = 200
        if rng:
            m = re.match(r"bytes=(\d+)-", rng)
            if m:
                start = int(m.group(1))
                code = 206
        if start >= size:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.end_headers()
            return
        length = size - start
        self.send_response(code)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{size-1}/{size}")
        self.end_headers()
        if head_only:
            return
        with open(path, "rb") as f:
            f.seek(start)
            sent = 0
            while sent < length:
                chunk = f.read(64 * 1024)
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return  # سرویس‌گیرنده قطع شد
                sent += len(chunk)
                # قطع عمدی اتصال وسط دانلود (شبیه‌سازی قطعی انترنت)
                if RangeHandler.cuts_left > 0 and sent >= RangeHandler.cut_after_bytes:
                    RangeHandler.cuts_left -= 1
                    self.close_connection = True
                    try:
                        self.connection.close()
                    except Exception:
                        pass
                    return

    def log_message(self, fmt, *a):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True

    def __init__(self, addr, root):
        self.root = root
        super().__init__(addr, RangeHandler)


def client_download(url, target, expected_sha, max_attempts=40):
    """همان الگوریتم PackageDownloader.downloadWithResume: ادامه با Range، ۲۰۶، SHA-256."""
    attempt = 0
    downloaded = os.path.getsize(target) if os.path.isfile(target) else 0
    while True:
        attempt += 1
        if attempt > max_attempts:
            raise RuntimeError("تلاش‌های بیش از حد")
        req = urllib.request.Request(url)
        req.add_header("User-Agent", "RahyabAfghanistan/test (+contract-test)")
        if downloaded > 0:
            req.add_header("Range", f"bytes={downloaded}-")
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                code = resp.getcode()
                if code == 416:
                    return downloaded
                if code not in (200, 206):
                    raise RuntimeError(f"HTTP {code}")
                resume_ok = code == 206
                if not resume_ok and downloaded > 0:
                    # سرور Range نمی‌پذیرد — از نو
                    target and open(target, "wb").close()
                    downloaded = 0
                mode = "ab" if resume_ok else "wb"
                with open(target, mode) as out, resp:
                    while True:
                        b = resp.read(64 * 1024)
                        if not b:
                            break
                        out.write(b)
                        downloaded += len(b)
            if downloaded >= expected_size:
                break
            # جریان ناقص — حلقه ادامه می‌دهد (ادامه دانلود)
        except Exception as e:
            if attempt >= max_attempts:
                raise
            time.sleep(0.2)
            downloaded = os.path.getsize(target) if os.path.isfile(target) else 0
    h = hashlib.sha256()
    with open(target, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
    actual = h.hexdigest()
    if actual != expected_sha:
        raise RuntimeError(f"SHA-256 مطابقت ندارد: {actual}")
    return downloaded


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", required=True)
    ap.add_argument("--cuts", type=int, default=4, help="تعداد قطع‌های عمدی اتصال")
    args = ap.parse_args()

    src = os.path.abspath(args.file)
    if not os.path.isfile(src):
        print(f"فایل نیست: {src}")
        return 1
    h = hashlib.sha256()
    with open(src, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
    expected_sha = h.hexdigest()
    global expected_size
    expected_size = os.path.getsize(src)

    tmpdir = os.path.join("/tmp", "rahyab_dl_test")
    os.makedirs(tmpdir, exist_ok=True)
    target = os.path.join(tmpdir, os.path.basename(src))
    if os.path.isfile(target):
        os.remove(target)

    RangeHandler.cuts_left = args.cuts
    srv = Server(("127.0.0.1", 0), os.path.dirname(src))
    port = srv.server_address[1]
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()

    url = f"http://127.0.0.1:{port}/{os.path.basename(src)}"
    t0 = time.time()
    try:
        n = client_download(url, target, expected_sha)
        dt = time.time() - t0
        print(f"آزمون قرارداد دانلود موفق ✔  فایل: {os.path.basename(src)} — "
              f"{n/1e6:.1f}MB با {args.cuts} قطع عمدی اتصال، SHA-256 تأیید شد ({dt:.1f}s)")
        os.remove(target)
        return 0
    except Exception as e:
        print(f"آزمون ناموفق: {e}")
        return 1
    finally:
        srv.shutdown()


expected_size = 0

if __name__ == "__main__":
    sys.exit(main())
