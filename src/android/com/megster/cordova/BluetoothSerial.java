package com.megster.cordova;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
// kludgy imports to support 2.9 and 3.0 due to package changes
import org.apache.cordova.*;
import org.apache.cordova.api.*;
// import org.apache.cordova.CordovaArgs;
// import org.apache.cordova.CordovaPlugin;
// import org.apache.cordova.CallbackContext;
// import org.apache.cordova.PluginResult;
// import org.apache.cordova.LOG;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.Override;
import java.lang.Runnable;
import java.util.Set;

/**
 * PhoneGap Plugin for Serial Communication over Bluetooth
 */
public class BluetoothSerial extends CordovaPlugin {
    //constant
    private static final int REQUEST_ENABLE_BT = 1;

    // actions
    private static final String LIST = "list";
    private static final String SCAN = "scan";
    private static final String CONNECT = "connect";
    private static final String CONNECT_INSECURE = "connectInsecure";
    private static final String DISCONNECT = "disconnect";
    private static final String WRITE = "write";
    private static final String AVAILABLE = "available";
    private static final String READ = "read";
    private static final String READ_UNTIL = "readUntil";
    private static final String SUBSCRIBE = "subscribe";
    private static final String UNSUBSCRIBE = "unsubscribe";
    private static final String IS_ENABLED = "isEnabled";
    private static final String IS_CONNECTED = "isConnected";
    private static final String IS_SCANNING = "isScanning";
    private static final String CLEAR = "clear";

    //connection filter and device list
    private IntentFilter filter;
    private final JSONArray scanDeviceList = new JSONArray();

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        String endSignal = BluetoothAdapter.ACTION_DISCOVERY_FINISHED;
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                JSONObject tmp = new JSONObject();
                tmp.put("name", device.getName());
                tmp.put("address", device.getAddress());
                tmp.put("rssi", intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE));
                scanDeviceList.put(tmp);
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                dataAvailableCallback.success(scanDeviceList);
            }
        }
    };

    // callbacks
    private CallbackContext connectCallback;
    private CallbackContext dataAvailableCallback;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSerialService bluetoothSerialService;

    // Debugging
    private static final String TAG = "BluetoothSerial";
    private static final boolean D = true;

    // Message types sent from the BluetoothSerialService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    StringBuffer buffer = new StringBuffer();
    private String delimiter;

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {

        LOG.d(TAG, "action = " + action);

        if (bluetoothAdapter == null) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        if (bluetoothSerialService == null) {
            bluetoothSerialService = new BluetoothSerialService(mHandler);
        }

        filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(btReceiver, filter);

        boolean validAction = true;
        
        if (action.equals(LIST)) {

            listBondedDevices(callbackContext);

        } else if (action.equals(SCAN)){

            scan(args, callbackContext);

        } else if (action.equals(CONNECT)) {

            boolean secure = true;
            connect(args, secure, callbackContext);

        } else if (action.equals(CONNECT_INSECURE)) {

            // see Android docs about Insecure RFCOMM http://goo.gl/1mFjZY
            boolean secure = false;
            connect(args, false, callbackContext);

        } else if (action.equals(DISCONNECT)) {

            connectCallback = null;
            bluetoothSerialService.stop();
            callbackContext.success();

        } else if (action.equals(WRITE)) {

            String data = args.getString(0);
            bluetoothSerialService.write(data.getBytes());
            callbackContext.success();

        } else if (action.equals(AVAILABLE)) {

            callbackContext.success(available());

        } else if (action.equals(READ)) {

            callbackContext.success(read());

        } else if (action.equals(READ_UNTIL)) {

            String interesting = args.getString(0);
            callbackContext.success(readUntil(interesting));

        } else if (action.equals(SUBSCRIBE)) {

            delimiter = args.getString(0);
            dataAvailableCallback = callbackContext;

            PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
            result.setKeepCallback(true);
            callbackContext.sendPluginResult(result);

        } else if (action.equals(UNSUBSCRIBE)) {

            delimiter = null;
            dataAvailableCallback = null;

            callbackContext.success();

        } else if (action.equals(IS_ENABLED)) {

            if (bluetoothAdapter.isEnabled()) {
                callbackContext.success();                
            } else {
                callbackContext.error("Bluetooth is disabled.");
            }            

        } else if (action.equals(IS_CONNECTED)) {
            
            if (bluetoothSerialService.getState() == BluetoothSerialService.STATE_CONNECTED) {
                callbackContext.success();                
            } else {
                callbackContext.error("Not connected.");
            }

        } else if (action.equals(IS_SCANNING)) {
            if (bluetoothAdapter.getScanMode() != bluetoothAdapter.STATE_ON) {
                callbackContext.success();
            } else {
                callbackContext.error("Actually free");
            }
        } else if (action.equals(CLEAR)) {

            buffer.setLength(0);
            callbackContext.success();

        } else {

            validAction = false;

        }

        return validAction;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bluetoothSerialService != null) {
            bluetoothSerialService.stop();
        }
    }

    private void listBondedDevices(CallbackContext callbackContext) throws JSONException {
        JSONArray deviceList = new JSONArray();
        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : bondedDevices) {
            //Intent intent = new Intent(device.ACTION_FOUND);
            JSONObject json = new JSONObject();
            json.put("name", device.getName());
            json.put("address", device.getAddress());
            json.put("id", device.getAddress());
            if (device.getBluetoothClass() != null) {
                json.put("class", device.getBluetoothClass().getDeviceClass());
            }
            json.put("rssi", device.EXTRA_RSSI);
            deviceList.put(json);
        }
        callbackContext.success(deviceList);
    }

    private void scan(CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        if (bluetoothAdapter.isEnabled() && !bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.startDiscovery();
            dataAvailableCallback = callbackContext;
            /*final Handler scanHandler = new Handler();
            final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(BluetoothDevice bluetoothDevice, int rssi, byte[] bytes) {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("name", bluetoothDevice.getName());
                        json.put("address", bluetoothDevice.getAddress());
                        json.put("id", bluetoothDevice.getAddress());
                        if (bluetoothDevice.getBluetoothClass() != null) {
                            json.put("class", bluetoothDevice.getBluetoothClass().getDeviceClass());
                        }
                        json.put("rssi", rssi);
                        deviceList.put(json);
                        callbackContext.success(deviceList);
                    } catch (JSONException e) {
                        callbackContext.error(e.getMessage());
                    }
                }
            };

            final Runnable startScan = new Runnable() {
                @Override
                public void run() {
                    bluetoothAdapter.startLeScan(leScanCallback);
                    scanHandler.postDelayed(stopScan, 5000);
                }
            };
            final Runnable stopScan = new Runnable() {
                @Override
                public void run() {
                    bluetoothAdapter.stopLeScan(leScanCallback);
                    scanHandler.postDelayed(startScan, 10000);
                }
            };
            startScan.run();*/
        } else {
            Intent enableBt = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBt, REQUEST_ENABLE_BT);
            callbackContext.error("Turn on your bluetooth device please");
        }
    }

    private void connect(CordovaArgs args, boolean secure, CallbackContext callbackContext) throws JSONException {
        String macAddress = args.getString(0);
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);

        if (device != null) {
            connectCallback = callbackContext;
            bluetoothSerialService.connect(device, secure);

            PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
            result.setKeepCallback(true);
            callbackContext.sendPluginResult(result);

        } else {
            callbackContext.error("Could not connect to " + macAddress);
        }
    }

    // The Handler that gets information back from the BluetoothSerialService
    // Original code used handler for the because it was talking to the UI.
    // Consider replacing with normal callbacks
    private final Handler mHandler = new Handler() {

         public void handleMessage(Message msg) {
             switch (msg.what) {
                 case MESSAGE_READ:
                    buffer.append((String)msg.obj);

                    if (dataAvailableCallback != null) {
                        sendDataToSubscriber();
                    }
                    break;
                 case MESSAGE_STATE_CHANGE:

                    if(D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                    switch (msg.arg1) {
                        case BluetoothSerialService.STATE_CONNECTED:
                            Log.i(TAG, "BluetoothSerialService.STATE_CONNECTED");
                            notifyConnectionSuccess();
                            break;
                        case BluetoothSerialService.STATE_CONNECTING:
                            Log.i(TAG, "BluetoothSerialService.STATE_CONNECTING");
                            break;
                        case BluetoothSerialService.STATE_LISTEN:
                            Log.i(TAG, "BluetoothSerialService.STATE_LISTEN");
                            break;
                        case BluetoothSerialService.STATE_NONE:
                            Log.i(TAG, "BluetoothSerialService.STATE_NONE");
                            break;
                    }
                    break;
                case MESSAGE_WRITE:
                    //  byte[] writeBuf = (byte[]) msg.obj;
                    //  String writeMessage = new String(writeBuf);
                    //  Log.i(TAG, "Wrote: " + writeMessage);
                    break;
                case MESSAGE_DEVICE_NAME:
                    Log.i(TAG, msg.getData().getString(DEVICE_NAME));
                    break;
                case MESSAGE_TOAST:
                    String message = msg.getData().getString(TOAST);
                    notifyConnectionLost(message);
                    break;
             }
         }
    };

    private void notifyConnectionLost(String error) {
        if (connectCallback != null) {
            connectCallback.error(error);
            connectCallback = null;
        }
    }

    private void notifyConnectionSuccess() {
        if (connectCallback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK);
            result.setKeepCallback(true);
            connectCallback.sendPluginResult(result);
        }
    }

    private void sendDataToSubscriber() {
        String data = readUntil(delimiter);
        if (data != null && data.length() > 0) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, data);
            result.setKeepCallback(true);
            dataAvailableCallback.sendPluginResult(result);

            sendDataToSubscriber();
        }
    }

    private int available() {
        return buffer.length();
    }

    private String read() {
        int length = buffer.length();
        String data = buffer.substring(0, length);
        buffer.delete(0, length);
        return data;
    }

    private String readUntil(String c) {
        String data = "";
        int index = buffer.indexOf(c, 0);
        if (index > -1) {
            data = buffer.substring(0, index + c.length());
            buffer.delete(0, index + c.length());
        }
        return data;
    }
}
