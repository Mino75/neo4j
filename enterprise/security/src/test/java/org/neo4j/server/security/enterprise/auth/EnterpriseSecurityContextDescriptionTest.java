/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.security.enterprise.auth;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.time.Clock;

import org.neo4j.internal.kernel.api.Token;
import org.neo4j.internal.kernel.api.security.AccessMode;
import org.neo4j.kernel.api.security.exception.InvalidAuthTokenException;
import org.neo4j.kernel.enterprise.api.security.EnterpriseSecurityContext;
import org.neo4j.kernel.impl.api.security.OverriddenAccessMode;
import org.neo4j.kernel.impl.api.security.RestrictedAccessMode;
import org.neo4j.server.security.auth.InMemoryUserRepository;
import org.neo4j.server.security.auth.RateLimitedAuthenticationStrategy;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.neo4j.server.security.auth.SecurityTestUtils.authToken;
import static org.neo4j.server.security.enterprise.auth.plugin.api.PredefinedRoles.PUBLISHER;

public class EnterpriseSecurityContextDescriptionTest
{
    @Rule
    public MultiRealmAuthManagerRule authManagerRule = new MultiRealmAuthManagerRule( new InMemoryUserRepository(),
            new RateLimitedAuthenticationStrategy( Clock.systemUTC(), 3 ) );

    private EnterpriseUserManager manager;
    private Token token;

    @Before
    public void setUp() throws Throwable
    {
        authManagerRule.getManager().start();
        manager = authManagerRule.getManager().getUserManager();
        manager.newUser( "mats", "foo", false );
        token = mock( Token.class );
    }

    @Test
    public void shouldMakeNiceDescriptionWithoutRoles() throws Exception
    {
        assertThat( context().description(), equalTo( "user 'mats' with no roles" ) );
    }

    @Test
    public void shouldMakeNiceDescriptionWithRoles() throws Exception
    {
        manager.newRole( "role1", "mats" );
        manager.addRoleToUser( PUBLISHER, "mats" );

        assertThat( context().description(), equalTo( "user 'mats' with roles [publisher,role1]" ) );
    }

    @Test
    public void shouldMakeNiceDescriptionWithMode() throws Exception
    {
        manager.newRole( "role1", "mats" );
        manager.addRoleToUser( PUBLISHER, "mats" );

        EnterpriseSecurityContext modified = context().withMode( AccessMode.Static.CREDENTIALS_EXPIRED );
        assertThat( modified.description(), equalTo( "user 'mats' with CREDENTIALS_EXPIRED" ) );
    }

    @Test
    public void shouldMakeNiceDescriptionRestricted() throws Exception
    {
        manager.newRole( "role1", "mats" );
        manager.addRoleToUser( PUBLISHER, "mats" );

        EnterpriseSecurityContext context = context();
        EnterpriseSecurityContext restricted =
                context.withMode( new RestrictedAccessMode( context.mode(), AccessMode.Static.READ ) );
        assertThat( restricted.description(), equalTo( "user 'mats' with roles [publisher,role1] restricted to READ" ) );
    }

    @Test
    public void shouldMakeNiceDescriptionOverridden() throws Exception
    {
        manager.newRole( "role1", "mats" );
        manager.addRoleToUser( PUBLISHER, "mats" );

        EnterpriseSecurityContext context = context();
        EnterpriseSecurityContext overridden =
                context.withMode( new OverriddenAccessMode( context.mode(), AccessMode.Static.READ ) );
        assertThat( overridden.description(), equalTo( "user 'mats' with roles [publisher,role1] overridden by READ" ) );
    }

    @Test
    public void shouldMakeNiceDescriptionAuthDisabled()
    {
        EnterpriseSecurityContext disabled = EnterpriseSecurityContext.AUTH_DISABLED;
        assertThat( disabled.description(), equalTo( "AUTH_DISABLED with FULL" ) );
    }

    @Test
    public void shouldMakeNiceDescriptionAuthDisabledAndRestricted()
    {
        EnterpriseSecurityContext disabled = EnterpriseSecurityContext.AUTH_DISABLED;
        EnterpriseSecurityContext restricted =
                disabled.withMode( new RestrictedAccessMode( disabled.mode(), AccessMode.Static.READ ) );
        assertThat( restricted.description(), equalTo( "AUTH_DISABLED with FULL restricted to READ" ) );
    }

    private EnterpriseSecurityContext context() throws InvalidAuthTokenException
    {
        return authManagerRule.getManager().login( authToken( "mats", "foo" ) ).authorize( token );
    }
}
