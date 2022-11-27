package com.mordred.mylibrary;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.os.RemoteException;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.mordred.shelljava.ServerConnectionListener;
import com.mordred.shelljava.ShellJava;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private TextView textView = null;

    private ShellJava shellJava = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textView = findViewById(R.id.textView);
        textView.setHorizontallyScrolling(true);
        textView.setMovementMethod(new ScrollingMovementMethod());

        String secretKey = "oguzhan123";

        shellJava = new ShellJava<ExampleResultAIDL>(getApplicationContext(),
                ExampleService.class, secretKey) {
            @Override
            public void onResult(ExampleResultAIDL exampleResultAIDL) {
                try {
                    printToTextView("");

                    // print result from shell side
                    printToTextView("CODE EXECUTION RESULT (Shell Side):");
                    printToTextView(exampleResultAIDL.getResult());

                    printToTextView("");

                    // print result from normal application process side
                    printToTextView("CODE EXECUTION RESULT (Non-shell side):");
                    printToTextView("Process uid: " + android.os.Process.myUid());
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onError(String errMsg) {
                printToTextView("Error: " + errMsg);
            }
        };

        shellJava.setServerConnectionListener(new ServerConnectionListener() {
            @Override
            public void onServerConnected() {
                printToTextView("User initiated adb command and Server started");
            }

            @Override
            public void onServerDisconnected() {
                printToTextView("Server exited");
            }
        });

        if (shellJava.isServerRunning()) {
            printToTextView("Server is already running, No need to initiate command");
        } else {
            printToTextView("Server is not running, You need to initiate following command: "
                    + shellJava.getServerStartCommand());
        }

        Log.e(TAG, "command: " + shellJava.getServerStartCommand());

        Button button = findViewById(R.id.button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                shellJava.execute();
            }
        });

        Button button2 = findViewById(R.id.button2);
        button2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                printToTextView("");
                printToTextView("COMMAND EXECUTION RESULT: "
                        + ShellJava.executeShellCommand("ls", secretKey));
            }
        });

        Button button3 = findViewById(R.id.button3);
        button3.setOnClickListener(view -> shellJava.quitServer());
    }

    @Override
    protected void onDestroy() {
        shellJava.release();

        super.onDestroy();
    }

    private void printToTextView(final String msg) {
        runOnUiThread(() -> textView.append(msg + "\n"));
    }
}