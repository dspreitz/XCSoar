// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar;

import java.util.Queue;
import java.util.UUID;
import java.util.LinkedList;
import java.util.List;
import java.io.IOException;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Read Bluetooth LE sensor values and report them to a
 * #SensorListener.
 */
public final class BluetoothSensor
  extends BluetoothGattCallback
  implements AndroidSensor
{
  private static final String DBG = "XCSDBG";
  private final SensorListener listener;
  private final SafeDestruct safeDestruct = new SafeDestruct();

  /** kept for reconnecting after a failed connection attempt */
  private final Context context;
  private final BluetoothDevice device;

  /**
   * Maximum number of milliseconds close() waits for the disconnected
   * state after calling BluetoothGatt.disconnect(), the same bound
   * BleSerialPort uses.
   */
  private static final int DISCONNECT_TIMEOUT = 500;

  /**
   * Assigned on the main thread, read on the Binder thread that
   * delivers the GATT callbacks, so the two have to agree on what
   * they see.
   */
  private volatile BluetoothGatt gatt;

  /**
   * The raw GATT connection state, so that close() can wait for the
   * disconnect it asked for.  Protected by itself.
   */
  private final Object gattStateSync = new Object();
  private int gattState = BluetoothProfile.STATE_DISCONNECTED;

  private int state = STATE_LIMBO;

  /**
   * Android drops the first connection attempt to a BLE device often
   * enough that treating it as fatal is wrong: reporting a failure
   * makes DeviceDescriptor::OnSysTicker() close the whole device and
   * reopen it seconds later, which the pilot sees as an error message
   * followed by a connection that works anyway.  Retry in place
   * instead, and only give up once the device has had its chances.
   */
  private static final int MAX_CONNECT_RETRIES = 2;
  private int connectRetries = 0;

  /**
   * Has this object ever reached STATE_CONNECTED?  A drop before that
   * is a failed attempt and worth retrying; one after it is the
   * device going away, which is not.
   */
  private boolean everConnected = false;

  private BluetoothGattCharacteristic currentEnableNotification;
  private final Queue<BluetoothGattCharacteristic> enableNotificationQueue =
    new LinkedList<BluetoothGattCharacteristic>();

  private boolean haveFlytecMovement = false;
  private double flytecGroundSpeed, flytecTrack;
  private int flytecSatellites = 0;

  public BluetoothSensor(final Context context, final BluetoothDevice device,
                         SensorListener listener)
    throws IOException
  {
    this.listener = listener;
    this.context = context;
    this.device = device;

    Log.w(DBG, "ctor: enter, posting connect");

    if (Build.VERSION.SDK_INT >= 23){
      /**
       * Run GATT connect, discover etc. on main thread. If not,
       * recent Android os will call close() before connection
       * is fully established.
       */
      new Handler(Looper.getMainLooper()).post(new Runnable() {
        @Override
        public void run() {
          /**
           * Change auto connect = false and remove transport hint, which
           * should be more stable and widespread supported.
           */
          Log.w(DBG, "ctor.runnable: running");

          try {
            gatt = device.connectGatt(context, false, BluetoothSensor.this);
            Log.w(DBG, "ctor.runnable: connectGatt returned " + gatt);
          } catch (SecurityException e) {
            /* Android 12+: BLUETOOTH_CONNECT required; may be denied or revoked. */
            submitError("Bluetooth connect not permitted");
            return;
          }
          if (gatt == null)
            submitError("Bluetooth GATT connect failed");
        }
      });
    }
    else {
      try {
        gatt = device.connectGatt(context, true, this);
      } catch (SecurityException e) {
        throw new IOException("Bluetooth GATT connect not permitted", e);
      }

      /* only this branch has connected synchronously and can say
         whether it worked; the posted one reports its own failure
         through submitError() once it has actually run */
      if (gatt == null)
        throw new IOException("Bluetooth GATT connect failed");
    }
  }

  @Override
  public void close() {
    Log.w(DBG, "close(): called, gatt=" + gatt, new Throwable());

    safeDestruct.beginShutdown();

    final BluetoothGatt gatt = this.gatt;
    if (gatt != null) {
      /* tell the peripheral to drop the link before the client is
         released.  Releasing alone leaves the connection standing:
         the sensor keeps the link and does not turn up in a new scan,
         which looks to the pilot as though disabling the device had
         done nothing (see #1836).  BleSerialPort does the same. */
      gatt.disconnect();

      synchronized (gattStateSync) {
        final long waitUntil = System.currentTimeMillis() + DISCONNECT_TIMEOUT;

        while (gattState != BluetoothProfile.STATE_DISCONNECTED) {
          final long timeToWait = waitUntil - System.currentTimeMillis();
          if (timeToWait <= 0)
            break;

          try {
            gattStateSync.wait(timeToWait);
          } catch (InterruptedException e) {
            break;
          }
        }
      }

      gatt.close();
    }

    safeDestruct.finishShutdown();
  }

  @Override
  public int getState() {
    return state;
  }

  private void setStateSafe(int _state) {
    if (_state == state)
      return;

    state = _state;

    if (safeDestruct.increment()) {
      try {
        listener.onSensorStateChanged();
      } finally {
        safeDestruct.decrement();
      }
    }
  }

  private void submitError(String msg) {
    haveFlytecMovement = false;
    flytecSatellites = 0;
    state = STATE_FAILED;

    if (safeDestruct.increment()) {
      try {
        listener.onSensorError(msg);
      } finally {
        safeDestruct.decrement();
      }
    }
  }

  private boolean doEnableNotification(BluetoothGattCharacteristic c) {
    BluetoothGattDescriptor d = c.getDescriptor(BluetoothUuids.CLIENT_CHARACTERISTIC_CONFIGURATION);
    if (d == null)
      return false;

    /* the PLX "Spot-check Measurement" characteristic is indicate-only,
       and writing the notification bit to such a characteristic enables
       nothing at all */
    final boolean indicate =
      (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
      (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

    gatt.setCharacteristicNotification(c, true);
    d.setValue(indicate
               ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
               : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
    return gatt.writeDescriptor(d);
  }

  private void enableNotification(BluetoothGattCharacteristic c) {
    synchronized(enableNotificationQueue) {
      if (currentEnableNotification == null) {
        currentEnableNotification = c;
        if (!doEnableNotification(c))
          currentEnableNotification = null;
      } else
        enableNotificationQueue.add(c);
    }
  }

  /**
   * Data in the characteristic has little endian byteorder.
   * Lowest bit of flags indicates valid ignitions_per_sec reading.
   * 0 Kelvin indicates invalid temperatures e.g 
   * no temperature sensor present.
  */
  private void engineSensorDataToListeners(BluetoothGattCharacteristic c) {
    final int flags = c.getIntValue(c.FORMAT_UINT8, 0);
    final int cht_temp = c.getIntValue(c.FORMAT_UINT16, 1);
    final int egt_temp = c.getIntValue(c.FORMAT_UINT16, 3);
    final int outside_air_temperature = c.getIntValue(c.FORMAT_UINT16, 5);

    if(outside_air_temperature != 0)
      listener.onTemperature(outside_air_temperature);

    final int pressure = c.getIntValue(c.FORMAT_UINT32, 7);

    // Just guessing the sensor_noise_variance.
    if(pressure != 0)
      listener.onBarometricPressureSensor(pressure / 100.0f, 0.01f);

    final int ignitions_per_second = c.getIntValue(c.FORMAT_UINT16, 11);
    listener.onEngineSensors(cht_temp != 0 ? true : false,
                             cht_temp,
                             egt_temp != 0 ? true : false,
                             egt_temp,
                             (flags&0x01) == 0x01 ? true : false,
                             ignitions_per_second);
  }

  private void readHeartRateMeasurement(BluetoothGattCharacteristic c) {
    int offset = 0;
    final int flags = c.getIntValue(c.FORMAT_UINT8, offset);
    ++offset;

    final boolean bpm16 = (flags & 0x1) != 0;

    final int bpm = bpm16
      ? c.getIntValue(c.FORMAT_UINT16, offset)
      : c.getIntValue(c.FORMAT_UINT8, offset);

    listener.onHeartRateSensor(bpm);
  }

  /**
   * Bits of the PLX "Measurement Status" field which mean the value must
   * not be shown to the pilot: the sensor either declares the
   * measurement unusable, or marks it as demonstration or test data.
   */
  private static final int PLX_MEASUREMENT_REJECT =
    (1 << 10) | /* Data for Demonstration */
    (1 << 11) | /* Data for Testing */
    (1 << 13) | /* Measurement Unavailable */
    (1 << 14) | /* Questionable Measurement Detected */
    (1 << 15);  /* Invalid Measurement Detected */

  /**
   * Locate the optional "Measurement Status" field, which sits behind a
   * different number of optional fields in each of the two
   * characteristics.
   *
   * @return the offset of the field, or -1 if the sensor did not send one
   */
  private static int findPLXMeasurementStatus(int flags, boolean spot_check) {
    /* both characteristics begin with the flags byte and four bytes of
       measurement, either SpO2 and pulse rate or the "SpO2PR-Normal"
       pair */
    int offset = 5;

    if (spot_check) {
      if ((flags & 0x02) == 0)
        return -1;

      if ((flags & 0x01) != 0)
        /* skip the timestamp */
        offset += 7;
    } else {
      if ((flags & 0x04) == 0)
        return -1;

      if ((flags & 0x01) != 0)
        /* skip "SpO2PR-Fast" */
        offset += 4;

      if ((flags & 0x02) != 0)
        /* skip "SpO2PR-Slow" */
        offset += 4;
    }

    return offset;
  }

  /**
   * Parse a PLX measurement and report the blood oxygen saturation.
   *
   * Both the "PLX Spot-Check Measurement" and the "PLX Continuous
   * Measurement" characteristic start with a flags byte followed by
   * SpO2 and the pulse rate, each an IEEE-11073 16 bit SFLOAT, so the
   * same code handles both; only the optional fields behind them
   * differ.
   */
  private void readPLXMeasurement(BluetoothGattCharacteristic c,
                                  boolean spot_check) {
    final Integer flags =
      c.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
    if (flags == null)
      return;

    final int status_offset = findPLXMeasurementStatus(flags, spot_check);
    if (status_offset >= 0) {
      final Integer status =
        c.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16,
                      status_offset);
      if (status == null || (status & PLX_MEASUREMENT_REJECT) != 0)
        /* truncated packet, or the sensor itself says the value is not
           fit to be used */
        return;
    }

    final Float spo2 = c.getFloatValue(BluetoothGattCharacteristic.FORMAT_SFLOAT,
                                       1);
    if (spo2 == null || spo2.isNaN())
      /* the sensor reports "not available" while it is still
         measuring */
      return;

    final int percent = Math.round(spo2);
    if (percent <= 0 || percent > 100)
      /* SFLOAT has several reserved values (NaN, NRes, infinity)
         which Android may pass through as numbers; those are outside
         the plausible range and get dropped here */
      return;

    listener.onBloodOxygenSensor(percent);
  }

  static long toUnsignedLong(int x) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
      // Android 7 "Nougat" supports Java 8
      return Integer.toUnsignedLong(x);

    // Reimplement for older Android versions
    long l = x;
    if (l < 0)
      l += (1L << 32);
    return l;
  }

  @Override
  public synchronized void onCharacteristicChanged(BluetoothGatt gatt,
                                                   BluetoothGattCharacteristic c) {
    if (!safeDestruct.increment())
      return;

    try {
      if (BluetoothUuids.HEART_RATE_MEASUREMENT_CHARACTERISTIC.equals(c.getUuid())) {
        readHeartRateMeasurement(c);
      }

      if (BluetoothUuids.PLX_CONTINUOUS_MEASUREMENT_CHARACTERISTIC.equals(c.getUuid())) {
        readPLXMeasurement(c, false);
      }

      if (BluetoothUuids.PLX_SPOT_CHECK_MEASUREMENT_CHARACTERISTIC.equals(c.getUuid())) {
        readPLXMeasurement(c, true);
      }

      if (BluetoothUuids.ENGINE_SENSORS_CHARACTERISTIC.equals(c.getUuid())) {
        engineSensorDataToListeners(c);
      }

      if (BluetoothUuids.FLYTEC_SENSBOX_NAVIGATION_SENSOR_CHARACTERISTIC.equals(c.getUuid())) {        
        /* protocol documentation:
            https://github.com/flytec/SensBoxLib_iOS/blob/master/_SensBox%20Documentation/SensorBox%20BLE%20Protocol.pdf */
        final int gps_status = c.getIntValue(c.FORMAT_UINT8, 18) & 0x7;
        final boolean hasAltitude = (gps_status == 2 || gps_status == 4);

        final long time = 1000 *
          toUnsignedLong(c.getIntValue(c.FORMAT_UINT32, 0));

        listener.onLocationSensor(time,
                                  flytecSatellites,
                                  c.getIntValue(c.FORMAT_SINT32, 8) / 10000000.,
                                  c.getIntValue(c.FORMAT_SINT32, 4) / 10000000.,
                                  hasAltitude, true,
                                  c.getIntValue(c.FORMAT_SINT16, 12),
                                  haveFlytecMovement, flytecTrack,
                                  haveFlytecMovement, flytecGroundSpeed,
                                  false, 0);

        listener.onPressureAltitudeSensor(c.getIntValue(c.FORMAT_SINT16, 14));
      } else if (BluetoothUuids.FLYTEC_SENSBOX_MOVEMENT_SENSOR_CHARACTERISTIC.equals(c.getUuid())) {
        flytecGroundSpeed = c.getIntValue(c.FORMAT_SINT16, 6) / 10.;
        flytecTrack = c.getIntValue(c.FORMAT_SINT16, 8) / 10.;

        listener.onVarioSensor(c.getIntValue(c.FORMAT_SINT16, 4) / 100.f);
        listener.onAccelerationSensor1(c.getIntValue(c.FORMAT_UINT16, 16) / 10.);

        haveFlytecMovement = true;
      } else if (BluetoothUuids.FLYTEC_SENSBOX_SECOND_GPS_CHARACTERISTIC.equals(c.getUuid())) {
        flytecSatellites = c.getIntValue(c.FORMAT_UINT8, 6);
      } else if (BluetoothUuids.FLYTEC_SENSBOX_SYSTEM_CHARACTERISTIC.equals(c.getUuid())) {
        listener.onBatteryPercent(c.getIntValue(c.FORMAT_UINT8, 4));

        final double CELSIUS_OFFSET = 273.15;
        double temperatureCelsius = c.getIntValue(c.FORMAT_SINT16, 6) / 10.;
        listener.onTemperature(CELSIUS_OFFSET + temperatureCelsius);
      }
    } catch (NullPointerException e) {
      /* probably caused by a malformed value - ignore */
    } finally {
      safeDestruct.decrement();
    }
  }

  /**
   * Close the failed connection and ask for a new one.  Android needs
   * the old client interface released before it will hand out
   * another, so the close is not optional.
   */
  private void retryConnect() {
    Log.w(DBG, "retryConnect: posting");

    new Handler(Looper.getMainLooper()).post(new Runnable() {
      @Override
      public void run() {
        if (!safeDestruct.increment()) {
          Log.w(DBG, "retryConnect.runnable: SUPPRESSED, safeDestruct is shut down");
          return;
        }

        Log.w(DBG, "retryConnect.runnable: proceeding");

        try {
          if (gatt != null) {
            gatt.close();
            gatt = null;
          }

          try {
            gatt = device.connectGatt(context, false, BluetoothSensor.this);
          } catch (SecurityException e) {
            submitError("Bluetooth connect not permitted");
            return;
          }

          if (gatt == null)
            submitError("Bluetooth GATT connect failed");
        } finally {
          safeDestruct.decrement();
        }
      }
    });
  }

  @Override
  public void onConnectionStateChange(BluetoothGatt gatt,
                                      int status, int newState) {
    Log.w(DBG, "onConnectionStateChange: status=" + status +
          " newState=" + newState + " gatt=" + gatt +
          " current=" + this.gatt);

    final BluetoothGatt current = this.gatt;
    if (current != null && gatt != current)
      /* a disconnect still in flight from the client retryConnect()
         has already closed.  Acting on it would close its replacement
         and spend another retry on a connection that is fine.  The
         null case is the first callback racing the assignment in the
         constructor, and that one is ours. */
      return;

    synchronized (gattStateSync) {
      gattState = newState;
      gattStateSync.notifyAll();
    }

    if (BluetoothProfile.STATE_CONNECTED == newState &&
        BluetoothGatt.GATT_SUCCESS == status) {
      everConnected = true;
      connectRetries = 0;

      if (!gatt.discoverServices())
        submitError("Discovering GATT services request failed");

      return;
    }

    if (BluetoothProfile.STATE_DISCONNECTED != newState)
      /* CONNECTING or DISCONNECTING: on the way somewhere, and not a
         state worth reporting either way */
      return;

    if (!everConnected && status != BluetoothGatt.GATT_SUCCESS &&
        connectRetries < MAX_CONNECT_RETRIES) {
      ++connectRetries;
      retryConnect();
      return;
    }

    submitError(BluetoothGatt.GATT_SUCCESS == status
                ? "GATT disconnected"
                : "GATT connection failed (status " + status + ")");
  }

  @Override
  public void onDescriptorWrite(BluetoothGatt gatt,
                                BluetoothGattDescriptor descriptor,
                                int status) {
    synchronized(enableNotificationQueue) {
      currentEnableNotification = enableNotificationQueue.poll();
      if (currentEnableNotification != null) {
        if (!doEnableNotification(currentEnableNotification))
          currentEnableNotification = null;
      }
    }
  }

  @Override
  public void onServicesDiscovered(BluetoothGatt gatt,
                                   int status) {
    if (BluetoothGatt.GATT_SUCCESS != status) {
      submitError("Discovering GATT services failed");
      return;
    }

    /** Check if we know the discovered characteristics, if known,
    * enable notification. Consecutive calls to getServices() 
    * might fail, so we do it just once and handle the lookups in 
    * the loops.
    */
    List<BluetoothGattService> services = gatt.getServices();
    for (BluetoothGattService s : services) {
        List<BluetoothGattCharacteristic> characteristics = s.getCharacteristics();
        for (BluetoothGattCharacteristic c : characteristics) {
            UUID id = c.getUuid();
            for (UUID supported_id : BluetoothUuids.getAllCharacteristicsUuids()) {
              if(id.equals(supported_id)){
                setStateSafe(STATE_READY);
                enableNotification(c);                
              }
            }
       }
    }

    if (state == STATE_LIMBO)
      submitError("Unsupported Bluetooth device");
  }
}
