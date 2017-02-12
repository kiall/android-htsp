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
    private final Thread mConnectionThread;

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

        mConnectionThread = new Thread(mConnection);
    }

    public void start() {
        mConnectionThread.start();
    }

    public void closeConnection() {
        mConnection.closeConnection();
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
    public long sendMessage(@NonNull HtspMessage message) {
        return mMessageDispatcher.sendMessage(message);
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
        if (state == HtspConnection.State.FAILED) {
            // TODO: Implement a retry backoff
            Log.w(TAG, "HTSP Connection failed, reconnecting");
            start();
        }
    }
}
