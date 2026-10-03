"""Socket protocol checks, not production application acceptance."""
import base64
from pathlib import Path
import socket
import subprocess
import sys
import threading
import unittest


class SlowClientTest(unittest.TestCase):
    def exercise(self, response, resume):
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen()
            server.settimeout(5)
            request = b"GET / HTTP/1.1\r\nAuthorization: Bearer fixture-secret\r\n\r\n"
            seen = []

            def serve():
                with server.accept()[0] as connection:
                    connection.settimeout(5)
                    received = bytearray()
                    while not received.endswith(b"\r\n\r\n"):
                        received.extend(connection.recv(1024))
                    seen.append(bytes(received))
                    # Split the event marker to verify cross-read framing.
                    connection.sendall(response[:12])
                    connection.sendall(response[12:])

            worker = threading.Thread(target=serve, daemon=True)
            worker.start()
            client = subprocess.Popen([sys.executable, str(Path(__file__).with_name("assistant_slow_tcp_client.py")),
                                       str(server.getsockname()[1])], stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                client.stdin.write(base64.b64encode(request) + b"\n")
                client.stdin.flush()
                buffer = client.stdout.readline()
                mss = client.stdout.readline()
                self.assertTrue(0 < int(buffer.split()[1]) <= 16384)
                self.assertTrue(0 < int(mss.split()[1]) <= 256)
                if resume:
                    paused = client.stdout.readline()
                    self.assertEqual(paused.split()[0], b"PAUSED")
                    self.assertIsNone(client.poll(), "Socket EOF must not bypass the explicit resume barrier")
                    client.stdin.write(b"RESUME\n")
                    client.stdin.flush()
                    stdout, stderr = client.communicate(timeout=5)
                    self.assertEqual(client.returncode, 0, stderr.decode())
                    self.assertEqual(stdout.split()[0], b"DRAINED")
                    reconstructed = base64.b64decode(paused.split()[1]) + base64.b64decode(stdout.split()[1])
                    self.assertEqual(reconstructed, response)
                    self.assertNotIn(b"fixture-secret", paused + stdout + stderr)
                else:
                    stdout, stderr = client.communicate(timeout=5)
                    self.assertNotEqual(client.returncode, 0)
                    self.assertIn(b"Connection ended before first token", stderr)
                    self.assertNotIn(b"PAUSED", stdout)
                    self.assertNotIn(b"fixture-secret", stdout + stderr)
                worker.join(timeout=5)
                self.assertFalse(worker.is_alive())
                self.assertEqual(seen, [request])
            finally:
                if client.poll() is None:
                    client.kill()
                    client.communicate(timeout=5)
                worker.join(timeout=5)

    def test_pause_requires_resume_and_preserves_bytes(self):
        self.exercise(b"HTTP/1.1 200 OK\r\n\r\nevent:token\ndata:preview\n\nevent:cancelled\n\n", True)

    def test_eof_before_token_is_failure(self):
        self.exercise(b"HTTP/1.1 200 OK\r\n\r\n", False)


if __name__ == "__main__":
    unittest.main()
