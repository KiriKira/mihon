"""Exercise the actual JVM CLI, including binary PGM header/raster boundaries."""

import argparse
import csv
import io
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    # Resolve before changing the subprocess cwd; never interpret a shell command supplied by the user.
    classpath = args.classpath
    command = [args.java, "-cp", classpath, "tachiyomi.core.common.util.system.SpreadProbeCliKt"]
    checked = 0
    with tempfile.TemporaryDirectory(prefix="spread-probe-", dir=Path.cwd()) as directory:
        path = Path(directory) / 'fixture,"quoted.pgm'

        def run(data, expected_status=None, expected_shift=None):
            nonlocal checked
            path.write_bytes(data)
            process = subprocess.run(command + [str(path), "44", "52"], capture_output=True, text=True)
            checked += 1
            if expected_status is None:
                assert process.returncode != 0, "invalid input was accepted"
                return
            assert process.returncode == 0, process.stderr
            rows = list(csv.DictReader(io.StringIO(process.stdout)))
            assert len(rows) == 1
            row = rows[0]
            assert row["sample"] == path.name, "CSV filename escaping changed"
            assert row["status"] == expected_status, row
            assert row["best_shift"] == ("" if expected_shift is None else str(expected_shift)), row
            if expected_shift is not None:
                assert abs(float(row["texture_correlation"]) - 1.0) < 1e-10, row
            return row

        width, height = 96, 128
        for separator in [b"\n", b"\r\n", b"\r"]:
            for first in [0, 10, 13, 32, 35, 255]:
                pixels = bytes([first]) + bytes([200]) * (width * height - 1)
                run(b"P5\n# header comment\n96 128\n255" + separator + pixels, "LOW_TEXTURE")

        pixels = bytearray()
        for y in range(height):
            for x in range(width):
                if 44 <= x < 52:
                    pixels.append(220)
                else:
                    n = (y - (3 if x >= 52 else 0)) % 997
                    amplitude = 4 + (n * n * 17 + n * 31 + 11) % 25
                    pixels.append(100 + (amplitude if x % 2 == 0 else -amplitude))
        measured = run(b"P5\n96 128\n255\n" + pixels, "MEASURED", 3)

        for invalid in [
            b"P2\n96 128\n255\n",
            b"P5\n513 128\n255\n",
            b"P5\n96 128\n65535\n",
            b"P5\n96 128\n255\n" + pixels[:-1],
            b"P5\n96 128\n255\n" + pixels + b"extra",
            b"P5\n0 128\n255\n",
            b"P5\n96 128\n255\n" + bytes(512 * 512 + 65536),
        ]:
            run(invalid)
        print(f"PASS: {checked} CLI cases (PGM boundaries, CSV escaping, known shift, invalid inputs)")
        print("Measured synthetic fixture:", measured)
        print("No real-image accuracy, Android decoder parity or on-device performance was measured.")


if __name__ == "__main__":
    main()
