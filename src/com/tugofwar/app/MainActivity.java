package com.tugofwar.app;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;


public class MainActivity extends Activity implements OnClickListener, SensorEventListener {

  private Button startButton;
  private Button joinButton;
  private EditText nameET;

  private SensorManager mSensorManager;
  private Sensor mAccelerometer;

  public static final int BUFFER_SIZE = 2048;
  private Socket socket = null;
  private PrintWriter out = null;
  private BufferedReader in = null;
  private static final int SERVERPORT = 23316;
  private static final String SERVER_IP = "54.213.16.116";

  private class DataPoint {
    public float y;
    public long atTimeMilliseconds;

    public DataPoint(float y, long atTimeMilliseconds) {
      this.y = y;
      this.atTimeMilliseconds = atTimeMilliseconds;
    }
  }

  private List<DataPoint> dataPoints = new ArrayList<DataPoint>();

  private static final int SHAKE_CHECK_THRESHOLD = 50;
  private static final int IGNORE_EVENTS_AFTER_SHAKE = 50;
  private long lastUpdate;
  private long lastShake = 0;
  private float last_y = 0;
  private static final long KEEP_DATA_POINTS_FOR = 500;
  private static final long MINIMUM_EACH_DIRECTION = 2;
  private static final float POSITIVE_COUNTER_THRESHHOLD = (float) 2.0;
  private static final float NEGATIVE_COUNTER_THRESHHOLD = (float) -2.0;

  private static final int ACTION_JOIN = 1;
  private static final int ACTION_LEAVE = 2;
  private static final int ACTION_TUG = 3;

  private static final int RESPONSE_JOIN = 1;
  private static final int RESPONSE_DISCONNECT = 2;
  private static final int RESPONSE_WAITING = 3;
  private static final int RESPONSE_TUG = 4;
  private static final int RESPONSE_WIN = 5;
  private static final int RESPONSE_LOSE = 6;

  private int pullCount = 0;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    startButton = (Button) findViewById(R.id.button_start);
    startButton.setOnClickListener(this);

    joinButton = (Button) findViewById(R.id.button_join);
    joinButton.setOnClickListener(this);

    nameET = (EditText) findViewById(R.id.etName);
  }

  @Override
  public void onClick(View v) {
    if (v.getId() == R.id.button_start) {
      System.out.println("starting game");
      new Thread(new ClientThread()).start();

      startButton.setVisibility(View.GONE);
      findViewById(R.id.join_layout).setVisibility(View.VISIBLE);
    } else if (v.getId() == R.id.button_join) {
      System.out.println("joining game");
      String name = nameET.getText().toString();
      join(name);

      findViewById(R.id.join_layout).setVisibility(View.GONE);
    }
  }

  protected void onResume() {
    super.onResume();
    //mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_GAME);
  }

  protected void onPause() {
    super.onPause();
    if (mSensorManager != null)
      mSensorManager.unregisterListener(this);

    //disConnectWithServer();
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
    // can be safely ignored for this demo
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {

      long curTime = System.currentTimeMillis();
      // if a shake in last X seconds ignore.
      if (lastShake != 0 && (curTime - lastShake) < IGNORE_EVENTS_AFTER_SHAKE) {
        //System.out.println("ignore shake");
        return;
      }
      float y = event.values[SensorManager.DATA_Y];
      if (last_y != 0 && last_y != y) {
        DataPoint dp = new DataPoint(last_y - y, curTime);
        dataPoints.add(dp);
        if ((curTime - lastUpdate) > SHAKE_CHECK_THRESHOLD) {
          lastUpdate = curTime;
          checkForShake();
        }
      }
      last_y = y;
    }
  }

  public void checkForShake() {
    long curTime = System.currentTimeMillis();
    long cutOffTime = curTime - KEEP_DATA_POINTS_FOR;
    while (dataPoints.size() > 0 && dataPoints.get(0).atTimeMilliseconds < cutOffTime)
      dataPoints.remove(0);

    int y_pos = 0, y_neg = 0, y_dir = 0;
    for (DataPoint dp : dataPoints) {
      if (dp.y > POSITIVE_COUNTER_THRESHHOLD && y_dir < 1) {
        ++y_pos;
        y_dir = 1;
      }
      if (dp.y < NEGATIVE_COUNTER_THRESHHOLD && y_dir > -1) {
        ++y_neg;
        y_dir = -1;
      }
    }
    if (y_pos >= MINIMUM_EACH_DIRECTION && y_neg >= MINIMUM_EACH_DIRECTION) {
      lastShake = System.currentTimeMillis();
      last_y = 0;
      dataPoints.clear();
      triggerShakeDetected();
      return;
    }

  }

  public void triggerShakeDetected() {
    pullCount++;
    System.out.println("pull detected: " + pullCount);
    pullRope();
  }

  class ClientThread implements Runnable {
    @Override
    public void run() {
      PrintWriter out = null;
      InputStream is = null;
      OutputStream os = null;
      ByteBuffer buf = ByteBuffer.allocate(1024);

      int beat = 0;
      while (true) {
        //if socket not created - create it
        //if socket not connected - connect it
        if (socket == null || !socket.isConnected()) {
          try {
            System.out.println("starting socket");
            InetAddress serverAddr = InetAddress.getByName(SERVER_IP);
            socket = new Socket(serverAddr, SERVERPORT);
            is = socket.getInputStream(); //open socket streams
            os = socket.getOutputStream();
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
          } catch (IOException e) {
            System.out.println("Can't open socket streams - IOException");
            break;
          }
          //System.out.println("Socket connected");
        }

        while (socket.isConnected()) {
          //send heart beats to socket to check connection
          //System.out.println("socket connected");
//
//          try {
//            String str = "{\"action\":" + ACTION_TUG + "}";
//            out.println(str);
//          } catch (Exception e) {
//            e.printStackTrace();
//            System.out.println("Can't send tug");
//            socket = null;
//            break;
//          }

          ByteArrayOutputStream output = new ByteArrayOutputStream();

          //read from socket
          try {
            if (is.available() > 0 && buf.remaining() > 0) {
              System.out.println("start " + buf.position());

//              while (is.available() > 0 && buf.remaining() > 0){
//                buf.put((byte) is.read());
//              }
              int read = 0;
              byte[] buffer = new byte[1024];
              while (is.available() > 0 && buf.remaining() > 0 && read != -1) {
                read = is.read();

                if (read != -1){
                  System.out.println("reading "+ read);
                  output.write(buffer,0,read);
                }else {
                  System.out.println("found -1");
                  break;
                }

              }
              System.out.println("end loading byte buffer");
              output.close();
            } else if (buf.position() > 0) {
              System.out.println("later " + buf.position());
              ByteBuffer cleanBuffer = ByteBuffer.wrap(buf.array());

              String v = new String(output.toByteArray(), Charset.forName("UTF-8") );
              System.out.println("input stream: " + v);
              buf.clear();
            } else
              buf.clear();
          } catch (IOException e) {
            System.out.println("Can't read from socket - IOException");
            e.printStackTrace();
          }
        }
      }
    }
//    @Override
//    public void run() {
//      try {
//        System.out.println("starting socket");
//        InetAddress serverAddr = InetAddress.getByName(SERVER_IP);
//        socket = new Socket(serverAddr, SERVERPORT);
//        out = new PrintWriter(socket.getOutputStream());
//        //in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
//        System.out.println("socket initialized");
//
////        ClientTask task = new ClientTask();
////        task.execute(socket);
//      } catch (UnknownHostException e1) {
//        e1.printStackTrace();
//      } catch (IOException e1) {
//        e1.printStackTrace();
//      }
////      while(true){
////        receiveDataFromServer();
////      }
//    }
  }

  private void disConnectWithServer() {
    if (socket != null) {
      if (socket.isConnected()) {
        try {
          in.close();
          out.close();
          socket.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  public void receiveDataFromServer() {
      String message = "";
      int charsRead = 0;
      char[] buffer = new char[BUFFER_SIZE];
      while (socket.isConnected()) {
        message += new String(buffer).substring(0, charsRead);
        System.out.println("received message " + message);
      }
  }

  public void join(String name) {
    String str = "{\"action\":" + ACTION_JOIN + ", \"name\":\"" + name + "\"}";
    post(str);

    //    SocketReceiverTask socketReceiverTask = new SocketReceiverTask();
    //    socketReceiverTask.execute();

    mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_GAME);
  }

  public void pullRope() {
    String str = "{\"action\":" + ACTION_TUG + "}";
    post(str);
  }

  public void post(String str) {
    try {
      PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
      out.println(str);
    } catch (UnknownHostException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  class ClientTask extends AsyncTask<Socket, Void, Void> {
    Socket socket;
    public BufferedReader in;
    public DataOutputStream dos;
    public String message;

    @Override
    protected Void doInBackground(Socket... soc) {
      System.out.println("doinbackgorund");
      try {
        InetAddress serverAddr = InetAddress.getByName(SERVER_IP);
        socket = new Socket(serverAddr, SERVERPORT);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        System.out.println("socket initialized in doinbackgorund");
      } catch (Exception e) {
        Log.i("AsyncTank class", "Socket has some trouble opening");
      }
      if(socket.isConnected()){
        System.out.println("socket open");
      }else {
        System.out.println("socket closed");
      }
      while(socket.isConnected()){
        try {
          String message = in.readLine();
          System.out.println(message);
          break;
        } catch (IOException e) {
          System.out.println(e.getMessage());
        }
      }
      return null;
    }

    @Override
    protected void onProgressUpdate(Void... values) {
      super.onProgressUpdate(values);

    }


  }

}
