# RoadGuard Hardware & Embedded Firmware

**Department of Computer Science & Engineering | IDEA Lab**  
**Ramdeobaba University, Academic Year 2026–27**  
**Project Guide:** Dr. Suresh Balpande  
**Team:** Bhoomi Magarde (A6-19), Mohammad Ejaz Mehendi (A6-25), Nidhish Dadhe (A6-53)

---

## 1. Hardware Architecture & Bill of Materials (BOM)

| Component | Specification | Purpose | Quantity |
| :--- | :--- | :--- | :---: |
| **Microcontroller** | ESP32 WROOM-32 (Dual Core 240MHz, BLE 4.2/5.0) | Sensor acquisition, buffering, BLE transmission | 1 |
| **IMU Sensor** | MPU-6050 (6-DOF Accelerometer + Gyroscope, I2C) | 50Hz dynamic acceleration ($a_x, a_y, a_z$) & pitch rate | 1 |
| **Power Supply** | 3.7V 18650 Li-ion Cell with TP4056 Charger & 5V Step-Up | Portable vehicle dashboard / handlebar mounting | 1 |
| **Status Indicator** | Multi-color LED & Piezo Buzzer | Local alert and BLE connection status | 1 |
| **Chassis** | 3D Printed PETG Weather-resistant Enclosure | Rigid vehicle chassis mounting | 1 |

---

## 2. Wiring Pinout Diagram

| MPU-6050 Pin | ESP32 GPIO Pin | Notes |
| :--- | :--- | :--- |
| **VCC** | **3V3** (or 5V for module with onboard regulator) | Recommended: 3.3V power |
| **GND** | **GND** | Ground reference |
| **SCL** | **GPIO 22** | I2C Clock (Default Hardware I2C) |
| **SDA** | **GPIO 21** | I2C Data (Default Hardware I2C) |
| **AD0** | **GND** | Sets I2C address to `0x68` |
| **INT** | **GPIO 19** (Optional) | Data-ready hardware interrupt |

---

## 3. BLE Telemetry Service Specifications
- **Service UUID:** `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (Nordic UART Service compatible)
- **TX Characteristic (Notify):** `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`
- **RX Characteristic (Write):** `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- **Sampling Rate:** 50 Hz (20 ms interval)
- **Binary Packet Format (20 Bytes):**
  - Bytes 0–3: `uint32_t` Milliseconds timestamp
  - Bytes 4–5: `int16_t` ax (scaled by $100$)
  - Bytes 6–7: `int16_t` ay (scaled by $100$)
  - Bytes 8–9: `int16_t` az (scaled by $100$)
  - Bytes 10–11: `int16_t` gx (scaled by $10$)
  - Bytes 12–13: `int16_t` gy (scaled by $10$)
  - Bytes 14–15: `int16_t` gz (scaled by $10$)
  - Bytes 16–17: `uint16_t` Packet sequence counter
  - Bytes 18–19: `uint16_t` CRC-16 checksum

---

## 4. Flashing Instructions (Arduino IDE / PlatformIO)
1. Install **ESP32 Board Support** in Arduino IDE (`https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`).
2. Install libraries:
   - `Adafruit MPU6050`
   - `Adafruit Unified Sensor`
3. Connect ESP32 via Micro-USB, select board **ESP32 Dev Module**.
4. Upload `roadguard_esp32.ino`.
5. Open Serial Monitor at **115200 baud** to verify sensor calibration and BLE advertising.
