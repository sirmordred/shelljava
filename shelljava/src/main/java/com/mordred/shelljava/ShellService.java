package com.mordred.shelljava;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;

import static com.mordred.shelljava.Constants.BROADCAST_ACTION;
import static com.mordred.shelljava.Constants.BROADCAST_BINDER;
import static com.mordred.shelljava.Constants.BROADCAST_EXTRA;
import static com.mordred.shelljava.Constants.CONNECTION_DEFAULT_TIMEOUT_MS;

public abstract class ShellService extends ContextWrapper {
    private static final String TAG = "ShellIPC";
    private String packageName;
    private IBinder userIPC;

    private final Object connectWaiter = new Object();
    private final Object disconnectWaiter = new Object();

    private final List<ShellIPCConnection> connections = new ArrayList<>();
    private volatile boolean connectionSeen = false;

    public IBinder executeInShell() { return null; }

    public ShellService() {
        super(null);
    }

    private final IBinder mainBinder = new IShellIPC.Stub() {
        @Override
        public void connect(IBinder self) {
            IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    ShellIPCConnection conn = getConnection(this);
                    if (conn != null) {
                        disconnect(conn.getBinder());
                    }
                }
            };
            try {
                self.linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                self = null;
            }

            if (self != null) {
                synchronized (connections) {
                    connections.add(new ShellIPCConnection(self, deathRecipient));
                    connectionSeen = true;
                }
                synchronized (connectWaiter) {
                    connectWaiter.notifyAll();
                }
            }
        }

        @Override
        public IBinder getUserIPC() {
            return userIPC;
        }

        @Override
        public void disconnect(IBinder self) {
            synchronized (connections) {
                ShellIPCConnection conn = getConnection(self);
                if (conn != null) {
                    try {
                        conn.getBinder().unlinkToDeath(conn.getDeathRecipient(), 0);
                    } catch (Exception e) {
                        // no action required
                    }
                    connections.remove(conn);
                }
            }
            synchronized (disconnectWaiter) {
                disconnectWaiter.notifyAll();
            }
        }
    };

    @Override
    protected final void attachBaseContext(Context base) {
        super.attachBaseContext(onAttach(Utils.getContextImpl(base)));
        IBinder resultBinder = executeInShell();
        if (resultBinder != null) {
            this.packageName = base.getPackageName();
            this.userIPC = resultBinder;
            broadcastIPC();

            synchronized (connectWaiter) {
                if (haveClientsDisconnected()) {
                    try {
                        connectWaiter.wait(CONNECTION_DEFAULT_TIMEOUT_MS);
                    } catch (InterruptedException e) {
                        // expected, do nothing
                    }
                }
                if (haveClientsDisconnected()) {
                    Log.e(TAG, "libshelljava: timeout waiting for IPC connection");
                    return;
                }
            }

            synchronized (disconnectWaiter) {
                while (!haveAllClientsDisconnected()) {
                    try {
                        disconnectWaiter.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    @NonNull
    protected Context onAttach(@NonNull Context base) {
        return base;
    }

    @Override
    public final Context getApplicationContext() {
        return Utils.getContext();
    }

    protected boolean haveClientsDisconnected() {
        synchronized (connections) {
            return connectionSeen;
        }
    }

    protected boolean haveAllClientsDisconnected() {
        synchronized (connections) {
            return connectionSeen && (getConnectionCount() == 0);
        }
    }

    protected void broadcastIPC() {
        Intent intent = new Intent();
        intent.setPackage(packageName);
        intent.setAction(BROADCAST_ACTION);
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        Bundle bundle = new Bundle();
        bundle.putBinder(BROADCAST_BINDER, mainBinder);
        intent.putExtra(BROADCAST_EXTRA, bundle);

        Utils.sendBroadcast(intent);
    }

    protected int getConnectionCount() {
        synchronized (connections) {
            pruneConnections();
            return connections.size();
        }
    }

    private void pruneConnections() {
        synchronized (connections) {
            if (connections.size() == 0) return;

            for (int i = connections.size() - 1; i >= 0; i--) {
                ShellIPCConnection conn = connections.get(i);
                if (!conn.getBinder().isBinderAlive()) {
                    connections.remove(i);
                }
            }

            if (!connectionSeen && (connections.size() > 0)) {
                connectionSeen = true;
                synchronized (connectWaiter) {
                    connectWaiter.notifyAll();
                }
            }

            if (connections.size() == 0) {
                synchronized (disconnectWaiter) {
                    disconnectWaiter.notifyAll();
                }
            }
        }
    }

    private ShellIPCConnection getConnection(IBinder binder) {
        synchronized (connections) {
            pruneConnections();
            for (ShellIPCConnection conn : connections) {
                if (conn.getBinder() == binder) {
                    return conn;
                }
            }
            return null;
        }
    }

    private ShellIPCConnection getConnection(IBinder.DeathRecipient deathRecipient) {
        synchronized (connections) {
            pruneConnections();
            for (ShellIPCConnection conn : connections) {
                if (conn.getDeathRecipient() == deathRecipient) {
                    return conn;
                }
            }
            return null;
        }
    }

    private static class ShellIPCConnection {
        private final IBinder binder;
        private final IBinder.DeathRecipient deathRecipient;

        public ShellIPCConnection(IBinder binder, IBinder.DeathRecipient deathRecipient) {
            this.binder = binder;
            this.deathRecipient = deathRecipient;
        }

        public IBinder getBinder() {
            return binder;
        }

        public IBinder.DeathRecipient getDeathRecipient() {
            return deathRecipient;
        }
    }
}