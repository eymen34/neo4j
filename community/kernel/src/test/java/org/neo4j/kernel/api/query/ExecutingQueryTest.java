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
package org.neo4j.kernel.api.query;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.neo4j.internal.helpers.MathUtil;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorCounters;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.lock.LockWaitEvent;
import org.neo4j.lock.ResourceType;
import org.neo4j.lock.WaitStrategy;
import org.neo4j.test.FakeCpuClock;
import org.neo4j.test.FakeMemoryTracker;
import org.neo4j.time.Clocks;
import org.neo4j.time.FakeClock;
import org.neo4j.values.AnyValue;
import org.neo4j.values.virtual.MapValue;

import static java.util.Collections.emptyList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.graphdb.QueryExecutionType.QueryType.READ_ONLY;
import static org.neo4j.kernel.database.DatabaseIdRepository.NAMED_SYSTEM_DATABASE_ID;
import static org.neo4j.kernel.database.TestDatabaseIdRepository.randomNamedDatabaseId;
import static org.neo4j.values.storable.Values.stringValue;
import static org.neo4j.values.virtual.VirtualValues.EMPTY_MAP;
import static org.neo4j.values.virtual.VirtualValues.map;

class ExecutingQueryTest
{
    private final FakeClock clock = Clocks.fakeClock( ZonedDateTime.parse( "2016-12-03T15:10:00+01:00" ) );
    private final FakeCpuClock cpuClock = new FakeCpuClock().add( randomLong( 0x1_0000_0000L ) );
    private final PageCursorCountersStub page = new PageCursorCountersStub();
    private final ExecutingQuery query = createExecutingQuery( 1, "hello world", page, clock, cpuClock );
    private final ExecutingQuery subQuery = createExecutingQuery( 2, "goodbye world", page, clock, cpuClock );
    private long lockCount;

    @Test
    void shouldReportElapsedTime()
    {
        // when
        clock.forward( 10, TimeUnit.MILLISECONDS );
        long elapsedTime = query.snapshot().elapsedTimeMicros();

        // then
        assertEquals( 10_000, elapsedTime );
    }

    @Test
    void shouldTransitionBetweenStates()
    {
        // initial
        assertEquals( "planning", query.snapshot().status() );

        // when
        query.compilationCompleted( new CompilerInfo( "the-planner", "the-runtime", emptyList() ), READ_ONLY, null );

        // then
        assertEquals( "planned", query.snapshot().status() );

        // when
        query.executionStarted( new FakeMemoryTracker() );

        // then
        assertEquals( "running", query.snapshot().status() );

        // when
        try ( LockWaitEvent ignored = lock( "NODE", 17 ) )
        {
            // then
            assertEquals( "waiting", query.snapshot().status() );
        }
        // then
        assertEquals( "running", query.snapshot().status() );
    }

    @Test
    void shouldReportPlanningTime()
    {
        // when
        clock.forward( 124, TimeUnit.MICROSECONDS );

        // then
        QuerySnapshot snapshot = query.snapshot();
        assertEquals( snapshot.compilationTimeMicros(), snapshot.elapsedTimeMicros() );

        // when
        clock.forward( 16, TimeUnit.MICROSECONDS );
        query.compilationCompleted( new CompilerInfo( "the-planner", "the-runtime", emptyList() ), READ_ONLY, null );
        clock.forward( 200, TimeUnit.MICROSECONDS );

        // then
        snapshot = query.snapshot();
        assertEquals( 140, snapshot.compilationTimeMicros() );
        assertEquals( 340, snapshot.elapsedTimeMicros() );
    }

    @Test
    void shouldReportWaitTime()
    {
        // given
        query.compilationCompleted( new CompilerInfo( "the-planner", "the-runtime", emptyList() ), READ_ONLY, null );
        query.executionStarted( new FakeMemoryTracker() );

        // then
        assertEquals( "running", query.snapshot().status() );

        // when
        clock.forward( 10, TimeUnit.SECONDS );
        try ( LockWaitEvent ignored = lock( "NODE", 17 ) )
        {
            clock.forward( 5, TimeUnit.SECONDS );

            // then
            QuerySnapshot snapshot = query.snapshot();
            assertEquals( "waiting", snapshot.status() );
            assertThat( snapshot.resourceInformation(), CoreMatchers.<Map<String,Object>>allOf(
                    hasEntry( "waitTimeMillis", 5_000L ),
                    hasEntry( "resourceType", "NODE" ),
                    hasEntry( equalTo( "resourceIds" ), longArray( 17 ) ) ) );
            assertEquals( 5_000_000, snapshot.waitTimeMicros() );
        }
        {
            QuerySnapshot snapshot = query.snapshot();
            assertEquals( "running", snapshot.status() );
            assertEquals( 5_000_000, snapshot.waitTimeMicros() );
        }

        // when
        clock.forward( 2, TimeUnit.SECONDS );
        try ( LockWaitEvent ignored = lock( "RELATIONSHIP", 612 ) )
        {
            clock.forward( 1, TimeUnit.SECONDS );

            // then
            QuerySnapshot snapshot = query.snapshot();
            assertEquals( "waiting", snapshot.status() );
            assertThat( snapshot.resourceInformation(), CoreMatchers.<Map<String,Object>>allOf(
                    hasEntry( "waitTimeMillis", 1_000L ),
                    hasEntry( "resourceType", "RELATIONSHIP" ),
                    hasEntry( equalTo( "resourceIds" ), longArray( 612 ) ) ) );
            assertEquals( 6_000_000, snapshot.waitTimeMicros() );
        }
        {
            QuerySnapshot snapshot = query.snapshot();
            assertEquals( "running", snapshot.status() );
            assertEquals( 6_000_000, snapshot.waitTimeMicros() );
        }
    }

    @Test
    void shouldReportCpuTime()
    {
        // given
        cpuClock.add( 60, TimeUnit.MICROSECONDS );

        // when
        long cpuTime = query.snapshot().cpuTimeMicros();

        // then
        assertEquals( 60, cpuTime );
    }

    @Test
    void shouldNotReportCpuTimeIfUnavailable()
    {
        // given
        ExecutingQuery query = new ExecutingQuery( 17,
                ClientConnectionInfo.EMBEDDED_CONNECTION, randomNamedDatabaseId(), "neo4j", "hello world",
                EMPTY_MAP,
                Collections.emptyMap(),
                () -> lockCount, () -> 0, () -> 1,
                Thread.currentThread().getId(),
                Thread.currentThread().getName(),
                clock,
                FakeCpuClock.NOT_AVAILABLE );

        // when
        QuerySnapshot snapshot = query.snapshot();

        // then
        assertNull( snapshot.cpuTimeMicros() );
        assertNull( snapshot.idleTimeMicros() );
    }

    @Test
    void shouldNotReportHeapAllocationIfUnavailable()
    {
        // given
        ExecutingQuery query = new ExecutingQuery( 17,
                ClientConnectionInfo.EMBEDDED_CONNECTION, randomNamedDatabaseId(), "neo4j", "hello world",
                EMPTY_MAP,
                Collections.emptyMap(),
                () -> lockCount,
                () -> 0,
                () -> 1,
                Thread.currentThread().getId(),
                Thread.currentThread().getName(),
                clock,
                FakeCpuClock.NOT_AVAILABLE );

        // when
        QuerySnapshot snapshot = query.snapshot();

        // then
        assertTrue( snapshot.allocatedBytes().isEmpty() );
    }

    @Test
    void shouldReportLockCount()
    {
        // given
        lockCount = 11;

        // then
        assertEquals( 11, query.snapshot().activeLockCount() );

        // given
        lockCount = 2;

        // then
        assertEquals( 2, query.snapshot().activeLockCount() );
    }

    @Test
    void shouldReportPageHitsAndFaults()
    {
        // given
        page.hits( 7 );
        page.faults( 3 );

        // when
        QuerySnapshot snapshot = query.snapshot();

        // then
        assertEquals( 7, snapshot.pageHits() );
        assertEquals( 3, snapshot.pageFaults() );

        // when
        page.hits( 2 );
        page.faults( 5 );
        snapshot = query.snapshot();

        // then
        assertEquals( 9, snapshot.pageHits() );
        assertEquals( 8, snapshot.pageFaults() );
    }

    @Test
    void includeQueryExecutorThreadName()
    {
        String queryDescription = query.toString();
        assertTrue( queryDescription.contains( "threadExecutingTheQueryName=" + Thread.currentThread().getName() ) );
    }

    @Test
    void shouldObfuscateCreateUser()
    {
        var queryPart = "create USER foo SET PaSsWoRd ";
        var exeQuery1 = createExecutingQuery( 1, queryPart + "'bar'", page, clock, cpuClock, NAMED_SYSTEM_DATABASE_ID, EMPTY_MAP );
        assertThat( exeQuery1.queryText(), equalTo( queryPart + "'******'" ) );
        assertThat( exeQuery1.queryParameters().size(), equalTo( 0 ) );

        MapValue params = map( new String[]{"password"}, new AnyValue[]{stringValue( "bar" )} );
        MapValue obfuscatedParams = map( new String[]{"password"}, new AnyValue[]{stringValue( "******" )} );
        var exeQuery2 = createExecutingQuery( 1, queryPart + "$password", page, clock, cpuClock, NAMED_SYSTEM_DATABASE_ID, params );
        assertThat( exeQuery2.queryText(), equalTo( queryPart + "$password" ) );
        assertThat( exeQuery2.queryParameters(), equalTo( obfuscatedParams ) );
    }

    @Test
    void shouldObfuscateAlterUser()
    {
        var queryPart = "alter USER foo SET PaSsWoRd ";
        var exeQuery = createExecutingQuery( 1, queryPart + "'bar'", page, clock, cpuClock, NAMED_SYSTEM_DATABASE_ID, EMPTY_MAP );
        assertThat( exeQuery.queryText(), equalTo( queryPart + "'******'" ) );
        assertThat( exeQuery.queryParameters().size(), equalTo( 0 ) );

        MapValue params = map( new String[]{"password"}, new AnyValue[]{stringValue( "bar" )} );
        MapValue obfuscatedParams = map( new String[]{"password"}, new AnyValue[]{stringValue( "******" )} );
        var exeQuery2 = createExecutingQuery( 1, queryPart + "$password", page, clock, cpuClock, NAMED_SYSTEM_DATABASE_ID, params );
        assertThat( exeQuery2.queryText(), equalTo( queryPart + "$password" ) );
        assertThat( exeQuery2.queryParameters(), equalTo( obfuscatedParams ) );
    }

    @Test
    void shouldObfuscateAlterCurrentUser()
    {
        var queryPart = "alter CuRRenT USER foo SET PaSsWoRd FROM ";
        var exeQuery = createExecutingQuery( 1, queryPart + "'bar' TO 'baz'", page, clock, cpuClock, NAMED_SYSTEM_DATABASE_ID, EMPTY_MAP );
        assertThat( exeQuery.queryText(), equalTo( queryPart + "'******' TO '******'" ) );
        assertThat( exeQuery.queryParameters().size(), equalTo( 0 ) );

        MapValue params2 = map( new String[]{"old", "new"}, new AnyValue[]{stringValue( "bar" ), stringValue( "baz" )} );
        MapValue obfuscatedParams2 = map( new String[]{"old", "new"}, new AnyValue[]{stringValue( "******" ), stringValue( "******" )} );
        var exeQuery2 = createExecutingQuery( 1, queryPart + "$old TO $new", page, clock, cpuClock, NAMED_SYSTEM_DATABASE_ID, params2 );
        assertThat( exeQuery2.queryText(), equalTo( queryPart + "$old TO $new" ) );
        assertThat( exeQuery2.queryParameters(), equalTo( obfuscatedParams2 ) );

        MapValue params3 = map( new String[]{"old"}, new AnyValue[]{stringValue( "bar" )} );
        MapValue obfuscatedParams3 = map( new String[]{"old"}, new AnyValue[]{stringValue( "******" )} );
        var exeQuery3 = createExecutingQuery( 1, queryPart + "$old TO 'baz'", page, clock, cpuClock, NAMED_SYSTEM_DATABASE_ID, params3 );
        assertThat( exeQuery3.queryText(), equalTo( queryPart + "$old TO '******'" ) );
        assertThat( exeQuery3.queryParameters(), equalTo( obfuscatedParams3 ) );

    }

    private LockWaitEvent lock( String resourceType, long resourceId )
    {
        return query.lockTracer().waitForLock( false, resourceType( resourceType ), resourceId );
    }

    static ResourceType resourceType( String name )
    {
        return new ResourceType()
        {
            @Override
            public String toString()
            {
                return name();
            }

            @Override
            public int typeId()
            {
                throw new UnsupportedOperationException( "not used" );
            }

            @Override
            public WaitStrategy waitStrategy()
            {
                throw new UnsupportedOperationException( "not used" );
            }

            @Override
            public String name()
            {
                return name;
            }
        };
    }

    @SuppressWarnings( "unchecked" )
    private static Matcher<Object> longArray( long... expected )
    {
        return (Matcher) new TypeSafeMatcher<long[]>()
        {
            @Override
            protected boolean matchesSafely( long[] item )
            {
                return Arrays.equals( expected, item );
            }

            @Override
            public void describeTo( Description description )
            {
                description.appendValue( expected );
            }
        };
    }

    @SuppressWarnings( "SameParameterValue" )
    private static long randomLong( long bound )
    {
        return ThreadLocalRandom.current().nextLong( bound );
    }

    private ExecutingQuery createExecutingQuery( int queryId, String hello_world, PageCursorCountersStub page,
            FakeClock clock, FakeCpuClock cpuClock )
    {
        return createExecutingQuery( queryId, hello_world, page, clock, cpuClock, randomNamedDatabaseId(), EMPTY_MAP );
    }

    private ExecutingQuery createExecutingQuery( int queryId, String hello_world, PageCursorCountersStub page,
            FakeClock clock, FakeCpuClock cpuClock, NamedDatabaseId dbID, MapValue params )
    {
        return new ExecutingQuery( queryId, ClientConnectionInfo.EMBEDDED_CONNECTION, dbID, "neo4j", hello_world,
                params, Collections.emptyMap(), () -> lockCount, page::hits, page::faults, Thread.currentThread().getId(),
                Thread.currentThread().getName(), clock, cpuClock );
    }

    private static class PageCursorCountersStub implements PageCursorCounters
    {
        private long faults;
        private long pins;
        private long unpins;
        private long hits;
        private long bytesRead;
        private long evictions;
        private long evictionExceptions;
        private long bytesWritten;
        private long flushes;

        @Override
        public long faults()
        {
            return faults;
        }

        public void faults( long increment )
        {
            faults += increment;
        }

        @Override
        public long pins()
        {
            return pins;
        }

        public void pins( long increment )
        {
            pins += increment;
        }

        @Override
        public long unpins()
        {
            return unpins;
        }

        public void unpins( long increment )
        {
            unpins += increment;
        }

        @Override
        public long hits()
        {
            return hits;
        }

        public void hits( long increment )
        {
            hits += increment;
        }

        @Override
        public long bytesRead()
        {
            return bytesRead;
        }

        public void bytesRead( long increment )
        {
            bytesRead += increment;
        }

        @Override
        public long evictions()
        {
            return evictions;
        }

        public void evictions( long increment )
        {
            evictions += increment;
        }

        @Override
        public long evictionExceptions()
        {
            return evictionExceptions;
        }

        public void evictionExceptions( long increment )
        {
            evictionExceptions += increment;
        }

        @Override
        public long bytesWritten()
        {
            return bytesWritten;
        }

        public void bytesWritten( long increment )
        {
            bytesWritten += increment;
        }

        @Override
        public long flushes()
        {
            return flushes;
        }

        public void flushes( long increment )
        {
            flushes += increment;
        }

        @Override
        public double hitRatio()
        {
            return MathUtil.portion( hits(), faults() );
        }
    }
}
