package com.welie.btserver;

import static android.bluetooth.BluetoothGattCharacteristic.*;

import static com.welie.blessed.BluetoothBytesParser.asHexString;
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
import com.welie.blessed.GattStatus;
import com.welie.blessed.ReadResponse;

import org.jetbrains.annotations.NotNull;

import java.util.Calendar;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Collectors;

import timber.log.Timber;

public class GenericHealthService extends BaseService {
    private static final UUID GHS_SERVICE_UUID = UUID.fromString("00007f44-0000-1000-8000-00805f9b34fb");
    private static final UUID OBSERVATION_CHAR_UUID = UUID.fromString("00007f43-0000-1000-8000-00805f9b34fb");
    private static final UUID GHS_FEATURES_CHAR_UUID = UUID.fromString("00007f41-0000-1000-8000-00805f9b34fb");
    private static final UUID GHS_SCHEDULE_CHANGED_CHAR_UUID = UUID.fromString("00007f3f-0000-1000-8000-00805f9b34fb");
    private static final UUID GHS_SCHEDULE_DESCRIPTOR_UUID = UUID.fromString("00007f35-0000-1000-8000-00805f9b34fb");

    public static final String MEASUREMENT_PULSE_OX = "ghs.observation.pulseox";
    public static final String MEASUREMENT_PULSE_OX_EXTRA_CONTINUOUS = "ghs.observation.pulseox.extra.value";

    private @NotNull final BluetoothGattService service = new BluetoothGattService(GHS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
    private @NotNull final BluetoothGattCharacteristic scheduleChanged = new BluetoothGattCharacteristic(GHS_SCHEDULE_CHANGED_CHAR_UUID, PROPERTY_INDICATE, 0);
    private @NotNull final BluetoothGattCharacteristic liveObservation = new BluetoothGattCharacteristic(OBSERVATION_CHAR_UUID, PROPERTY_NOTIFY, 0);

    private @NotNull final Handler handler = new Handler(Looper.getMainLooper());
    public static final int MDC_PULS_OXIM_SAT_O2 = 150456;
    private volatile byte[] scheduleValue;
    private float interval = 1.0f;
    private float measurement_duration = 1.0f;
    private final byte[] featureValue;
    private @NotNull final Runnable notifyRunnable = this::notifyLiveObservation;
    private boolean isNotifyingLiveObservations = false;
    private int segmentCounter = 0;

    GenericHealthService(@NotNull BluetoothPeripheralManager peripheralManager) {
        super(peripheralManager);

        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setUInt32(MDC_PULS_OXIM_SAT_O2);
        parser.setFloatValue(measurement_duration, 1);
        parser.setFloatValue(interval, 1);
        scheduleValue = parser.getValue().clone();

        BluetoothGattCharacteristic feature = new BluetoothGattCharacteristic(GHS_FEATURES_CHAR_UUID, PROPERTY_READ, PERMISSION_READ);
        BluetoothGattDescriptor scheduleDescriptor = new BluetoothGattDescriptor(GHS_SCHEDULE_DESCRIPTOR_UUID, PERMISSION_READ | PERMISSION_WRITE);
        feature.addDescriptor(scheduleDescriptor);

        BluetoothBytesParser parser2 = new BluetoothBytesParser();
        parser2.setUInt8(0);
        parser2.setUInt8(1);
        parser2.setUInt32(MDC_PULS_OXIM_SAT_O2);
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
        Timber.d("Central bondstate is %s", central.getBondState());
        if (!getConnectedCentralsWantingObservations().isEmpty()) {
            if (!isNotifyingLiveObservations) {
                startNotifyingLiveObservations();
            }
        }
    }

    @Override
    public void onCentralDisconnected(@NotNull BluetoothCentral central) {
        if (getConnectedCentralsWantingObservations().isEmpty()) {
            stopNotifyingLiveObservations();
        }
    }

    private void startNotifyingLiveObservations() {
        Timber.d("starting sending live observations");
        isNotifyingLiveObservations = true;
        notifyLiveObservation();
    }

    private void stopNotifyingLiveObservations() {
        Timber.d("stopping sending live observations");
        handler.removeCallbacks(notifyRunnable);
        isNotifyingLiveObservations = false;
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
            Timber.d("notifying observation <%s>", asHexString(observation));
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
        Timber.d("notifying observation <%s>", asHexString(packet));
        Set<BluetoothCentral> allCentrals = getConnectedCentralsWantingObservations();
        for (BluetoothCentral connectedCentral : allCentrals) {
            peripheralManager.notifyCharacteristicChanged(packet, connectedCentral, liveObservation);
        }
    }

    private void addElapsedTime(@NotNull BluetoothBytesParser parser) {
        long elapsed_time_epoch = 946684800;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        long seconds_since_unix_epoch = calendar.getTime().getTime() / 1000;
        long seconds_since_ets_epoch = seconds_since_unix_epoch - elapsed_time_epoch;

        parser.setUInt8(0x22);  // Flags
        parser.setUInt48(seconds_since_ets_epoch);
        parser.setUInt8(0x06);  // Cellular Network
        parser.setUInt8(0x00);  // Tz/DST offset
    }

    private byte[] createObservation(float spo2Value) {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        int NUMERIC_OBSERVATION = 0;

        parser.setUInt8(NUMERIC_OBSERVATION);
        parser.setUInt16(28);  // Length
        parser.setUInt16(0x07);  // Flags
        parser.setUInt32(MDC_PULS_OXIM_SAT_O2);
        addElapsedTime(parser);
        parser.setFloatValue(measurement_duration, 1); // Measurement duration
        int MDC_DIM_PER_CENT = 0x0220;
        parser.setUInt16(MDC_DIM_PER_CENT);
        parser.setFloatValue(spo2Value, 1);

        return parser.getValue().clone();
    }

    @Override
    public void onNotifyingEnabled(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        if (characteristic.getUuid().equals(OBSERVATION_CHAR_UUID)) {
            if (!isNotifyingLiveObservations) {
                startNotifyingLiveObservations();
            }
        }
    }

    @Override
    public void onNotifyingDisabled(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        if (characteristic.getUuid().equals(OBSERVATION_CHAR_UUID)) {
            if (getConnectedCentralsWantingObservations().isEmpty()) {
                stopNotifyingLiveObservations();
            }
        }
    }

    @Override
    public void onDescriptorWriteCompleted(@NotNull BluetoothCentral central, @NotNull BluetoothGattDescriptor descriptor, @NonNull byte[] value) {
        for (BluetoothCentral connectedCentral : getConnectedCentralsWantingScheduleUpdates()) {
            if (!(connectedCentral.equals(central))) {
                peripheralManager.notifyCharacteristicChanged(value, connectedCentral, scheduleChanged);
            }
        }
    }

    @Override
    public GattStatus onDescriptorWrite(@NotNull BluetoothCentral central, @NotNull BluetoothGattDescriptor descriptor, byte[] value) {
        if (value.length != 12) return GattStatus.VALUE_OUT_OF_RANGE;

        BluetoothBytesParser parser = new BluetoothBytesParser(value, 0, LITTLE_ENDIAN);
        final int mdc = parser.getUInt32();
        final float schedule_measurement_period = parser.getFloat();
        final float schedule_update_interval = parser.getFloat();

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
            Timber.d("returning <%s> for schedule descriptor", asHexString(scheduleValue));
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

    private Set<BluetoothCentral> getConnectedCentralsWantingObservations() {
        final Set<BluetoothCentral> centralsWantingObsNotifications = peripheralManager.getCentralsWantingNotifications(liveObservation);
        return peripheralManager.getConnectedCentrals().stream().filter(centralsWantingObsNotifications::contains).collect(Collectors.toSet());
    }

    private Set<BluetoothCentral> getConnectedCentralsWantingScheduleUpdates() {
        final Set<BluetoothCentral> centralsWantingObsNotifications = peripheralManager.getCentralsWantingNotifications(scheduleChanged);
        return peripheralManager.getConnectedCentrals().stream().filter(centralsWantingObsNotifications::contains).collect(Collectors.toSet());
    }
}