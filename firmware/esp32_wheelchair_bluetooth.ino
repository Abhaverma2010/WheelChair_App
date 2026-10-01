/*
 * ESP32 Wheelchair Controller — Bluetooth Classic SPP
 * ====================================================
 * Pairs with the "Bluetooth Switch" Android app over Bluetooth Classic
 * (BluetoothSerial / SPP), drives the motors, runs MPU6050-based fall
 * detection, and (once NEO-6M + SIM800L are wired in) sends an SMS with
 * GPS location to a caregiver on a confirmed fall.
 *
 * ================= Phone -> ESP32 protocol (unchanged) =================
 *   F = forward   B = backward   L = left   R = right   S = stop
 *   (single raw byte per command, exactly as before)
 *
 * ================= ESP32 -> Phone protocol (NEW) =================
 *   Sent as plain text lines (newline-terminated) over the same Bluetooth
 *   connection, so the app can show live status when a phone is connected:
 *     ALERT:FALL         - a fall was just confirmed
 *     ALERT:CLEAR        - the wheelchair has returned to a normal, upright
 *                           orientation (lets the app clear its banner)
 *     GPS:<lat>,<lon>     - latest GPS fix (sent periodically + right after a fall)
 *     GPS:NOFIX           - no GPS fix yet (sent periodically until one is acquired)
 *
 * ================= IMPORTANT: two independent alert paths =================
 * Bluetooth Classic only reaches a phone that is within range AND actively
 * connected. The ALERT:FALL / GPS: lines above are for THAT phone only (a
 * companion/caregiver walking alongside, or the user's own phone).
 * The SIM800L SMS below is a SEPARATE path that fires directly from the
 * ESP32 regardless of whether any phone is Bluetooth-connected, so it is
 * the one that actually reaches a caregiver who is not nearby. Keep both.
 *
 * ================= STATUS =================
 * Motor control + fall detection: carried over from the tested Phase 1
 * firmware (tilt-angle algorithm, same thresholds).
 * GPS + SMS: NEWLY ADDED in this file, not yet bench-tested on real
 * NEO-6M / SIM800L hardware. Verify AT command behavior and the NMEA
 * parsing against your actual modules before trusting this in the field --
 * see the TODO comments near SIM800_* and the GPS section below.
 */

#include "BluetoothSerial.h"
#include <Wire.h>
#include <math.h>
#include <HardwareSerial.h>
#include <TinyGPS++.h>     // Library Manager: "TinyGPSPlus" by Mikal Hart (header is literally "TinyGPS++.h")

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled! Use "Tools > Board" to select an ESP32 board, and enable Bluetooth in menuconfig if needed.
#endif

// ================= BLUETOOTH =================
BluetoothSerial SerialBT;
#define BT_DEVICE_NAME "ESP32_WHEELCHAIR"

// ================= MOTOR PINS =================
// Right Motor
#define ENA 14
#define IN1 27
#define IN2 26
// Left Motor
#define ENB 32
#define IN3 25
#define IN4 33

#define PWM_FREQ 1000
#define PWM_RESOLUTION 8
int motorSpeed = 255;
int turningSpeed = 255;

// ================= MPU6050 =================
#define MPU_ADDR 0x68
#define ACCEL_XOUT_H 0x3B
#define PWR_MGMT_1 0x6B
bool fallAlreadyDetected = false;
unsigned long lastFallCheckMs = 0;
const unsigned long FALL_CHECK_INTERVAL_MS = 200;

// ================= GPS (NEO-6M) on UART1 =================
// TODO: confirm these GPIOs are free on your wiring and match your module.
#define GPS_RX_PIN 16   // ESP32 RX  <- NEO-6M TX
#define GPS_TX_PIN 17   // ESP32 TX  -> NEO-6M RX
HardwareSerial GPSSerial(1);
TinyGPSPlus gps;
unsigned long lastGpsReportMs = 0;
const unsigned long GPS_REPORT_INTERVAL_MS = 10000; // report every 10s while connected

// ================= SIM800L (GSM/SMS) on UART2 =================
// TODO: confirm these GPIOs are free on your wiring, and that the SIM800L
// has its OWN dedicated regulated power supply (NOT powered from the
// ESP32), per the safety note in the project's wiring diagram.
// NOTE: GPIO2 is an ESP32 boot-strapping pin and is deliberately avoided
// here -- using it for UART can interfere with normal boot.
#define SIM800_RX_PIN 4    // ESP32 RX  <- SIM800L TX
#define SIM800_TX_PIN 19   // ESP32 TX  -> SIM800L RX
HardwareSerial SIM800(2);
#define CAREGIVER_PHONE_NUMBER "+917828467906"

// Runs the (slow, blocking-on-its-own-task) SMS dispatch so it never stalls
// motor control or Bluetooth command handling on the main loop.
TaskHandle_t smsTaskHandle = NULL;
struct SmsJob {
  bool hasFix;
  double lat;
  double lon;
};

// ================= MOTOR CONTROL =================
void rotateMotor(int rightSpeed, int leftSpeed) {
  if (rightSpeed > 0)      { digitalWrite(IN1, HIGH); digitalWrite(IN2, LOW); }
  else if (rightSpeed < 0) { digitalWrite(IN1, LOW);  digitalWrite(IN2, HIGH); }
  else                     { digitalWrite(IN1, LOW);  digitalWrite(IN2, LOW); }

  if (leftSpeed > 0)       { digitalWrite(IN3, HIGH); digitalWrite(IN4, LOW); }
  else if (leftSpeed < 0)  { digitalWrite(IN3, LOW);  digitalWrite(IN4, HIGH); }
  else                     { digitalWrite(IN3, LOW);  digitalWrite(IN4, LOW); }

  ledcWrite(ENA, abs(rightSpeed));
  ledcWrite(ENB, abs(leftSpeed));
}

void forward()  { rotateMotor(motorSpeed, motorSpeed); }
void backward() { rotateMotor(-motorSpeed, -motorSpeed); }
void left()     { rotateMotor(-turningSpeed, turningSpeed); }
void right()    { rotateMotor(turningSpeed, -turningSpeed); }
void stopMotor(){ rotateMotor(0, 0); }

// ================= BLUETOOTH COMMAND HANDLING =================
void handleIncomingCommand(char command) {
  Serial.print("Received: ");
  Serial.println(command);

  switch (command) {
    case 'F': forward();  break;
    case 'B': backward(); break;
    case 'L': left();     break;
    case 'R': right();    break;
    case 'S': stopMotor();break;
    default:
      // Unknown byte -- ignore rather than guess, for safety.
      break;
  }
}

// ================= FALL DETECTION (MPU6050) =================
void checkFall() {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(ACCEL_XOUT_H);
  Wire.endTransmission(false);
  Wire.requestFrom(MPU_ADDR, 6);

  if (Wire.available() != 6) {
    return;
  }

  int16_t rawX = Wire.read() << 8 | Wire.read();
  int16_t rawY = Wire.read() << 8 | Wire.read();
  int16_t rawZ = Wire.read() << 8 | Wire.read();

  float ax = rawX / 16384.0;
  float ay = rawY / 16384.0;
  float az = rawZ / 16384.0;

  float roll  = atan2(ay, az) * 180.0 / PI;
  float pitch = atan2(-ax, sqrt(ay * ay + az * az)) * 180.0 / PI;

  if (abs(roll) > 45 || abs(pitch) > 45) {
    if (!fallAlreadyDetected) {
      fallAlreadyDetected = true;
      onFallConfirmed();
    }
  } else if (abs(roll) < 35 && abs(pitch) < 35) {
    if (fallAlreadyDetected) {
      fallAlreadyDetected = false;
      SerialBT.println("ALERT:CLEAR");
    }
  }
}

void onFallConfirmed() {
  Serial.println("FALL DETECTED");

  // 1) Tell whichever phone is Bluetooth-connected right now.
  SerialBT.println("ALERT:FALL");
  sendGpsLineOverBluetooth();

  // 2) Independently, kick off the SMS to the remote caregiver. This does
  //    NOT depend on a phone being Bluetooth-connected at all.
  SmsJob* job = new SmsJob();
  job->hasFix = gps.location.isValid();
  job->lat = job->hasFix ? gps.location.lat() : 0.0;
  job->lon = job->hasFix ? gps.location.lng() : 0.0;

  xTaskCreatePinnedToCore(
      smsTask, "smsTask", 4096, job, 1, &smsTaskHandle, 1);
}

// ================= GPS (NEO-6M) =================
void pollGps() {
  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }
}

void sendGpsLineOverBluetooth() {
  if (gps.location.isValid()) {
    SerialBT.print("GPS:");
    SerialBT.print(gps.location.lat(), 6);
    SerialBT.print(",");
    SerialBT.println(gps.location.lng(), 6);
  } else {
    SerialBT.println("GPS:NOFIX");
  }
}

// ================= SIM800L (SMS) =================
// Runs on its own FreeRTOS task so a slow (multi-second) AT+CMGS exchange
// never blocks motor control or Bluetooth command handling on core 1's
// main loop.
void smsTask(void* param) {
  SmsJob* job = (SmsJob*) param;

  // TODO: bench-test this AT command sequence against your actual SIM800L
  // before relying on it -- timing and exact responses vary by firmware
  // revision. This is the standard sequence, not yet hardware-verified.
  SIM800.println("AT");
  delay(500);
  SIM800.println("AT+CMGF=1"); // text mode
  delay(500);
  SIM800.print("AT+CMGS=\"");
  SIM800.print(CAREGIVER_PHONE_NUMBER);
  SIM800.println("\"");
  delay(500);

  SIM800.print("Wheelchair fall detected. ");
  if (job->hasFix) {
    SIM800.print("Location: https://maps.google.com/?q=");
    SIM800.print(job->lat, 6);
    SIM800.print(",");
    SIM800.println(job->lon, 6);
  } else {
    SIM800.println("GPS location not yet available.");
  }

  SIM800.write(26); // Ctrl+Z sends the message
  delay(5000);       // give the module time to transmit

  Serial.println("SMS dispatch attempted.");
  delete job;
  smsTaskHandle = NULL;
  vTaskDelete(NULL);
}

// ================= SETUP =================
void setup() {
  Serial.begin(115200);

  // Motor pins
  pinMode(ENA, OUTPUT); pinMode(IN1, OUTPUT); pinMode(IN2, OUTPUT);
  pinMode(ENB, OUTPUT); pinMode(IN3, OUTPUT); pinMode(IN4, OUTPUT);
  ledcAttach(ENA, PWM_FREQ, PWM_RESOLUTION);
  ledcAttach(ENB, PWM_FREQ, PWM_RESOLUTION);
  stopMotor();

  // MPU6050
  Wire.begin(21, 22);
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(PWR_MGMT_1);
  Wire.write(0x00);
  Wire.endTransmission();

  // GPS
  GPSSerial.begin(9600, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);

  // SIM800L
  SIM800.begin(9600, SERIAL_8N1, SIM800_RX_PIN, SIM800_TX_PIN);

  // Bluetooth Classic SPP
  SerialBT.begin(BT_DEVICE_NAME);
  Serial.println();
  Serial.println("==============================");
  Serial.println("WHEELCHAIR BLUETOOTH STARTED");
  Serial.print("Device name: ");
  Serial.println(BT_DEVICE_NAME);
  Serial.println("==============================");
}

// ================= LOOP =================
void loop() {
  // Bluetooth commands: non-blocking, checked every iteration.
  if (SerialBT.available()) {
    char command = (char) SerialBT.read();
    handleIncomingCommand(command);
  }

  // GPS: feed any pending NMEA bytes into the parser, non-blocking.
  pollGps();

  // Fall detection: timed with millis(), never delay().
  unsigned long now = millis();
  if (now - lastFallCheckMs >= FALL_CHECK_INTERVAL_MS) {
    lastFallCheckMs = now;
    checkFall();
  }

  // Periodic GPS status report to whichever phone is connected.
  if (SerialBT.hasClient() && now - lastGpsReportMs >= GPS_REPORT_INTERVAL_MS) {
    lastGpsReportMs = now;
    sendGpsLineOverBluetooth();
  }
}
