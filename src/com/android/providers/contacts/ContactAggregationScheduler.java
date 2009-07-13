/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.providers.contacts;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;

/**
 * A scheduler for asynchronous aggregation of contacts. Aggregation will start after
 * a short delay after it is scheduled, unless it is scheduled again, in which case the
 * aggregation pass is delayed.  There is an upper boundary on how long aggregation can
 * be delayed.
 */
public class ContactAggregationScheduler {

    public interface Aggregator {

        /**
         * Performs an aggregation run.
         */
        void run();

        /**
         * Interrupts aggregation.
         */
        void interrupt();
    }

    // Message ID used for communication with the aggregator
    private static final int START_AGGREGATION_MESSAGE_ID = 1;

    // Aggregation is delayed by this many milliseconds to allow changes to accumulate
    public static final int AGGREGATION_DELAY = 500;

    // Maximum delay of aggregation from the initial aggregation request
    public static final int MAX_AGGREGATION_DELAY = 5000;

    public static final int STATUS_STAND_BY = 0;
    public static final int STATUS_SCHEDULED = 1;
    public static final int STATUS_RUNNING = 2;

    private Aggregator mAggregator;

    // Aggregation status
    private int mStatus = STATUS_STAND_BY;

    // If true, we need to automatically reschedule aggregation after the current pass is done
    private boolean mRescheduleWhenComplete;

    // The time when aggregation was request for the first time. Reset when aggregation is completed
    private long mInitialRequestTimestamp;

    private HandlerThread mHandlerThread;
    private Handler mMessageHandler;

    public void setAggregator(Aggregator aggregator) {
        mAggregator = aggregator;
    }

    public void start() {
        mHandlerThread = new HandlerThread("ContactAggregator", Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mMessageHandler = new Handler(mHandlerThread.getLooper()) {

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case START_AGGREGATION_MESSAGE_ID:
                        run();
                        break;

                    default:
                        throw new IllegalStateException("Unhandled message: " + msg.what);
                }
            }
        };
    }

    public void stop() {
        mAggregator.interrupt();
        Looper looper = mHandlerThread.getLooper();
        if (looper != null) {
            looper.quit();
        }
    }

    /**
     * Schedules an aggregation pass after a short delay.
     */
    public synchronized void schedule() {

        switch (mStatus) {
            case STATUS_STAND_BY: {

                mInitialRequestTimestamp = currentTime();
                mStatus = STATUS_SCHEDULED;
                runDelayed();
                break;
            }

            case STATUS_SCHEDULED: {

                // If it has been less than MAX_AGGREGATION_DELAY millis since the initial request,
                // reschedule the request.
                if (currentTime() - mInitialRequestTimestamp < MAX_AGGREGATION_DELAY) {
                    runDelayed();
                }
                break;
            }

            case STATUS_RUNNING: {

                // If it has been less than MAX_AGGREGATION_DELAY millis since the initial request,
                // interrupt the current pass and reschedule the request.
                if (currentTime() - mInitialRequestTimestamp < MAX_AGGREGATION_DELAY) {
                    mAggregator.interrupt();
                }

                mRescheduleWhenComplete = true;
                break;
            }
        }
    }

    /**
     * Called just before an aggregation pass begins.
     */
    public void run() {
        synchronized (this) {
            mStatus = STATUS_RUNNING;
            mRescheduleWhenComplete = false;
        }
        try {
            mAggregator.run();
        } finally {
            synchronized (this) {
                mStatus = STATUS_STAND_BY;
                mInitialRequestTimestamp = 0;
                if (mRescheduleWhenComplete) {
                    mRescheduleWhenComplete = false;
                    schedule();
                }
            }
        }
    }

    /* package */ void runDelayed() {

        // If aggregation has already been requested, cancel the previous request
        mMessageHandler.removeMessages(START_AGGREGATION_MESSAGE_ID);

        // Schedule aggregation for AGGREGATION_DELAY milliseconds from now
        mMessageHandler.sendEmptyMessageDelayed(
                START_AGGREGATION_MESSAGE_ID, AGGREGATION_DELAY);
    }

    /* package */ long currentTime() {
        return System.currentTimeMillis();
    }
}