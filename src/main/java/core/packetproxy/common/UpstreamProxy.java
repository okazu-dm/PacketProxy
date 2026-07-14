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

import java.net.InetSocketAddress;
import packetproxy.model.Server;

/**
 * Immutable descriptor of an upstream proxy (the next hop PacketProxy connects
 * through) together with the credentials it needs. Built from a {@link Server}
 * configured as a proxy.
 */
public class UpstreamProxy {

	public enum Type {
		HTTP, SOCKS5
	}

	private final Type type;
	private final InetSocketAddress address;
	private final String username;
	private final String password;

	public UpstreamProxy(Type type, InetSocketAddress address, String username, String password) {
		this.type = type;
		this.address = address;
		this.username = username;
		this.password = password;
	}

	public Type getType() {
		return type;
	}

	public InetSocketAddress getAddress() {
		return address;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	/**
	 * Builds the upstream proxy for a server that is explicitly attached to a
	 * proxy-style listen port as its next hop. Any attached server acts as a proxy;
	 * its {@link Server.ProxyType} only selects HTTP (the legacy default) versus
	 * SOCKS5. Returns {@code null} when no server is attached (i.e. connect
	 * directly).
	 */
	public static UpstreamProxy forListenUpstream(Server server) throws Exception {
		if (server == null) {

			return null;
		}
		if (server.getProxyType() == Server.ProxyType.SOCKS5) {

			return new UpstreamProxy(Type.SOCKS5, server.getAddress(), server.getSocksUser(),
					server.getSocksPassword());
		}
		return new UpstreamProxy(Type.HTTP, server.getAddress(), null, null);
	}
}
