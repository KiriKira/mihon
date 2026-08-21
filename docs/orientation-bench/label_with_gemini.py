#!/usr/bin/env python3
"""Label landscape samples' true upright rotation using Gemini vision."""
import base64
import json
import os
import sys
from pathlib import Path

import urllib.request

HERE = Path(__file__).resolve().parent
KEY = [l.split("=", 1)[1] for l in Path("/opt/data/.env").read_text().splitlines()
       if l.startswith("GEMINI_API_KEY=")][0]
URL = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key={KEY}"

PROMPT = """这是漫画页方向标注对照表。每行左侧是文件名，右侧4列缩略图依次是原图顺时针旋转 0°/90°/180°/270° 后的样子。
对每一行判断哪一列是"正立可读"的（竖排文字从上到下、气泡文字正立、人物重心正常）。
只输出 JSON 数组，每项 {"file": "文件名", "upright_rot": 0|90|180|270}。无文字页按画面判断；确实无法判断用 null。"""

def analyze(path: Path) -> str:
    b64 = base64.b64encode(path.read_bytes()).decode()
    body = json.dumps({
        "contents": [{"parts": [
            {"text": PROMPT},
            {"inline_data": {"mime_type": "image/jpeg", "data": b64}},
        ]}],
        "generationConfig": {"temperature": 0},
    }).encode()
    req = urllib.request.Request(URL, data=body, headers={"Content-Type": "application/json"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                out = json.load(r)
            return out["candidates"][0]["content"]["parts"][0]["text"]
        except Exception as e:
            print(f"{path.name}: attempt {attempt}: {e}", file=sys.stderr)
    return "ERROR"

results = []
for i in range(6):
    p = HERE / "results" / f"sheet_{i}.jpg"
    text = analyze(p)
    print(f"--- sheet_{i} ---")
    print(text)
    results.append(text)

(HERE / "results" / "gemini_labels_raw.txt").write_text("\n".join(results))
print("saved raw")
