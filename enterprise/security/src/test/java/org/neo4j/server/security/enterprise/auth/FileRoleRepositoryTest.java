/*
 * Copyright (c) 2002-2016 "Neo Technology,"
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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.Arrays;
import java.util.Collection;

import org.neo4j.io.fs.DelegatingFileSystem;
import org.neo4j.io.fs.DelegatingFileSystemProvider;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.server.security.auth.exception.ConcurrentModificationException;
import org.neo4j.string.UTF8;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(Parameterized.class)
public class FileRoleRepositoryTest
{
    private final FileSystem fs;
    private Path authFile;

    @Parameters(name = "{1} filesystem")
    public static Collection<Object[]> data()
    {
        return Arrays.asList( new Object[][]{
                        {Configuration.unix(), "unix"},
                        {Configuration.osX(), "osX"},
                        {Configuration.windows(), "windows"}}
        );
    }

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    public FileRoleRepositoryTest( Configuration fsConfig, String fsType )
    {
        fs = Jimfs.newFileSystem( fsConfig );
        authFile = fs.getPath( "dbms", "auth.db" );
    }

    @Test
    public void shouldStoreAndRetriveRolesByName() throws Exception
    {
        // Given
        FileRoleRepository roleRepository = new FileRoleRepository( authFile, NullLogProvider.getInstance() );
        RoleRecord role = new RoleRecord( "admin", "petra", "olivia" );
        roleRepository.create( role );

        // When
        RoleRecord result = roleRepository.findByName( role.name() );

        // Then
        assertThat( result, equalTo( role ) );
    }

    @Test
    public void shouldPersistRoles() throws Throwable
    {
        // Given
        FileRoleRepository roleRepository = new FileRoleRepository( authFile, NullLogProvider.getInstance() );
        RoleRecord role = new RoleRecord( "admin", "craig", "karl" );
        roleRepository.create( role );

        roleRepository = new FileRoleRepository( authFile, NullLogProvider.getInstance() );
        roleRepository.start();

        // When
        RoleRecord resultByName = roleRepository.findByName( role.name() );

        // Then
        assertThat( resultByName, equalTo( role ) );
    }

    @Test
    public void shouldNotFindRoleAfterDelete() throws Throwable
    {
        // Given
        FileRoleRepository roleRepository = new FileRoleRepository( authFile, NullLogProvider.getInstance() );
        RoleRecord role = new RoleRecord( "jake", "admin" );
        roleRepository.create( role );

        // When
        roleRepository.delete( role );

        // Then
        assertThat( roleRepository.findByName( role.name() ), nullValue() );
    }

    @Test
    public void shouldNotAllowComplexNames() throws Exception
    {
        // Given
        FileRoleRepository roleRepository = new FileRoleRepository( authFile, NullLogProvider.getInstance() );

        // When
        assertTrue( roleRepository.isValidName( "neo4j" ) );
        assertTrue( roleRepository.isValidName( "johnosbourne" ) );
        assertTrue( roleRepository.isValidName( "john_osbourne" ) );

        assertFalse( roleRepository.isValidName( ":" ) );
        assertFalse( roleRepository.isValidName( "" ) );
        assertFalse( roleRepository.isValidName( "john osbourne" ) );
        assertFalse( roleRepository.isValidName( "john:osbourne" ) );
    }

    @Test
    public void shouldRecoverIfCrashedDuringMove() throws Throwable
    {
        // Given
        final IOException exception = new IOException( "simulated IO Exception on create" );
        FileSystem moveFailingFileSystem = new DelegatingFileSystem( fs )
        {
            @Override
            protected DelegatingFileSystemProvider createDelegate( FileSystemProvider provider )
            {
                return new WrappedProvider( provider, this )
                {
                    @Override
                    public void move( Path source, Path target, CopyOption... options ) throws IOException
                    {
                        if ( authFile.getFileName().toString().equals( target.getFileName().toString() ) )
                        {
                            throw exception;
                        }
                        super.move( source, target, options );
                    }
                };
            }
        };

        Path authFile = moveFailingFileSystem.getPath( "dbms", "auth.db" );

        FileRoleRepository roleRepository = new FileRoleRepository( authFile, NullLogProvider.getInstance() );
        roleRepository.start();
        RoleRecord role = new RoleRecord( "admin", "jake" );

        // When
        try
        {
            roleRepository.create( role );
            fail( "Expected an IOException" );
        } catch ( IOException e )
        {
            assertSame( exception, e );
        }

        // Then
        assertFalse( Files.exists( authFile ) );
        assertFalse( Files.newDirectoryStream( authFile.getParent() ).iterator().hasNext() );
    }

    @Test
    public void shouldThrowIfUpdateChangesName() throws Throwable
    {
        // Given
        FileRoleRepository roleRepository = new FileRoleRepository( authFile, NullLogProvider.getInstance() );
        RoleRecord role = new RoleRecord( "admin", "steve", "bob" );
        roleRepository.create( role );

        // When
        RoleRecord updatedRole = new RoleRecord( "admins", "steve", "bob" );
        try
        {
            roleRepository.update( role, updatedRole );
            fail( "expected exception not thrown" );
        } catch ( IllegalArgumentException e )
        {
            // Then continue
        }

        assertThat( roleRepository.findByName( role.name() ), equalTo( role ) );
    }

    @Test
    public void shouldThrowIfExistingRoleDoesNotMatch() throws Throwable
    {
        // Given
        FileRoleRepository roleRepository = new FileRoleRepository( authFile, NullLogProvider.getInstance() );
        RoleRecord role = new RoleRecord( "admin", "jake" );
        roleRepository.create( role );
        RoleRecord modifiedRole = new RoleRecord( "admin", "jake", "john" );

        // When
        RoleRecord updatedRole = new RoleRecord( "admin", "john" );
        try
        {
            roleRepository.update( modifiedRole, updatedRole );
            fail( "expected exception not thrown" );
        } catch ( ConcurrentModificationException e )
        {
            // Then continue
        }
    }

    @Test
    public void shouldIgnoreInvalidEntries() throws Throwable
    {
        // Given
        AssertableLogProvider logProvider = new AssertableLogProvider();
        Files.createDirectories( authFile.getParent() );
        Files.write( authFile, UTF8.encode(
                "neo4j:admin:\n" +
                "admin:admin:\n" ) );

        // When
        FileRoleRepository roleRepository = new FileRoleRepository( authFile, logProvider );
        thrown.expect( IllegalStateException.class );
        thrown.expectMessage( startsWith( "Failed to read role file: " ) );
        roleRepository.start();

        // Then
        assertThat( roleRepository.numberOfRoles(), equalTo( 1 ) );
        logProvider.assertExactly(
                AssertableLogProvider.inLog( FileRoleRepository.class ).error(
                        "Ignoring role file \"%s\" (%s)", authFile.toAbsolutePath(), "wrong number of line fields [line 1]"
                )
        );
    }
}
