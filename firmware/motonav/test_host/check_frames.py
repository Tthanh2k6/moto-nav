#!/usr/bin/env python3
"""Đối chiếu bộ giải mã C++ với android/testdata/golden_frames.json (20 khung mẫu do bản Kotlin sinh ra).

Chạy: python -X utf8 firmware/motonav/test_host/check_frames.py <host_test.exe>
Mỗi khung: giải mã bằng bản C++ rồi so từng trường với thông điệp trong JSON; INFO và SYNC_REQUEST còn được mã hoá
lại bằng bộ mã hoá C++ và phải ra đúng từng byte của khung gốc.
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GOLDEN = os.path.join(HERE, "..", "..", "..", "android", "testdata", "golden_frames.json")


def normalize(m):
    m = dict(m)
    if m["type"] == "Text":
        m["utf8hex"] = m.pop("text").encode("utf-8").hex().upper()
    return m


def main():
    exe = sys.argv[1]
    data = json.load(open(GOLDEN, encoding="utf-8"))
    frames = data["frames"]
    stdin = "\n".join(f["hex"] for f in frames) + "\n"
    out = subprocess.run([exe, "decode"], input=stdin, capture_output=True, text=True, encoding="utf-8").stdout.splitlines()
    crc_line = out.pop()
    ok = 0
    for f, line in zip(frames, out):
        got_json, _, reenc = line.partition("\treencoded=")
        got = json.loads(got_json)
        want = normalize(f["message"])
        same = got == want
        if reenc:
            same = same and reenc.strip() == f["hex"].upper()
        print(f"{f['name']:24} {'KHỚP' if same else 'LỆCH'}" + ("  (mã hoá lại khớp từng byte)" if reenc and same else ""))
        if not same:
            print("   C++   :", got_json[:300])
            print("   Kotlin:", json.dumps(want)[:300])
        ok += same
    crc_ok = crc_line.endswith(data["crc_check"]["crc"].upper())
    print(f"{crc_line}  {'KHỚP' if crc_ok else 'LỆCH'}")
    print(f"TỔNG: {ok}/{len(frames)} khung khớp")
    sys.exit(0 if ok == len(frames) and len(out) == len(frames) and crc_ok else 1)


if __name__ == "__main__":
    main()
