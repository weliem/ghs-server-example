package com.welie.btserver;

import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import com.welie.blessed.BluetoothBytesParser;
import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothPeripheralManager;
import com.welie.blessed.GattStatus;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import timber.log.Timber;

public class UserDataService extends BaseService {

    private static final UUID UDS_SERVICE_UUID = UUID.fromString("0000181C-0000-1000-8000-00805f9b34fb");
    private static final UUID USER_CONTROL_POINT_CHARACTERISTIC_UUID = UUID.fromString("00002A9F-0000-1000-8000-00805f9b34fb");
    private static final UUID REGISTERED_USER_CHARACTERISTIC_UUID = UUID.fromString("00007F00-0000-1000-8000-00805f9b34fb");

    private @NotNull final BluetoothGattService service = new BluetoothGattService(UDS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
    private @NotNull final ConcurrentHashMap<Integer, Integer> registeredUsers = new ConcurrentHashMap<>();

    UserDataService(@NotNull BluetoothPeripheralManager peripheralManager) {
        super(peripheralManager);

        registeredUsers.put(1, 8);
        registeredUsers.put(2, 16);
        BluetoothGattCharacteristic controlPoint = new BluetoothGattCharacteristic(USER_CONTROL_POINT_CHARACTERISTIC_UUID, PROPERTY_WRITE | PROPERTY_INDICATE, PERMISSION_WRITE);
        controlPoint.addDescriptor(getCccDescriptor());
        service.addCharacteristic(controlPoint);
    }

    @Override
    public void onNotifyingEnabled(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        super.onNotifyingEnabled(central, characteristic);
        Timber.i("UDS notify enabled");
    }

    @Override
    public void onNotifyingDisabled(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        super.onNotifyingDisabled(central, characteristic);
        Timber.i("UDS notify disabled");
    }

    @Override
    public GattStatus onCharacteristicWrite(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic, @NotNull byte[] value) {
        final int UDS_CP_CONSENT = 0x02;

        Timber.i("Got %s", BluetoothBytesParser.asHexString(value));
        if (characteristic.getUuid().equals(USER_CONTROL_POINT_CHARACTERISTIC_UUID)) {
            BluetoothBytesParser parser = new BluetoothBytesParser(value, ByteOrder.LITTLE_ENDIAN);
            final int code = parser.getUInt8();

            if (code == UDS_CP_CONSENT && value.length == 4) {
                final int userIndex = parser.getUInt8();
                final int consentCode = parser.getUInt16();

                final Integer registeredCode = registeredUsers.get(userIndex);
                if (registeredCode != null && registeredCode == consentCode) {
                    return GattStatus.SUCCESS;
                } else {
                    return GattStatus.VALUE_NOT_ALLOWED;
                }
            }
            return GattStatus.REQUEST_NOT_SUPPORTED;
        }
        return GattStatus.REQUEST_NOT_SUPPORTED;
    }

    @Override
    public @NotNull BluetoothGattService getService() {
        return service;
    }

    @Override
    public String getServiceName() {
        return "User Data Service";
    }
}
