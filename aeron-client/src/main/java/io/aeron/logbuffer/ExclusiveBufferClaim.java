/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.logbuffer;

import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteOrder;

import static io.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;
import static io.aeron.protocol.DataHeaderFlyweight.RESERVED_VALUE_OFFSET;
import static io.aeron.protocol.HeaderFlyweight.*;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * Represents a claimed range in a buffer to be used for recording a message without copy semantics for later commit.
 *
 * {@link ExclusiveBufferClaim}s offer additional functionality over standard {@link BufferClaim}s in that the header
 * can be manipulated for setting flags and type. This allows the user to implement things such as their own
 * fragmentation policy.
 *
 * The claimed space is in {@link #buffer()} between {@link #offset()} and {@link #offset()} + {@link #length()}.
 * When the buffer is filled with message data, use {@link #commit()} to make it available to subscribers.
 *
 * If the claimed space is no longer required it can be aborted by calling {@link #abort()}.
 *
 * <a href="https://github.com/real-logic/Aeron/wiki/Protocol-Specification#data-frame">Data Frame</a>
 *
 * @see io.aeron.ExclusivePublication#tryClaim(int, ExclusiveBufferClaim)
 */
public class ExclusiveBufferClaim
{
    private final UnsafeBuffer buffer = new UnsafeBuffer(0, 0);

    /**
     * Wrap a region of an underlying log buffer so can can represent a claimed space for use by a publisher.
     *
     * @param buffer to be wrapped.
     * @param offset at which the claimed region begins including space for the header.
     * @param length length of the underlying claimed region including space for the header.
     */
    public void wrap(final AtomicBuffer buffer, final int offset, final int length)
    {
        this.buffer.wrap(buffer, offset, length);
    }

    /**
     * The referenced buffer to be used.
     *
     * @return the referenced buffer to be used..
     */
    public MutableDirectBuffer buffer()
    {
        return buffer;
    }

    /**
     * The offset in the buffer at which the claimed range begins.
     *
     * @return offset in the buffer at which the range begins.
     */
    public int offset()
    {
        return HEADER_LENGTH;
    }

    /**
     * The length of the claimed range in the buffer.
     *
     * @return length of the range in the buffer.
     */
    public int length()
    {
        return buffer.capacity() - HEADER_LENGTH;
    }

    /**
     * Get the value of the flags field.
     *
     * @return the value of the header flags field.
     * @see DataHeaderFlyweight
     */
    public byte flags()
    {
        return buffer.getByte(FLAGS_FIELD_OFFSET);
    }

    /**
     * Set the value of the header flags field.
     *
     * @param flags value to be set in the header.
     * @return this for a fluent API.
     * @see DataHeaderFlyweight
     */
    public ExclusiveBufferClaim flags(final byte flags)
    {
        buffer.putByte(FLAGS_FIELD_OFFSET, flags);

        return this;
    }

    /**
     * Get the value of the header type field. The lower 16 bits are valid.
     *
     * @return the value of the header type field.
     * @see DataHeaderFlyweight
     */
    public int headerType()
    {
        return buffer.getShort(TYPE_FIELD_OFFSET, LITTLE_ENDIAN) & 0xFFFF;
    }

    /**
     * Set the value of the header type field. The lower 16 bits are valid.
     *
     * @param type value to be set in the header.
     * @return this for a fluent API.
     * @see DataHeaderFlyweight
     */
    public ExclusiveBufferClaim headerType(final int type)
    {
        buffer.putShort(TYPE_FIELD_OFFSET, (short)type, LITTLE_ENDIAN);

        return this;
    }

    /**
     * Get the value stored in the reserve space at the end of a data frame header.
     *
     * Note: The value is in {@link ByteOrder#LITTLE_ENDIAN} format.
     *
     * @return the value stored in the reserve space at the end of a data frame header.
     * @see DataHeaderFlyweight
     */
    public long reservedValue()
    {
        return buffer.getLong(RESERVED_VALUE_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * Write the provided value into the reserved space at the end of the data frame header.
     *
     * Note: The value will be written in {@link ByteOrder#LITTLE_ENDIAN} format.
     *
     * @param value to be stored in the reserve space at the end of a data frame header.
     * @return this for fluent API semantics.
     * @see DataHeaderFlyweight
     */
    public ExclusiveBufferClaim reservedValue(final long value)
    {
        buffer.putLong(RESERVED_VALUE_OFFSET, value, LITTLE_ENDIAN);
        return this;
    }

    /**
     * Commit the message to the log buffer so that is it available to subscribers.
     */
    public void commit()
    {
        int frameLength = buffer.capacity();
        if (ByteOrder.nativeOrder() != LITTLE_ENDIAN)
        {
            frameLength = Integer.reverseBytes(frameLength);
        }

        buffer.putIntOrdered(FRAME_LENGTH_FIELD_OFFSET, frameLength);
    }

    /**
     * Abort a claim of the message space to the log buffer so that the log can progress by ignoring this claim.
     */
    public void abort()
    {
        int frameLength = buffer.capacity();
        if (ByteOrder.nativeOrder() != LITTLE_ENDIAN)
        {
            frameLength = Integer.reverseBytes(frameLength);
        }

        buffer.putShort(TYPE_FIELD_OFFSET, (short)HDR_TYPE_PAD, LITTLE_ENDIAN);
        buffer.putIntOrdered(FRAME_LENGTH_FIELD_OFFSET, frameLength);
    }
}

