/*
 * ============================================================
 * RoadGuard ESP32 Firmware
 * Smart Pothole & Speed Breaker Detection and Warning System
 *
 * Ramdeobaba University — IDEA Lab
 * Team: Bhoomi Magarde (A6-19), Mohammad Ejaz Mehendi (A6-25),
 *       Nidhish Dadhe (A6-53)
 * Guide: Dr. Suresh Balpande
 *
 * Hardware:
 *   - ESP32 DevKit v1 (or equivalent)
 *   - MPU6050 Accelerometer + Gyroscope (I2C)
 *   - NEO-6M GPS Module (UART)
 *
 * Function:
 *   Reads MPU6050 and NEO-6M GPS, packages timestamped JSON
 *   telemetry, and sends it over BLE to the RoadGuard Android
 *   application. No laptop or Wi-Fi required during operation.
 *
 * BLE Protocol:
 *   Service UUID : 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *   TX Char UUID : 6E400003-B5A3-F393-E0A9-E50E24DCCA9E (ESP32→Android)
 *   RX Char UUID : 6E400002-B5A3-F393-E0A9-E50E24DCCA9E (Android→ESP32)
 *   Device name  : RoadGuard-ESP32
 *
 * Telemetry JSON schema (sent over TX characteristic):
 * {
 *   "deviceId"  : "RoadGuard-ESP32",
 *   "timestamp" : <millis since boot>,
 *   "accelX"    : <m/s², float>,
 *   "accelY"    : <m/s², float>,
 *   "accelZ"    : <m/s², float>,
 *   "gyroX"     : <deg/s, float>,
 *   "gyroY"     : <deg/s, float>,
 *   "gyroZ"     : <deg/s, float>,
 *   "latitude"  : <decimal degrees, float>,
 *   "longitude" : <decimal degrees, float>,
 *   "speedKmh"  : <km/h, float>,
 *   "gpsFix"    : <bool>,
 *   "sensorOk"  : <bool>
 * }
 *
 * Wiring (see docs/hardware-wiring.md for diagram):
 *   MPU6050  → ESP32
 *     VCC    → 3.3 V
 *     GND    → GND
 *     SDA    → GPIO 21
 *     SCL    → GPIO 22
 *     AD0    → GND (I2C address 0x68)
 *     INT    → not connected (polling mode)
 *
 *   NEO-6M   → ESP32
 *     VCC    → 3.3 V (check module regulator; some need 5 V input)
 *     GND    → GND
 *     TX     → GPIO 16 (ESP32 UART2 RX)
 *     RX     → GPIO 17 (ESP32 UART2 TX) — optional, no commands sent
 *
 * Required Arduino Libraries (install via Library Manager):
 *   - Adafruit MPU6050          (by Adafruit)
 *   - Adafruit Unified Sensor   (dependency)
 *   - TinyGPS++                 (by Mikal Hart)
 *   - ArduinoJson               (by Benoit Blanchon) v6.x
 *   - ESP32 BLE Arduino         (built-in with ESP32 board package)
 *
 * Board: "ESP32 Dev Module" in Arduino IDE
 * Flash: esptool or Arduino IDE Upload button
 *
 * ============================================================
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <TinyGPS++.h>
#include <HardwareSerial.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <ArduinoJson.h>

// ============================================================
// Configuration — change these to match your hardware setup
// ============================================================
#define PROTOCOL_VERSION     "2.0"
#define DEVICE_ID            "RoadGuard-ESP32"
#define BLE_DEVICE_NAME      "RoadGuard-ESP32"

// BLE UUIDs (Nordic UART Service — NUS — compatible)
#define SERVICE_UUID         "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHAR_UUID_TX         "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"  // ESP32 → Android
#define CHAR_UUID_RX         "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"  // Android → ESP32

// I2C pins for MPU6050
#define MPU_SDA_PIN          21
#define MPU_SCL_PIN          22
#define MPU_I2C_ADDRESS      0x68  // AD0 tied to GND

// UART pins for NEO-6M GPS
#define GPS_RX_PIN           16   // ESP32 RX ← GPS TX
#define GPS_TX_PIN           17   // ESP32 TX → GPS RX (optional)
#define GPS_BAUD             9600

// Sampling configuration (50 Hz initial target)
#define SAMPLE_INTERVAL_MS   20   // 50 Hz target (20 ms interval)
#define GPS_TIMEOUT_MS       5000 // Time without GPS data before marking fix lost
#define BLE_MTU_BYTES        512  // Maximum BLE payload per notification

// Calibration offsets — update after running DIAGNOSTIC_MODE
// These are applied to raw sensor readings at runtime.
// Run in DIAGNOSTIC_MODE first and adjust these values.
float ACCEL_OFFSET_X = 0.0f;
float ACCEL_OFFSET_Y = 0.0f;
float ACCEL_OFFSET_Z = 0.0f;
float GYRO_OFFSET_X  = 0.0f;
float GYRO_OFFSET_Y  = 0.0f;
float GYRO_OFFSET_Z  = 0.0f;

// Set to true to run sensor diagnostics at startup instead of normal operation
#define DIAGNOSTIC_MODE      false

// ============================================================
// Global Objects
// ============================================================
Adafruit_MPU6050 mpu;
TinyGPSPlus      gps;
HardwareSerial   gpsSerial(2);  // UART2

BLEServer*           pServer           = nullptr;
BLECharacteristic*   pTxCharacteristic = nullptr;
BLECharacteristic*   pRxCharacteristic = nullptr;
bool                 deviceConnected   = false;
bool                 oldDeviceConnected = false;

// State & Frequency measurement
bool          sensorOk              = false;
bool          gpsFix                = false;
float         latitude              = 0.0f;
float         longitude             = 0.0f;
float         speedKmh              = 0.0f;
unsigned long lastGpsData           = 0;
unsigned long lastSample            = 0;
uint32_t      packetSequenceNumber  = 0;
unsigned long sampleCounter         = 0;
unsigned long rateCalcStartMs       = 0;
float         measuredSamplingRateHz = 50.0f;

// Calibration state
int   calibSamples        = 0;
float calibSumAX          = 0.0f, calibSumAY = 0.0f, calibSumAZ = 0.0f;
float calibSumGX          = 0.0f, calibSumGY = 0.0f, calibSumGZ = 0.0f;

// ============================================================
// BLE Server Callbacks
// ============================================================
class RoadGuardServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pSrv) override {
    deviceConnected = true;
    Serial.println("[BLE] Android device connected.");
  }
  void onDisconnect(BLEServer* pSrv) override {
    deviceConnected = false;
    Serial.println("[BLE] Device disconnected. Restarting advertisement.");
  }
};

// ============================================================
// BLE RX Callback (commands from Android to ESP32)
// ============================================================
class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) override {
    std::string rxValue = pChar->getValue();
    if (rxValue.length() > 0) {
      Serial.print("[BLE RX] Received: ");
      Serial.println(rxValue.c_str());
      // Future: parse calibrate, ping, config commands
    }
  }
};

// ============================================================
// MPU6050 Initialization
// ============================================================
bool initMPU() {
  Wire.begin(MPU_SDA_PIN, MPU_SCL_PIN);
  if (!mpu.begin(MPU_I2C_ADDRESS)) {
    Serial.println("[MPU6050] INIT FAILED — check wiring (SDA:21, SCL:22, VCC:3.3V).");
    return false;
  }
  mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
  mpu.setGyroRange(MPU6050_RANGE_500_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
  Serial.println("[MPU6050] Initialized. Range: ±8g accel, ±500°/s gyro, 21 Hz LPF.");
  return true;
}

// ============================================================
// BLE Setup
// ============================================================
void initBLE() {
  BLEDevice::init(BLE_DEVICE_NAME);
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new RoadGuardServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  // TX Characteristic — ESP32 notifies Android
  pTxCharacteristic = pService->createCharacteristic(
    CHAR_UUID_TX,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pTxCharacteristic->addDescriptor(new BLE2902());

  // RX Characteristic — Android writes to ESP32
  pRxCharacteristic = pService->createCharacteristic(
    CHAR_UUID_RX,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  pRxCharacteristic->setCallbacks(new RxCallbacks());

  pService->start();

  BLEAdvertising* pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(true);
  pAdv->setMinPreferred(0x06);  // helps with iPhone connections
  BLEDevice::startAdvertising();

  Serial.print("[BLE] Advertising as: "); Serial.println(BLE_DEVICE_NAME);
  Serial.print("[BLE] Service UUID : "); Serial.println(SERVICE_UUID);
  Serial.print("[BLE] TX Char UUID : "); Serial.println(CHAR_UUID_TX);
}

// ============================================================
// Calibration Routine
// Collects 100 samples at rest and computes mean offsets.
// Run with the device mounted in the vehicle, engine off.
// ============================================================
void runCalibration() {
  Serial.println("[CAL] Starting 100-sample calibration (keep device still)...");
  calibSamples = 0;
  calibSumAX = calibSumAY = calibSumAZ = 0.0f;
  calibSumGX = calibSumGY = calibSumGZ = 0.0f;

  for (int i = 0; i < 100; i++) {
    sensors_event_t a, g, temp;
    if (mpu.getEvent(&a, &g, &temp)) {
      calibSumAX += a.acceleration.x;
      calibSumAY += a.acceleration.y;
      calibSumAZ += a.acceleration.z;
      calibSumGX += g.gyro.x;
      calibSumGY += g.gyro.y;
      calibSumGZ += g.gyro.z;
      calibSamples++;
    }
    delay(10);
  }

  if (calibSamples > 0) {
    ACCEL_OFFSET_X = calibSumAX / calibSamples;
    ACCEL_OFFSET_Y = calibSumAY / calibSamples;
    // Z-axis: subtract gravity (9.81 m/s²) — depends on orientation
    ACCEL_OFFSET_Z = (calibSumAZ / calibSamples) - 9.81f;
    GYRO_OFFSET_X  = calibSumGX / calibSamples;
    GYRO_OFFSET_Y  = calibSumGY / calibSamples;
    GYRO_OFFSET_Z  = calibSumGZ / calibSamples;

    Serial.printf("[CAL] Offsets — AX:%.3f AY:%.3f AZ:%.3f GX:%.3f GY:%.3f GZ:%.3f\n",
      ACCEL_OFFSET_X, ACCEL_OFFSET_Y, ACCEL_OFFSET_Z,
      GYRO_OFFSET_X, GYRO_OFFSET_Y, GYRO_OFFSET_Z);
    Serial.println("[CAL] Update ACCEL_OFFSET_* and GYRO_OFFSET_* constants above.");
  }
}

// ============================================================
// Diagnostic Mode
// Prints raw sensor readings to Serial for hardware verification.
// ============================================================
void runDiagnosticMode() {
  Serial.println("=== DIAGNOSTIC MODE ===");
  Serial.println("MPU6050 and GPS readings are printed every 500 ms.");
  Serial.println("Connect with Serial Monitor at 115200 baud.");
  Serial.println("No BLE advertising in this mode.");

  while (true) {
    // MPU6050
    if (sensorOk) {
      sensors_event_t a, g, temp;
      mpu.getEvent(&a, &g, &temp);
      Serial.printf("[MPU] AX:%.3f AY:%.3f AZ:%.3f GX:%.3f GY:%.3f GZ:%.3f Temp:%.1f°C\n",
        a.acceleration.x, a.acceleration.y, a.acceleration.z,
        g.gyro.x, g.gyro.y, g.gyro.z,
        temp.temperature);
    } else {
      Serial.println("[MPU] SENSOR NOT AVAILABLE");
    }

    // GPS
    while (gpsSerial.available() > 0) {
      gps.encode(gpsSerial.read());
    }
    if (gps.location.isValid()) {
      Serial.printf("[GPS] Lat:%.6f Lon:%.6f Speed:%.1f km/h Sats:%d\n",
        gps.location.lat(), gps.location.lng(),
        gps.speed.kmph(), gps.satellites.value());
    } else {
      Serial.println("[GPS] Waiting for fix... (may take 1–3 min outdoors)");
    }

    delay(500);
  }
}

// ============================================================
// Setup
// ============================================================
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n==============================================");
  Serial.println(" RoadGuard ESP32 Firmware v1.0");
  Serial.println(" Smart Pothole & Speed Breaker Detection");
  Serial.println("==============================================");

  // Initialize GPS UART
  gpsSerial.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  Serial.println("[GPS] UART initialized at 9600 baud (RX:16, TX:17).");

  // Initialize MPU6050
  sensorOk = initMPU();

  if (!sensorOk) {
    Serial.println("[WARN] MPU6050 unavailable. Telemetry will have sensorOk=false.");
  }

  if (DIAGNOSTIC_MODE) {
    runDiagnosticMode();
    return;
  }

  // Run calibration at startup
  if (sensorOk) {
    runCalibration();
  }

  // Initialize BLE
  initBLE();

  Serial.println("[READY] RoadGuard is running. Waiting for Android to connect...");
}

// ============================================================
// GPS Update (non-blocking)
// ============================================================
void updateGPS() {
  while (gpsSerial.available() > 0) {
    if (gps.encode(gpsSerial.read())) {
      lastGpsData = millis();
    }
  }

  // Check fix validity
  if (gps.location.isValid() && gps.location.age() < 2000) {
    gpsFix    = true;
    latitude  = (float)gps.location.lat();
    longitude = (float)gps.location.lng();
    speedKmh  = gps.speed.isValid() ? (float)gps.speed.kmph() : 0.0f;
  } else if (millis() - lastGpsData > GPS_TIMEOUT_MS) {
    // GPS data too old or never arrived — mark fix lost
    gpsFix    = false;
    speedKmh  = 0.0f;
  }
}

// ============================================================
// Build and Send Telemetry JSON over BLE
// ============================================================
void sendTelemetry() {
  sensors_event_t a, g, temp;
  bool mpuRead = false;
  float ax = 0.0f, ay = 0.0f, az = 9.81f;
  float gx = 0.0f, gy = 0.0f, gz = 0.0f;

  if (sensorOk) {
    mpuRead = mpu.getEvent(&a, &g, &temp);
    if (mpuRead) {
      ax = a.acceleration.x - ACCEL_OFFSET_X;
      ay = a.acceleration.y - ACCEL_OFFSET_Y;
      az = a.acceleration.z - ACCEL_OFFSET_Z;
      gx = g.gyro.x - GYRO_OFFSET_X;
      gy = g.gyro.y - GYRO_OFFSET_Y;
      gz = g.gyro.z - GYRO_OFFSET_Z;
    }
  }

  // Measure actual sampling rate every 50 samples
  sampleCounter++;
  if (sampleCounter >= 50) {
    unsigned long dt = millis() - rateCalcStartMs;
    if (dt > 0) {
      measuredSamplingRateHz = (sampleCounter * 1000.0f) / dt;
    }
    sampleCounter = 0;
    rateCalcStartMs = millis();
  }

  // Build JSON — use StaticJsonDocument for deterministic memory
  StaticJsonDocument<512> doc;
  doc["proto"]     = PROTOCOL_VERSION;
  doc["seq"]       = packetSequenceNumber++;
  doc["deviceId"]  = DEVICE_ID;
  doc["timestamp"] = millis();
  doc["hz"]        = round(measuredSamplingRateHz * 10.0f) / 10.0f;
  doc["accelX"]    = round(ax * 1000.0f) / 1000.0f;  // 3 decimal places
  doc["accelY"]    = round(ay * 1000.0f) / 1000.0f;
  doc["accelZ"]    = round(az * 1000.0f) / 1000.0f;
  doc["gyroX"]     = round(gx * 1000.0f) / 1000.0f;
  doc["gyroY"]     = round(gy * 1000.0f) / 1000.0f;
  doc["gyroZ"]     = round(gz * 1000.0f) / 1000.0f;
  doc["latitude"]  = gpsFix ? latitude  : 0.0f;
  doc["longitude"] = gpsFix ? longitude : 0.0f;
  doc["speedKmh"]  = gpsFix ? speedKmh  : 0.0f;
  doc["gpsFix"]    = gpsFix;
  doc["sensorOk"]  = sensorOk && mpuRead;
  doc["health"]    = (sensorOk && gpsFix) ? "OK" : (sensorOk ? "GPS_SEARCH" : "SENSOR_ERR");

  char jsonBuffer[512];
  size_t len = serializeJson(doc, jsonBuffer, sizeof(jsonBuffer));

  if (deviceConnected && pTxCharacteristic != nullptr) {
    pTxCharacteristic->setValue((uint8_t*)jsonBuffer, len);
    pTxCharacteristic->notify();
  }

  // Serial echo for development
  Serial.println(jsonBuffer);
}

// ============================================================
// Main Loop
// ============================================================
void loop() {
  // Handle BLE reconnection advertising
  if (!deviceConnected && oldDeviceConnected) {
    delay(500);
    pServer->startAdvertising();
    oldDeviceConnected = false;
  }
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = true;
  }

  // Non-blocking GPS update
  updateGPS();

  // Sample and transmit at the configured interval
  unsigned long now = millis();
  if (now - lastSample >= SAMPLE_INTERVAL_MS) {
    lastSample = now;
    sendTelemetry();
  }
}
