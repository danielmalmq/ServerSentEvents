package se.miip.serversentevents;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Created by danielma on 2016-06-08.
 *
 * https://www.w3.org/TR/eventsource/
 */
public class ServerSentEventConnection {
    private static final String TAG = ServerSentEventConnection.class.getSimpleName();

    private String mUrl;
    private final Map<String, String> mHeaders;
    private final ServerSideEventCallback mCallback;
    private int mReconnectionDelay = 3000;
    private boolean mShouldReconnect = true;
    private HttpURLConnection sConnection;
    private String sLastEventId;
    private long mOverrideRetryTime = -1;
    private int mReadTimeout = 3600 * 1000;
    private int mConnectionTimeout = 10 * 1000;
    private int mMaxRedirects = 5;
    private final boolean mVerboseLogging;

    public ServerSentEventConnection(String url, @Nullable Map<String, String> headers, int reconnectionDelay, @NonNull ServerSideEventCallback callback) {
        this(url, headers, reconnectionDelay, callback, 3600 * 1000, 10 * 1000, false);
    }

    public ServerSentEventConnection(String url, @Nullable Map<String, String> headers, int reconnectionDelay, @NonNull ServerSideEventCallback callback, int readTimeout, int connectionTimeout, boolean verboseLogging) {
        mUrl = url;
        mHeaders = headers;
        mCallback = callback;
        mReconnectionDelay = reconnectionDelay;
        mReadTimeout = readTimeout;
        mConnectionTimeout = connectionTimeout;
        mVerboseLogging = verboseLogging;
    }

    private boolean reconnectExistingConnection() {
        try {
            final URL url;
            url = new URL(mUrl);
            sConnection = (HttpURLConnection) url.openConnection();

            sConnection.setRequestMethod("GET");
            sConnection.setRequestProperty("Accept", "text/event-stream");
            sConnection.setRequestProperty("Cache-Control", "no-cache");
            sConnection.setRequestProperty("Connection", "keep-alive");

            if (!TextUtils.isEmpty(sLastEventId)) {
                sConnection.setRequestProperty("Last-Event-ID", sLastEventId);
            }

            sConnection.setReadTimeout(mReadTimeout);
            sConnection.setConnectTimeout(mConnectionTimeout);

            if (mHeaders != null && !mHeaders.isEmpty()) {
                for (Map.Entry<String, String> entry : mHeaders.entrySet()) {
                    sConnection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (!mCallback.isAuthStillValid()) {
                return false;
            }

            sConnection.connect();
            int responseCode = sConnection.getResponseCode();
            if (mVerboseLogging) {
                Log.v(TAG, "Status code: " + responseCode);
            }

            // "Event stream requests can be redirected using HTTP 301 and 307 redirects as with normal HTTP requests."
            if (responseCode == 301 || responseCode == 307) {
                String redirectLocation = sConnection.getHeaderField("Location");
                sConnection.disconnect();
                sConnection = null;
                mMaxRedirects--;

                if (!TextUtils.isEmpty(redirectLocation) && mMaxRedirects > 0) {
                    mUrl = redirectLocation;
                    return true;
                }
                else {
                    mCallback.onConnectionError(responseCode);
                    return false;
                }
            }
            else if (responseCode != 204 && 200 <= responseCode && responseCode < 300) {
                long lastConnectionTime = System.currentTimeMillis();
                mCallback.onConnected();

                BufferedReader in = new BufferedReader(new InputStreamReader(sConnection.getInputStream()));
                String line;
                ServerSentMessage currentMessage = new ServerSentMessage();

                try {
                    while ((line = in.readLine()) != null) {
                        if (mVerboseLogging) {
                            Log.v(TAG, "Line: " + line);
                        }

                        if (line.isEmpty()) {
                            if (!TextUtils.isEmpty(currentMessage.getEvent())) {
                                mCallback.onNewMessage(currentMessage);
                            }
                            currentMessage = new ServerSentMessage();
                        }
                        else if (line.startsWith(":")) {
                            // Comment, ignore line
                        }
                        else {
                            int commaPlacement = line.indexOf(":");

                            String field = line.substring(0, commaPlacement).trim();
                            String value = "";
                            if (line.length() > commaPlacement) {
                                value = line.substring(commaPlacement+1).trim();
                            }

                            if (mVerboseLogging) {
                                Log.v(TAG, "Field: |" + field + "|");
                                Log.v(TAG, "Value: |" + value + "|");
                            }

                            switch (field) {
                                case "event":
                                    currentMessage.setEvent(value);
                                    break;
                                case "data":
                                    currentMessage.appendData(value);
                                    break;
                                case "id":
                                    sLastEventId = value;
                                    break;
                                case "retry":
                                    try {
                                        mOverrideRetryTime = Long.parseLong(value);
                                    } catch (NumberFormatException e) {
                                        e.printStackTrace();
                                    }
                                    break;
                            }
                        }
                    }
                } catch (EOFException e) {
                    mCallback.onTimedOut();
                }

                if (!TextUtils.isEmpty(currentMessage.getEvent())) {
                    mCallback.onNewMessage(currentMessage);
                }

                if (mShouldReconnect) {
                    long sleepTime = lastConnectionTime + mReconnectionDelay - System.currentTimeMillis();

                    if (mOverrideRetryTime > 0) {
                        sleepTime = mOverrideRetryTime;
                        mOverrideRetryTime = -1;
                    }

                    if (sleepTime > 0) {
                        if (mVerboseLogging) {
                            Log.v(TAG, "Sleeping for: " + sleepTime);
                        }
                        Thread.sleep(Math.min(sleepTime, mReconnectionDelay));
                    }

                    return true;
                }
                else {
                    sConnection.disconnect();
                    sConnection = null;
                }
            }
            else {
                // "a client can be told to stop reconnecting using the HTTP 204 No Content response code"
                if (responseCode == 204) {
                    mCallback.onStopRequest();
                }
                else {
                    mCallback.onConnectionError(responseCode);
                }

                sConnection.disconnect();
                sConnection = null;
            }
        } catch (Exception e) {
            e.printStackTrace();

            if (sConnection != null) {
                sConnection.disconnect();
            }
            sConnection = null;
        }
        return false;
    }

    public void connect(){
        reconnectIfNotConnected();
    }

    public void reconnectIfNotConnected() {
        setShouldReconnect(true);

        if (sConnection == null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (reconnectExistingConnection()) {
                            if (mVerboseLogging) {
                                Log.v(TAG, "Reconnecting");
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        sConnection = null;
                    }
                }
            }).start();
        }
    }

    public void setShouldReconnect(boolean shouldReconnect) {
        mShouldReconnect = shouldReconnect;
        if (mVerboseLogging) {
            Log.v(TAG, "setShouldReconnect " + shouldReconnect);
        }
    }

    public void dontReconnect() {
        mShouldReconnect = false;
    }

    public void disconnect() {
        mShouldReconnect = false;

        if (sConnection != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        sConnection.disconnect();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    sConnection = null;

                    if (mVerboseLogging) {
                        Log.d(TAG, "Disconnected");
                    }
                }
            }).start();
        }
    }

    public interface ServerSideEventCallback {
        void onNewMessage(ServerSentMessage serverSentMessage);
        void onConnectionError(int statusCode);
        void onConnected();
        void onStopRequest();
        boolean isAuthStillValid();
        void onTimedOut();
    }

    public static class SimpleServerSideEventCallback implements ServerSideEventCallback {

        @Override
        public void onNewMessage(ServerSentMessage serverSentMessage) {

        }

        @Override
        public void onConnectionError(int statusCode) {

        }

        @Override
        public void onConnected() {

        }

        @Override
        public void onStopRequest() {

        }

        @Override
        public boolean isAuthStillValid() {
            return true;
        }

        @Override
        public void onTimedOut() {

        }
    }

    public static class ServerSentMessage {
        private String mEvent;
        private String mData = "";

        public void setEvent(@NonNull String event) {
            mEvent = event;
        }

        public void appendData(@NonNull String data) {
            mData = data + "\n";
        }

        public String getEvent() {
            return mEvent;
        }

        public String getData() {
            return mData;
        }

        @Override
        public String toString() {
            return "ServerSentMessage{" +
                    "mEvent='" + mEvent + '\'' +
                    ", mData='" + mData + '\'' +
                    '}';
        }
    }
}
