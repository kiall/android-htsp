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
package ie.macinnes.htsp;

import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;

import ie.macinnes.htsp.tasks.Authenticator;

public class SimpleHtspConnection implements HtspMessage.Dispatcher, HtspConnection.Listener {
    private static final String TAG = SimpleHtspConnection.class.getSimpleName();

    private final HtspMessageSerializer mMessageSerializer;
    private final HtspMessageDispatcher mMessageDispatcher;
    private final HtspDataHandler mDataHandler;
    private final HtspConnection.ConnectionDetails mConnectionDetails;
    private final Authenticator mAuthenticator;

    private final HtspConnection mConnection;
    private Thread mConnectionThread;

    private boolean mEnableReconnect = false;
    private int mRetryCount = 0;
    private int mRetryDelay = 0;

    public SimpleHtspConnection(HtspConnection.ConnectionDetails connectionDetails) {
        mConnectionDetails = connectionDetails;

        mMessageSerializer = new HtspMessageSerializer();
        mMessageDispatcher = new HtspMessageDispatcher();

        mDataHandler = new HtspDataHandler(
                mMessageSerializer, mMessageDispatcher);

        mAuthenticator = new Authenticator(
                mMessageDispatcher, mConnectionDetails);

        mConnection = new HtspConnection(
                mConnectionDetails, mDataHandler, mDataHandler);
        mConnection.addConnectionListener(this);
        mConnection.addConnectionListener(mMessageDispatcher);
        mConnection.addConnectionListener(mDataHandler);
        mConnection.addConnectionListener(mAuthenticator);
    }

    public void start() {
        start(true);
    }

    private void start(boolean allowRestart) {
        if (mConnectionThread != null) {
            Log.w(TAG, "SimpleHtspConnection already started");
            return;
        }

        if (allowRestart) {
            mEnableReconnect = true;
        }

        mConnectionThread = new Thread(mConnection);
        mConnectionThread.start();
    }

    private void restart() {
        if (mConnectionThread != null) {
            stop(false);
        }

        start(false);
    }

    public void stop() {
        stop(true);
    }

    private void stop(boolean preventRestart) {
        if (mConnectionThread == null) {
            Log.w(TAG, "SimpleHtspConnection not started");
            return;
        }

        if (preventRestart) {
            mEnableReconnect = false;
        }

        mConnection.closeConnection();
        mConnectionThread.interrupt();
        try {
            mConnectionThread.join();
        } catch (InterruptedException e) {
            // Ignore.
        }
        mConnectionThread = null;
    }

    public HtspMessageDispatcher getMessageDispatcher() {
        return mMessageDispatcher;
    }

    public boolean isClosed() {
        return mConnection.isClosed();
    }

    public boolean isClosedOrClosing() {
        return mConnection.isClosedOrClosing();
    }

    public void addConnectionListener(HtspConnection.Listener listener) {
        mConnection.addConnectionListener(listener);
    }

    public void removeConnectionListener(HtspConnection.Listener listener) {
        mConnection.removeConnectionListener(listener);
    }

    public void addAuthenticationListener(Authenticator.Listener listener) {
        mAuthenticator.addAuthenticationListener(listener);
    }

    public void removeAuthenticationListener(Authenticator.Listener listener) {
        mAuthenticator.removeAuthenticationListener(listener);
    }

    @Override
    public void addMessageListener(HtspMessage.Listener listener) {
        mMessageDispatcher.addMessageListener(listener);
    }

    @Override
    public void removeMessageListener(HtspMessage.Listener listener) {
        mMessageDispatcher.removeMessageListener(listener);
    }

    @Override
    public long sendMessage(@NonNull HtspMessage message) throws HtspNotConnectedException {
        return mMessageDispatcher.sendMessage(message);
    }

    @Override
    public HtspMessage sendMessage(@NonNull HtspMessage message, int timeout) throws HtspNotConnectedException {
        return mMessageDispatcher.sendMessage(message, timeout);
    }

    @Override
    public Handler getHandler() {
        return null;
    }

    @Override
    public void setConnection(@NonNull HtspConnection connection) {}

    @Override
    public void onConnectionStateChange(@NonNull HtspConnection.State state) {
        // Simple HTSP Connections will take care of reconnecting upon failure for you..
        if (mEnableReconnect && state == HtspConnection.State.FAILED) {
            Log.w(TAG, "HTSP Connection failed, reconnecting in " + mRetryDelay + " milliseconds");

            try {
                Thread.sleep(mRetryDelay);
            } catch (InterruptedException e) {
                // Ignore
            }

            mRetryCount += 1;
            mRetryDelay = Math.min(mRetryCount * 100, 3000);

            restart();
        } else if (state == HtspConnection.State.CONNECTED) {
            // Reset our retry counter and delay back to zero
            mRetryCount = 0;
            mRetryDelay = 0;
        }
    }
}
