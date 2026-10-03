"""Owned Linux wire fixture; credentials arrive on stdin, never command arguments."""
import base64
import socket
import sys


def main():
    request = base64.b64decode(sys.stdin.buffer.readline().strip(), validate=True)
    port = int(sys.argv[1]) if len(sys.argv) == 2 else 9971
    with socket.socket() as connection:
        # Both are per-client handshake settings, not host sysctls or Tomcat configuration.
        connection.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
        connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_MAXSEG, 256)
        connection.settimeout(4)
        connection.connect(("127.0.0.1", port))
        connection.settimeout(10)
        print("BUFFER", connection.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF), flush=True)
        print("MSS", connection.getsockopt(socket.IPPROTO_TCP, socket.TCP_MAXSEG), flush=True)
        connection.sendall(request)
        preview = bytearray()
        while not preview.endswith(b"event:token"):
            next_byte = connection.recv(1)
            if not next_byte:
                raise RuntimeError("Connection ended before first token")
            preview.extend(next_byte)
            if len(preview) > 65536:
                raise RuntimeError("First token exceeds fixture header budget")
        print("PAUSED", base64.b64encode(preview).decode("ascii"), flush=True)
        if sys.stdin.buffer.readline() != b"RESUME\n":
            raise RuntimeError("Missing explicit resume command")
        remainder = bytearray()
        while True:
            block = connection.recv(8192)
            if not block:
                break
            remainder.extend(block)
            if len(remainder) > 4_000_000:
                raise RuntimeError("Fixture response exceeds bounded wire budget")
        print("DRAINED", base64.b64encode(remainder).decode("ascii"), flush=True)


if __name__ == "__main__":
    main()
