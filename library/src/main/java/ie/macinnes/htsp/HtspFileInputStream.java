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

import android.support.annotation.NonNull;
import android.util.Log;
import android.util.LongSparseArray;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;


/**
 * Fetches a file over a HTSP Connection
 */
public class HtspFileInputStream extends InputStream {
    private static final String TAG = HtspFileInputStream.class.getSimpleName();

    private final HtspMessage.Dispatcher mDispatcher;
    private final String mFileName;

    private ByteBuffer mBuffer;
    private int mFileId = -1;
    private long mFileSize = -1;
    private long mFileOffset = 0;

    private final ArrayList<Long> mSequences = new ArrayList<>();
    private final LongSparseArray<Object> mSequenceLocks = new LongSparseArray<>();

    public HtspFileInputStream(@NonNull HtspMessage.Dispatcher dispatcher, String fileName) {
        mDispatcher = dispatcher;
        mFileName = fileName;

        sendFileOpen();
    }

    // InputStream Methods
    /**
     * Reads the next byte of data from the input stream. The value byte is
     * returned as an <code>int</code> in the range <code>0</code> to
     * <code>255</code>. If no byte is available because the end of the stream
     * has been reached, the value <code>-1</code> is returned. This method
     * blocks until input data is available, the end of the stream is detected,
     * or an exception is thrown.
     * <p>
     * <p> A subclass must provide an implementation of this method.
     *
     * @return the next byte of data, or <code>-1</code> if the end of the
     * stream is reached.
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public int read() throws IOException {
        if (mBuffer == null || !mBuffer.hasRemaining()) {
            sendFileRead(1024);
        }

        return mBuffer.get();
    }

    /**
     * Closes this stream and releases any system resources associated
     * with it. If the stream is already closed then invoking this
     * method has no effect.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        sendFileClose();
    }

    // Internal Methods
    private void sendFileOpen() {
        HtspMessage fileOpenRequest = new HtspMessage();

        fileOpenRequest.put("method", "fileOpen");
        fileOpenRequest.put("file", mFileName);

        HtspMessage fileOpenResponse = mDispatcher.sendMessage(fileOpenRequest, 5000);

        if (fileOpenResponse == null) {
            Log.e(TAG, "Failed to receive response to fileOpen request");
            return;
        }

        mFileId = fileOpenResponse.getInteger("id");

        if (fileOpenResponse.containsKey("size")) {
            // Size is optional
            mFileSize = fileOpenResponse.getLong("size");
        }
    }

    private void sendFileRead(long size) {
        sendFileRead(size, -1);
    }

    private void sendFileRead(long size, long offset) {
         long readSize;

        if (mFileSize != -1) {
            // Make sure we don't overrun the file
            if (offset == -1) {
                readSize = Math.min(mFileOffset + size, mFileSize);
                readSize = readSize - mFileOffset;

                // Store the new offset
                mFileOffset = mFileOffset + readSize;
            } else {
                readSize = Math.min(offset + size, mFileSize);
                readSize = readSize - offset;

                // Store the new offset
                mFileOffset = offset + readSize;
            }
        } else {
            // Since we don't know the size, we can't prevent requesting more data than exists
            readSize = size;
        }

        HtspMessage fileReadRequest = new HtspMessage();

        fileReadRequest.put("method", "fileRead");
        fileReadRequest.put("id", mFileId);
        fileReadRequest.put("size", readSize);

        if (offset != -1) {
            fileReadRequest.put("offset", offset);
        }

        HtspMessage fileReadResponse = mDispatcher.sendMessage(fileReadRequest, 5000);

        if (fileReadResponse == null) {
            Log.e(TAG, "Failed to receive response to fileRead request");
            return;
        }

        mBuffer = ByteBuffer.wrap(fileReadResponse.getByteArray("data"));
    }

    private void sendFileClose() {
        HtspMessage fileCloseRequest = new HtspMessage();

        fileCloseRequest.put("method", "fileClose");
        fileCloseRequest.put("id", mFileId);

        // We just go ahead and send the close, if it fails, oh well.
        mDispatcher.sendMessage(fileCloseRequest);
    }
}
