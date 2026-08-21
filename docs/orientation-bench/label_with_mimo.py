#!/usr/bin/env python3
"""Label upright rotation per row using opencode-go/mimo-v2.5 vision."""
import base64
import json
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
KEY = [l.split("=", 1)[1] for l in Path("/opt/data/.env").read_text().splitlines()
       if l.startswith("OPENCODE_GO_API_KEY=")][0]
URL = "https://opencode.ai/zen/go/v1/chat/completions"
MODEL = "mimo-v2.5"

PROMPT = """这是漫画页方向标注对照表。每行左侧是文件名，右侧4列缩略图依次是原图顺时针旋转 0°/90°/180°/270° 后的样子。
对每一行判断哪一列是"正立可读"的（竖排文字从上到下、气泡文字正立、人物重心正常）。
只输出 JSON 数组，每项 {"file": "文件名", "upright_rot": 0|90|180|270}。无文字页按画面判断；确实无法判断用 null。不要输出其他内容。"""

def analyze(path: Path) -> str:
    b64 = base64.b64encode(path.read_bytes()).decode()
    body = json.dumps({
        "model": MODEL,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": PROMPT},
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
        ]}],
        "temperature": 0, "max_tokens": 4000,
    }).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {KEY}",
        "User-Agent": "hermes-agent",
    })
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                out = json.load(r)
            return out["choices"][0]["message"]["content"]
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

(HERE / "results" / "mimo_labels_raw.txt").write_text("\n".join(results))
print("saved")
