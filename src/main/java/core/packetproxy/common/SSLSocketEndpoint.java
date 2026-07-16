/*
 * Copyright 2019 DeNA Co., Ltd.
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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLSocket;
import packetproxy.http.Https;

public class SSLSocketEndpoint implements Endpoint {

	protected SSLSocket socket;
	protected String server_name;
	protected String alpn;
	/** 実宛先アドレス。upstream proxy 経由でソケットの接続先がプロキシになる場合に設定する。 */
	protected InetSocketAddress addressOverride;

	public SSLSocketEndpoint(SSLSocketEndpoint ep) {
		this.server_name = ep.server_name;
		this.socket = ep.socket;
		this.alpn = ep.alpn;
		this.addressOverride = ep.addressOverride;
	}

	public SSLSocketEndpoint(SSLSocket socket, String SNIServerName) {
		this.server_name = SNIServerName;
		this.socket = socket;
		this.alpn = socket.getApplicationProtocol();
	}

	public SSLSocketEndpoint(SSLSocket socket, String SNIServerName, InetSocketAddress address) {
		this.server_name = SNIServerName;
		this.socket = socket;
		this.alpn = socket.getApplicationProtocol();
		this.addressOverride = address;
	}

	public SSLSocketEndpoint(InetSocketAddress addr, String SNIServerName, String alpn) throws Exception {
		this(addr, SNIServerName, alpn, null);
	}

	public SSLSocketEndpoint(InetSocketAddress addr, String SNIServerName, String alpn, UpstreamProxy proxy)
			throws Exception {
		this.server_name = SNIServerName;
		this.alpn = alpn;
		this.socket = Https.createClientSSLSocket(addr, SNIServerName, alpn, proxy);
	}

	@Override
	public InputStream getInputStream() throws Exception {
		return socket.getInputStream();
	}

	@Override
	public OutputStream getOutputStream() throws Exception {
		return socket.getOutputStream();
	}

	@Override
	public InetSocketAddress getAddress() {
		if (addressOverride != null) {

			return addressOverride;
		}
		return new InetSocketAddress(socket.getInetAddress(), socket.getPort());
	}

	@Override
	public int getLocalPort() {
		return socket.getLocalPort();
	}

	@Override
	public String getName() {
		return server_name;
	}

	public String getApplicationProtocol() {
		return this.socket.getApplicationProtocol();
	}
}
