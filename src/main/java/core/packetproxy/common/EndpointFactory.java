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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Objects;
import javax.net.ssl.SSLSocket;
import packetproxy.PrivateDNSClient;
import packetproxy.http.Https;
import packetproxy.model.CAs.CA;
import packetproxy.model.ListenPort;
import packetproxy.model.ListenPorts;
import packetproxy.model.OneShotPacket;
import packetproxy.model.Server;
import packetproxy.quic.service.connection.ServerConnection;
import packetproxy.quic.value.ConnectionIdPair;

public class EndpointFactory {

	public static Endpoint createClientEndpoint(Socket socket, InputStream lookaheadBuffer) throws Exception {
		return new SocketEndpoint(socket, lookaheadBuffer);
	}

	public static SSLSocketEndpoint[] createBothSideSSLEndpoints(Socket clientSocket, InputStream lookahead,
			InetSocketAddress serverAddr, UpstreamProxy upstreamProxy, String serverName, CA ca) throws Exception {
		SSLSocket[] sslSockets = Https.createBothSideSSLSockets(clientSocket, lookahead, serverAddr, upstreamProxy,
				serverName, ca);
		SSLSocketEndpoint clientEndpoint = new SSLSocketEndpoint(sslSockets[0], serverName);
		SSLSocketEndpoint serverEndpoint = new SSLSocketEndpoint(sslSockets[1], serverName);
		return new SSLSocketEndpoint[]{clientEndpoint, serverEndpoint};
	}

	public static SSLSocketEndpoint createClientEndpointFromSNIServerName(Socket socket, String serverName, CA ca,
			InputStream is) throws Exception {
		SSLSocket ssl_client = Https.convertToServerSSLSocket(socket, serverName, ca, is);
		return new SSLSocketEndpoint(ssl_client, serverName);
	}

	public static Endpoint createFromURI(String uri) throws Exception {
		URI u = new URI(uri);
		String host = u.getHost();
		int port = u.getPort() > 0 ? u.getPort() : 80;
		if (u.getScheme().equalsIgnoreCase("https")) {

			return new SSLSocketEndpoint(new InetSocketAddress(PrivateDNSClient.getByName(host), port), host, null);
		} else if (u.getScheme().equalsIgnoreCase("http")) {

			return new SocketEndpoint(new InetSocketAddress(PrivateDNSClient.getByName(host), port));
		} else {

			throw new Exception(String.format("[Error] Unknown scheme!%s", u.getScheme()));
		}
	}

	public static Endpoint createFromOneShotPacket(OneShotPacket packet) throws Exception {
		// 再送信でもライブ傍受と同じく、リッスンポートに設定された upstream proxy 経由で実サーバに到達する
		UpstreamProxy upstreamProxy = resolveUpstreamProxy(packet.getListenPort());
		if (Objects.equals(packet.getAlpn(), "h3")) {

			// HTTP3 on QUICの場合は特別対応
			return new ServerConnection(ConnectionIdPair.generateRandom(), packet.getServerName(),
					packet.getServerPort());
		} else if (packet.getUseSSL()) {

			return new SSLSocketEndpoint(packet.getServer(), packet.getServerName(), packet.getAlpn(), upstreamProxy);
		} else if (upstreamProxy != null) {

			// upstream proxy 経由: 実サーバまでトンネルして origin 形式で送信する
			final String destHost = (packet.getServerName() != null && !packet.getServerName().isEmpty())
					? packet.getServerName()
					: packet.getServer().getHostString();
			Socket tunnel = UpstreamProxyConnector.connect(destHost, packet.getServerPort(), upstreamProxy);
			return new SocketEndpoint(tunnel);
		} else {

			// nc など複数同時接続を受け付けないconnection用に10秒でtimeoutする
			return new SocketEndpoint(packet.getServer(), 10 * 1000);
		}
	}

	/**
	 * Resolves the upstream proxy configured on the HTTP_PROXY listen port a resend
	 * packet originated from, or {@code null} when that port has no upstream server
	 * (or is not an HTTP_PROXY port). This lets resend reach the destination the
	 * same way live interception does.
	 */
	private static UpstreamProxy resolveUpstreamProxy(int listenPort) throws Exception {
		ListenPort lp = ListenPorts.getInstance().queryByHttpProxyPort(listenPort);
		return lp != null ? UpstreamProxy.forListenUpstream(lp.getServer()) : null;
	}

	public static Endpoint createFromServer(Server server) throws Exception {
		if (server.getUseSSL()) {

			return new SSLSocketEndpoint(server.getAddress(), server.getIp(), null);
		} else {

			return new SocketEndpoint(server.getAddress());
		}
	}

	public static Endpoint createServerEndpoint(InetSocketAddress addr) throws Exception {
		return new SocketEndpoint(addr);
	}
}
