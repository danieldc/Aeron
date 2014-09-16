/*
 * Copyright 2014 Real Logic Ltd.
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

package uk.co.real_logic.aeron.examples;

import org.HdrHistogram.Histogram;
import uk.co.real_logic.aeron.*;
import uk.co.real_logic.aeron.common.BusySpinIdleStrategy;
import uk.co.real_logic.aeron.common.CloseHelper;
import uk.co.real_logic.aeron.common.IdleStrategy;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.driver.MediaDriver;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/**
 * Ping component of Ping-Pong.
 *
 * Initiates and records times.
 */
public class Ping
{
    private static final int PING_STREAM_ID = ExampleConfiguration.PING_STREAM_ID;
    private static final int PONG_STREAM_ID = ExampleConfiguration.PONG_STREAM_ID;
    private static final String PING_CHANNEL = ExampleConfiguration.PING_CHANNEL;
    private static final String PONG_CHANNEL = ExampleConfiguration.PONG_CHANNEL;
    private static final int NUMBER_OF_MESSAGES = ExampleConfiguration.NUMBER_OF_MESSAGES;
    private static final int WARMUP_NUMBER_OF_MESSAGES = ExampleConfiguration.WARMUP_NUMBER_OF_MESSAGES;
    private static final int WARMUP_NUMBER_OF_ITERATIONS = ExampleConfiguration.WARMUP_NUMBER_OF_ITERATIONS;
    private static final int MESSAGE_LENGTH = ExampleConfiguration.MESSAGE_LENGTH;
    private static final int FRAGMENT_COUNT_LIMIT = ExampleConfiguration.FRAGMENT_COUNT_LIMIT;
    private static final long LINGER_TIMEOUT_MS = ExampleConfiguration.LINGER_TIMEOUT_MS;
    private static final boolean EMBEDDED_MEDIA_DRIVER = ExampleConfiguration.EMBEDDED_MEDIA_DRIVER;

    private static final AtomicBuffer ATOMIC_BUFFER = new AtomicBuffer(ByteBuffer.allocateDirect(MESSAGE_LENGTH));
    private static final Histogram HISTOGRAM = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
    private static final CountDownLatch PONG_CONNECTION_LATCH = new CountDownLatch(1);

    private static int numPongsReceived;

    public static void main(final String[] args) throws Exception
    {
        ExamplesUtil.useSharedMemoryOnLinux();

        final MediaDriver driver = EMBEDDED_MEDIA_DRIVER ? MediaDriver.launch() : null;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Aeron.Context ctx = new Aeron.Context()
            .newConnectionHandler(Ping::newPongConnectionHandler);

        System.out.println("Publishing Ping at " + PING_CHANNEL + " on stream Id " + PING_STREAM_ID);
        System.out.println("Subscribing Pong at " + PONG_CHANNEL + " on stream Id " + PONG_STREAM_ID);
        System.out.println("Message size of " + MESSAGE_LENGTH + " bytes");

        final FragmentAssemblyAdapter dataHandler = new FragmentAssemblyAdapter(Ping::pongHandler);

        try (final Aeron aeron = Aeron.connect(ctx);
             final Publication pingPublication = aeron.addPublication(PING_CHANNEL, PING_STREAM_ID);
             final Subscription pongSubscription = aeron.addSubscription(PONG_CHANNEL, PONG_STREAM_ID, dataHandler))
        {
            final int totalWarmupMessages = WARMUP_NUMBER_OF_MESSAGES * WARMUP_NUMBER_OF_ITERATIONS;
            final Future warmup = executor.submit(() -> runSubscriber(pongSubscription, totalWarmupMessages));

            System.out.println("Waiting for new connection from Pong...");

            PONG_CONNECTION_LATCH.await();

            System.out.println(
                "Warming up... " + WARMUP_NUMBER_OF_ITERATIONS + " iterations of " + WARMUP_NUMBER_OF_MESSAGES + " messages");

            for (int i = 0; i < WARMUP_NUMBER_OF_ITERATIONS; i++)
            {
                sendMessages(pingPublication, WARMUP_NUMBER_OF_MESSAGES);
            }

            warmup.get();
            HISTOGRAM.reset();

            System.out.println("Pinging " + NUMBER_OF_MESSAGES + " messages");

            final Future timedRun = executor.submit(() -> runSubscriber(pongSubscription, NUMBER_OF_MESSAGES));
            sendMessages(pingPublication, NUMBER_OF_MESSAGES);

            System.out.println("Done pinging.");

            timedRun.get();
        }

        System.out.println("Done playing... Histogram of RTT latencies in microseconds.");

        HISTOGRAM.outputPercentileDistribution(System.out, 1000.0);

        executor.shutdown();
        CloseHelper.quietClose(driver);
    }

    private static void sendMessages(final Publication pingPublication, final int numMessages)
    {
        for (int i = 0; i < numMessages; i++)
        {
            do
            {
                ATOMIC_BUFFER.putLong(0, System.nanoTime());
            }
            while (!pingPublication.offer(ATOMIC_BUFFER, 0, MESSAGE_LENGTH));

            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static void pongHandler(
        final AtomicBuffer buffer, final int offset, final int length, final int sessionId, final byte flags)
    {
        final long pingTimestamp = buffer.getLong(offset);
        final long rttNs = System.nanoTime() - pingTimestamp;

        HISTOGRAM.recordValue(rttNs);
        numPongsReceived++;
    }

    private static void runSubscriber(final Subscription pongSubscription, final int numMessages)
    {
        final IdleStrategy idleStrategy = new BusySpinIdleStrategy();

        numPongsReceived = 0;
        do
        {
            final int fragmentsRead = pongSubscription.poll(FRAGMENT_COUNT_LIMIT);
            idleStrategy.idle(fragmentsRead);
        }
        while (numPongsReceived < numMessages);
    }

    private static void newPongConnectionHandler(
        final String channel, final int streamId, final int sessionId, final String sourceInfo)
    {
        if (channel.equals(PONG_CHANNEL) && PONG_STREAM_ID == streamId)
        {
            PONG_CONNECTION_LATCH.countDown();
        }
    }
}
