/*
 * Copyright 2011 LMAX Ltd.
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
package com.lmax.disruptor;

import java.util.concurrent.TimeUnit;

import static com.lmax.disruptor.Util.ceilingNextPowerOfTwo;
import static com.lmax.disruptor.Util.getMinimumSequence;

/**
 * Ring based store of reusable entries containing the data representing an {@link AbstractEntry} being exchanged between producers and consumers.
 *
 * @param <T> AbstractEntry implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class RingBuffer<T extends AbstractEntry>
{
    /** Set to -1 as sequence starting point */
    public static final long INITIAL_CURSOR_VALUE = -1L;

    public long p1, p2, p3, p4, p5, p6, p7; // cache line padding
    private volatile long cursor = INITIAL_CURSOR_VALUE;
    public long p8, p9, p10, p11, p12, p13, p14; // cache line padding

    private final AbstractEntry[] entries;
    private final int ringModMask;

    private final ClaimStrategy claimStrategy;
    private final ClaimStrategy.Option claimStrategyOption;
    private final WaitStrategy waitStrategy;

    /**
     * Construct a RingBuffer with the full option set.
     *
     * @param entryFactory to create {@link AbstractEntry}s for filling the RingBuffer
     * @param size of the RingBuffer that will be rounded up to the next power of 2
     * @param claimStrategyOption threading strategy for producers claiming {@link AbstractEntry}s in the ring.
     * @param waitStrategyOption waiting strategy employed by consumers waiting on {@link AbstractEntry}s becoming available.
     */
    public RingBuffer(final EntryFactory<T> entryFactory, final int size,
                      final ClaimStrategy.Option claimStrategyOption,
                      final WaitStrategy.Option waitStrategyOption)
    {
        int sizeAsPowerOfTwo = ceilingNextPowerOfTwo(size);
        ringModMask = sizeAsPowerOfTwo - 1;
        entries = new AbstractEntry[sizeAsPowerOfTwo];

        this.claimStrategyOption = claimStrategyOption;
        claimStrategy = claimStrategyOption.newInstance();
        waitStrategy = waitStrategyOption.newInstance();

        fill(entryFactory);
    }

    /**
     * Construct a RingBuffer with default strategies of:
     * {@link ClaimStrategy.Option#MULTI_THREADED} and {@link WaitStrategy.Option#BLOCKING}
     *
     * @param entryFactory to create {@link AbstractEntry}s for filling the RingBuffer
     * @param size of the RingBuffer that will be rounded up to the next power of 2
     */
    public RingBuffer(final EntryFactory<T> entryFactory, final int size)
    {
        this(entryFactory, size,
             ClaimStrategy.Option.MULTI_THREADED,
             WaitStrategy.Option.BLOCKING);
    }

    /**
     * Create a {@link ConsumerBarrier} that gates on the RingBuffer and a list of {@link Consumer}s
     *
     * @param consumersToTrack this barrier will track
     * @return the barrier gated as required
     */
    public ConsumerBarrier<T> createConsumerBarrier(final Consumer... consumersToTrack)
    {
        return new ConsumerTrackingConsumerBarrier(consumersToTrack);
    }

    /**
     * Create a {@link ProducerBarrier} on this RingBuffer that tracks dependent {@link Consumer}s.
     *
     * @param consumersToTrack to be tracked to prevent wrapping.
     * @return a {@link ProducerBarrier} with the above configuration.
     */
    public ProducerBarrier<T> createProducerBarrier(final Consumer... consumersToTrack)
    {
        return new ConsumerTrackingProducerBarrier(consumersToTrack);
    }

    /**
     * Create a {@link ForceFillProducerBarrier} on this RingBuffer that tracks dependent {@link Consumer}s.
     * This barrier is to be used for filling a RingBuffer when no other producers exist.
     *
     * @param consumersToTrack to be tracked to prevent wrapping.
     * @return a {@link ForceFillProducerBarrier} with the above configuration.
     */
    public ForceFillProducerBarrier<T> createForceFillProducerBarrier(final Consumer... consumersToTrack)
    {
        return new ForceFillConsumerTrackingProducerBarrier(consumersToTrack);
    }

    /**
     * The capacity of the RingBuffer to hold entries.
     *
     * @return the size of the RingBuffer.
     */
    public int getCapacity()
    {
        return entries.length;
    }

    /**
     * Get the current sequence that producers have committed to the RingBuffer.
     *
     * @return the current committed sequence.
     */
    public long getCursor()
    {
        return cursor;
    }

    /**
     * Get the {@link AbstractEntry} for a given sequence in the RingBuffer.
     *
     * @param sequence for the {@link AbstractEntry}
     * @return {@link AbstractEntry} for the sequence
     */
    @SuppressWarnings("unchecked")
    public T getEntry(final long sequence)
    {
        return (T)entries[(int)sequence & ringModMask];
    }

    private void fill(final EntryFactory<T> entryFactory)
    {
        for (int i = 0; i < entries.length; i++)
        {
            entries[i] = entryFactory.create();
        }
    }

    /**
     * ConsumerBarrier handed out for gating consumers of the RingBuffer and dependent {@link Consumer}(s)
     */
    private final class ConsumerTrackingConsumerBarrier implements ConsumerBarrier<T>
    {
        private volatile boolean alerted = false;
        private final Consumer[] consumers;

        public ConsumerTrackingConsumerBarrier(final Consumer... consumers)
        {
            this.consumers = consumers;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T getEntry(final long sequence)
        {
            return (T)entries[(int)sequence & ringModMask];
        }

        @Override
        public long waitFor(final long sequence)
            throws AlertException, InterruptedException
        {
            return waitStrategy.waitFor(consumers, RingBuffer.this, this, sequence);
        }

        @Override
        public long waitFor(final long sequence, final long timeout, final TimeUnit units)
            throws AlertException, InterruptedException
        {
            return waitStrategy.waitFor(consumers, RingBuffer.this, this, sequence, timeout, units);
        }

        @Override
        public long getCursor()
        {
            return cursor;
        }

        @Override
        public boolean isAlerted()
        {
            return alerted;
        }

        @Override
        public void alert()
        {
            alerted = true;
            waitStrategy.signalAll();
        }

        @Override
        public void clearAlert()
        {
            alerted = false;
        }
    }

    /**
     * {@link ProducerBarrier} that tracks multiple {@link Consumer}s when trying to claim
     * an {@link AbstractEntry} in the {@link RingBuffer}.
     */
    private final class ConsumerTrackingProducerBarrier implements ProducerBarrier<T>
    {
        private final Consumer[] consumers;
        private long lastConsumerMinimum = RingBuffer.INITIAL_CURSOR_VALUE;

        public ConsumerTrackingProducerBarrier(final Consumer... consumers)
        {
            if (0 == consumers.length)
            {
                throw new IllegalArgumentException("There must be at least one Consumer to track for preventing ring wrap");
            }
            this.consumers = consumers;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T nextEntry()
        {
            final long sequence = claimStrategy.incrementAndGet();
            ensureConsumersAreInRange(sequence);

            AbstractEntry entry = entries[(int)sequence & ringModMask];
            entry.setSequence(sequence);

            return (T)entry;
        }

        @Override
        public void commit(final T entry)
        {
            commit(entry.getSequence(), 1);
        }

        @Override
        public SequenceBatch nextEntries(final SequenceBatch sequenceBatch)
        {
            final long sequence = claimStrategy.incrementAndGet(sequenceBatch.getSize());
            sequenceBatch.setEnd(sequence);
            ensureConsumersAreInRange(sequence);

            for (long i = sequenceBatch.getStart(), end = sequenceBatch.getEnd(); i <= end; i++)
            {
                AbstractEntry entry = entries[(int)i & ringModMask];
                entry.setSequence(i);
            }

            return sequenceBatch;
        }

        @Override
        public void commit(final SequenceBatch sequenceBatch)
        {
            commit(sequenceBatch.getEnd(), sequenceBatch.getSize());
        }

        @Override
        @SuppressWarnings("unchecked")
        public T getEntry(final long sequence)
        {
            return (T)entries[(int)sequence & ringModMask];
        }

        @Override
        public long getCursor()
        {
            return cursor;
        }

        private void ensureConsumersAreInRange(final long sequence)
        {
            final long wrapPoint = sequence - entries.length;
            while (wrapPoint > lastConsumerMinimum &&
                   wrapPoint > (lastConsumerMinimum = getMinimumSequence(consumers)))
            {
                Thread.yield();
            }
        }

        private void commit(final long sequence, final long batchSize)
        {
            if (ClaimStrategy.Option.MULTI_THREADED == claimStrategyOption)
            {
                final long expectedSequence = sequence - batchSize;
                while (expectedSequence != cursor)
                {
                    // busy spin
                }
            }

            cursor = sequence;
            waitStrategy.signalAll();
        }
    }

    /**
     * {@link ForceFillProducerBarrier} that tracks multiple {@link Consumer}s when trying to claim
     * a {@link AbstractEntry} in the {@link RingBuffer}.
     */
    private final class ForceFillConsumerTrackingProducerBarrier implements ForceFillProducerBarrier<T>
    {
        private final Consumer[] consumers;
        private long lastConsumerMinimum = RingBuffer.INITIAL_CURSOR_VALUE;

        public ForceFillConsumerTrackingProducerBarrier(final Consumer... consumers)
        {
            if (0 == consumers.length)
            {
                throw new IllegalArgumentException("There must be at least one Consumer to track for preventing ring wrap");
            }
            this.consumers = consumers;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T claimEntry(final long sequence)
        {
            ensureConsumersAreInRange(sequence);

            AbstractEntry entry = entries[(int)sequence & ringModMask];
            entry.setSequence(sequence);

            return (T)entry;
        }

        @Override
        public void commit(final T entry)
        {
            long sequence = entry.getSequence();
            claimStrategy.setSequence(sequence);
            cursor = sequence;
            waitStrategy.signalAll();
        }

        @Override
        public long getCursor()
        {
            return cursor;
        }

        private void ensureConsumersAreInRange(final long sequence)
        {
            final long wrapPoint = sequence - entries.length;
            while (wrapPoint > lastConsumerMinimum &&
                   wrapPoint > (lastConsumerMinimum = getMinimumSequence(consumers)))
            {
                Thread.yield();
            }
        }
    }
}
