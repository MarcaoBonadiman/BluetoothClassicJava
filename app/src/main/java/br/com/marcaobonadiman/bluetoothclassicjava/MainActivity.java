package br.com.marcaobonadiman.bluetoothclassicjava;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private LinearLayout linearLayoutLed;
    private ImageButton imageButtonLed;
    private TextView tvStatus;

    private final UUID m_myUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final String deviceNome = "ESP32test"; // Nome do dispositivo que tem de parear (É o Bluetooth do ESP32 que está gerando)
    private Boolean isConnectBT = false;
    //private Boolean conectar = false;
    private Boolean stopWorker = false;
    Thread myThread;
    private BluetoothDevice deviceESP32 = null;
    private BluetoothAdapter m_Adapter = null;
    private BluetoothSocket m_bluetoothSocket = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.TextViewStatus);
        tvStatus.setText("");

        // Este LinearLayout contém o Botão da lâmpada e um TextView informativo. Ele só será exibido se conectar ao bluetooth
        linearLayoutLed = findViewById(R.id.LinearLayoutLed);
        linearLayoutLed .setVisibility(View.GONE);

        imageButtonLed = findViewById(R.id.imageButtonLed);
        imageButtonLed.setOnClickListener(v->{
            sendCommand("*\n"); // Ao enviar um "*", faz o ESP32 inverter o estado do LED
        });

        if (ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Solicita permissão ao usuário
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,Manifest.permission.ACCESS_FINE_LOCATION)) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
        }else{
            // Se já foi dado a permissão
            Init();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,@NonNull String[] permissions,@NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if ((ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
                    //Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show()
                    Init(); // Se foi dado permissão inicia o app
                }
            }else{
                finish();
            }
        }
    }

    private void Init(){
        m_Adapter = BluetoothAdapter.getDefaultAdapter();

        // Verifica se o Bluetooth está ligado, se não estiver pede ao usuário confirmação para ligar
        if (!m_Adapter.isEnabled()){
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            resultLigaBT.launch(enableBtIntent);
        }else{
            Init2();
        }
    }

    // Verifica a resposta da confirmação de ligar o Bluetooth
    ActivityResultLauncher<Intent> resultLigaBT = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK){
            Init2();
        }else if (result.getResultCode()==Activity.RESULT_CANCELED){
            finish();
        }
    });

    // Inicializa a thread e procura pelo dispositivo "ESP32test"
    private void Init2(){
        Servico(); // Inicializa a thread que vai conectar e receber os dados do ESP32, se o dispositivo estiver pareado com sucesso.
        getPairedDevices(); // Procura pelo dispositivo
    }

    // Função que envia dados ao ESP32
    private void sendCommand(String input) {
        if (m_bluetoothSocket.isConnected())
        {
            try{
//                m_bluetoothSocket!!.outputStream.write(input.toByteArray())
                //out.write(input.getBytes());
                m_bluetoothSocket.getOutputStream().write(input.getBytes());
            } catch(IOException e) {
                e.printStackTrace();
            }
        }
    }


    // Função chamada ao Sair do App
    private void Sair(){
        try {
            stopWorker = true;
            if (m_bluetoothSocket!=null && m_bluetoothSocket.isConnected()){
                m_bluetoothSocket.close();
                m_bluetoothSocket = null;
            }
            finish();
        }catch (IOException e ) {
            e.printStackTrace();
            Log.e("Erro",e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Sair();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Sair();
            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    @SuppressLint("MissingPermission")
    private void getPairedDevices() {
        ShowStatus("Procurando pelo dispositivo:\n\n "+deviceNome+"\n\naguarde...");
        new Handler().postDelayed(() -> {
            Set<BluetoothDevice> pairedDevice = m_Adapter.getBondedDevices();
            if (pairedDevice.size() > 0) {
                boolean flagErro = true;
                for (BluetoothDevice device : pairedDevice) {
                    if(device.getName().equals(deviceNome)){
                        deviceESP32 = device;
                        flagErro = false;
                        break;
                    }
                }
                if (flagErro){
                    Parear();
                }else{
                    Conectar();
                }
            }
        }, 100);



    }


    @SuppressLint("MissingPermission")
    private void Parear(){
        try{
            IntentFilter intentFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            //intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
            intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            registerReceiver(myReceiver, intentFilter);
            m_Adapter.startDiscovery();
        }catch (Exception e){
            Log.e("Erro",e.getMessage());
        }
    }

    private final BroadcastReceiver myReceiver = new BroadcastReceiver() {
        Boolean achou= false;
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            //Message msg = Message.obtain();
            //Log.e("Scan", " -> "+msg);
            String action = intent.getAction();
            //Log.e("Action", " -> "+action);
            //if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if(state==BluetoothDevice.BOND_NONE){ // Se o usuário cancelou
                    m_Adapter.cancelDiscovery();
                    finish();
                }else if(state==BluetoothDevice.BOND_BONDED){ // Se o usuário confirmou
                    m_Adapter.cancelDiscovery();
                    ShowStatus("Pareando com dispositivo:\n\n"+deviceNome+"\n\naguarde...");
                    // Chama novamente a rotina "getPairedDevices" para tentar conectar após 5 segundos (Se houver erro, aumente esse tempo)
                    new Handler().postDelayed(() -> getPairedDevices(), 5000);
                }
            }else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device!=null){
                    String nomeDevice=null;
                    try{
                        nomeDevice = device.getName();
                    }catch (Exception e){
                        Log.e("Error",e.getMessage());
                    }
                    if (nomeDevice!=null && nomeDevice.equals(deviceNome)){
                        m_Adapter.cancelDiscovery();
                        achou=true;
                        ShowStatus("Achou o dispositivo:\n\n"+deviceNome);
                        new Handler().postDelayed(() -> {
                            try {
                                // Vai fazer o pedido de pareamento, é usado um retardo de 100 ms para o aviso acima poder ser visalizado na tela.
                                // Esse pedido é assincrono, isto é, não espera pela resposta, por isso o "intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)" é
                                // usado na função de Parear, para captar a resposta de confirmação do pareamento.
                                boolean isBonded = createBond(device);
                                if(isBonded){
                                    Log.e("Ret", String.valueOf(isBonded));
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, 100);

                    }
                }
            }else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                m_Adapter.cancelDiscovery();
                if (!achou){
                    ShowStatus("Dispositivo não encotrado,\n\n o App será finalizado",R.color.red);
                    new Handler().postDelayed(() -> finish(), 100);
                }
            }
        }
    };

    // Exibe na tela a solicitação do pareamento com o dispositivo (ESP32test)
    public boolean createBond(BluetoothDevice btDevice) throws Exception {
        boolean ret;
        Class class1 = Class.forName("android.bluetooth.BluetoothDevice");
        Method createBondMethod = class1.getMethod("createBond");
        ret = (boolean) createBondMethod.invoke(btDevice);
        return ret;
    }

    public void Servico(){
        stopWorker = false;
        myThread = new Thread("My") {
            @SuppressLint("MissingPermission")
            @Override
            public void run() {
                String expC = "";
                OutputStream out = null;
                InputStream in = null;
                while (!stopWorker) {
                    try {
                        if(isConnectBT){
                            if (in==null && out==null) {
                                in = m_bluetoothSocket.getInputStream();
                                out = m_bluetoothSocket.getOutputStream();
                            }
                            int bytesAvailable = in.available();
                            if (bytesAvailable>0){
                                while (in.available()>0){
                                    byte[] packetBytes = new byte[bytesAvailable];
                                    if(in.read(packetBytes)>0){
                                        expC += new String(packetBytes);
                                        if (expC.contains("\n") || expC.length() > 20) {
                                            expC = expC.replace("\n","").replace("\r","");
                                            String toSend = "Ack\n";
                                            if (expC.length()==1) {
                                                ShowLampada(expC);
                                            }else{
                                                toSend = "Err\n";
                                            }
                                            expC = "";
                                            out.write(toSend.getBytes());
                                            out.flush();
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e("Loop"," Erro: "+e.getMessage());
                    }
                    SystemClock.sleep(5);
                }
            }
        };
        myThread.start();
    }

    // Exibe o status da lâmpada
    private void ShowLampada(String expC ){
        runOnUiThread(()->{
            if (expC.equals("1")){
                imageButtonLed.setImageResource(R.drawable.lampadaacesa48);
            }else {
                imageButtonLed.setImageResource(R.drawable.lampadaapagada48);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void Conectar(){
        ShowStatus("Conectando no dispostivo:\n\n"+deviceNome+"\n\naguarde...");
        try {
            m_bluetoothSocket = deviceESP32.createRfcommSocketToServiceRecord(m_myUUID);
            m_bluetoothSocket.connect();
            if (m_bluetoothSocket.isConnected()){
                isConnectBT = true;
                linearLayoutLed.setVisibility(View.VISIBLE);
                sendCommand("ST\n"); // Envia uma solicitação de status ao dispositivo
                ShowStatus("O dispositivo:\n\n"+deviceNome+"\n\nestá conectado",R.color.green);
            }else{
                ShowStatus("O dispositivo:\n\n"+deviceNome+"\n\nestá pareado,\n\n mais não conseguiu conectar!",R.color.red);
            }
        } catch (IOException e) {
            ShowStatus("O dispositivo:\n\n"+deviceNome+"\n\nestá pareado,\n\n mais não conseguiu conectar!",R.color.red);
        }
    }

    private void ShowStatus(String expC){
        ShowStatus(expC,R.color.white);
    }

    private void ShowStatus(String expC, int cor){
        runOnUiThread(() -> {
            tvStatus.setTextColor(ContextCompat.getColor(this, cor));
            tvStatus.setText(expC);
        });
    }

}