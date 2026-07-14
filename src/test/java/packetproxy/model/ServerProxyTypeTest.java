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
package packetproxy.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import packetproxy.model.Server.ProxyType;

class ServerProxyTypeTest {

	private static Server newServer(boolean httpProxy) {
		// numeric IP avoids any DNS lookup in the constructor
		return new Server("1.2.3.4", 8080, false, "HTTP", false, false, httpProxy, "");
	}

	@Test
	void defaultServerHasNoProxy() {
		Server s = newServer(false);
		assertEquals(ProxyType.NONE, s.getProxyType());
		assertFalse(s.isHttpProxy());
		assertFalse(s.isSocksProxy());
	}

	@Test
	void legacyHttpProxyFlagDerivesHttpType() {
		// old rows have no proxy_type column value; the boolean flag must still be
		// honored
		Server s = newServer(true);
		assertEquals(ProxyType.HTTP, s.getProxyType());
		assertTrue(s.isHttpProxy());
		assertFalse(s.isSocksProxy());
	}

	@Test
	void setSocks5UpdatesTypeAndClearsLegacyHttpFlag() {
		Server s = newServer(false);
		s.setProxyType(ProxyType.SOCKS5);
		assertEquals(ProxyType.SOCKS5, s.getProxyType());
		assertTrue(s.isSocksProxy());
		assertFalse(s.isHttpProxy());
	}

	@Test
	void setHttpTypeIsReportedAsHttpProxy() {
		Server s = newServer(false);
		s.setProxyType(ProxyType.HTTP);
		assertEquals(ProxyType.HTTP, s.getProxyType());
		assertTrue(s.isHttpProxy());
	}

	@Test
	void legacySetHttpProxyFalseDoesNotClobberSocks() {
		Server s = newServer(false);
		s.setProxyType(ProxyType.SOCKS5);
		s.setHttpProxy(false); // toggling the legacy flag off must leave SOCKS5 intact
		assertEquals(ProxyType.SOCKS5, s.getProxyType());
	}

	@Test
	void socksCredentialsRoundTrip() {
		Server s = newServer(false);
		s.setProxyType(ProxyType.SOCKS5);
		s.setSocksUser("alice");
		s.setSocksPassword("s3cr3t");
		assertEquals("alice", s.getSocksUser());
		assertEquals("s3cr3t", s.getSocksPassword());
	}
}
