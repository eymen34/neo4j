/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.transport;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.neo4j.bolt.AbstractBoltTransportsTest;
import org.neo4j.bolt.testing.client.TransportConnection;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.neo4j.bolt.testing.MessageMatchers.msgSuccess;

/**
 * Multiple concurrent users should be able to connect simultaneously. We test this with multiple users running
 * load that they roll back, asserting they don't see each others changes.
 */
public class ConcurrentAccessIT extends AbstractBoltTransportsTest
{
    @Rule
    public Neo4jWithSocket server = new Neo4jWithSocket( getClass(), getSettingsFunction() );

    @Test
    public void shouldRunSimpleStatement() throws Throwable
    {
        // Given
        int numWorkers = 5;
        int numRequests = 1_000;

        List<Callable<Void>> workers = createWorkers( numWorkers, numRequests );
        ExecutorService exec = Executors.newFixedThreadPool( numWorkers );

        try
        {
            // When & then
            for ( Future<Void> f : exec.invokeAll( workers ) )
            {
                f.get( 60, TimeUnit.SECONDS );
            }
        }
        finally
        {
            exec.shutdownNow();
            exec.awaitTermination( 30, TimeUnit.SECONDS );
        }
    }

    private List<Callable<Void>> createWorkers( int numWorkers, int numRequests ) throws Exception
    {
        List<Callable<Void>> workers = new LinkedList<>();
        for ( int i = 0; i < numWorkers; i++ )
        {
            workers.add( newWorker( numRequests ) );
        }
        return workers;
    }

    private Callable<Void> newWorker( final int iterationsToRun ) throws Exception
    {
        return new Callable<Void>()
        {
            private final byte[] init = util.defaultAuth();
            private final byte[] createAndRollback = util.defaultRunExplicitCommitTxAndRollBack( "CREATE (n)" );

            private final byte[] matchAll = util.defaultRunAutoCommitTx( "MATCH (n) RETURN n" );

            @Override
            public Void call() throws Exception
            {
                // Connect
                TransportConnection client = newConnection();
                client.connect( server.lookupDefaultConnector() ).send( util.defaultAcceptedVersions() );
                assertThat( client, util.eventuallyReceivesSelectedProtocolVersion() );

                init( client );

                for ( int i = 0; i < iterationsToRun; i++ )
                {
                    createAndRollback( client );
                }

                return null;
            }

            private void init( TransportConnection client ) throws Exception
            {
                client.send( init );
                assertThat( client, util.eventuallyReceives( msgSuccess() ) );
            }

            private void createAndRollback( TransportConnection client ) throws Exception
            {
                client.send( createAndRollback );
                Matcher<Map<? extends String,?>> entryMatcher = hasEntry( is( "fields" ), equalTo( emptyList() ) );
                assertThat( client, util.eventuallyReceives(
                        msgSuccess(), // begin
                        msgSuccess( CoreMatchers.allOf( entryMatcher, hasKey( "t_first" ), hasKey( "qid" ) ) ), // run
                        msgSuccess( CoreMatchers.allOf( hasKey( "t_last" ), hasKey( "db" ) ) ), // pull_all
                        msgSuccess() // roll_back
                        ) );

                client.send( matchAll );
                Matcher<Map<? extends String,?>> fieldsMatcher = hasEntry( is( "fields" ), equalTo( singletonList( "n" ) ) );
                assertThat( client, util.eventuallyReceives(
                        msgSuccess( CoreMatchers.allOf( fieldsMatcher, hasKey( "t_first" ) ) ), // run
                        msgSuccess( CoreMatchers.allOf( hasKey( "t_last" ), hasKey( "db" ) ) ) ) );// pull_all
            }
        };

    }
}
