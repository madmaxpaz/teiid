/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.transport;

import static org.junit.Assert.*;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.util.Properties;

import javax.security.auth.Subject;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.teiid.common.buffer.BufferManagerFactory;
import org.teiid.core.util.UnitTestUtil;
import org.teiid.jdbc.FakeServer;
import org.teiid.jdbc.TeiidDriver;
import org.teiid.jdbc.TeiidSQLException;
import org.teiid.net.TeiidURL;
import org.teiid.net.socket.AuthenticationType;
import org.teiid.runtime.DoNothingSecurityHelper;
import org.teiid.runtime.EmbeddedConfiguration;

@SuppressWarnings("nls")
public class TestJDBCSocketAuthentication {

	static InetSocketAddress addr;
	static SocketListener jdbcTransport;
	static FakeServer server;
	static int delay;
	
	@BeforeClass public static void oneTimeSetup() throws Exception {
		SocketConfiguration config = new SocketConfiguration();
		config.setSSLConfiguration(new SSLConfiguration());
		addr = new InetSocketAddress(0);
		config.setBindAddress(addr.getHostName());
		config.setPortNumber(0);
		
		EmbeddedConfiguration dqpConfig = new EmbeddedConfiguration();
		dqpConfig.setMaxActivePlans(2);
		dqpConfig.setSecurityHelper(new DoNothingSecurityHelper() {
            
            @Override
            public Subject getSubjectInContext(Object context) {
                return null;
            }
            
            @Override
            public Subject getSubjectInContext(String securityDomain) {
                return null;
            }
            
        });
		server = new FakeServer(false);
		server.start(dqpConfig, false);
		server.deployVDB("parts", UnitTestUtil.getTestDataPath() + "/PartsSupplier.vdb");
		
		jdbcTransport = new SocketListener(addr, config, server.getClientServiceRegistry(), BufferManagerFactory.getStandaloneBufferManager()) {
			@Override
			protected SSLAwareChannelHandler createChannelHandler() {
			    SSLAwareChannelHandler result = new SSLAwareChannelHandler(this) {
                    public void messageReceived(io.netty.channel.ChannelHandlerContext ctx,
                            Object msg) throws Exception {
                        if (delay > 0) {
                            Thread.sleep(delay);
                        }
                        super.messageReceived(ctx, msg);
                    }
			    };
                return result;			    
		    }
		};
	}
	
	@AfterClass public static void oneTimeTearDown() throws Exception {
		if (jdbcTransport != null) {
			jdbcTransport.stop();
		}
		server.stop();
	}
	
	Connection conn;
	
	@After public void tearDown() throws Exception {
		if (conn != null) {
			conn.close();
		}
	}
	
	@Test(expected=TeiidSQLException.class) public void testTrustLocalFails() throws Exception {
	    Properties p = new Properties();
        p.setProperty("user", "testuser");
        p.setProperty("password", "testpassword");
        p.setProperty(TeiidURL.CONNECTION.PASSTHROUGH_AUTHENTICATION, "true");
        conn = TeiidDriver.getInstance().connect("jdbc:teiid:parts@mm://"+addr.getHostName()+":" +jdbcTransport.getPort(), p);
	}
	
    @Test public void testTrustLocal() throws Exception {
        Properties p = new Properties();
        p.setProperty("user", "testuser");
        p.setProperty("password", "testpassword");
        conn = TeiidDriver.getInstance().connect("jdbc:teiid:parts@mm://"+addr.getHostName()+":" +jdbcTransport.getPort(), p);
    }
    
    @Test public void testSetAuthType() throws Exception {
        FakeServer es = new FakeServer(false);
        EmbeddedConfiguration ec = new EmbeddedConfiguration();
        ec.setAuthenticationType(AuthenticationType.GSS);
        es.start(ec);
        es.deployVDB("parts", UnitTestUtil.getTestDataPath() + "/PartsSupplier.vdb");
        assertEquals(AuthenticationType.GSS, es.getSessionService().getAuthenticationType("parts", null, "testuser"));
        assertEquals(AuthenticationType.GSS, es.getClientServiceRegistry().getAuthenticationType());
        es.stop();
    }
	
}
