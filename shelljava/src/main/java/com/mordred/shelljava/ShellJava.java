package com.mordred.shelljava;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.Socket;

import static com.mordred.shelljava.Constants.BROADCAST_ACTION;
import static com.mordred.shelljava.Constants.BROADCAST_ACTION_SERVER_STARTED;
import static com.mordred.shelljava.Constants.BROADCAST_ACTION_SERVER_STOPPED;
import static com.mordred.shelljava.Constants.BROADCAST_BINDER;
import static com.mordred.shelljava.Constants.BROADCAST_EXTRA;
import static com.mordred.shelljava.Constants.BROADCAST_SERVER_KEY;
import static com.mordred.shelljava.Constants.HOST;
import static com.mordred.shelljava.Constants.SERVER_TIMEOUT;

public abstract class ShellJava<T> {
    private static final String TAG = "ShellJava";

    public abstract void onResult(T ipc);
    public abstract void onError(String errMsg);

    private static HandlerThread handlerThread = null;
    private static Handler handler;

    private Class<T> clazz;
    private final IBinder self = new Binder();
    private final Object binderSync = new Object();
    private final Object eventSync = new Object();

    private volatile WeakReference<Context> context;
    private volatile IBinder binder = null;
    private volatile IShellIPC ipc = null;
    private volatile T userIPC = null;
    private volatile boolean inEvent = false;

    private String packageName = null;
    private String targetClassName = null;
    private String serverKey;
    private boolean immediatelyRun = false;
    private int serverPort;

    private String serverStartCommand;
    private boolean isServerRunning = false;

    private ServerConnectionListener serverConnectionListener = null;

    private final IBinder.DeathRecipient deathRecipient = () -> {
        synchronized (binderSync) {
            clearBinder();
            binderSync.notifyAll();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (intent.getAction() == null) {
                return;
            }
            if (intent.getAction().equals(BROADCAST_ACTION)) {
                Bundle bundle = intent.getBundleExtra(BROADCAST_EXTRA);
                IBinder receivedBinder = bundle.getBinder(BROADCAST_BINDER);
                if (receivedBinder != null) {
                    try {
                        receivedBinder.linkToDeath(deathRecipient, 0);
                    } catch (RemoteException e) {
                        receivedBinder = null;
                    }
                }

                if (receivedBinder == null) {
                    return;
                }

                synchronized (binderSync) {
                    binder = receivedBinder;
                    ipc = IShellIPC.Stub.asInterface(binder);
                    try {
                        userIPC = getInterfaceFromBinder(ipc.getUserIPC(), clazz);
                    } catch (RemoteException e) {
                        onError(e.getMessage());
                    }
                    try {
                        // we send over our own Binder that the other end can linkToDeath with
                        ipc.connect(self);

                        // schedule a call to doOnConnectRunnable so we stop blocking the receiver
                        handler.post(onConnectRunnable);
                    } catch (RemoteException e) {
                        onError(e.getMessage());
                    }
                    binderSync.notifyAll();
                }
            } else if (intent.getAction().equals(BROADCAST_ACTION_SERVER_STARTED)) {
                Bundle bundle = intent.getBundleExtra(BROADCAST_EXTRA);
                if (!bundle.getString(BROADCAST_SERVER_KEY).equals(serverKey)) {
                    return;
                }
                if (immediatelyRun) {
                    execute();
                }
                if (serverConnectionListener != null) {
                    serverConnectionListener.onServerConnected();
                }
            } else if (intent.getAction().equals(BROADCAST_ACTION_SERVER_STOPPED)) {
                Bundle bundle = intent.getBundleExtra(BROADCAST_EXTRA);
                if (!bundle.getString(BROADCAST_SERVER_KEY).equals(serverKey)) {
                    return;
                }
                if (serverConnectionListener != null) {
                    serverConnectionListener.onServerDisconnected();
                }
            }
        }
    };

    private final Runnable onConnectRunnable = () -> {
        synchronized (binderSync) {
            doOnConnect();
        }
    };

    public ShellJava(Context context, Class<?> targetClass,
                     String serverKey) {
        this(context, targetClass, serverKey, false);
    }

    public ShellJava(Context context, Class<?> targetClass,
                     String serverKey, boolean immediatelyRun) {
        if (context == null || targetClass == null
                || serverKey == null || serverKey.isEmpty()) {
            onError("ShellJava initialization failed, incorrect parameters");
            return;
        }

        this.context = new WeakReference<>(context);
        this.targetClassName = targetClass.getName();
        this.serverKey = serverKey;
        this.immediatelyRun = immediatelyRun;

        // init server
        initServer(context, serverKey);

        this.clazz = getTargetClassType();
        if (this.clazz == null) {
            return;
        }

        if (this.packageName == null) {
            this.packageName = context.getPackageName();
        }

        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }

        handlerThread = new HandlerThread("libshelljava:ShellJava#" + this.packageName);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(BROADCAST_ACTION);
        filter.addAction(BROADCAST_ACTION_SERVER_STARTED);
        filter.addAction(BROADCAST_ACTION_SERVER_STOPPED);
        context.registerReceiver(receiver, filter, null, handler);
    }

    @SuppressWarnings("unchecked")
    private Class<T> getTargetClassType() {
        Type superClass = getClass().getGenericSuperclass();
        ParameterizedType paramSuperClass = (ParameterizedType) superClass;
        if (paramSuperClass != null && paramSuperClass.getActualTypeArguments().length > 0) {
            Type tType = paramSuperClass.getActualTypeArguments()[0];
            return (Class<T>) tType;
        } else {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private T getInterfaceFromBinder(IBinder binderObj, Class<T> targetClazz) {
        try {
            Class<?> cStub = Class.forName(targetClazz.getName() + "$Stub");
            Field fDescriptor = cStub.getDeclaredField("DESCRIPTOR");
            fDescriptor.setAccessible(true);

            String descriptor = (String)fDescriptor.get(binderObj);
            IInterface intf = binderObj.queryLocalInterface(descriptor);
            if (targetClazz.isInstance(intf)) {
                // local
                return (T)intf;
            } else {
                // remote
                Class<?> cProxy = Class.forName(targetClazz.getName() + "$Stub$Proxy");
                Constructor<?> ctorProxy = cProxy.getDeclaredConstructor(IBinder.class);
                ctorProxy.setAccessible(true);
                return (T)ctorProxy.newInstance(binderObj);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while retrieving interface from binder", e);
        }
        return null;
    }

    private void doOnConnect() {
        // must be called inside synchronized(binderSync)
        if (binder == null || userIPC == null) {
            return;
        }
        synchronized (eventSync) {
            inEvent = true;
        }
        onResult(userIPC);
        synchronized (eventSync) {
            inEvent = false;
        }
        synchronized (eventSync) {
            disconnect();
        }
    }

    private void clearBinder() {
        // must be called inside synchronized(binderSync)
        if (binder != null) {
            try {
                binder.unlinkToDeath(deathRecipient, 0);
            } catch (Exception e) {
                // no action required
            }
        }
        binder = null;
        ipc = null;
        userIPC = null;
    }

    private void disconnect() {
        synchronized (binderSync) {
            if (ipc != null) {
                try {
                    ipc.disconnect(self);
                } catch (RemoteException e) {
                    // ignored
                }
            }
            clearBinder();
        }
    }

    public void release() {
        if (this.context != null) {
            Context context = this.context.get();
            if (context != null) {
                context.unregisterReceiver(receiver);
            }
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
    }

    public void execute() {
        if (this.packageName == null || this.targetClassName == null) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Socket client = new Socket(HOST, this.serverPort);
                client.setSoTimeout(SERVER_TIMEOUT);
                DataOutputStream os = new DataOutputStream(client.getOutputStream());
                os.writeUTF(this.serverKey);
                os.writeInt(Constants.ACTION_EXEC_CODE);
                os.writeUTF(this.packageName);
                os.writeUTF(this.targetClassName);
            } catch (Exception e) {
                // ignored
            }
        });

        thread.start();
    }

    // Note that this is blocking/syncronous call, TODO make it also async
    public static String executeShellCommand(String command, String serverSecretKey,
                                             boolean includeErrorStream) {
        StringBuilder response = new StringBuilder();
        if (command == null || command.isEmpty() || serverSecretKey == null) {
            return response.toString();
        }
        Thread thread = new Thread(() -> {
            try {
                int serverPort = Utils.getRunningServerPort(serverSecretKey);

                if (serverPort == -1) {
                    return;
                }
                Socket client = new Socket(HOST, serverPort);
                client.setSoTimeout(SERVER_TIMEOUT);
                DataOutputStream os = new DataOutputStream(client.getOutputStream());
                DataInputStream is = new DataInputStream(client.getInputStream());
                os.writeUTF(serverSecretKey);
                os.writeInt(Constants.ACTION_EXEC_CMD);
                os.writeUTF(command);
                os.writeBoolean(includeErrorStream);
                response.append(is.readUTF());
            } catch (Exception e) {
                // ignored
            }
        });

        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return response.toString();
    }

    public static String executeShellCommand(String command, String serverSecretKey) {
        return executeShellCommand(command, serverSecretKey, true);
    }

    public void quitServer() {
        Thread thread = new Thread(() -> {
            try {
                Socket socket = new Socket(HOST, this.serverPort);
                socket.setSoTimeout(SERVER_TIMEOUT);
                DataOutputStream os = new DataOutputStream(socket.getOutputStream());
                os.writeUTF(this.serverKey);
                os.writeInt(Constants.ACTION_STOP);
                os.writeBoolean(true);
            } catch (Exception e) {
                // ignored
            }
        });

        thread.start();
    }

    private void initServer(Context context, String serverSecretKey) {
        File file = new File(context.getExternalFilesDir(null), "start.sh");
        serverStartCommand = "adb shell sh " + file.getAbsolutePath();

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                serverPort = Utils.getRunningServerPort(serverSecretKey);

                if (serverPort != -1) {
                    // it means there is server running already, so no need to create one
                    isServerRunning = true;
                    return;
                }

                // it means there is no server running, so create one
                serverPort = Utils.getAvailablePort();

                if (serverPort == -1) {
                    onError("Error while searching for usable free port");
                    return;
                }

                if (file.exists()) {
                    file.delete();
                }
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    onError("Error while creating server script file: "
                            + e.getMessage());
                    return;
                }

                // copy lib
                String starterPath = context.getExternalFilesDir(null).getAbsolutePath()
                        + File.separator + "libshelljava.so";

                String[] supportedAbis = Build.SUPPORTED_ABIS;
                String mostPrefferedAbi = supportedAbis[0];
                switch (mostPrefferedAbi) {
                    case "armeabi-v7a":
                        Utils.copyStarterBinary(context, R.raw.libshelljava_arm, starterPath);
                        break;
                    case "arm64-v8a":
                        Utils.copyStarterBinary(context, R.raw.libshelljava_arm64, starterPath);
                        break;
                    case "x86":
                        Utils.copyStarterBinary(context, R.raw.libshelljava_x86, starterPath);
                        break;
                    case "x86_64":
                        Utils.copyStarterBinary(context, R.raw.libshelljava_x86_64, starterPath);
                        break;
                    default:
                        onError("Error while copying server starter binary, " +
                                "no preferred architecture has been found");
                        return;
                }

                String starterParam = starterParam(context);

                if (starterParam == null) {
                    onError("Error while creating server script, " +
                            "starterParam is null");
                    return;
                }

                String finalScript = Utils.getScript(starterPath, starterParam,
                        String.valueOf(serverPort), serverSecretKey);

                FileWriter fw;
                try {
                    fw = new FileWriter(file);
                } catch (IOException e) {
                    onError("Error while saving generated server script file: "
                            + e.getMessage());
                    return;
                }
                PrintWriter pw = new PrintWriter(fw);
                pw.print(finalScript);
                pw.flush();
                pw.close();
                try {
                    fw.flush();
                    fw.close();
                } catch (IOException e) {
                    // ignored
                }
            }
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            onError("Error while joining server init thread: "
                    + e.getMessage());
        }
    }

    private String starterParam(Context context) {
        try {
            return "--apk=" + context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0)
                    .publicSourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }


    public String getServerStartCommand() {
        return serverStartCommand;
    }

    public boolean isServerRunning() {
        return isServerRunning;
    }

    public void setServerConnectionListener(ServerConnectionListener serverConnectionListener) {
        this.serverConnectionListener = serverConnectionListener;
    }
}
