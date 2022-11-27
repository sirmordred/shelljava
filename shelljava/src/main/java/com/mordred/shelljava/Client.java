package com.mordred.shelljava;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;


import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

class Client implements Runnable {

    private final Handler mHandler;
    private final ServerSocket mServerSocket;
    private final String mServerKey;
    private static final Method attachBaseContext;

    static {
        try {
            attachBaseContext = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attachBaseContext.setAccessible(true);
        } catch (Exception e) {
            // Shall not happen!
            throw new RuntimeException(e);
        }
    }

    Client(Handler handler, ServerSocket serverSocket, String serverKey) {
        mHandler = handler;
        mServerSocket = serverSocket;
        mServerKey = serverKey;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        for (; ; ) {
            try {
                Socket socket = mServerSocket.accept();
                boolean quit = false;
                DataInputStream is = new DataInputStream(socket.getInputStream());
                DataOutputStream os = new DataOutputStream(socket.getOutputStream());
                String serverSecretKey = is.readUTF();
                if (serverSecretKey.equals(mServerKey)) {
                    int action = is.readInt();
                    switch (action) {
                        case Constants.ACTION_EXEC_CODE:
                            // execute code with shell privilege here
                            String pkgName = is.readUTF();
                            String className = is.readUTF();

                            if (pkgName == null || className == null) {
                                break;
                            }

                            if (Looper.myLooper() == null) {
                                Looper.prepare();
                            }

                            Context systemContext = Utils.getSystemContext();
                            Context context = systemContext.createPackageContext(pkgName,
                                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                            ContextWrapper cw = new ContextWrapper(context);

                            // Use classloader from the package context to run everything
                            ClassLoader cl = context.getClassLoader();
                            Class<?> clz = cl.loadClass(className);
                            Constructor<?> ctor = clz.getDeclaredConstructor();
                            ctor.setAccessible(true);
                            attachBaseContext.invoke(ctor.newInstance(), cw);
                            if (Looper.myLooper() != null) {
                                Looper.myLooper().quit();
                            }
                            break;
                        case Constants.ACTION_EXEC_CMD:
                            // execute shell command with uid==2000 here
                            String shellCmd = is.readUTF();
                            boolean includeErrStream = is.readBoolean();
                            String cmdResult = Utils.executeShellCmd(shellCmd, includeErrStream);
                            os.writeUTF(cmdResult);
                            break;
                        case Constants.ACTION_CHECK_SERVER_RUNNING:
                            os.writeBoolean(true);
                            break;
                        case Constants.ACTION_STOP:
                            // broadcast server stop
                            boolean shouldBroadcastStop = is.readBoolean();
                            if (shouldBroadcastStop) {
                                Utils.broadcastServerStop(mServerKey);
                            }
                            quit = true;
                            break;
                        default:
                            break;
                    }
                }

                is.close();
                os.flush();
                os.close();

                socket.close();

                if (quit) {
                    break;
                }
            } catch (IOException e) {
                if (SocketException.class.equals(e.getClass()) && "Socket closed".equals(e.getMessage())) {
                    Log.i("SocketThread","server socket is closed");
                    break;
                }
            } catch (Exception e) {
                Log.i("SocketThread", "Exception", e);
            }
        }
        mHandler.sendEmptyMessage(Server.MESSAGE_EXIT);
        try {
            mServerSocket.close();
        } catch (IOException ignored) {
        }
    }
}
