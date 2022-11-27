// IRootIPC.aidl
package com.mordred.shelljava;

// Declare any non-default types here with import statements

interface IShellIPC {
    void connect(IBinder self);
    IBinder getUserIPC();
    void disconnect(IBinder self);
}
