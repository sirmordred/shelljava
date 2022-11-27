package com.mordred.shelljava;

import android.util.Pair;

import java.net.InetAddress;

public class Constants {
    protected static final int ACTION_EXEC_CMD = 123;
    protected static final int ACTION_EXEC_CODE = 124;
    protected static final int ACTION_STOP = 125;
    protected static final int ACTION_CHECK_SERVER_RUNNING = 126;
    protected static final Pair<Integer, Integer> PORT_RANGE = new Pair<Integer, Integer>(55608, 55708);;
    protected static final InetAddress HOST = InetAddress.getLoopbackAddress();
    protected static final int SERVER_TIMEOUT = 10000;
    protected static final String LIBRARY_PKG_NAME = "com.mordred.shelljava";

    protected static final String BROADCAST_ACTION = LIBRARY_PKG_NAME + ".intent.BROADCAST";
    protected static final String BROADCAST_EXTRA = LIBRARY_PKG_NAME + ".intent.BROADCAST.EXTRA";
    protected static final String BROADCAST_BINDER = "binder";
    protected static final String BROADCAST_SERVER_KEY = "server_key";

    protected static final String BROADCAST_ACTION_SERVER_STARTED = LIBRARY_PKG_NAME + ".intent.SERVER_STARTED";
    protected static final String BROADCAST_ACTION_SERVER_STOPPED = LIBRARY_PKG_NAME + ".intent.SERVER_STOPPED";

    public static final int CONNECTION_DEFAULT_TIMEOUT_MS = 30 * 1000;
}
