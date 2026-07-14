/*
 * Copyright 2025 DeNA Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package packetproxy.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UpstreamProxyConnectorTest {

	/** Captures what a fake proxy received during a single connection. */
	private static class Capture {
		byte[] offeredMethods;
		int atyp;
		String host; // for ATYP=domain
		byte[] addr; // for ATYP=ipv4/ipv6
		int port;
		String authUser;
		String authPassword;
		String httpRequestLine;
		Throwable error; // any failure (incl. AssertionError) raised inside the server thread
	}

	/**
	 * Runs a one-shot fake SOCKS5 server: accepts a single connection, performs the
	 * handshake (optionally requiring username/password), reads the CONNECT request
	 * into {@code capture} and replies success. When {@code replyDomainAtyp} is set
	 * the success reply uses ATYP=domain to exercise the client's reply parsing.
	 */
	private static Thread startFakeSocks5Server(ServerSocket server, boolean requireAuth, boolean replyDomainAtyp,
			Capture capture) {
		Thread thread = new Thread(() -> {
			try (Socket client = server.accept()) {

				client.setSoTimeout(3000); // never let a misbehaving client hang the test
				DataInputStream in = new DataInputStream(client.getInputStream());
				OutputStream out = client.getOutputStream();

				assertEquals(0x05, in.readUnsignedByte());
				int nMethods = in.readUnsignedByte();
				byte[] methods = new byte[nMethods];
				in.readFully(methods);
				capture.offeredMethods = methods;

				if (requireAuth) {

					out.write(new byte[]{0x05, 0x02}); // choose username/password
					out.flush();
					assertEquals(0x01, in.readUnsignedByte()); // auth version
					capture.authUser = readLengthPrefixed(in);
					capture.authPassword = readLengthPrefixed(in);
					out.write(new byte[]{0x01, 0x00}); // auth success
					out.flush();
				} else {

					out.write(new byte[]{0x05, 0x00}); // no auth
					out.flush();
				}

				assertEquals(0x05, in.readUnsignedByte()); // VER
				assertEquals(0x01, in.readUnsignedByte()); // CMD = CONNECT
				in.readUnsignedByte(); // RSV
				int atyp = in.readUnsignedByte();
				capture.atyp = atyp;
				if (atyp == 0x03) {

					capture.host = readLengthPrefixed(in);
				} else if (atyp == 0x01) {

					byte[] addr = new byte[4];
					in.readFully(addr);
					capture.addr = addr;
				} else if (atyp == 0x04) {

					byte[] addr = new byte[16];
					in.readFully(addr);
					capture.addr = addr;
				}
				capture.port = (in.readUnsignedByte() << 8) | in.readUnsignedByte();

				if (replyDomainAtyp) {

					byte[] name = "proxy.local".getBytes(StandardCharsets.US_ASCII);
					out.write(new byte[]{0x05, 0x00, 0x00, 0x03, (byte) name.length});
					out.write(name);
					out.write(new byte[]{0x00, 0x00}); // BND.PORT
				} else {

					// success reply: ATYP=IPv4, BND.ADDR = 0.0.0.0, BND.PORT = 0
					out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
				}
				out.flush();
			} catch (Throwable e) {

				capture.error = e;
			}
		});
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	private static String readLengthPrefixed(DataInputStream in) throws IOException {
		int len = in.readUnsignedByte();
		byte[] buf = new byte[len];
		in.readFully(buf);
		return new String(buf, StandardCharsets.UTF_8);
	}

	private static UpstreamProxy socks5(ServerSocket server, String user, String pass) {
		return new UpstreamProxy(UpstreamProxy.Type.SOCKS5, new InetSocketAddress("127.0.0.1", server.getLocalPort()),
				user, pass);
	}

	@Test
	void socks5_domainDestination_sendsRemoteDnsAtyp() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {

			Capture capture = new Capture();
			Thread t = startFakeSocks5Server(server, false, false, capture);

			Socket socket = UpstreamProxyConnector.connect("example.com", 8080, socks5(server, null, null));
			t.join(3000);

			assertTrue(socket.isConnected());
			socket.close();
			assertNull(capture.error);
			assertEquals(0x03, capture.atyp, "domain names must be sent as ATYP=0x03 for remote DNS");
			assertEquals("example.com", capture.host);
			assertEquals(8080, capture.port);
			assertTrue(contains(capture.offeredMethods, (byte) 0x00), "no-auth method must be offered");
		}
	}

	@Test
	void socks5_ipv4LiteralDestination_sendsAtyp1() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {

			Capture capture = new Capture();
			Thread t = startFakeSocks5Server(server, false, false, capture);

			Socket socket = UpstreamProxyConnector.connect("1.2.3.4", 443, socks5(server, null, null));
			t.join(3000);

			assertTrue(socket.isConnected());
			socket.close();
			assertNull(capture.error);
			assertEquals(0x01, capture.atyp, "IPv4 literals must be sent as ATYP=0x01");
			assertArrayEquals(new byte[]{1, 2, 3, 4}, capture.addr);
			assertEquals(443, capture.port);
		}
	}

	@Test
	void socks5_ipv6LiteralDestination_sendsAtyp4() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {

			Capture capture = new Capture();
			Thread t = startFakeSocks5Server(server, false, false, capture);

			Socket socket = UpstreamProxyConnector.connect("::1", 993, socks5(server, null, null));
			t.join(3000);

			assertTrue(socket.isConnected());
			socket.close();
			assertNull(capture.error);
			assertEquals(0x04, capture.atyp, "IPv6 literals must be sent as ATYP=0x04");
			assertEquals(16, capture.addr.length);
			assertEquals(993, capture.port);
		}
	}

	@Test
	void socks5_withCredentials_performsUserPassAuth() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {

			Capture capture = new Capture();
			Thread t = startFakeSocks5Server(server, true, false, capture);

			Socket socket = UpstreamProxyConnector.connect("example.com", 80, socks5(server, "alice", "s3cr3t"));
			t.join(3000);

			assertTrue(socket.isConnected());
			socket.close();
			assertNull(capture.error);
			assertTrue(contains(capture.offeredMethods, (byte) 0x02), "username/password method must be offered");
			assertEquals("alice", capture.authUser);
			assertEquals("s3cr3t", capture.authPassword);
			assertEquals("example.com", capture.host);
		}
	}

	@Test
	void socks5_domainAtypReply_isParsedAndConnectionSucceeds() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {

			Capture capture = new Capture();
			Thread t = startFakeSocks5Server(server, false, true, capture); // reply uses ATYP=domain

			Socket socket = UpstreamProxyConnector.connect("example.com", 80, socks5(server, null, null));
			t.join(3000);

			assertTrue(socket.isConnected(), "client must correctly consume a domain-ATYP bound address in the reply");
			socket.close();
			assertNull(capture.error);
		}
	}

	@Test
	void http_connectTunnel_sendsConnectLine() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {

			Capture capture = new Capture();
			Thread t = new Thread(() -> {
				try (Socket client = server.accept()) {

					client.setSoTimeout(3000);
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
					capture.httpRequestLine = reader.readLine();
					String line;
					while ((line = reader.readLine()) != null && !line.isEmpty()) {
						// consume remaining request headers
					}
					OutputStream out = client.getOutputStream();
					out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
					out.flush();
				} catch (Throwable e) {

					capture.error = e;
				}
			});
			t.setDaemon(true);
			t.start();

			UpstreamProxy proxy = new UpstreamProxy(UpstreamProxy.Type.HTTP,
					new InetSocketAddress("127.0.0.1", server.getLocalPort()), null, null);
			Socket socket = UpstreamProxyConnector.connect("example.com", 8443, proxy);
			t.join(3000);

			assertTrue(socket.isConnected());
			socket.close();
			assertNull(capture.error);
			assertEquals("CONNECT example.com:8443 HTTP/1.1", capture.httpRequestLine);
		}
	}

	@Test
	void connect_emptyHost_throws() {
		UpstreamProxy proxy = new UpstreamProxy(UpstreamProxy.Type.SOCKS5, new InetSocketAddress("127.0.0.1", 1), null,
				null);
		assertThrows(IOException.class, () -> UpstreamProxyConnector.connect("", 80, proxy));
		assertThrows(IOException.class, () -> UpstreamProxyConnector.connect(null, 80, proxy));
	}

	@Test
	void socks5_serverRejectsAllMethods_throws() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {

			startGreetingOnlyServer(server, (byte) 0xFF); // no acceptable methods
			assertThrows(IOException.class,
					() -> UpstreamProxyConnector.connect("example.com", 80, socks5(server, null, null)));
		}
	}

	@Test
	void socks5_missingCredentialsButServerRequiresAuth_throws() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {

			startGreetingOnlyServer(server, (byte) 0x02); // demand username/password
			assertThrows(IOException.class,
					() -> UpstreamProxyConnector.connect("example.com", 80, socks5(server, null, null)));
		}
	}

	/**
	 * A server that reads the greeting and replies with a single method byte, then
	 * closes.
	 */
	private static void startGreetingOnlyServer(ServerSocket server, byte method) {
		Thread t = new Thread(() -> {
			try (Socket client = server.accept()) {

				client.setSoTimeout(3000);
				DataInputStream in = new DataInputStream(client.getInputStream());
				in.readUnsignedByte(); // VER
				int n = in.readUnsignedByte();
				in.readFully(new byte[n]);
				client.getOutputStream().write(new byte[]{0x05, method});
				client.getOutputStream().flush();
			} catch (IOException ignored) {
				// the client side asserts the failure
			}
		});
		t.setDaemon(true);
		t.start();
	}

	private static boolean contains(byte[] arr, byte value) {
		if (arr == null) {

			return false;
		}
		for (byte b : arr) {

			if (b == value) {

				return true;
			}
		}
		return false;
	}
}
