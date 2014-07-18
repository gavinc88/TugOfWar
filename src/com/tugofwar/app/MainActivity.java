package com.tugofwar.app;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;


public class MainActivity extends Activity implements OnClickListener, SensorEventListener {

  private Button startButton;
  private Button joinButton;
  private EditText nameET;
  private View flash;
  private ProgressDialog progressDialog;
  private TextView winLose;

  private boolean isFirstPlayer = false;

  private SensorManager mSensorManager;
  private Sensor mAccelerometer;

  public static final int BUFFER_SIZE = 2048;
  private Socket socket = null;
  private PrintWriter out = null;
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

    flash = (View) findViewById(R.id.flash_screen);

    winLose = (TextView) findViewById(R.id.winLose);
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
      try {
        InetAddress serverAddr = InetAddress.getByName(SERVER_IP);
        socket = new Socket(serverAddr, SERVERPORT);
        InputStreamReader inputStream = new InputStreamReader(socket.getInputStream());
        BufferedReader input = new BufferedReader(inputStream);

        while (true) {
          final String message = input.readLine();
          System.out.println(message);
          handleMessage(message);

        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private void handleMessage(String message) {
    JSONObject parser;
    try {
      parser = new JSONObject(message);
      System.out.println("response: "+parser.getInt("action"));
      switch (parser.getInt("action")){
        case RESPONSE_JOIN:{
          handleJoin(parser.getString("opponent_name"));
          break;
        }
        case RESPONSE_WAITING:{
          handleWaiting();
          break;
        }
        case RESPONSE_DISCONNECT:{
          handleDisconnect();
          break;
        }
        case RESPONSE_TUG:{
          handleScoreUpdate(parser.getInt("score"));
          break;
        }
        case RESPONSE_WIN:{
          handleEndGame(true);
          break;
        }
        case RESPONSE_LOSE: {
          handleEndGame(false);
          break;
        }
      }
    } catch (JSONException e) {

    }
  }

  private void handleJoin(final String name) {
    System.out.println("Connected with player: " + name);
    if(progressDialog != null && progressDialog.isShowing())
      progressDialog.dismiss();
    runOnUiThread(new Runnable() {
      public void run() {
        Toast.makeText(MainActivity.this, "Connected with player: " + name, Toast.LENGTH_SHORT).show();
      }
    });

  }

  private void handleWaiting(){
    System.out.println("Waiting...");
    runOnUiThread(new Runnable() {
      public void run() {
        showProgress("Looking for player...");
      }
    });
    isFirstPlayer = true;
  }

  public void handleDisconnect()
  {
    System.out.println("Disconnected...");
    if (socket != null) {
      if (socket.isConnected()) {
        try {
          out.close();
          socket.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    // TODO: Reset activity
  }

  public void handleScoreUpdate(int score) {
    System.out.println("Score: " + score);
    if ((score >= 0 && isFirstPlayer) || (score < 0 && !isFirstPlayer)) {
      runOnUiThread(new Runnable() {
        public void run() {
          flash.setBackgroundResource(R.color.green);
          Animation fadeOut = new AlphaAnimation(1, 0);
          fadeOut.setFillAfter(true);
          fadeOut.setDuration(250);
          flash.startAnimation(fadeOut);
        }
      });

    } else {
      runOnUiThread(new Runnable() {
        public void run() {
          flash.setBackgroundResource(R.color.red_bright);
          Animation fadeOut = new AlphaAnimation(1, 0);
          fadeOut.setFillAfter(true);
          fadeOut.setDuration(250);
          flash.startAnimation(fadeOut);
        }
      });
    }
  }

  public void handleEndGame(Boolean win){
    if (win){
      System.out.println("YOU WON!");
      runOnUiThread(new Runnable() {
        public void run() {
          winLose.setText("YOU WON!!");
          winLose.setVisibility(View.VISIBLE);
        }
      });
    }else {
      System.out.println("YOU LOST");
      runOnUiThread(new Runnable() {
        public void run() {
          winLose.setText("YOU LOST!!");
          winLose.setVisibility(View.VISIBLE);
        }
      });
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
      System.out.println("posting "+str);
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

  public void showProgress(final String message){
    System.out.println("showing progress for " + message);
    progressDialog = ProgressDialog.show(MainActivity.this, "", message);
    progressDialog.show();
  }

}
