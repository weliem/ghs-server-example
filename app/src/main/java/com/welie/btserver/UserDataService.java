package com.welie.btserver;

import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import com.welie.blessed.BluetoothPeripheralManager;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class UserDataService extends BaseService {

    private static final UUID UDS_SERVICE_UUID = UUID.fromString("0000181C-0000-1000-8000-00805f9b34fb");
    private static final UUID USER_CONTROL_POINT_CHARACTERISTIC_UUID = UUID.fromString("00002A9F-0000-1000-8000-00805f9b34fb");
    private static final UUID REGISTERED_USER_CHARACTERISTIC_UUID = UUID.fromString("00007F00-0000-1000-8000-00805f9b34fb");

    private @NotNull final BluetoothGattService service = new BluetoothGattService(UDS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

    UserDataService(@NotNull BluetoothPeripheralManager peripheralManager) {
        super(peripheralManager);

        BluetoothGattCharacteristic controlPoint = new BluetoothGattCharacteristic(USER_CONTROL_POINT_CHARACTERISTIC_UUID, PROPERTY_WRITE, PERMISSION_WRITE_ENCRYPTED);
        service.addCharacteristic(controlPoint);
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
