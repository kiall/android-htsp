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
import java.util.concurrent.atomic.AtomicInteger;

import ie.macinnes.htsp.HtspMessage;
import ie.macinnes.htsp.HtspNotConnectedException;


/**
 * Handles a Subscription on a HTSP Connection
 */
public class Subscriber implements HtspMessage.Listener, Authenticator.Listener {
    private static final String TAG = Subscriber.class.getSimpleName();
    private static final int STATS_INTERVAL = 10000;

    private static final Set<String> HANDLED_METHODS = new HashSet<>(Arrays.asList(new String[]{
            "subscriptionStart", "subscriptionStatus", "subscriptionStop",
            "queueStatus", "signalStatus", "muxpkt",

            // "subscriptionGrace", "subscriptionSkip", "subscriptionSpeed",
            // "signalStatus", "timeshiftStatus"
    }));

    private static final AtomicInteger mSubscriptionCount = new AtomicInteger();

    /**
     * A listener for Subscription events
     */
    public interface Listener {
        void onSubscriptionStart(@NonNull HtspMessage message);
        void onSubscriptionStatus(@NonNull HtspMessage message);
        void onSubscriptionStop(@NonNull HtspMessage message);
        void onQueueStatus(@NonNull HtspMessage message);
        void onSignalStatus(@NonNull HtspMessage message);
        void onMuxpkt(@NonNull HtspMessage message);
    }

    private final HtspMessage.Dispatcher mDispatcher;
    private final Listener mListener;
    private final int mSubscriptionId;

    private final Timer mTimer;
    private HtspMessage mQueueStatus;
    private HtspMessage mSignalStatus;

    private long mChannelId;
    private String mProfile;

    private boolean mIsSubscribed = false;

    public Subscriber(@NonNull HtspMessage.Dispatcher dispatcher, @NonNull Listener listener) {
        mDispatcher = dispatcher;
        mListener = listener;

        mSubscriptionId = mSubscriptionCount.incrementAndGet();
        mTimer = new Timer();
    }

    public void subscribe(long channelId) throws HtspNotConnectedException {
        subscribe(channelId, null);
    }

    public void subscribe(long channelId, String profile) throws HtspNotConnectedException {
        Log.i(TAG, "Requesting subscription to channel " + mChannelId);

        if (!mIsSubscribed) {
            mDispatcher.addMessageListener(this);
        }

        HtspMessage subscribeRequest = new HtspMessage();

        subscribeRequest.put("method", "subscribe");
        subscribeRequest.put("subscriptionId", mSubscriptionId);
        subscribeRequest.put("channelId", channelId);

        if (mProfile != null) {
            subscribeRequest.put("profile", mProfile);
        }

        mDispatcher.sendMessage(subscribeRequest);

        mChannelId = channelId;
        mProfile = profile;

        mIsSubscribed = true;

        mTimer.scheduleAtFixedRate(new StatsTimerTask(), STATS_INTERVAL, STATS_INTERVAL);
    }

    public void unsubscribe() {
        Log.i(TAG, "Requesting unsubscription from channel " + mChannelId);

        mTimer.cancel();
        mTimer.purge();

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

    @Override
    public Handler getHandler() {
        return null;
    }

    // HtspMessage.Listener Methods
    @Override
    public void onMessage(@NonNull HtspMessage message) {
        final String method = message.getString("method", null);

        if (HANDLED_METHODS.contains(method)) {
            final int subscriptionId = message.getInteger("subscriptionId");

            if (subscriptionId != mSubscriptionId) {
                // This message relates to a different subscription, don't handle it
                return;
            }

            switch (method) {
                case "subscriptionStart":
                    mListener.onSubscriptionStart(message);
                    break;
                case "subscriptionStatus":
                    onSubscriptionStatus(message);
                    mListener.onSubscriptionStatus(message);
                    break;
                case "subscriptionStop":
                    mListener.onSubscriptionStop(message);
                    break;
                case "queueStatus":
                    onQueueStatus(message);
                    mListener.onQueueStatus(message);
                    break;
                case "signalStatus":
                    onSignalStatus(message);
                    mListener.onSignalStatus(message);
                    break;
                case "muxpkt":
                    mListener.onMuxpkt(message);
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
                subscribe(mChannelId, mProfile);
            } catch (HtspNotConnectedException e) {
                Log.e(TAG, "Resubscribing to channel failed, not connected");
            }
        }
    }

    // Misc Internal Methods
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

    private void onQueueStatus(@NonNull HtspMessage message) {
        mQueueStatus = message;
    }

    private void onSignalStatus(@NonNull HtspMessage message) {
        mSignalStatus = message;
    }

    private class StatsTimerTask extends TimerTask {
        @Override
        public void run() {
            if (mQueueStatus != null) {
                logQueueStatus();
            }

            if (mSignalStatus != null) {
                logSignalStatus();
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
    }
}
