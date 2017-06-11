/*
 * Copyright (c) 2017 Kiall Mac Innes <kiall@macinnes.ie>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ie.macinnes.htsp.tasks;

import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import ie.macinnes.htsp.HtspMessage;
import ie.macinnes.htsp.HtspNotConnectedException;

/**
 * Handles a Subscription on a HTSP Connection
 */
public class Subscriber implements HtspMessage.Listener, Authenticator.Listener {
    private static final String TAG = Subscriber.class.getSimpleName();

    private static final int INVALID_SUBSCRIPTION_ID = -1;
    private static final int INVALID_START_TIME = -1;
    private static final int STATS_INTERVAL = 10000;
    private static final int DEFAULT_TIMESHIFT_PERIOD = 0;

    // Copy of TvInputManager.TIME_SHIFT_INVALID_TIME, available on M+ Only.
    public static final long INVALID_TIMESHIFT_TIME = -9223372036854775808L;

    private static final Set<String> HANDLED_METHODS = new HashSet<>(Arrays.asList(new String[]{
            "subscriptionStart", "subscriptionStatus", "subscriptionStop",
            "queueStatus", "signalStatus", "timeshiftStatus", "muxpkt",
            "subscriptionSkip", "subscriptionSpeed",
            // "subscriptionGrace"
    }));

    private static final AtomicInteger mSubscriptionCount = new AtomicInteger();

    /**
     * A listener for Subscription events
     */
    public interface Listener {
        void onSubscriptionStart(@NonNull HtspMessage message);
        void onSubscriptionStatus(@NonNull HtspMessage message);
        void onSubscriptionStop(@NonNull HtspMessage message);
        void onSubscriptionSkip(@NonNull HtspMessage message);
        void onSubscriptionSpeed(@NonNull HtspMessage message);
        void onQueueStatus(@NonNull HtspMessage message);
        void onSignalStatus(@NonNull HtspMessage message);
        void onTimeshiftStatus(@NonNull HtspMessage message);
        void onMuxpkt(@NonNull HtspMessage message);
    }

    private final HtspMessage.Dispatcher mDispatcher;
    private final Set<Listener> mListeners = new CopyOnWriteArraySet<>();
    private final int mSubscriptionId;

    private Timer mTimer;
    private HtspMessage mQueueStatus;
    private HtspMessage mSignalStatus;
    private HtspMessage mTimeshiftStatus;

    private long mChannelId;
    private String mProfile;
    private int mTimeshiftPeriod = 0;
    private long mStartTime = INVALID_START_TIME;

    private boolean mIsSubscribed = false;

    public Subscriber(@NonNull HtspMessage.Dispatcher dispatcher) {
        mDispatcher = dispatcher;

        mSubscriptionId = mSubscriptionCount.incrementAndGet();
    }

    public void addSubscriptionListener(Listener listener) {
        if (mListeners.contains(listener)) {
            Log.w(TAG, "Attempted to add duplicate subscription listener");
            return;
        }
        mListeners.add(listener);
    }

    public void removeSubscriptionListener(Listener listener) {
        if (!mListeners.contains(listener)) {
            Log.w(TAG, "Attempted to remove non existing subscription listener");
            return;
        }
        mListeners.remove(listener);
    }

    public int getSubscriptionId() {
        return mSubscriptionId;
    }

    public void subscribe(long channelId) throws HtspNotConnectedException {
        subscribe(channelId, null, DEFAULT_TIMESHIFT_PERIOD);
    }

    public void subscribe(long channelId, String profile) throws HtspNotConnectedException {
        subscribe(channelId, profile, DEFAULT_TIMESHIFT_PERIOD);
    }

    public void subscribe(long channelId, int timeshiftPeriod) throws HtspNotConnectedException {
        subscribe(channelId, null, timeshiftPeriod);
    }

    public void subscribe(long channelId, String profile, int timeshiftPeriod) throws HtspNotConnectedException {
        Log.i(TAG, "Requesting subscription to channel " + mChannelId);

        if (!mIsSubscribed) {
            mDispatcher.addMessageListener(this);
        }

        mChannelId = channelId;
        mProfile = profile;

        HtspMessage subscribeRequest = new HtspMessage();

        subscribeRequest.put("method", "subscribe");
        subscribeRequest.put("subscriptionId", mSubscriptionId);
        subscribeRequest.put("channelId", channelId);
        subscribeRequest.put("timeshiftPeriod", timeshiftPeriod);

        if (mProfile != null) {
            subscribeRequest.put("profile", mProfile);
        }

        HtspMessage subscribeResponse = mDispatcher.sendMessage(subscribeRequest, 5000);

        mTimeshiftPeriod = subscribeResponse.getInteger("timeshiftPeriod", 0);
        Log.i(TAG, "Available timeshift period in seconds: " + mTimeshiftPeriod);

        mIsSubscribed = true;

        startTimer();
    }

    public void unsubscribe() {
        Log.i(TAG, "Requesting unsubscription from channel " + mChannelId);

        cancelTimer();

        mIsSubscribed = false;

        mDispatcher.removeMessageListener(this);

        HtspMessage unsubscribeRequest = new HtspMessage();

        unsubscribeRequest.put("method", "unsubscribe");
        unsubscribeRequest.put("subscriptionId", mSubscriptionId);

        try {
            mDispatcher.sendMessage(unsubscribeRequest);
        } catch (HtspNotConnectedException e) {
            // Ignore: If we're not connected, TVHeadend has already unsubscribed us
        }
    }

    public void setSpeed(int speed) {
        Log.i(TAG, "Requesting speed " + speed + " for channel " + mChannelId);

        HtspMessage subscriptionSpeedRequest = new HtspMessage();

        subscriptionSpeedRequest.put("method", "subscriptionSpeed");
        subscriptionSpeedRequest.put("subscriptionId", mSubscriptionId);
        subscriptionSpeedRequest.put("speed", speed);

        try {
            mDispatcher.sendMessage(subscriptionSpeedRequest);
        } catch (HtspNotConnectedException e) {
            // Ignore: If we're not connected, TVHeadend has already unsubscribed us
        }
    }

    public void pause() {
        setSpeed(0);
    }

    public void resume() {
        setSpeed(100);
    }

    public void skip(long time) {
        Log.i(TAG, "Requesting skip for channel " + mChannelId);

        HtspMessage subscriptionSkipRequest = new HtspMessage();

        subscriptionSkipRequest.put("method", "subscriptionSkip");
        subscriptionSkipRequest.put("subscriptionId", mSubscriptionId);
        subscriptionSkipRequest.put("time", time);
        subscriptionSkipRequest.put("absolute", 1);

        try {
            mDispatcher.sendMessage(subscriptionSkipRequest);
        } catch (HtspNotConnectedException e) {
            // Ignore: If we're not connected, TVHeadend has already unsubscribed us
        }
    }

    public void live() {
        Log.i(TAG, "Requesting live for channel " + mChannelId);

        HtspMessage subscriptionLiveRequest = new HtspMessage();

        subscriptionLiveRequest.put("method", "subscriptionLive");
        subscriptionLiveRequest.put("subscriptionId", mSubscriptionId);

        try {
            mDispatcher.sendMessage(subscriptionLiveRequest);
        } catch (HtspNotConnectedException e) {
            // Ignore: If we're not connected, TVHeadend has already unsubscribed us
        }
    }

    public long getTimeshiftOffsetPts() {
        if (mTimeshiftStatus != null) {
            return mTimeshiftStatus.getLong("shift") * -1;
        }

        return INVALID_TIMESHIFT_TIME;
    }

    public long getTimeshiftStartPts() {
        if (mTimeshiftStatus != null) {
            return mTimeshiftStatus.getLong("start", INVALID_TIMESHIFT_TIME);
        }

        return INVALID_TIMESHIFT_TIME;
    }

    public long getTimeshiftStartTime() {
        long startPts = getTimeshiftStartPts();

        if (startPts == INVALID_TIMESHIFT_TIME || mStartTime == INVALID_START_TIME) {
            return INVALID_TIMESHIFT_TIME;
        }

        return mStartTime + startPts;
    }

    @Override
    public Handler getHandler() {
        return null;
    }

    // HtspMessage.Listener Methods
    @Override
    public void onMessage(@NonNull HtspMessage message) {
        final String method = message.getString("method", null);

        if (HANDLED_METHODS.contains(method)) {
            final int subscriptionId = message.getInteger("subscriptionId", INVALID_SUBSCRIPTION_ID);

            if (subscriptionId != mSubscriptionId) {
                // This message relates to a different subscription, don't handle it
                return;
            }

            switch (method) {
                case "subscriptionStart":
                    onSubscriptionStart(message);
                    for (final Listener listener : mListeners) {
                        listener.onSubscriptionStart(message);
                    }
                    break;
                case "subscriptionStatus":
                    onSubscriptionStatus(message);
                    for (final Listener listener : mListeners) {
                        listener.onSubscriptionStatus(message);
                    }
                    break;
                case "subscriptionStop":
                    onSubscriptionStop(message);
                    for (final Listener listener : mListeners) {
                        listener.onSubscriptionStop(message);
                    }
                    break;
                case "subscriptionSkip":
                    for (final Listener listener : mListeners) {
                        listener.onSubscriptionSkip(message);
                    }
                    break;
                case "subscriptionSpeed":
                    for (final Listener listener : mListeners) {
                        listener.onSubscriptionSpeed(message);
                    }
                    break;
                case "queueStatus":
                    onQueueStatus(message);
                    for (final Listener listener : mListeners) {
                        listener.onQueueStatus(message);
                    }
                    break;
                case "signalStatus":
                    onSignalStatus(message);
                    for (final Listener listener : mListeners) {
                        listener.onSignalStatus(message);
                    }
                    break;
                case "timeshiftStatus":
                    onTimeshiftStatus(message);
                    for (final Listener listener : mListeners) {
                        listener.onTimeshiftStatus(message);
                    }
                    break;
                case "muxpkt":
                    for (final Listener listener : mListeners) {
                        listener.onMuxpkt(message);
                    }
                    break;
            }
        }
    }

    // Authenticator.Listener Methods
    @Override
    public void onAuthenticationStateChange(@NonNull Authenticator.State state) {
        if (mIsSubscribed && state == Authenticator.State.AUTHENTICATED) {
            Log.w(TAG, "Resubscribing to channel " + mChannelId);
            try {
                subscribe(mChannelId, mProfile, mTimeshiftPeriod);
            } catch (HtspNotConnectedException e) {
                Log.e(TAG, "Resubscribing to channel failed, not connected");
            }
        }
    }

    // Misc Internal Methods
    private void onSubscriptionStart(@NonNull HtspMessage message) {
        // TODO: -1000 is a total hack, we're running this about 500ms after the actual start time..
        mStartTime = (System.currentTimeMillis() * 1000) - 1000;
    }

    private void onSubscriptionStatus(@NonNull HtspMessage message) {
        final int subscriptionId = message.getInteger("subscriptionId");
        final String status = message.getString("status", null);
        final String subscriptionError = message.getString("subscriptionError", null);

        if (status != null || subscriptionError != null) {
            StringBuilder builder = new StringBuilder()
                    .append("Subscription Status:")
                    .append(" S: ").append(subscriptionId);

            if (status != null) {
                builder.append(" Status: ").append(status);
            }

            if (subscriptionError != null) {
                builder.append(" Error: ").append(subscriptionError);
            }

            Log.w(TAG, builder.toString());
        }
    }

    private void onSubscriptionStop(@NonNull HtspMessage message) {
        cancelTimer();
    }

    private void onQueueStatus(@NonNull HtspMessage message) {
        mQueueStatus = message;
    }

    private void onSignalStatus(@NonNull HtspMessage message) {
        mSignalStatus = message;
    }

    private void onTimeshiftStatus(@NonNull HtspMessage message) {
        mTimeshiftStatus = message;
    }

    private void startTimer() {
        cancelTimer();
        mTimer = new Timer();
        mTimer.scheduleAtFixedRate(new StatsTimerTask(), STATS_INTERVAL, STATS_INTERVAL);
    }

    private void cancelTimer() {
        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
            mTimer = null;
        }
    }

    private class StatsTimerTask extends TimerTask {
        @Override
        public void run() {
            if (mQueueStatus != null) {
                try {
                    logQueueStatus();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to log queue status", e);
                }
            }

            if (mSignalStatus != null) {
                try {
                    logSignalStatus();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to log signal status", e);
                }
            }

            if (mTimeshiftStatus != null) {
                try {
                    logTimeshiftStatus();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to log timeshift status", e);
                }
            }
        }

        private void logQueueStatus() {
            final int subscriptionId = mQueueStatus.getInteger("subscriptionId");
            final int packets = mQueueStatus.getInteger("packets");
            final int bytes = mQueueStatus.getInteger("bytes");
            final int errors = mQueueStatus.getInteger("errors", 0);
            final long delay = mQueueStatus.getLong("delay");
            final int bDrops = mQueueStatus.getInteger("Bdrops");
            final int pDrops = mQueueStatus.getInteger("Pdrops");
            final int iDrops = mQueueStatus.getInteger("Idrops");

            StringBuilder builder = new StringBuilder()
                    .append("Queue Status:")
                    .append(" S: ").append(subscriptionId)
                    .append(" P: ").append(packets)
                    .append(" B: ").append(bytes)
                    .append(" E: ").append(errors)
                    .append(" D: ").append(delay)
                    .append(" bD: ").append(bDrops)
                    .append(" pD: ").append(pDrops)
                    .append(" iD: ").append(iDrops);

            Log.i(TAG, builder.toString());
        }

        private void logSignalStatus() {
            final int subscriptionId = mSignalStatus.getInteger("subscriptionId");
            final String feStatus = mSignalStatus.getString("feStatus");
            final int feSNR = mSignalStatus.getInteger("feSNR", -1);
            final int feSignal = mSignalStatus.getInteger("feSignal", -1);
            final int feBER = mSignalStatus.getInteger("feBER", -1);
            final int feUNC = mSignalStatus.getInteger("feUNC", -1);

            StringBuilder builder = new StringBuilder()
                    .append("Signal Status:")
                    .append(" S: ").append(subscriptionId)
                    .append(" feStatus: ").append(feStatus);

            if (feSNR != -1) {
                builder.append(" feSNR: ").append(feSNR);
            }

            if (feSignal != -1) {
                builder.append(" feSignal: ").append(feSignal);
            }

            if (feBER != -1) {
                builder.append(" feBER: ").append(feBER);
            }

            if (feUNC != -1) {
                builder.append(" feUNC: ").append(feUNC);
            }

            Log.i(TAG, builder.toString());
        }

        private void logTimeshiftStatus() {
            final int subscriptionId = mTimeshiftStatus.getInteger("subscriptionId");
            final int full = mTimeshiftStatus.getInteger("full");
            final long shift = mTimeshiftStatus.getLong("shift");
            final long start = mTimeshiftStatus.getLong("start", -1);
            final long end = mTimeshiftStatus.getLong("end", -1);

            StringBuilder builder = new StringBuilder()
                    .append("Timeshift Status:")
                    .append(" S: ").append(subscriptionId)
                    .append(" full: ").append(full)
                    .append(" shift: ").append(shift);

            if (start != -1) {
                builder.append(" start: ").append(start);
            }

            if (end != -1) {
                builder.append(" end: ").append(end);
            }

            Log.i(TAG, builder.toString());
        }
    }
}
