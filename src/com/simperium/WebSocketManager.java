/**
 * Used by Simperium to create a WebSocket connection to Simperium. Manages Channels
 * and listens for channel write events. Notifies channels when the connection is connected
 * or disconnected.
 *
 * WebSocketManager is configured by Simperium and shouldn't need to be access directly
 * by applications.
 */
package com.simperium;

import com.simperium.Simperium;
import com.codebutler.android_websockets.*;

import com.simperium.Bucket;
import com.simperium.Channel;
import java.net.URI;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Iterator;
import java.util.Set;
import java.util.HashMap;
import org.apache.http.message.BasicNameValuePair;

import java.util.Timer;
import java.util.TimerTask;
import java.net.ConnectException;

public class WebSocketManager implements WebSocketClient.Listener, Channel.Listener {
    
    private static final String WEBSOCKET_URL = "wss://api.simperium.com/sock/websocket";
    private static final String SOCKETIO_URL = "https://api.simperium.com/";
    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final String COMMAND_HEARTBEAT = "h";
    private String appId;
    private String clientId;
    private WebSocketClient socketClient;
    private boolean connected = false, reconnect = true;
    private HashMap<Channel,Integer> channelIndex = new HashMap<Channel,Integer>();;
    private HashMap<Integer,Channel> channels = new HashMap<Integer,Channel>();;
    
    static final long HEARTBEAT_INTERVAL = 20000; // 20 seconds
    static final long DEFAULT_RECONNECT_INTERVAL = 3000; // 3 seconds
    
    private Timer heartbeatTimer, reconnectTimer;
    private int heartbeatCount = 0;
    private long reconnectInterval = DEFAULT_RECONNECT_INTERVAL;
    
    public WebSocketManager(String appId){
        this.appId = appId;
        
        List<BasicNameValuePair> headers = Arrays.asList(
            new BasicNameValuePair(USER_AGENT_HEADER, Simperium.HTTP_USER_AGENT)
        );
        socketClient = new WebSocketClient(URI.create(WEBSOCKET_URL), this, headers);
    }
    /**
     * Creates a channel for the bucket. Starts the websocket connection if not connected
     *
     */
    public Channel createChannel(Bucket bucket, User user){
        // create a channel
        Channel channel = new Channel(appId, bucket, user, this);
        int channelId = channels.size();
        channelIndex.put(channel, channelId);
        channels.put(channelId, channel);
        // If we're not connected then connect, if we don't have a user
        // access token we'll have to hold off until the user does have one
        if (!isConnected() && user.hasAccessToken()) {
            connect();
        } else if (isConnected()){
            channel.onConnect();
        }
        return channel;
    }
    
    protected void connect(){
        // if we have channels, then connect, otherwise wait for a channel
        if (!isConnected() && !channels.isEmpty()) {
            Simperium.log(String.format("Connecting to %s", WEBSOCKET_URL));
            reconnect = true;
            socketClient.connect();
        }
    }
    
    protected void disconnect(){
        // disconnect the channel
        if (isConnected()) {
            Simperium.log("Disconnecting");
            // being told to disconnect so don't automatically reconnect
            reconnect = false;
            socketClient.disconnect();
        }
    }
    
    public boolean isConnected(){
        return connected;
    }
    
    public boolean getConnected(){
        return isConnected();
    }
    
    protected void setConnected(boolean connected){
        this.connected = connected;
    }
    
    private void notifyChannelsConnected(){
        Set<Channel> channelSet = channelIndex.keySet();
        Iterator<Channel> iterator = channelSet.iterator();
        while(iterator.hasNext()){
            Channel channel = iterator.next();
            channel.onConnect();
        }
    }
    
    private void notifyChannelsDisconnected(){
        Set<Channel> channelSet = channelIndex.keySet();
        Iterator<Channel> iterator = channelSet.iterator();
        while(iterator.hasNext()){
            Channel channel = iterator.next();
            channel.onDisconnect();
        }
    }
    
    private void cancelHeartbeat(){
        if(heartbeatTimer != null) heartbeatTimer.cancel();
    }
    
    private void scheduleHeartbeat(){
        cancelHeartbeat();
        heartbeatTimer = new Timer();
        heartbeatTimer.schedule(new TimerTask(){
            public void run(){
                sendHearbeat();
            }
        }, HEARTBEAT_INTERVAL);
    }
    
    private void sendHearbeat(){
        heartbeatCount ++;
        String command = String.format("%s:%d", COMMAND_HEARTBEAT, heartbeatCount);
        Simperium.log(String.format(" => %s", command));
        socketClient.send(command);
    }
    
    private void cancelReconnect(){
        if (reconnectTimer != null) reconnectTimer.cancel();
    }

    private void scheduleReconnect(){
        reconnectTimer = new Timer();
        // exponential backoff
        long retryIn = nextReconnectInterval();
        reconnectTimer.schedule(new TimerTask(){
            public void run(){
                connect();
            }
        }, retryIn);
        Simperium.log(String.format("Retrying in %d", retryIn));
    }
    
    // duplicating javascript reconnect interval calculation
    // doesn't do exponential backoff
    private long nextReconnectInterval(){
        long current = reconnectInterval;
        if (reconnectInterval < 4000) {
            reconnectInterval ++;
        } else {
            reconnectInterval = 15000;
        }
        return current;
    }

    /**
     *
     * Channel.Listener event listener
     *
     */
    public void onMessage(Channel.MessageEvent event){
        Channel channel = (Channel)event.getSource();
        Integer channelId = channelIndex.get(channel);
        // Prefix the message with the correct channel id
        String message = String.format("%d:%s", channelId, event.getMessage());
        Simperium.log(String.format(" => %s", message));
        socketClient.send(message);
    }
    /** 
     *
     * WebSocketClient.Listener methods for receiving status events from the socket
     *
     */
    public void onConnect(){
        Simperium.log(String.format("Connect %s", this));
        setConnected(true);
        notifyChannelsConnected();
        scheduleHeartbeat();
        cancelReconnect();
        reconnectInterval = DEFAULT_RECONNECT_INTERVAL;
    }
    public void onMessage(String message){
        scheduleHeartbeat();
        Simperium.log(String.format(" <= %s", message));
        String[] parts = message.split(":", 2);;
        if (parts[0].equals(COMMAND_HEARTBEAT)) {
            heartbeatCount = Integer.parseInt(parts[1]);
            return;
        }
        int channelId = Integer.parseInt(parts[0]);
        Channel channel = channels.get(channelId);
        channel.receiveMessage(parts[1]);
    }
    public void onMessage(byte[] data){
        Simperium.log(String.format("From socket (data) %s", new String(data)));
    }
    public void onDisconnect(int code, String reason){
        Simperium.log(String.format("Disconnect %d %s", code, reason));
        setConnected(false);
        notifyChannelsDisconnected();
        cancelHeartbeat();
        if(reconnect) scheduleReconnect();
    }
    public void onError(Exception error){
        Simperium.log(String.format("Error: %s", error));
        if (error.getClass().isAssignableFrom(java.io.IOException.class) && reconnect) {
            scheduleReconnect();
        }
        setConnected(false);
    }
    
}