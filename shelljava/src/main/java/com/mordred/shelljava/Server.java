package com.mordred.shelljava;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;

import static com.mordred.shelljava.Constants.HOST;
import static com.mordred.shelljava.Constants.SERVER_TIMEOUT;
import static com.mordred.shelljava.Utils.broadcastServerStart;

public class Server extends Handler {

    public static void main(String[] args) throws IOException, InterruptedException {
        setOut();

        if (args.length < 2) {
            System.exit(1);
            return;
        }

        String server_key = args[0];
        int server_port = Integer.parseInt(args[1]);

        System.out.println("shelljava: Server started");

        Looper.prepare();

        Server server = new Server();
        System.out.flush();
        if (!server.start(server_port, server_key)) {
            System.exit(1);
            return;
        }

        Looper.loop();

        System.out.println("shelljava: Server exited");
        System.exit(0);
    }

    private static void setOut() throws IOException {
        File file = new File("/data/local/tmp/shelljava_server.log");
        if (!file.exists()) {
            file.createNewFile();
        }
        PrintStream os = new PrintStream(file);

        System.setOut(os);
        System.setErr(os);

    }

    public static final int MESSAGE_EXIT = 1;

    private Server() {
        super();
    }

    @Override
    public void handleMessage(Message msg) {
        if (msg.what == MESSAGE_EXIT) {
            Looper.myLooper().quit();
        }
    }

    private boolean sendQuit(String key, int port, boolean shouldBroadcastStop) {
        try {
            Socket socket = new Socket(HOST, port);
            socket.setSoTimeout(SERVER_TIMEOUT);
            DataOutputStream os = new DataOutputStream(socket.getOutputStream());
            DataInputStream is = new DataInputStream(socket.getInputStream());
            os.writeUTF(key);
            os.writeInt(Constants.ACTION_STOP);
            os.writeBoolean(shouldBroadcastStop);
            is.close();
            os.flush();
            os.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean start(int port, String key) throws IOException, InterruptedException {
        if (sendQuit(key, port,false)) {
            Thread.sleep(500);
        }

        ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(port, 0, HOST);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        serverSocket.setReuseAddress(true);

        Client clientSocket = new Client(this, serverSocket, key);

        Thread clientSocketThread = new Thread(clientSocket);
        clientSocketThread.start();

        broadcastServerStart(key);

        return true;
    }
}
