#!/usr/bin/env python3
"""Check that network ADB is closed on the TV.

  tools/adb-closed.py [ip] [port]

Only "connection refused" proves the port is closed; a timeout or
"no route to host" may just mean the box is off or unreachable.
"""
import errno
import socket
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

host = sys.argv[1] if len(sys.argv) > 1 else tv.HOST
if not host:
    sys.exit("usage: tools/adb-closed.py <tv ip>   (or set tv_host in local.json)")
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5555

s = socket.socket()
s.settimeout(4)
try:
    s.connect((host, port))
    print(f"OPEN: {host}:{port} accepts connections. Turn USB debugging off and reboot the TV.")
    sys.exit(1)
except ConnectionRefusedError:
    print(f"CLOSED (connection refused): {host}:{port}")
except socket.timeout:
    print(f"NO ANSWER: {host} does not reply. Is it on and on the network? This does not prove the port is closed.")
    sys.exit(2)
except OSError as e:
    if e.errno in (errno.ECONNREFUSED, 10061):
        print(f"CLOSED (connection refused): {host}:{port}")
    else:
        print(f"INCONCLUSIVE: {e}")
        sys.exit(2)
finally:
    s.close()
