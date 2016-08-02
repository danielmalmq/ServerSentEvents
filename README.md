# ServerSentEvents
Android ServerSentEvent lib

```
repositories {
  maven { url "https://jitpack.io" }
}

dependencies {
  compile 'com.github.danielmalmq:ServerSentEvents:1.0.0'
}
```


# Usage
```
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Map<String, String> extraHeaders = new HashMap<>();
    extraHeaders.put("X-API-KEY", "my key");

    sServerSentEventConnection = new ServerSentEventConnection("http://www.someServerEventStream.url", extraHeaders, 3000, new ServerSentEventConnection.SimpleServerSideEventCallback() {
        @Override
        public void onNewMessage(ServerSentEventConnection.ServerSentMessage serverSentMessage) {
            // Do something fancy with the message
        }
    });
}

@Override
protected void onResume() {
    super.onResume();
    sServerSentEventConnection.reconnectIfNotConnected();
}

@Override
protected void onPause() {
    super.onPause();
    sServerSentEventConnection.dontReconnect();
}
```
