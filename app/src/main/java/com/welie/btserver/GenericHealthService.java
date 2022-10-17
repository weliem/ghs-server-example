package com.welie.btserver;

import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;

import static com.welie.blessed.BluetoothBytesParser.FORMAT_FLOAT;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT16;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT32;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT48;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;
import static com.welie.blessed.BluetoothBytesParser.bytes2String;
import static com.welie.blessed.BluetoothBytesParser.mergeArrays;

import static java.lang.Math.min;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.welie.blessed.BluetoothBytesParser;
import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothPeripheralManager;
import com.welie.blessed.BondState;
import com.welie.blessed.GattStatus;
import com.welie.blessed.ReadResponse;

import org.jetbrains.annotations.NotNull;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import timber.log.Timber;

public class GenericHealthService extends BaseService {
    private static final UUID GHS_SERVICE_UUID = UUID.fromString("00007f44-0000-1000-8000-00805f9b34fb");
    private static final UUID OBSERVATION_CHAR_UUID = UUID.fromString("00007f43-0000-1000-8000-00805f9b34fb");
    private static final UUID GHS_FEATURES_CHAR_UUID = UUID.fromString("00007f41-0000-1000-8000-00805f9b34fb");
    private static final UUID GHS_SCHEDULE_CHANGED_CHAR_UUID = UUID.fromString("00007f3f-0000-1000-8000-00805f9b34fb");
    private static final UUID GHS_SCHEDULE_DESCRIPTOR_UUID = UUID.fromString("00007f35-0000-1000-8000-00805f9b34fb");

    public static final String MEASUREMENT_PULSE_OX = "ghs.observation.pulseox";
    public static final String MEASUREMENT_PULSE_OX_EXTRA_CONTINUOUS = "ghs.observation.pulseox.extra.value";

    private @NotNull
    final BluetoothGattService service = new BluetoothGattService(GHS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

    private @NotNull
    final BluetoothGattCharacteristic scheduleChanged = new BluetoothGattCharacteristic(GHS_SCHEDULE_CHANGED_CHAR_UUID, PROPERTY_NOTIFY, 0);

    private @NotNull
    final BluetoothGattCharacteristic liveObservation = new BluetoothGattCharacteristic(OBSERVATION_CHAR_UUID, PROPERTY_NOTIFY, 0);

    private @NotNull
    final Handler handler = new Handler(Looper.getMainLooper());
    private final int MDC_PULS_OXIM_SAT_O2 = 150456;
    private volatile byte[] scheduleValue;
    private float interval = 1.0f;
    private float measurement_duration = 1.0f;
    private final byte[] featureValue;
    private @NotNull
    final Runnable notifyRunnable = this::notifyLiveObservation;
    private int segmentCounter = 0;
    private final Set<String> centralsWantingObsNotifications = new HashSet<>();
    private final Set<String> centralsWantingScheduleNotifications = new HashSet<>();

    GenericHealthService(@NotNull BluetoothPeripheralManager peripheralManager) {
        super(peripheralManager);

        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(MDC_PULS_OXIM_SAT_O2, FORMAT_UINT32);
        parser.setFloatValue(measurement_duration, 1);
        parser.setFloatValue(interval, 1);
        scheduleValue = parser.getValue().clone();

        BluetoothGattCharacteristic feature = new BluetoothGattCharacteristic(GHS_FEATURES_CHAR_UUID, PROPERTY_READ, PERMISSION_READ);
        BluetoothGattDescriptor scheduleDescriptor = new BluetoothGattDescriptor(GHS_SCHEDULE_DESCRIPTOR_UUID, PERMISSION_READ | PERMISSION_WRITE);
        feature.addDescriptor(scheduleDescriptor);

        BluetoothBytesParser parser2 = new BluetoothBytesParser();
        parser2.setIntValue(0, FORMAT_UINT8);
        parser2.setIntValue(1, FORMAT_UINT8);
        parser2.setIntValue(MDC_PULS_OXIM_SAT_O2, FORMAT_UINT32);
        featureValue = parser2.getValue().clone();
        service.addCharacteristic(feature);

        scheduleChanged.addDescriptor(getCccDescriptor());
        service.addCharacteristic(scheduleChanged);

        liveObservation.addDescriptor(getCccDescriptor());
        service.addCharacteristic(liveObservation);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onCentralConnected(@NotNull BluetoothCentral central) {
        if (central.getBondState() == BondState.BONDED && centralsWantingObsNotifications.contains(central.getAddress())) {
            notifyLiveObservation();
        }
    }

    @Override
    public void onCentralDisconnected(@NotNull BluetoothCentral central) {
        if (central.getBondState() != BondState.BONDED) {
            centralsWantingObsNotifications.remove(central.getAddress());
            centralsWantingScheduleNotifications.remove(central.getAddress());
        }

        if (noCentralsConnected()) {
            handler.removeCallbacks(notifyRunnable);
        }
    }

    @Override
    public ReadResponse onCharacteristicRead(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        if (characteristic.getUuid() == GHS_FEATURES_CHAR_UUID) {
            return new ReadResponse(GattStatus.SUCCESS, featureValue);
        }
        return new ReadResponse(GattStatus.REQUEST_NOT_SUPPORTED, null);
    }

    private void broadcastValue(float spo2Value) {
        Intent intent = new Intent(MEASUREMENT_PULSE_OX);
        intent.putExtra(MEASUREMENT_PULSE_OX_EXTRA_CONTINUOUS, spo2Value);
        context.sendBroadcast(intent);
    }

    private void notifyLiveObservation() {
        int minMTU = getMinMTU();

        float spo2Value = (float) (95.0f + (Math.random() * 2));
        broadcastValue(spo2Value);
        byte[] observation = createObservation(spo2Value);
        byte[] packet;
        if (minMTU - 4 >= observation.length) {
            packet = mergeArrays(new byte[]{(byte) ((segmentCounter << 2) + 3)}, observation);
            Timber.d("notifying observation <%s>", bytes2String(observation));
            notifyCharacteristicChanged(packet, liveObservation);
        } else {
            int numberOfSegments = (int) Math.ceil((double) observation.length / (minMTU - 4));
            int observationIndex = 0;
            int observationRemaining = observation.length;
            for (int i = 0; i < numberOfSegments; i++) {
                int segmentsize = min(minMTU - 4, observationRemaining);
                byte[] segment = new byte[segmentsize];
                System.arraycopy(observation, observationIndex, segment, 0, segmentsize);
                observationRemaining -= segmentsize;
                observationIndex += segmentsize;

                if (i == 0) {
                    packet = mergeArrays(new byte[]{(byte) ((segmentCounter << 2) + 1)}, segment);
                } else if (i == numberOfSegments - 1) {
                    packet = mergeArrays(new byte[]{(byte) ((segmentCounter << 2) + 2)}, segment);
                } else {
                    packet = mergeArrays(new byte[]{(byte) (segmentCounter << 2)}, segment);
                }
                notifyObservationToCentrals(packet);

                segmentCounter++;
                if (segmentCounter > 63) segmentCounter = 0;
            }
        }
        handler.postDelayed(notifyRunnable, (long) (interval * 1000L));
    }

    private void notifyObservationToCentrals(byte[] packet) {
        Timber.d("notifying observation <%s>", bytes2String(packet));
        Set<BluetoothCentral> allCentrals = peripheralManager.getConnectedCentrals();
        for (BluetoothCentral connectedCentral : allCentrals) {
            if (centralsWantingObsNotifications.contains(connectedCentral.getAddress())) {
                peripheralManager.notifyCharacteristicChanged(packet, connectedCentral, liveObservation);
            }
        }
    }

    private void addElapsedTime(@NotNull BluetoothBytesParser parser) {
        long elapsed_time_epoch = 946684800;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        long seconds_since_unix_epoch = calendar.getTime().getTime() / 1000;
        long seconds_since_ets_epoch = seconds_since_unix_epoch - elapsed_time_epoch;

        parser.setIntValue(0x22, FORMAT_UINT8);  // Flags
        parser.setLong(seconds_since_ets_epoch, FORMAT_UINT48);
        parser.setIntValue(0x06, FORMAT_UINT8);  // Cellular Network
        parser.setIntValue(0x00, FORMAT_UINT8);  // Tz/DST offset
    }

    private byte[] createObservation(float spo2Value) {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        int NUMERIC_OBSERVATION = 0;
        parser.setIntValue(NUMERIC_OBSERVATION, BluetoothGattCharacteristic.FORMAT_UINT8);
        parser.setIntValue(25, FORMAT_UINT16);  // Length
        parser.setIntValue(0x07, FORMAT_UINT16);  // Flags
        parser.setIntValue(MDC_PULS_OXIM_SAT_O2, FORMAT_UINT32);
        addElapsedTime(parser);
        parser.setFloatValue(measurement_duration, 1); // Measurement duration
        int MDC_DIM_PER_CENT = 0x0220;
        parser.setIntValue(MDC_DIM_PER_CENT, FORMAT_UINT16);
        parser.setFloatValue(spo2Value, 1);
        return parser.getValue().clone();
    }

    @Override
    public void onNotifyingEnabled(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        if (characteristic.getUuid().equals(GHS_SCHEDULE_CHANGED_CHAR_UUID)) {
            centralsWantingScheduleNotifications.add(central.getAddress());
        } else if (characteristic.getUuid().equals(OBSERVATION_CHAR_UUID)) {
            final boolean shouldStartNotifying = centralsWantingObsNotifications.isEmpty();
            centralsWantingObsNotifications.add(central.getAddress());
            if (shouldStartNotifying) notifyLiveObservation();
        }
    }

    @Override
    public void onNotifyingDisabled(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        if (characteristic.getUuid().equals(GHS_SCHEDULE_CHANGED_CHAR_UUID)) {
            centralsWantingScheduleNotifications.remove(central.getAddress());
        } else if (characteristic.getUuid().equals(OBSERVATION_CHAR_UUID)) {
            centralsWantingObsNotifications.remove(central.getAddress());
            if(centralsWantingObsNotifications.isEmpty()) {
                handler.removeCallbacks(notifyRunnable);
            }
        }
    }

    @Override
    public void onDescriptorWriteCompleted(@NotNull BluetoothCentral central, @NotNull BluetoothGattDescriptor descriptor, @NonNull byte[] value) {
        for (String centralAddress : centralsWantingScheduleNotifications) {
            if (!(centralAddress.equals(central.getAddress()))) {
                BluetoothCentral connectedCentral = peripheralManager.getCentral(centralAddress);
                if (connectedCentral == null) {
                    Timber.e("could not find central with address %s", centralAddress);
                } else {
                    peripheralManager.notifyCharacteristicChanged(value, connectedCentral, scheduleChanged);
                }
            }
        }
    }

    @Override
    public GattStatus onDescriptorWrite(@NotNull BluetoothCentral central, @NotNull BluetoothGattDescriptor descriptor, byte[] value) {
        if (value.length != 12) return GattStatus.VALUE_OUT_OF_RANGE;

        BluetoothBytesParser parser = new BluetoothBytesParser(value, 0, LITTLE_ENDIAN);
        final int mdc = parser.getIntValue(FORMAT_UINT32);
        final float schedule_measurement_period = parser.getFloatValue(FORMAT_FLOAT);
        final float schedule_update_interval = parser.getFloatValue(FORMAT_FLOAT);

        if (mdc != MDC_PULS_OXIM_SAT_O2) {
            return GattStatus.VALUE_OUT_OF_RANGE;
        }

        if (schedule_measurement_period > 5 || schedule_measurement_period < 1) {
            return GattStatus.VALUE_OUT_OF_RANGE;
        }

        if (schedule_update_interval < schedule_measurement_period || schedule_update_interval > 10) {
            return GattStatus.VALUE_OUT_OF_RANGE;
        }

        scheduleValue = value;
        measurement_duration = schedule_measurement_period;
        interval = schedule_update_interval;
        return GattStatus.SUCCESS;
    }

    @Override
    public ReadResponse onDescriptorRead(@NotNull BluetoothCentral central, @NotNull BluetoothGattDescriptor descriptor) {
        BluetoothGattCharacteristic characteristic = Objects.requireNonNull(descriptor.getCharacteristic(), "Descriptor has no Characteristic");
        if (characteristic.getUuid().equals(GHS_FEATURES_CHAR_UUID) && descriptor.getUuid().equals(GHS_SCHEDULE_DESCRIPTOR_UUID)) {
            Timber.d("returning <%s> for schedule descriptor", bytes2String(scheduleValue));
            return new ReadResponse(GattStatus.SUCCESS, scheduleValue);
        }
        return new ReadResponse(GattStatus.REQUEST_NOT_SUPPORTED, null);
    }

    @Override
    public @NotNull BluetoothGattService getService() {
        return service;
    }

    @Override
    public String getServiceName() {
        return "Generic Health Service";
    }

    private int getMinMTU() {
        Set<BluetoothCentral> allCentrals = peripheralManager.getConnectedCentrals();
        if (allCentrals.isEmpty()) return 23;
        return allCentrals.stream().map(BluetoothCentral::getCurrentMtu).min(Integer::compare).get();
    }
}
