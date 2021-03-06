/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archiver;

import io.aeron.*;
import io.aeron.archiver.codecs.RecordingDescriptorDecoder;
import io.aeron.logbuffer.ExclusiveBufferClaim;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.*;

import java.io.*;

/**
 * A replay session with a client which works through the required request response flow and streaming of recorded data.
 * The {@link ArchiveConductor} will initiate a session on receiving a ReplayRequest
 * (see {@link io.aeron.archiver.codecs.ReplayRequestDecoder}). The session will:
 * <ul>
 * <li>Validate request parameters and respond with error,
 *     or OK message(see {@link io.aeron.archiver.codecs.ControlResponseDecoder})</li>
 * <li>Stream recorded data into the replayPublication {@link Publication}</li>
 * </ul>
 */
class ReplaySession implements
    ArchiveConductor.Session,
    RecordingFragmentReader.SimplifiedControlledPoll
{
    private enum State
    {
        INIT, REPLAY, LINGER, INACTIVE, CLOSED
    }

    private final int recordingId;
    private final int fromTermId;
    private final int fromTermOffset;
    private final long replayLength;

    private final ExclusivePublication replayPublication;
    private final ExclusivePublication controlPublication;

    private final File archiveDir;
    private final ClientSessionProxy clientSessionProxy;
    private final ExclusiveBufferClaim bufferClaim = new ExclusiveBufferClaim();

    private State state = State.INIT;
    private RecordingFragmentReader cursor;
    private final int replaySessionId;
    private final long correlationId;
    private int lingerCounter;

    ReplaySession(
        final int recordingId,
        final int fromTermId,
        final int fromTermOffset,
        final long replayLength,
        final ExclusivePublication replayPublication,
        final ExclusivePublication controlPublication,
        final File archiveDir,
        final ClientSessionProxy clientSessionProxy,
        final int replaySessionId,
        final long correlationId)
    {
        this.recordingId = recordingId;

        this.fromTermId = fromTermId;
        this.fromTermOffset = fromTermOffset;
        this.replayLength = replayLength;

        this.replayPublication = replayPublication;
        this.controlPublication = controlPublication;
        this.archiveDir = archiveDir;
        this.clientSessionProxy = clientSessionProxy;
        this.replaySessionId = replaySessionId;
        this.correlationId = correlationId;
    }

    public int doWork()
    {
        int workDone = 0;
        if (state == State.REPLAY)
        {
            workDone += replay();
        }
        else if (state == State.INIT)
        {
            workDone += init();
        }
        else if (state == State.LINGER)
        {
            workDone += linger();
        }

        if (state == State.INACTIVE)
        {
            workDone += close();
        }

        return workDone;
    }

    private int linger()
    {
        lingerCounter++;
        if (lingerCounter == 10000)
        {
            this.state = State.INACTIVE;
        }

        return 0;
    }

    public void abort()
    {
        this.state = State.INACTIVE;
    }

    public boolean isDone()
    {
        return state == State.CLOSED;
    }

    public void remove(final ArchiveConductor conductor)
    {
        conductor.removeReplaySession(replaySessionId);
    }

    private int init()
    {
        if (replayPublication.isClosed() || controlPublication.isClosed())
        {
            // TODO: add counter
            this.state = State.INACTIVE;
            return 0;
        }

        if (!replayPublication.isConnected() || !controlPublication.isConnected())
        {
            // TODO: introduce some timeout mechanism here to prevent stale requests linger
            return 0;
        }

        final String recordingMetaFileName = ArchiveUtil.recordingMetaFileName(recordingId);
        final File recordingMetaFile = new File(archiveDir, recordingMetaFileName);
        if (!recordingMetaFile.exists())
        {
            return closeOnError(null, recordingMetaFile.getAbsolutePath() + " not found");
        }

        final RecordingDescriptorDecoder metaData;
        try
        {
            metaData = ArchiveUtil.recordingMetaFileFormatDecoder(recordingMetaFile);
        }
        catch (final IOException ex)
        {
            return closeOnError(ex, recordingMetaFile.getAbsolutePath() + " : failed to map");
        }

        final int initialTermId = metaData.initialTermId();
        final int initialTermOffset = metaData.initialTermOffset();

        final int lastTermId = metaData.lastTermId();
        final int lastTermOffset = metaData.lastTermOffset();
        final int termBufferLength = metaData.termBufferLength();

        // Note: when debugging this may cause a crash as the debugger might try to call metaData.toString after unmap
        IoUtil.unmap(metaData.buffer().byteBuffer());

        final int replayEndTermId = (int)(fromTermId + (replayLength / termBufferLength));
        final int replayEndTermOffset = (int)((replayLength + fromTermOffset) % termBufferLength);

        if (fromTermOffset >= termBufferLength || fromTermOffset < 0 ||
            !isTermIdInRange(fromTermId, initialTermId, lastTermId) ||
            !isTermOffsetInRange(
                initialTermId,
                initialTermOffset,
                lastTermId,
                lastTermOffset,
                fromTermId,
                fromTermOffset) ||
            !isTermIdInRange(replayEndTermId, initialTermId, lastTermId) ||
            !isTermOffsetInRange(
                initialTermId,
                initialTermOffset,
                lastTermId,
                lastTermOffset,
                replayEndTermId,
                replayEndTermOffset))
        {
            return closeOnError(null, "Requested replay is out of recorded range [(" +
                initialTermId + ", " + initialTermOffset + "), (" +
                lastTermId + ", " + lastTermOffset + ")]");
        }

        try
        {
            cursor = new RecordingFragmentReader(
                recordingId,
                archiveDir,
                fromTermId,
                fromTermOffset,
                replayLength);
        }
        catch (final IOException ex)
        {
            return closeOnError(ex, "Failed to open cursor for a recording");
        }

        clientSessionProxy.sendResponse(controlPublication, null, correlationId);
        this.state = State.REPLAY;

        return 1;
    }

    private static boolean isTermOffsetInRange(
        final int initialTermId,
        final int initialTermOffset,
        final int lastTermId,
        final int lastTermOffset,
        final int termId,
        final int termOffset)
    {
        return (initialTermId == termId && termOffset >= initialTermOffset) ||
            (lastTermId == termId && termOffset <= lastTermOffset);
    }

    private static boolean isTermIdInRange(final int term, final int start, final int end)
    {
        if (start <= end)
        {
            return term >= start && term <= end;
        }
        else
        {
            return term >= start || term <= end;
        }
    }

    private int closeOnError(final Throwable e, final String errorMessage)
    {
        this.state = State.INACTIVE;
        if (controlPublication.isConnected())
        {
            clientSessionProxy.sendResponse(controlPublication, errorMessage, correlationId);
        }

        if (e != null)
        {
            LangUtil.rethrowUnchecked(e);
        }

        return 0;
    }

    private int replay()
    {
        try
        {
            // TODO: Really, 42 as a magic number?
            final int polled = cursor.controlledPoll(this, 42);
            if (cursor.isDone())
            {
                this.state = State.INACTIVE;
            }

            return polled;
        }
        catch (final Exception ex)
        {
            return closeOnError(ex, "Cursor read failed");
        }
    }

    private int close()
    {
        CloseHelper.close(replayPublication);
        CloseHelper.close(cursor);
        this.state = State.CLOSED;

        return 1;
    }

    public boolean onFragment(
        final DirectBuffer fragmentBuffer,
        final int fragmentOffset,
        final int fragmentLength,
        final DataHeaderFlyweight header)
    {
        if (isDone())
        {
            return false;
        }

        final long result = replayPublication.tryClaim(fragmentLength, bufferClaim);
        if (result > 0)
        {
            try
            {
                final MutableDirectBuffer publicationBuffer = bufferClaim.buffer();
                bufferClaim.flags((byte)header.flags());
                bufferClaim.reservedValue(header.reservedValue());
                // TODO: ??? bufferClaim.headerType(header.type()); ???
                // TODO: Need to handle padding records

                final int offset = bufferClaim.offset();
                publicationBuffer.putBytes(offset, fragmentBuffer, fragmentOffset, fragmentLength);
            }
            finally
            {
                bufferClaim.commit();
            }

            return true;
        }
        else if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED)
        {
            closeOnError(null, "Reply publication to replay requestor has shutdown mid-replay");
        }

        return false;
    }
}
