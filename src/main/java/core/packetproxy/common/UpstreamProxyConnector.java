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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Opens a TCP connection to a destination through an upstream proxy. Supports
 * HTTP {@code CONNECT} tunnels and SOCKS5 (RFC 1928, with optional RFC 1929
 * username/password authentication).
 *
 * <p>
 * For SOCKS5 the destination host name is sent as-is (ATYP=0x03) so that name
 * resolution happens on the proxy side (remote DNS); an IP literal is sent as
 * ATYP=0x01/0x04 instead.
 */
public class UpstreamProxyConnector {

	private static final byte SOCKS5_VERSION = 0x05;
	private static final byte SOCKS5_CMD_CONNECT = 0x01;
	private static final byte SOCKS5_RSV = 0x00;
	private static final byte SOCKS5_ATYP_IPV4 = 0x01;
	private static final byte SOCKS5_ATYP_DOMAIN = 0x03;
	private static final byte SOCKS5_ATYP_IPV6 = 0x04;
	private static final byte SOCKS5_METHOD_NO_AUTH = 0x00;
	private static final byte SOCKS5_METHOD_USERPASS = 0x02;
	private static final int SOCKS5_METHOD_NONE_ACCEPTABLE = 0xFF;
	private static final byte SOCKS5_AUTH_VERSION = 0x01;

	/**
	 * Bounds the TCP connect and every handshake read so a stalling proxy cannot
	 * hang the worker thread.
	 */
	private static final int CONNECT_TIMEOUT_MS = 30_000;

	private static final int HANDSHAKE_TIMEOUT_MS = 30_000;

	/**
	 * Connects to {@code destHost:destPort} through {@code proxy}. The destination
	 * is not resolved locally: for HTTP the host name is placed in the
	 * {@code CONNECT} line, for SOCKS5 it is placed in the request as a domain
	 * name. When {@code proxy} is {@code null} the destination is resolved locally
	 * and connected to directly.
	 */
	public static Socket connect(String destHost, int destPort, UpstreamProxy proxy) throws Exception {
		if (destHost == null || destHost.isEmpty()) {

			throw new IOException("upstream proxy: destination host is empty");
		}
		if (proxy == null) {

			Socket socket = new Socket();
			try {

				socket.connect(new InetSocketAddress(destHost, destPort), CONNECT_TIMEOUT_MS);
				return socket;
			} catch (Exception e) {

				closeQuietly(socket);
				throw e;
			}
		}
		switch (proxy.getType()) {
			case HTTP :
				return connectViaHttp(destHost, destPort, proxy);
			case SOCKS5 :
				return connectViaSocks5(destHost, destPort, proxy);
			default :
				throw new IOException("Unsupported upstream proxy type: " + proxy.getType());
		}
	}

	private static Socket connectViaHttp(String destHost, int destPort, UpstreamProxy proxy) throws Exception {
		Socket socket = openProxySocket(proxy.getAddress());
		try {

			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write(String.format("CONNECT %s:%d HTTP/1.1\r\nHost: %s\r\n\r\n", destHost, destPort, destHost)
					.getBytes(StandardCharsets.ISO_8859_1));
			out.flush();

			int length;
			byte[] buf = new byte[1024];
			while ((length = in.read(buf, 0, buf.length)) != -1) {

				if (Utils.indexOf(buf, 0, length, "\r\n\r\n".getBytes()) >= 0) {

					break;
				}
			}
			socket.setSoTimeout(0); // the tunnel is established: restore blocking semantics for the caller
			return socket;
		} catch (Exception e) {

			closeQuietly(socket);
			throw e;
		}
	}

	private static Socket connectViaSocks5(String destHost, int destPort, UpstreamProxy proxy) throws Exception {
		Socket socket = openProxySocket(proxy.getAddress());
		try {

			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();

			socks5Handshake(in, out, proxy.getUsername(), proxy.getPassword());
			socks5Connect(in, out, destHost, destPort);
			socket.setSoTimeout(0); // the tunnel is established: restore blocking semantics for the caller
			return socket;
		} catch (Exception e) {

			closeQuietly(socket);
			throw e;
		}
	}

	private static Socket openProxySocket(InetSocketAddress proxyAddr) throws IOException {
		Socket socket = new Socket();
		try {

			socket.connect(proxyAddr, CONNECT_TIMEOUT_MS);
			socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
			return socket;
		} catch (IOException e) {

			closeQuietly(socket);
			throw e;
		}
	}

	private static void socks5Handshake(InputStream in, OutputStream out, String username, String password)
			throws Exception {
		boolean useAuth = username != null && !username.isEmpty();
		if (useAuth) {

			out.write(new byte[]{SOCKS5_VERSION, 0x02, SOCKS5_METHOD_NO_AUTH, SOCKS5_METHOD_USERPASS});
		} else {

			out.write(new byte[]{SOCKS5_VERSION, 0x01, SOCKS5_METHOD_NO_AUTH});
		}
		out.flush();

		byte[] selection = readFully(in, 2);
		if ((selection[0] & 0xFF) != SOCKS5_VERSION) {

			throw new IOException("SOCKS5: unexpected version in method selection: " + (selection[0] & 0xFF));
		}
		int method = selection[1] & 0xFF;
		if (method == SOCKS5_METHOD_NONE_ACCEPTABLE) {

			throw new IOException("SOCKS5: no acceptable authentication method offered by the proxy");
		}
		if (method == (SOCKS5_METHOD_NO_AUTH & 0xFF)) {

			return;
		}
		if (method == (SOCKS5_METHOD_USERPASS & 0xFF)) {

			if (!useAuth) {

				throw new IOException(
						"SOCKS5: proxy requires username/password authentication but no credentials were configured");
			}
			socks5UserPassAuth(in, out, username, password);
			return;
		}
		throw new IOException("SOCKS5: unsupported authentication method selected: " + method);
	}

	private static void socks5UserPassAuth(InputStream in, OutputStream out, String username, String password)
			throws Exception {
		byte[] user = (username == null ? "" : username).getBytes(StandardCharsets.UTF_8);
		byte[] pass = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
		if (user.length > 255 || pass.length > 255) {

			throw new IOException("SOCKS5: username/password must be at most 255 bytes");
		}
		ByteArrayOutputStream req = new ByteArrayOutputStream();
		req.write(SOCKS5_AUTH_VERSION);
		req.write(user.length);
		req.write(user, 0, user.length);
		req.write(pass.length);
		req.write(pass, 0, pass.length);
		out.write(req.toByteArray());
		out.flush();

		byte[] resp = readFully(in, 2);
		if ((resp[1] & 0xFF) != 0x00) {

			throw new IOException("SOCKS5: username/password authentication failed (status=" + (resp[1] & 0xFF) + ")");
		}
	}

	private static void socks5Connect(InputStream in, OutputStream out, String destHost, int destPort)
			throws Exception {
		ByteArrayOutputStream req = new ByteArrayOutputStream();
		req.write(SOCKS5_VERSION);
		req.write(SOCKS5_CMD_CONNECT);
		req.write(SOCKS5_RSV);

		// destHost is guaranteed non-null/non-empty by connect(); a domain name is sent
		// as-is for
		// remote DNS
		if (!isIpLiteral(destHost)) {

			byte[] host = destHost.getBytes(StandardCharsets.US_ASCII);
			if (host.length > 255) {

				throw new IOException("SOCKS5: destination host name must be at most 255 bytes");
			}
			req.write(SOCKS5_ATYP_DOMAIN);
			req.write(host.length);
			req.write(host, 0, host.length);
		} else {

			InetAddress addr = InetAddress.getByName(destHost); // IP literal: parsed without a DNS lookup
			byte[] raw = addr.getAddress();
			req.write(raw.length == 16 ? SOCKS5_ATYP_IPV6 : SOCKS5_ATYP_IPV4);
			req.write(raw, 0, raw.length);
		}
		req.write((destPort >> 8) & 0xFF);
		req.write(destPort & 0xFF);
		out.write(req.toByteArray());
		out.flush();

		byte[] head = readFully(in, 4); // VER, REP, RSV, ATYP
		if ((head[0] & 0xFF) != SOCKS5_VERSION) {

			throw new IOException("SOCKS5: unexpected version in reply: " + (head[0] & 0xFF));
		}
		int rep = head[1] & 0xFF;
		if (rep != 0x00) {

			throw new IOException("SOCKS5: connection request failed (" + replyMessage(rep) + ")");
		}
		int atyp = head[3] & 0xFF;
		int addrLen;
		switch (atyp) {
			case SOCKS5_ATYP_IPV4 :
				addrLen = 4;
				break;
			case SOCKS5_ATYP_IPV6 :
				addrLen = 16;
				break;
			case SOCKS5_ATYP_DOMAIN :
				addrLen = readFully(in, 1)[0] & 0xFF;
				break;
			default :
				throw new IOException("SOCKS5: unknown address type in reply: " + atyp);
		}
		readFully(in, addrLen + 2); // discard BND.ADDR and BND.PORT
	}

	private static String replyMessage(int rep) {
		switch (rep) {
			case 0x01 :
				return "general SOCKS server failure";
			case 0x02 :
				return "connection not allowed by ruleset";
			case 0x03 :
				return "network unreachable";
			case 0x04 :
				return "host unreachable";
			case 0x05 :
				return "connection refused";
			case 0x06 :
				return "TTL expired";
			case 0x07 :
				return "command not supported";
			case 0x08 :
				return "address type not supported";
			default :
				return "REP=" + rep;
		}
	}

	private static boolean isIpLiteral(String host) {
		if (host.indexOf(':') >= 0) {

			return true; // IPv6 literal
		}
		return host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
	}

	private static byte[] readFully(InputStream in, int n) throws IOException {
		byte[] buf = new byte[n];
		int off = 0;
		while (off < n) {

			int r = in.read(buf, off, n - off);
			if (r < 0) {

				throw new EOFException("SOCKS5: unexpected end of stream from proxy");
			}
			off += r;
		}
		return buf;
	}

	private static void closeQuietly(Socket socket) {
		try {

			socket.close();
		} catch (IOException ignored) {
			// best effort
		}
	}
}
