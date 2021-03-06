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
package org.neo4j.kernel.impl.transaction.log.files;

import java.io.File;
import java.io.IOException;

import org.neo4j.io.fs.FlushableChannel;
import org.neo4j.io.fs.ReadableChannel;
import org.neo4j.kernel.impl.transaction.log.FlushablePositionAwareChecksumChannel;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogVersionBridge;
import org.neo4j.kernel.impl.transaction.log.ReadableClosablePositionAwareChecksumChannel;
import org.neo4j.kernel.impl.transaction.log.ReadableLogChannel;

/**
 * Sees a log file as bytes, including taking care of rotation of the file into optimal chunks.
 */
public interface LogFile
{
    @FunctionalInterface
    interface LogFileVisitor
    {
        boolean visit( ReadableClosablePositionAwareChecksumChannel channel ) throws IOException;
    }

    /**
     * @return {@link FlushableChannel} capable of appending data to this log.
     */
    FlushablePositionAwareChecksumChannel getWriter();

    /**
     * Opens a {@link ReadableLogChannel reader} at the desired {@link LogPosition}, capable of reading log entries
     * from that position and onwards, through physical log versions.
     *
     * @param position {@link LogPosition} to position the returned reader at.
     * @return {@link ReadableChannel} capable of reading log data, starting from {@link LogPosition position}.
     * @throws IOException on I/O error.
     */
    ReadableLogChannel getReader( LogPosition position ) throws IOException;

    /**
     * Opens a {@link ReadableLogChannel reader} at the desired {@link LogPosition}, capable of reading log entries
     * from that position and onwards, with the given {@link LogVersionBridge}.
     *
     * @param position {@link LogPosition} to position the returned reader at.
     * @param logVersionBridge {@link LogVersionBridge} how to bridge log versions.
     * @return {@link ReadableChannel} capable of reading log data, starting from {@link LogPosition position}.
     * @throws IOException on I/O error.
     */
    ReadableLogChannel getReader( LogPosition position, LogVersionBridge logVersionBridge ) throws IOException;

    void accept( LogFileVisitor visitor, LogPosition startingFromPosition ) throws IOException;

    /**
     * @return {@code true} if a rotation is needed.
     */
    boolean rotationNeeded();

    /**
     * Rotate the active log file.
     * @return A file object representing the file name and path of the log file rotated to.
     * @throws IOException if something goes wrong with either flushing the existing log file, or creating the new log file.
     */
    File rotate() throws IOException;
}
