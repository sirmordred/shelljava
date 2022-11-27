package com.mordred.mylibrary;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import com.mordred.shelljava.ShellService;

import androidx.core.content.ContextCompat;

public class ExampleService extends ShellService {

    @Override
    public IBinder executeInShell() {
        StringBuilder sb = new StringBuilder();
        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            sb.append("READ_SMS permission is granted\n");
        } else {
            sb.append("READ_SMS permission is not granted\n");
        }

        int myProcessUid = android.os.Process.myUid();
        sb.append("Process uid: ").append(myProcessUid);

        return new ExampleResultAIDL.Stub() {
            @Override
            public String getResult() {
                return sb.toString();
            }
        };
    }
}
