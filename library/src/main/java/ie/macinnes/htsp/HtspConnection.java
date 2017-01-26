package ie.macinnes.htsp;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class HtspConnection implements Runnable {
    private static final String TAG = HtspConnection.class.getName();

    private final String mHostname;
    private final int mPort;
    private final String mUsername;
    private final String mPassword;
    private final String mClientName;
    private final String mClientVersion;

    public interface ConnectionListener {
        void onConnectionStateChange(@NonNull State state);
    }

    public enum State {
        CLOSED,
        CONNECTING,
        CONNECTED,
        AUTHENTICATING,
        READY,
        CLOSING,
        FAILED
    }

    private State mState = State.CLOSED;

    protected Lock mLock;

    protected SocketChannel mSocketChannel;
    protected Selector mSelector;

    protected ByteBuffer mReadBuffer;

    private Handler mMainHandler = new Handler(Looper.getMainLooper());

    protected List<ConnectionListener> mConnectionListeners = new ArrayList<>();

    protected Queue<HtspMessage> mSendMessageQueue = new ConcurrentLinkedQueue<>();

    public HtspConnection(String hostname, int port, String username, String password, String clientName, String clientVersion) {
        mHostname = hostname;
        mPort = port;
        mUsername = username;
        mPassword = password;
        mClientName = clientName;
        mClientVersion = clientVersion;

        mLock = new ReentrantLock();
        mReadBuffer = ByteBuffer.allocate(1024000);
    }

    @Override
    public void run() {
        // Do the initial connection
        mLock.lock();
        try {
            openConnection();
            authenticate();
        } catch (Exception e) {
            Log.e(TAG, "Unhandled exception in HTSP Connection Thread, Shutting down", e);
            if (!isClosed()) {
                closeConnection(State.FAILED);
            }
            return;
        } finally {
            mLock.unlock();
        }

        // Main Loop
        while (getState() == State.READY) {
            try {
                mSelector.select();
            } catch (IOException e) {
                Log.e(TAG, "Failed to select from socket channel", e);
                closeConnection(State.FAILED);
                break;
            }

            if (mSelector == null || !mSelector.isOpen()) {
                break;
            }

            Set<SelectionKey> keys = mSelector.selectedKeys();
            Iterator<SelectionKey> i = keys.iterator();

            try {
                mLock.lock();
                try {
                    while (i.hasNext()) {
                        SelectionKey selectionKey = i.next();
                        i.remove();

                        if (!selectionKey.isValid()) {
                            break;
                        }

                        if (selectionKey.isValid() && selectionKey.isConnectable()) {
                            processConnectableSelectionKey();
                        }

                        if (selectionKey.isValid() && selectionKey.isReadable()) {
                            processReadableSelectionKey();
                        }

                        if (selectionKey.isValid() && selectionKey.isWritable()) {
                            processWritableSelectionKey();
                        }

                        if (isClosed()) {
                            break;
                        }
                    }

                    if (isClosed()) {
                        break;
                    }

                    if (mSocketChannel.isConnected() && mSendMessageQueue.isEmpty()) {
                        mSocketChannel.register(mSelector, SelectionKey.OP_READ);
                    } else if (mSocketChannel.isConnected()) {
                        mSocketChannel.register(mSelector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    }
                } finally {
                    mLock.unlock();
                }
            } catch (Exception e) {
                Log.e(TAG, "Something failed - shutting down", e);
                closeConnection(State.FAILED);
                break;
            }
        }

        mLock.lock();
        try {
            if (!isClosed()) {
                Log.e(TAG, "HTSP Connection thread wrapping up without already being closed");
                closeConnection(State.FAILED);
                return;
            }

            if (getState() == State.CLOSED) {
                Log.i(TAG, "HTSP Connection thread wrapped up cleanly");
            } else if (getState() == State.FAILED) {
                Log.e(TAG, "HTSP Connection thread wrapped up upon failure");
            } else {
                Log.e(TAG, "HTSP Connection thread wrapped up in an unexpected state: " + getState());
            }
        } finally {
            mLock.unlock();
        }
    }

    private void processConnectableSelectionKey() throws IOException {
        Log.v(TAG, "processConnectableSelectionKey()");

        if (mSocketChannel.isConnectionPending()) {
            mSocketChannel.finishConnect();
        }

        mSocketChannel.register(mSelector, SelectionKey.OP_READ);
    }

    private void processReadableSelectionKey() throws IOException {
        Log.v(TAG, "processReadableSelectionKey()");

        int bufferStartPosition = mReadBuffer.position();
        int bytesRead = this.mSocketChannel.read(mReadBuffer);

        Log.v(TAG, "Read " + bytesRead + " bytes.");

        int bytesToBeConsumed = bufferStartPosition + bytesRead;

        if (bytesRead == -1) {
            Log.e(TAG, "Failed to process readable selection key, read -1 bytes");
            closeConnection(State.FAILED);
            return;
        } else if (bytesRead > 0) {
            int bytesConsumed = -1;

//            while (mRunning && bytesConsumed != 0 && bytesToBeConsumed > 0) {
//                bytesConsumed = processMessage(bytesToBeConsumed);
//                bytesToBeConsumed = bytesToBeConsumed - bytesConsumed;
//            }
        }
    }

    private void processWritableSelectionKey() throws IOException {
        Log.v(TAG, "processWritableSelectionKey()");
//        HtspMessage htspMessage = mSendMessageQueue.poll();
//
//        if (!isClosed() && htspMessage != null) {
//            mSocketChannel.write(htspMessage.toWire());
//        }
    }

    public void addConnectionListener(ConnectionListener listener) {
        if (mConnectionListeners.contains(listener)) {
            Log.w(TAG, "Attempted to add duplicate connection listener");
            return;
        }
        mConnectionListeners.add(listener);
    }

    public boolean isClosed() {
        return getState() == State.CLOSED || getState() == State.FAILED;
    }

    public boolean isClosedOrClosing() {
        return isClosed() || getState() == State.CLOSING;
    }

    public State getState() {
        return mState;
    }

    private void setState(final State state) {
        mLock.lock();
        try {
            mState = state;
        } finally {
            mLock.unlock();
        }


        if (mConnectionListeners != null) {
            for (final ConnectionListener listener : mConnectionListeners) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onConnectionStateChange(state);
                    }
                });
            }
        }
    }

    private void openConnection() throws HtspException {
        Log.i(TAG, "Opening HTSP Connection");

        mLock.lock();
        try {
            if (!isClosed()) {
                throw new HtspException("Attempting to connect while already connected");
            }

            setState(State.CONNECTING);

            final Object openLock = new Object();

            try {
                mSocketChannel = SocketChannel.open();
                mSocketChannel.connect(new InetSocketAddress(mHostname, mPort));
                mSocketChannel.configureBlocking(false);
                mSelector = Selector.open();
            } catch (IOException e) {
                Log.e(TAG, "Caught IOException while opening SocketChannel: " + e.getLocalizedMessage());
                closeConnection(State.FAILED);
                throw new HtspException(e.getLocalizedMessage(), e);
            } catch (UnresolvedAddressException e) {
                Log.e(TAG, "Failed to resolve HTSP server address: " + e.getLocalizedMessage());
                closeConnection(State.FAILED);
                throw new HtspException(e.getLocalizedMessage(), e);
            }

            try {
                mSocketChannel.register(mSelector, SelectionKey.OP_CONNECT, openLock);
            } catch (ClosedChannelException e) {
                Log.e(TAG, "Failed to register selector, channel closed: " + e.getLocalizedMessage());
                closeConnection(State.FAILED);
                throw new HtspException(e.getLocalizedMessage(), e);
            }

            synchronized (openLock) {
                try {
                    openLock.wait(2000);
                    if (mSocketChannel.isConnectionPending()) {
                        Log.e(TAG, "Failed to register selector, timeout");
                        closeConnection(State.FAILED);
                        throw new HtspException("Timeout while registering selector");
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "Failed to register selector, interrupted");
                    closeConnection(State.FAILED);
                    throw new HtspException(e.getLocalizedMessage(), e);
                }
            }

            Log.i(TAG, "HTSP Connected");
            setState(State.CONNECTED);
        } finally {
            mLock.unlock();
        }
    }

    private void authenticate() {
        Log.i(TAG, "Authenticating HTSP Connection");

        mLock.lock();
        try {
            // TODO Build and send authentication yadda ydda
            setState(State.READY);
        } finally {
            mLock.unlock();
        }
    }

    public void closeConnection() {
        closeConnection(State.CLOSED);
    }

    private void closeConnection(State finalState) {
        Log.i(TAG, "Closing HTSP Connection");

        mLock.lock();
        try {
            if (isClosedOrClosing()) {
                Log.e(TAG, "Attempting to close while already closed, or closing");
                return;
            }

            setState(State.CLOSING);

            if (mSocketChannel != null) {
                try {
                    Log.i(TAG, "Calling SocketChannel close");
                    mSocketChannel.socket().close();
                    mSocketChannel.close();
                } catch (IOException e) {
                    Log.w(TAG, "Failed to close socket channel: " + e.getLocalizedMessage());
                } finally {
                    mSocketChannel = null;
                }
            }

            if (mSelector != null) {
                try {
                    Log.w(TAG, "Calling Selector close");
                    mSelector.close();
                } catch (IOException e) {
                    Log.w(TAG, "Failed to close socket channel: " + e.getLocalizedMessage());
                } finally {
                    mSelector = null;
                }
            }

            if (mReadBuffer != null) {
                // Wipe the read buffer
                mReadBuffer.clear();
                mReadBuffer = null;
            }

            setState(finalState);
        } finally {
            mLock.unlock();
        }
    }
}
