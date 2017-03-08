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
import java.util.concurrent.atomic.AtomicInteger;

import ie.macinnes.htsp.HtspMessage;
import ie.macinnes.htsp.HtspNotConnectedException;


/**
 * Handles a Subscription on a HTSP Connection
 */
public class Subscriber implements HtspMessage.Listener, Authenticator.Listener {
    private static final String TAG = Subscriber.class.getSimpleName();

    private static final Set<String> HANDLED_METHODS = new HashSet<>(Arrays.asList(new String[]{
            "subscriptionStart", "subscriptionStatus", "subscriptionStop",
            "muxpkt",

            // "subscriptionGrace", "subscriptionSkip", "subscriptionSpeed",
            // "queueStatus", "signalStatus", "timeshiftStatus"
    }));

    private static final AtomicInteger mSubscriptionCount = new AtomicInteger();

    /**
     * A listener for Subscription events
     */
    public interface Listener {
        void onSubscriptionStart(@NonNull HtspMessage message);
        void onSubscriptionStatus(@NonNull HtspMessage message);
        void onSubscriptionStop(@NonNull HtspMessage message);
        void onMuxpkt(@NonNull HtspMessage message);
    }

    private final HtspMessage.Dispatcher mDispatcher;
    private final Listener mListener;
    private final int mSubscriptionId;

    private long mChannelId;
    private String mProfile;

    private boolean mIsSubscribed = false;

    public Subscriber(@NonNull HtspMessage.Dispatcher dispatcher, @NonNull Listener listener) {
        mDispatcher = dispatcher;
        mListener = listener;

        mSubscriptionId = mSubscriptionCount.getAndIncrement();
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
    }

    public void unsubscribe() {
        Log.i(TAG, "Requesting unsubscription from channel " + mChannelId);

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
            if (method.equals("subscriptionStart")) {
                mListener.onSubscriptionStart(message);
            } else if (method.equals("subscriptionStatus")) {
                mListener.onSubscriptionStatus(message);
            } else if (method.equals("subscriptionStop")) {
                mListener.onSubscriptionStop(message);
            } else if (method.equals("muxpkt")) {
                mListener.onMuxpkt(message);
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
}
