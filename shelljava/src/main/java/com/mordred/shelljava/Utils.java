package com.mordred.shelljava;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.Process;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;

import static com.mordred.shelljava.Constants.BROADCAST_ACTION_SERVER_STARTED;
import static com.mordred.shelljava.Constants.BROADCAST_ACTION_SERVER_STOPPED;
import static com.mordred.shelljava.Constants.BROADCAST_EXTRA;
import static com.mordred.shelljava.Constants.BROADCAST_SERVER_KEY;
import static com.mordred.shelljava.Constants.HOST;
import static com.mordred.shelljava.Constants.PORT_RANGE;

public class Utils {

    private static final String TAG = "Utils";
    private static final Object lock = new Object();

    private static Object oActivityManager = null;
    private static Integer FLAG_RECEIVER_FROM_SHELL = null;
    private static Method mBroadcastIntent = null;

    // TODO more correct version https://programmer.group/android-remote-shell-command-control-and-whitelist.html
    protected static String executeShellCmd(String commandStr, boolean includeErrStream) {
        StringBuilder outputMsg = new StringBuilder();

        if (commandStr == null || commandStr.isEmpty()) {
            return outputMsg.toString();
        }
        String[] commArr = new String[]{commandStr};


        Process process = null;
        BufferedReader successReader = null;
        BufferedReader errorReader = null;
        DataOutputStream os = null;

        try {
            process = Runtime.getRuntime().exec("sh");
            os = new DataOutputStream(process.getOutputStream());
            for (String command : commArr) {
                os.write(command.getBytes());
                os.writeBytes(System.lineSeparator());
                os.flush();
            }
            os.writeBytes("exit" + System.lineSeparator());
            os.flush();

            process.waitFor();

            successReader = new BufferedReader(new InputStreamReader(
                    process.getInputStream()));

            String lineStr;
            while ((lineStr = successReader.readLine()) != null) {
                outputMsg.append(lineStr).append(System.lineSeparator());
            }

            if (includeErrStream) {
                errorReader = new BufferedReader(new InputStreamReader(
                        process.getErrorStream()));

                String lineStrErr;
                while ((lineStrErr = errorReader.readLine()) != null) {
                    outputMsg.append(lineStrErr).append(System.lineSeparator());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (successReader != null) {
                    successReader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                if (errorReader != null) {
                    errorReader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (process != null) {
                process.destroy();
            }
        }

        return outputMsg.toString();
    }

    protected static String getScript(String starterPath, String starterParam,
                                      String serverPort, String serverKey) {
        String newLine = System.lineSeparator();
        return String.join(newLine,
                "#!/system/bin/sh",
                "SERVER_KEY=--key="+serverKey,
                "SERVER_PORT=--port="+serverPort,
                "STARTER_PATH="+starterPath,
                "STARTER_PARAM=\""+starterParam +"\"",
                "echo \"info: start.sh begin\"",
                "if [ -f \"$STARTER_PATH\" ]; then",
                "    rm /data/local/tmp/shelljava_starter 2> /dev/null",
                "    cp \"$STARTER_PATH\" /data/local/tmp/shelljava_starter",
                "    chmod 755 /data/local/tmp/shelljava_starter",
                "    export PATH=/data/local/tmp:/system/bin:$PATH",
                "    shelljava_starter $STARTER_PARAM $SERVER_KEY $SERVER_PORT $1",
                "    result=$?",
                "    if [ $result -ne 0 ]",
                "    then",
                "        echo \"shelljava_starter exit with non-zero value $result\"",
                "    else",
                "        echo \"shelljava_starter started successfully\"",
                "    fi",
                "else",
                "    echo \"Starter file not exist, try again.\"",
                "fi");
    }

    protected static void copyStarterBinary(Context context, int binaryResId, String outputPath) {
        InputStream in = null;
        FileOutputStream out = null;
        byte[] buff = new byte[1024];
        int read;
        try {
            in = context.getResources().openRawResource(binaryResId);
            out = new FileOutputStream(outputPath);

            while ((read = in.read(buff)) > 0) {
                out.write(buff, 0, read);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressLint("PrivateApi")
    @SuppressWarnings({"JavaReflectionMemberAccess"})
    private static Object getActivityManager() {
        synchronized (lock) {
            if (oActivityManager != null) {
                return oActivityManager;
            }

            try {
                Class<?> cActivityManagerNative = Class.forName("android.app.ActivityManagerNative");
                Method mGetDefault = cActivityManagerNative.getMethod("getDefault");
                oActivityManager = mGetDefault.invoke(null);
                return oActivityManager;
            } catch (Exception e) {
                // ignored
            }

            try {
                Class<?> cActivityManager = Class.forName("android.app.ActivityManager");
                Method mGetService = cActivityManager.getMethod("getService");
                oActivityManager = mGetService.invoke(null);
                return oActivityManager;
            } catch (Exception e) {
                e.printStackTrace();
            }

            return null;
        }
    }

    @SuppressWarnings({"JavaReflectionMemberAccess", "SoonBlockedPrivateApi"})
    private static int getFlagReceiverFromShell() {
        synchronized (lock) {
            if (FLAG_RECEIVER_FROM_SHELL != null) {
                return FLAG_RECEIVER_FROM_SHELL;
            }

            try {
                Field fFlagReceiverFromShell = Intent.class.getDeclaredField("FLAG_RECEIVER_FROM_SHELL");
                FLAG_RECEIVER_FROM_SHELL = fFlagReceiverFromShell.getInt(null);
                return FLAG_RECEIVER_FROM_SHELL;
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // ignored
            }

            FLAG_RECEIVER_FROM_SHELL = 0x00400000;
            return FLAG_RECEIVER_FROM_SHELL;
        }
    }


    private static Method getBroadcastIntent(Class<?> cActivityManager) {
        synchronized (lock) {
            if (mBroadcastIntent != null) {
                return mBroadcastIntent;
            }

            for (Method m : cActivityManager.getMethods()) {
                if (m.getName().equals("broadcastIntent")) {
                    if (m.getParameterTypes().length == 13) {
                        mBroadcastIntent = m;
                        return mBroadcastIntent;
                    } else if (m.getParameterTypes().length == 12) {
                        mBroadcastIntent = m;
                        return mBroadcastIntent;
                    }
                }
            }

            return null;
        }
    }

    @SuppressLint("PrivateApi")
    protected static void sendBroadcast(Intent intent) {
        try {
            intent.setFlags(getFlagReceiverFromShell());

            Object oActivityManager = getActivityManager();
            if (oActivityManager == null) {
                return;
            }
            Method mBroadcastIntent = getBroadcastIntent(oActivityManager.getClass());
            if (mBroadcastIntent == null) {
                return;
            }
            if (mBroadcastIntent.getParameterTypes().length == 13) {
                // API 24+
                mBroadcastIntent.invoke(oActivityManager, null, intent, null, null, 0,
                        null, null, null, -1, null, false, false, 0);
            } else if (mBroadcastIntent.getParameterTypes().length == 12) {
                // API 21+
                mBroadcastIntent.invoke(oActivityManager, null, intent, null, null, 0,
                        null, null, null, -1, false, false, 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected static void broadcastServerStart(String serverSecretKey) {
        Intent intent = new Intent();
        intent.setAction(BROADCAST_ACTION_SERVER_STARTED);
        Bundle bundle = new Bundle();
        bundle.putString(BROADCAST_SERVER_KEY, serverSecretKey);
        intent.putExtra(BROADCAST_EXTRA, bundle);
        Utils.sendBroadcast(intent);
    }

    protected static void broadcastServerStop(String serverSecretKey) {
        Intent intent = new Intent();
        intent.setAction(BROADCAST_ACTION_SERVER_STOPPED);
        Bundle bundle = new Bundle();
        bundle.putString(BROADCAST_SERVER_KEY, serverSecretKey);
        intent.putExtra(BROADCAST_EXTRA, bundle);
        Utils.sendBroadcast(intent);
    }

    @SuppressLint("PrivateApi")
    protected static Context getSystemContext() {
        try {
            Class<?> atClazz = Class.forName("android.app.ActivityThread");
            Method systemMain = atClazz.getMethod("systemMain");
            Object activityThread = systemMain.invoke(null);
            Method getSystemContext = atClazz.getMethod("getSystemContext");
            return (Context) getSystemContext.invoke(activityThread);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @SuppressLint("StaticFieldLeak")
    static Context context;

    @SuppressLint("PrivateApi")
    public static Context getContext() {
        if (context == null) {
            try {
                Context c = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication")
                        .invoke(null);
                context = getContextImpl(c);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return context;
    }

    public static Context getContextImpl(Context context) {
        while (context instanceof ContextWrapper) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context;
    }

    protected static int getAvailablePort() {
        for(int i = PORT_RANGE.first; i < PORT_RANGE.second; i++) {
            if (isPortAvailable(i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isPortAvailable(int port) {
        Socket s = null;
        try {
            s = new Socket(HOST, port);
            return false;
        } catch (IOException e) {
            return true;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    // ignored
                }
            }
        }
    }

    protected static int getRunningServerPort(String servKey) {
        for(int i = PORT_RANGE.first; i < PORT_RANGE.second; i++) {
            if (isPortAlreadyUsing(i)) {
                if (isServerRunning(i, servKey)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isPortAlreadyUsing(int port) {
        Socket s = null;
        try {
            s = new Socket(HOST, port);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    // ignored
                }
            }
        }
    }

    protected static boolean isServerRunning(int serverPort, String serverKey) {
        try {
            Socket socket = new Socket(HOST, serverPort);
            socket.setSoTimeout(250);
            DataOutputStream os = new DataOutputStream(socket.getOutputStream());
            DataInputStream is = new DataInputStream(socket.getInputStream());
            os.writeUTF(serverKey);
            os.writeInt(Constants.ACTION_CHECK_SERVER_RUNNING);
            return is.readBoolean();
        } catch (Exception e) {
            // ignored
            return false;
        }
    }
}
